#!/usr/bin/env python3
"""Writes the one-off SQL file that imports the legacy data into the new schema (Q-55, reopened
by the owner on 2026-10-28: a file loaded once with psql, no importer in the application).

Reads the mysqldump of `lsdb` directly (no MySQL server needed) and applies the mapping of
chapter 12b §5.5: every `soggetti` row becomes a customer with its OWNER login; the
`spedizioni` created on or after the cut-off (UTC) become orders with lines rebuilt from the
`pallet` units, tracking events and the internal note. `users`, `user_session` and the `*_seq`
tables are not imported.

The output holds real personal data: write it only under .local/ (gitignored), never commit it,
never paste its values anywhere. The report printed on stdout holds counts and legacy ids only.

    python3 -I tools/legacy-import/generate_import_sql.py \
        .local/legacy/lsdb_full_<date>.sql.gz .local/legacy/import_legacy.sql \
        [--cutoff '2026-04-28 00:00:00']

The SQL file runs in one transaction, takes every id from the identity sequences, refuses to
run twice (audit_log action legacy.import) and needs an ADMIN staff user when orders carry a
note (order_internal_note.updated_by is required).
"""

import argparse
import collections
import datetime
import json
import os
import re
import sys
from decimal import ROUND_HALF_UP, Decimal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mysqldump import read_dump  # noqa: E402

CUSTOMER_STATES = {"NEW", "PENDING", "REGISTERED", "DECLINED"}
ORDER_STATES = {"NEW", "PENDING", "REGISTERED", "DECLINED"}
# 12b §2.2: stored label -> tracking_state.id (V009 seeds the same ids).
TRACKING = {
    "non gestito": 0,
    "presa in carico": 1,
    "transito": 2,
    "terminal destinatario": 3,
    "assegnato": 4,
    "consegnato": 5,
}
PRESA, TRANSITO, CONSEGNATO = 1, 2, 5
# 12b §2.2: stored label -> accessory_service.name (V009); item 0 means no service (NULL).
SERVICES = {
    "nessun servizio accessorio": None,
    "prenotazione telefonica": "PRENOTAZIONE_TEL",
    "sponda idraulica": "SPONDA_IDR",
    "segnalazione merce lunga": "MERCE_LUNGA",
    "facchinaggio": "FACCHINAGGIO",
    "gdo": "GDO",
}
UNIT_KINDS = {"PALLET": "GENERIC", "EPAL": "EPAL"}
NOTE_PLACEHOLDER = "nessuna nota presente"
# The schema requires a DDT on every order (Q-24); a legacy order without one gets this marker.
MISSING_DDT = "N/D"

# Same rules as ItalianFormats (platform/validation).
CAP = re.compile(r"^[0-9]{5}$")
EMAIL = re.compile(r"^[^\s@<>()\",;:]+@[^\s@<>()\",;:]+\.[^\s@<>()\",;:]{2,}$")
PHONE_CHARS = re.compile(r"^\+?[0-9][0-9 .()/-]*$")

# Column lengths of V003/V005.
LIMITS = {"company_name": 200, "address_line": 200, "city": 100, "province": 60, "phone": 30}


class Blocking(Exception):
    """A row the mapping cannot import without a decision: the file is not written."""


def normalise_phone(value):
    """ItalianFormats.normalisePhone: E.164-like, +39 when no prefix; None when not a phone."""
    raw = (value or "").strip()
    if not raw or len(raw) > 30 or not PHONE_CHARS.match(raw):
        return None
    digits = re.sub(r"[^0-9+]", "", raw)
    if digits.startswith("00"):
        digits = "+" + digits[2:]
    if not digits.startswith("+"):
        digits = "+39" + digits
    if "+" in digits[1:]:
        return None
    if not 8 <= len(digits) - 1 <= 15:
        return None
    return digits


def text(value, field, limit, where):
    out = re.sub(r"\s+", " ", (value or "").strip())
    if not out:
        raise Blocking(f"{where}: {field} is empty")
    if len(out) > limit:
        raise Blocking(f"{where}: {field} longer than {limit}")
    return out


def strip_code(value):
    """Codes lose surrounding whitespace, tabs included (DQ-29); inner characters are kept."""
    return re.sub(r"^\s+|\s+$", "", value or "")


def ts(value):
    """A legacy datetime(6), stored in UTC (12b §5.6), as a timestamptz literal."""
    return None if value is None else value + "+00"


def later(a, b):
    return a if a >= b else b


def lit(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return repr(value)
    if "\0" in value:
        raise Blocking("NUL character in a value")
    return "'" + value.replace("'", "''") + "'"


def money(value):
    """round_half_up(value, 2) of a source double, as text (12b §3.5)."""
    return str(Decimal(repr(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def decimal2(value):
    return Decimal(repr(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


class Report:
    def __init__(self):
        self.counts = collections.OrderedDict()
        self.anomalies = []
        self.amounts = Decimal("0.00")

    def count(self, key, n=1):
        self.counts[key] = self.counts.get(key, 0) + n

    def flag(self, code, legacy_ids, what):
        self.anomalies.append((code, sorted(legacy_ids), what))


def map_customers(rows, orders_by_customer, report):
    """12b §5.5 status table and column rules; DQ-01 duplicates resolved or blocking."""
    for s in rows:
        if s["stato"] not in CUSTOMER_STATES:
            raise Blocking(f"soggetti {s['id']}: unknown stato")
    groups = collections.defaultdict(list)
    for s in rows:
        groups[s["email"].strip().lower()].append(s)
    dropped = set()
    for email, group in groups.items():
        if len(group) < 2:
            continue
        # DQ-01: the same e-mail more than once. Resolved only when every row is an unconfirmed
        # registration without orders (the legacy never used them: lookups by e-mail failed):
        # the latest registration is what the person typed last and is kept.
        ids = [s["id"] for s in group]
        if all(s["stato"] == "NEW" and s["update_user"] is None and not orders_by_customer.get(s["id"]) for s in group):
            keep = max(group, key=lambda s: (s["create_tms"], s["id"]))
            gone = [i for i in ids if i != keep["id"]]
            dropped.update(gone)
            report.flag("DQ-01", ids, f"same e-mail on {len(ids)} unconfirmed registrations without orders: kept legacy id {keep['id']} (latest), not imported {gone}")
        else:
            raise Blocking(f"DQ-01: soggetti {ids} share one e-mail and cannot be merged by rule")

    customers = []
    for s in sorted(rows, key=lambda r: r["id"]):
        report.count(f"source soggetti {s['stato']}")
        if s["id"] in dropped:
            report.count("customers not imported (DQ-01)")
            continue
        where = f"soggetti {s['id']}"
        email = s["email"].strip().lower()
        if not EMAIL.match(email) or len(email) > 254:
            raise Blocking(f"{where}: e-mail not valid")
        if email != s["email"]:
            report.count("customers: e-mail trimmed or lower-cased")
        postal = (s["cap"] or "").strip()
        if not CAP.match(postal):
            raise Blocking(f"{where}: CAP is not five digits")
        phone = normalise_phone(s["telefono"])
        if phone is None:
            phone = text(s["telefono"], "telefono", LIMITS["phone"], where)
            report.flag("DQ-06", [s["id"]], "customer phone not a phone number: kept as typed")
        elif not re.fullmatch(r"\d{10}", s["telefono"].strip()):
            report.count("customers: phone not 10 digits, normalised")

        created = s["create_tms"]
        updated = s["update_tms"] or created
        status, verified, approved, declined, source = None, None, None, None, "CUSTOMER_AREA"
        if s["create_user"] in ("ADMIN", "ROLE_ADMIN"):
            source = "BACKOFFICE"  # created by staff, not through the registration form
        if s["stato"] == "NEW" and s["update_user"] is None:
            status = "PENDING_VERIFICATION"
        elif s["stato"] == "NEW" and s["update_user"] == "ROLE_ADMIN":
            status, declined = "DECLINED", updated
        elif s["stato"] == "NEW":
            status = "PENDING_VERIFICATION"
            report.flag("DQ-25", [s["id"]], "NEW with an unexpected update_user: imported as PENDING_VERIFICATION")
        elif s["stato"] == "PENDING":
            status, verified = "PENDING_APPROVAL", updated
        elif s["stato"] == "REGISTERED":
            status = "ACTIVE"
            if s["update_user"] == "ROLE_ADMIN":
                approved = updated
            elif s["update_user"] is None and source == "BACKOFFICE":
                approved = created  # created already approved by staff
                report.count("customers: created by staff, approved_at = registration time")
            else:
                approved = updated
                report.flag("DQ-25", [s["id"]], "REGISTERED without a staff update: approved_at = last update (estimated)")
            verified = approved  # estimated: the e-mail was confirmed before approval
        else:  # DECLINED (builds 2024-02-29 -> 2024-07-22)
            status, declined = "DECLINED", updated
        report.count(f"customers {status}")
        customers.append(
            {
                "legacy_id": s["id"],
                "email": email,
                "company_name": text(s["ragione_sociale"], "ragione_sociale", LIMITS["company_name"], where),
                "address_line": text(s["indirizzo"], "indirizzo", LIMITS["address_line"], where),
                "city": text(s["citta"], "citta", LIMITS["city"], where),
                "province": text(s["provincia"], "provincia", LIMITS["province"], where),
                "postal_code": postal,
                "phone": phone,
                "status": status,
                "email_verified_at": ts(verified),
                "registered_at": ts(created),
                "approved_at": ts(approved),
                "declined_at": ts(declined),
                "source": source,
                "created_at": ts(created),
                "updated_at": ts(updated),
            }
        )
    return customers, dropped


def rebuild_lines(units, where, report):
    """12b §5.5: consecutive equal units (by pallet.id) become one line with their count."""
    lines = []
    for p in sorted(units, key=lambda u: u["id"]):
        kind = UNIT_KINDS.get(p["type"])
        if kind is None:
            raise Blocking(f"{where}: unit {p['id']} has unknown type")
        dims = []
        for k in ("altezza", "lunghezza", "larghezza"):
            v = p[k]
            if v <= 0:
                raise Blocking(f"{where}: unit {p['id']} has {k} <= 0")
            if float(v) != int(v):
                report.flag("DQ-16", [p["id"]], f"{where}: {k} not whole centimetres, rounded")
            dims.append(int(round(v)))
        if p["peso"] <= 0:
            raise Blocking(f"{where}: unit {p['id']} has peso <= 0")
        key = (kind, *dims, decimal2(p["peso"]))
        if lines and lines[-1]["key"] == key:
            lines[-1]["quantity"] += 1
        else:
            lines.append({"key": key, "quantity": 1})
    out = []
    for n, line in enumerate(lines, start=1):
        kind, h, l, w, weight = line["key"]
        q = line["quantity"]
        out.append(
            {
                "line_no": n,
                "kind": kind,
                "height_cm": h,
                "length_cm": l,
                "width_cm": w,
                "weight_kg": weight,
                "quantity": q,
                # OrderTotals: weight x quantity; round2(h x l x w x quantity / 1 000 000).
                "line_weight_kg": (weight * q).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP),
                "line_volume_m3": (Decimal(h * l * w * q) / Decimal(1000000)).quantize(
                    Decimal("0.01"), rounding=ROUND_HALF_UP
                ),
            }
        )
    return out


def map_orders(rows, aggregates, units, customers_by_legacy, cutoff, report):
    agg_by_order = {}
    for d in aggregates:
        if d["spedizione_id"] in agg_by_order:
            raise Blocking(f"spedizioni {d['spedizione_id']}: more than one dettaglio_colli")
        agg_by_order[d["spedizione_id"]] = d
    units_by_agg = collections.defaultdict(list)
    for p in units:
        units_by_agg[p["dettaglio_colli_id"]].append(p)

    orders, seen_codes = [], {}
    for o in sorted(rows, key=lambda r: r["id"]):
        if o["create_tms"] < cutoff:
            continue
        where = f"spedizioni {o['id']}"
        report.count(f"source spedizioni {o['stato']} / {o['tracking']}")
        if o["stato"] not in ORDER_STATES:
            raise Blocking(f"{where}: unknown stato")
        tracking = TRACKING.get(o["tracking"].strip().lower())
        if tracking is None:
            raise Blocking(f"{where}: unknown tracking label")
        label = o["servizio_accessorio"].strip().lower()
        if label not in SERVICES:
            raise Blocking(f"{where}: unknown servizio_accessorio")
        customer = customers_by_legacy.get(o["mittente_id"])
        if customer is None:
            raise Blocking(f"{where}: customer {o['mittente_id']} not imported")
        agg = agg_by_order.get(o["id"])
        if agg is None:
            raise Blocking(f"{where}: no dettaglio_colli")
        order_units = units_by_agg.get(agg["id"], [])
        if not order_units:
            raise Blocking(f"{where}: no pallet units")
        lines = rebuild_lines(order_units, where, report)

        code_raw = o["codice"] or ""
        code = strip_code(code_raw)
        if code != code_raw:
            report.flag("DQ-29", [o["id"]], "code with surrounding whitespace: trimmed")
        status, temp_code, final_code, amount, approved, declined = None, None, None, None, None, None
        created, updated = o["create_tms"], o["update_tms"] or o["create_tms"]
        if o["stato"] == "REGISTERED":
            status = "APPROVED"
            if not code or code.upper().startswith("TEMP") or o["confirmation_ts"] is None:
                raise Blocking(f"{where}: DQ-32 approved without a definitive code or approval time")
            final_code = code
            # No TEMP code survives an approval (12b §3.3): the order gets a marker that names its legacy id.
            temp_code = f"LEGACY-{o['id']}"
            value = o["importo"] if o["importo"] is not None else o["importo_assicurato"]
            if value is None or value < 0:
                raise Blocking(f"{where}: DQ-31 approved without an amount")
            if o["importo"] is None:
                report.flag("DQ-31", [o["id"]], "importo NULL: amount from importo_assicurato")
            elif o["importo_assicurato"] is not None and abs(o["importo"] - o["importo_assicurato"]) > 0.005:
                report.flag("DQ-13", [o["id"]], "importo and importo_assicurato differ: importo kept")
            amount = money(value)
            approved = o["confirmation_ts"]
        elif o["stato"] == "PENDING":
            status, temp_code = "PENDING", code
        else:  # NEW or DECLINED persisted = declined (12b §3.2)
            status, temp_code, declined = "DECLINED", code, updated
        if not temp_code:
            raise Blocking(f"{where}: empty code")
        for c in filter(None, (temp_code, final_code)):
            k = c.lower()
            if k in seen_codes:
                raise Blocking(f"{where}: code also on spedizioni {seen_codes[k]}")
            seen_codes[k] = o["id"]

        ddt = (o["codice_documento_trasporto"] or "").strip()
        if not ddt:
            ddt = MISSING_DDT
            report.flag("DQ-11", [o["id"]], f"no DDT: '{MISSING_DDT}'")
        if len(ddt) > 100:
            raise Blocking(f"{where}: DDT longer than 100")

        postal = (o["cap"] or "").strip()
        if not CAP.match(postal):
            raise Blocking(f"{where}: recipient CAP is not five digits")
        recipient_phone = None
        if o["telefono"] and o["telefono"].strip():
            recipient_phone = normalise_phone(o["telefono"])
            if recipient_phone is None:
                recipient_phone = text(o["telefono"], "telefono", LIMITS["phone"], where)
                report.flag("DQ-06", [o["id"]], "recipient phone not a phone number: kept as typed")

        # Tracking events (12b §5.5), approved orders only; no invented intermediate states.
        events = []
        if status == "APPROVED":
            events.append((PRESA, approved, "APPROVAL"))
            if tracking != PRESA:
                if tracking == CONSEGNATO and o["delivery_ts"]:
                    at = o["delivery_ts"]
                elif tracking == TRANSITO and o["shipping_ts"]:
                    at = o["shipping_ts"]
                else:
                    at = updated  # estimated: the last change of the row
                    report.count("events: time estimated from update_tms")
                if at < approved:
                    report.flag("DQ-30", [o["id"]], "tracking time before approval: set to the approval time")
                    at = approved
                events.append((tracking, at, "STAFF"))
            if o["delivery_ts"] and tracking != CONSEGNATO:
                report.flag("DQ-30", [o["id"]], "delivery_ts with tracking not CONSEGNATO: no delivery event")
        elif tracking != 0:
            report.flag("DQ-17", [o["id"]], "tracking set on a non-approved order: reset to Non gestito")
            tracking = 0

        note = (o["note"] or "").strip()
        internal_note = None
        if note and note.lower() != NOTE_PLACEHOLDER:
            if len(note) > 5000:
                raise Blocking(f"{where}: note longer than 5000")
            internal_note = note
        customer_notes = None
        if internal_note and status == "PENDING" and o["update_user"] == "CUSTOMER":
            customer_notes = internal_note  # never touched by staff: it is the customer's own note

        total_w = sum(line["line_weight_kg"] for line in lines)
        total_v = sum(line["line_volume_m3"] for line in lines)
        if abs(float(total_w) - agg["peso"]) > 0.01:
            report.flag("DQ-28", [o["id"]], "dettaglio_colli.peso differs from the units: recomputed")
        n_epal = sum(1 for u in order_units if u["type"] == "EPAL")
        if (agg["epal"] or 0) != n_epal or (agg["pallet"] or 0) != len(order_units) - n_epal:
            report.flag("DQ-28", [o["id"]], "dettaglio_colli counts differ from the units")

        report.count(f"orders {status} / tracking {tracking}")
        report.count("order lines", len(lines))
        report.count("units", len(order_units))
        report.count("units EPAL", n_epal)
        report.count("tracking events", len(events))
        if internal_note:
            report.count("internal notes")
        if customer_notes:
            report.count("customer notes")
        service = SERVICES[label]
        if service:
            report.count(f"orders with service {service}")
        if amount is not None:
            report.amounts += Decimal(amount)
        orders.append(
            {
                "legacy_id": o["id"],
                "legacy_customer_id": o["mittente_id"],
                "status": status,
                "temp_code": temp_code,
                "code": final_code,
                "transport_document_code": ddt,
                "package_count": agg["colli"],
                "service": service,
                "customer_notes": customer_notes,
                "amount": amount,
                "total_weight_kg": str(total_w),
                "total_volume_m3": str(total_v),
                "tracking_state_id": tracking,
                "tracking_updated_at": ts(events[-1][1]) if events else None,
                "recipient_name": text(o["ragione_sociale"], "ragione_sociale", LIMITS["company_name"], where),
                "recipient_address_line": text(o["indirizzo"], "indirizzo", LIMITS["address_line"], where),
                "recipient_city": text(o["citta"], "citta", LIMITS["city"], where),
                "recipient_province": text(o["provincia"], "provincia", LIMITS["province"], where),
                "recipient_postal_code": postal,
                "recipient_phone": recipient_phone,
                "submitted_at": ts(created),
                "approved_at": ts(approved),
                "declined_at": ts(declined),
                "source": "BACKOFFICE" if o["create_user"] == "ROLE_ADMIN" else "CUSTOMER_AREA",
                "created_at": ts(created),
                "updated_at": ts(updated),
                "lines": lines,
                "events": [(state, ts(at), source) for state, at, source in events],
                "internal_note": internal_note,
            }
        )
    return orders


def values(rows, columns):
    return ",\n".join("    (" + ", ".join(lit(r[c]) for c in columns) + ")" for r in rows)


def write_sql(path, customers, orders, report, meta):
    c_cols = [
        "legacy_id", "email", "company_name", "address_line", "city", "province", "postal_code", "phone",
        "status", "email_verified_at", "registered_at", "approved_at", "declined_at", "source", "created_at",
        "updated_at",
    ]
    o_cols = [
        "legacy_id", "legacy_customer_id", "status", "temp_code", "code", "transport_document_code",
        "package_count", "service", "customer_notes", "amount", "total_weight_kg", "total_volume_m3",
        "tracking_state_id", "tracking_updated_at", "recipient_name", "recipient_address_line", "recipient_city",
        "recipient_province", "recipient_postal_code", "recipient_phone", "submitted_at", "approved_at",
        "declined_at", "source", "created_at", "updated_at",
    ]
    lines = [dict(legacy_order_id=o["legacy_id"], **{k: v for k, v in line.items() if k != "key"}) for o in orders for line in o["lines"]]
    for line in lines:
        for k in ("weight_kg", "line_weight_kg", "line_volume_m3"):
            line[k] = str(line[k])
    events = [
        {"legacy_order_id": o["legacy_id"], "state_id": s, "occurred_at": at, "source": src}
        for o in orders
        for s, at, src in o["events"]
    ]
    notes = [
        {"legacy_order_id": o["legacy_id"], "body": o["internal_note"], "updated_at": o["updated_at"]}
        for o in orders
        if o["internal_note"]
    ]
    expected = {
        "customers": len(customers),
        "orders": len(orders),
        "order_lines": len(lines),
        "tracking_events": len(events),
        "internal_notes": len(notes),
    }
    summary = {"source": meta["source"], "cutoff_utc": meta["cutoff"], **expected}

    out = []
    w = out.append
    w("-- One-off import of the legacy data (Q-55, owner decision of 2026-10-28; 12b §5.5).")
    w("-- Generated by tools/legacy-import/generate_import_sql.py from " + meta["source"])
    w(f"-- on {meta['generated']}; orders created from {meta['cutoff']} UTC.")
    w("-- HOLDS REAL PERSONAL DATA: keep it under .local/ or on the server, never commit or share it.")
    w("-- Load with: psql -v ON_ERROR_STOP=1 -f import_legacy.sql (the file holds its own BEGIN/COMMIT)")
    w("")
    w("\\set ON_ERROR_STOP on")
    w("BEGIN;")
    w("")
    w("-- Runs once, after the migrations, on an app database with at least one ADMIN when notes exist.")
    w("DO $$")
    w("BEGIN")
    w("    IF EXISTS (SELECT 1 FROM audit_log WHERE action = 'legacy.import') THEN")
    w("        RAISE EXCEPTION 'legacy import: already loaded (audit_log action legacy.import)';")
    w("    END IF;")
    if notes:
        w("    IF NOT EXISTS (SELECT 1 FROM staff_user WHERE role = 'ADMIN') THEN")
        w("        RAISE EXCEPTION 'legacy import: create an ADMIN first (create-staff-user): the internal notes need one';")
        w("    END IF;")
    w("END $$;")
    w("")
    w("CREATE TEMP TABLE imp_customer (")
    w("    legacy_id bigint PRIMARY KEY, email varchar(254) NOT NULL UNIQUE, company_name varchar(200) NOT NULL,")
    w("    address_line varchar(200) NOT NULL, city varchar(100) NOT NULL, province varchar(60) NOT NULL,")
    w("    postal_code varchar(10) NOT NULL, phone varchar(30) NOT NULL, status varchar(24) NOT NULL,")
    w("    email_verified_at timestamptz, registered_at timestamptz NOT NULL, approved_at timestamptz,")
    w("    declined_at timestamptz, source varchar(16) NOT NULL, created_at timestamptz NOT NULL,")
    w("    updated_at timestamptz NOT NULL) ON COMMIT DROP;")
    w("INSERT INTO imp_customer (" + ", ".join(c_cols) + ") VALUES")
    w(values(customers, c_cols) + ";")
    w("")
    w("CREATE TEMP TABLE imp_order (")
    w("    legacy_id bigint PRIMARY KEY, legacy_customer_id bigint NOT NULL REFERENCES imp_customer (legacy_id),")
    w("    status varchar(16) NOT NULL, temp_code varchar(64) NOT NULL UNIQUE, code varchar(255),")
    w("    transport_document_code varchar(100) NOT NULL, package_count integer, service varchar(60),")
    w("    customer_notes text, amount numeric(10,2), total_weight_kg numeric(12,2) NOT NULL,")
    w("    total_volume_m3 numeric(10,2) NOT NULL, tracking_state_id smallint NOT NULL, tracking_updated_at timestamptz,")
    w("    recipient_name varchar(200) NOT NULL, recipient_address_line varchar(200) NOT NULL,")
    w("    recipient_city varchar(100) NOT NULL, recipient_province varchar(60) NOT NULL,")
    w("    recipient_postal_code varchar(10) NOT NULL, recipient_phone varchar(30), submitted_at timestamptz NOT NULL,")
    w("    approved_at timestamptz, declined_at timestamptz, source varchar(16) NOT NULL,")
    w("    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL) ON COMMIT DROP;")
    if orders:
        w("INSERT INTO imp_order (" + ", ".join(o_cols) + ") VALUES")
        w(values(orders, o_cols) + ";")
    w("")
    l_cols = ["legacy_order_id", "line_no", "kind", "height_cm", "length_cm", "width_cm", "weight_kg", "quantity", "line_weight_kg", "line_volume_m3"]
    w("CREATE TEMP TABLE imp_line (legacy_order_id bigint NOT NULL REFERENCES imp_order (legacy_id), line_no smallint NOT NULL,")
    w("    kind varchar(8) NOT NULL, height_cm integer NOT NULL, length_cm integer NOT NULL, width_cm integer NOT NULL,")
    w("    weight_kg numeric(9,2) NOT NULL, quantity integer NOT NULL, line_weight_kg numeric(12,2) NOT NULL,")
    w("    line_volume_m3 numeric(10,2) NOT NULL, PRIMARY KEY (legacy_order_id, line_no)) ON COMMIT DROP;")
    if lines:
        w("INSERT INTO imp_line (" + ", ".join(l_cols) + ") VALUES")
        w(values(lines, l_cols) + ";")
    w("")
    e_cols = ["legacy_order_id", "state_id", "occurred_at", "source"]
    w("CREATE TEMP TABLE imp_event (legacy_order_id bigint NOT NULL REFERENCES imp_order (legacy_id),")
    w("    state_id smallint NOT NULL, occurred_at timestamptz NOT NULL, source varchar(16) NOT NULL) ON COMMIT DROP;")
    if events:
        w("INSERT INTO imp_event (" + ", ".join(e_cols) + ") VALUES")
        w(values(events, e_cols) + ";")
    w("")
    n_cols = ["legacy_order_id", "body", "updated_at"]
    w("CREATE TEMP TABLE imp_note (legacy_order_id bigint PRIMARY KEY REFERENCES imp_order (legacy_id),")
    w("    body text NOT NULL, updated_at timestamptz NOT NULL) ON COMMIT DROP;")
    if notes:
        w("INSERT INTO imp_note (" + ", ".join(n_cols) + ") VALUES")
        w(values(notes, n_cols) + ";")
    w("")
    w(SQL_BODY.replace("@SUMMARY@", json.dumps(summary).replace("'", "''")).replace("@EXPECTED@", json.dumps(expected)))
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n")
    return expected


# The part of the file that does not depend on the rows: checks against the target database,
# the inserts in dependency order with the ids the sequences hand out, the final assertions.
SQL_BODY = r"""
-- Codes must be free in the target database (unique ignoring case, as the app checks them).
DO $$
DECLARE n integer;
BEGIN
    SELECT count(*) INTO n FROM imp_order i JOIN shipment_order o
        ON lower(btrim(o.temp_code)) = lower(btrim(i.temp_code))
        OR (i.code IS NOT NULL AND lower(btrim(o.code)) = lower(btrim(i.code)))
        OR (i.code IS NOT NULL AND lower(btrim(o.temp_code)) = lower(btrim(i.code)));
    IF n > 0 THEN
        RAISE EXCEPTION 'legacy import: % order code(s) already used in this database', n;
    END IF;
    SELECT count(*) INTO n FROM imp_customer i
        JOIN customer_user u ON lower(btrim(u.email)) = i.email
        LEFT JOIN customer c ON lower(btrim(c.email)) = i.email
        WHERE c.id IS NULL OR c.id <> u.customer_id;
    IF n > 0 THEN
        RAISE EXCEPTION 'legacy import: % e-mail(s) are the login of another customer', n;
    END IF;
END $$;

-- Customers. A legacy e-mail that is already a customer here (registered in the new app before
-- the import) is not created again: its orders join that customer.
CREATE TEMP TABLE map_customer (legacy_id bigint PRIMARY KEY, customer_id bigint NOT NULL, created boolean NOT NULL) ON COMMIT DROP;
INSERT INTO map_customer (legacy_id, customer_id, created)
SELECT i.legacy_id, c.id, false FROM imp_customer i JOIN customer c ON lower(btrim(c.email)) = i.email;

WITH ins AS (
    INSERT INTO customer (email, company_name, address_line, city, province, postal_code, phone, status,
                          email_verified_at, registered_at, approved_at, declined_at, source, created_at, updated_at)
    SELECT email, company_name, address_line, city, province, postal_code, phone, status,
           email_verified_at, registered_at, approved_at, declined_at, source, created_at, updated_at
      FROM imp_customer i
     WHERE NOT EXISTS (SELECT 1 FROM map_customer m WHERE m.legacy_id = i.legacy_id)
     ORDER BY legacy_id
    RETURNING id, email)
INSERT INTO map_customer (legacy_id, customer_id, created)
SELECT i.legacy_id, ins.id, true FROM ins JOIN imp_customer i ON i.email = ins.email;

INSERT INTO customer_user (customer_id, email, role, created_at)
SELECT m.customer_id, i.email, 'OWNER', i.created_at
  FROM imp_customer i JOIN map_customer m ON m.legacy_id = i.legacy_id
 WHERE m.created
 ORDER BY i.legacy_id;

-- Orders: sender = the legacy customer row (snapshot), recipient = the legacy order row.
CREATE TEMP TABLE map_order (legacy_id bigint PRIMARY KEY, order_id bigint NOT NULL) ON COMMIT DROP;
WITH ins AS (
    INSERT INTO shipment_order (customer_id, status, temp_code, code, transport_document_code, package_count,
                                accessory_service_id, customer_notes, amount, amount_vat_included,
                                total_weight_kg, total_volume_m3, tracking_state_id, tracking_updated_at,
                                sender_name, sender_address_line, sender_city, sender_province, sender_postal_code,
                                sender_email, sender_phone, recipient_name, recipient_address_line, recipient_city,
                                recipient_province, recipient_postal_code, recipient_phone, recipient_email,
                                submitted_at, approved_at, declined_at, source, created_at, updated_at)
    SELECT m.customer_id, o.status, o.temp_code, o.code, o.transport_document_code, o.package_count,
           s.id, o.customer_notes, o.amount, CASE WHEN o.amount IS NULL THEN NULL ELSE false END, -- Q-05 default
           o.total_weight_kg, o.total_volume_m3, o.tracking_state_id, o.tracking_updated_at,
           c.company_name, c.address_line, c.city, c.province, c.postal_code,
           c.email, c.phone, o.recipient_name, o.recipient_address_line, o.recipient_city,
           o.recipient_province, o.recipient_postal_code, o.recipient_phone, NULL,
           o.submitted_at, o.approved_at, o.declined_at, o.source, o.created_at, o.updated_at
      FROM imp_order o
      JOIN imp_customer c ON c.legacy_id = o.legacy_customer_id
      JOIN map_customer m ON m.legacy_id = o.legacy_customer_id
      LEFT JOIN accessory_service s ON s.name = o.service
     ORDER BY o.legacy_id
    RETURNING id, temp_code)
INSERT INTO map_order (legacy_id, order_id)
SELECT o.legacy_id, ins.id FROM ins JOIN imp_order o ON o.temp_code = ins.temp_code;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM imp_order WHERE service IS NOT NULL AND service NOT IN (SELECT name FROM accessory_service)) THEN
        RAISE EXCEPTION 'legacy import: an accessory service of the orders is missing from accessory_service';
    END IF;
END $$;

INSERT INTO order_line (order_id, line_no, kind, height_cm, length_cm, width_cm, weight_kg, quantity,
                        line_weight_kg, line_volume_m3)
SELECT m.order_id, l.line_no, l.kind, l.height_cm, l.length_cm, l.width_cm, l.weight_kg, l.quantity,
       l.line_weight_kg, l.line_volume_m3
  FROM imp_line l JOIN map_order m ON m.legacy_id = l.legacy_order_id
 ORDER BY l.legacy_order_id, l.line_no;

INSERT INTO tracking_event (order_id, state_id, occurred_at, source)
SELECT m.order_id, e.state_id, e.occurred_at, e.source
  FROM imp_event e JOIN map_order m ON m.legacy_id = e.legacy_order_id
 ORDER BY e.legacy_order_id, e.occurred_at, e.state_id;

-- The legacy note was one shared column, written by the customer and overwritten by staff (12b §3.6):
-- it becomes the internal note, signed by the first ADMIN (the legacy stored a role, not a person).
INSERT INTO order_internal_note (order_id, body, updated_at, updated_by)
SELECT m.order_id, n.body, n.updated_at,
       (SELECT id FROM staff_user WHERE role = 'ADMIN' ORDER BY created_at, id LIMIT 1)
  FROM imp_note n JOIN map_order m ON m.legacy_id = n.legacy_order_id
 ORDER BY n.legacy_order_id;

INSERT INTO audit_log (actor_type, action, entity_type, after)
VALUES ('SYSTEM', 'legacy.import', NULL, '@SUMMARY@'::jsonb);

-- Assertions: what was written equals what the file carries; the identity sequences are ahead of
-- every id (they handed the ids out, so this only guards against a hand-made insert).
DO $$
DECLARE
    expected jsonb := '@EXPECTED@';
    got jsonb;
    t text;
    seq text;
    last bigint;
    top bigint;
BEGIN
    SELECT jsonb_build_object(
        'customers', (SELECT count(*) FROM map_customer),
        'orders', (SELECT count(*) FROM map_order),
        'order_lines', (SELECT count(*) FROM order_line l JOIN map_order m ON m.order_id = l.order_id),
        'tracking_events', (SELECT count(*) FROM tracking_event e JOIN map_order m ON m.order_id = e.order_id),
        'internal_notes', (SELECT count(*) FROM order_internal_note n JOIN map_order m ON m.order_id = n.order_id))
      INTO got;
    IF got <> expected THEN
        RAISE EXCEPTION 'legacy import: written % <> expected %', got, expected;
    END IF;
    IF EXISTS (SELECT 1 FROM map_order m JOIN shipment_order o ON o.id = m.order_id
                 JOIN imp_order i ON i.legacy_id = m.legacy_id
                WHERE o.total_weight_kg <> (SELECT sum(line_weight_kg) FROM order_line WHERE order_id = o.id)
                   OR o.total_volume_m3 <> (SELECT sum(line_volume_m3) FROM order_line WHERE order_id = o.id)) THEN
        RAISE EXCEPTION 'legacy import: order totals differ from their lines';
    END IF;
    FOREACH t IN ARRAY ARRAY['customer', 'customer_user', 'shipment_order', 'order_line', 'tracking_event', 'audit_log'] LOOP
        seq := pg_get_serial_sequence(t, 'id');
        EXECUTE format('SELECT coalesce(max(id), 0) FROM %I', t) INTO top;
        EXECUTE format('SELECT coalesce(last_value, 0) FROM %s', seq) INTO last;
        IF last < top THEN
            RAISE EXCEPTION 'legacy import: sequence % (%) behind max(id) of % (%)', seq, last, t, top;
        END IF;
    END LOOP;
    RAISE NOTICE 'legacy import: % (customers created: %, reused: %)', got,
        (SELECT count(*) FROM map_customer WHERE created), (SELECT count(*) FROM map_customer WHERE NOT created);
END $$;

COMMIT;
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dump")
    ap.add_argument("out")
    ap.add_argument("--cutoff", default="2026-04-28 00:00:00", help="first create_tms of the imported orders, UTC")
    args = ap.parse_args()
    cutoff = datetime.datetime.strptime(args.cutoff, "%Y-%m-%d %H:%M:%S").strftime("%Y-%m-%d %H:%M:%S")

    tables = read_dump(args.dump)
    report = Report()
    for name in ("users", "user_session", "dettaglio_colli_seq"):
        if name in tables:
            report.count(f"source {name} (not imported)", len(tables[name]["rows"]))
    soggetti = tables["soggetti"]["rows"]
    spedizioni = tables["spedizioni"]["rows"]
    orders_by_customer = collections.Counter(o["mittente_id"] for o in spedizioni)
    try:
        customers, _ = map_customers(soggetti, orders_by_customer, report)
        by_legacy = {c["legacy_id"]: c for c in customers}
        report.count("source spedizioni (all dates)", len(spedizioni))
        report.count("source spedizioni before the cut-off (not imported)", sum(1 for o in spedizioni if o["create_tms"] < cutoff))
        orders = map_orders(
            spedizioni,
            tables["dettaglio_colli"]["rows"],
            tables["pallet"]["rows"],
            by_legacy,
            cutoff,
            report,
        )
        expected = write_sql(
            args.out,
            customers,
            orders,
            report,
            {
                "source": os.path.basename(args.dump),
                "cutoff": cutoff,
                "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
            },
        )
    except Blocking as e:
        print(f"BLOCKING: {e}", file=sys.stderr)
        return 2
    for k, v in report.counts.items():
        print(f"{k}: {v}")
    print(f"sum of the amounts of the approved orders: {report.amounts}")
    print("expected in the target:", json.dumps(expected))
    for code, ids, what in report.anomalies:
        print(f"{code} legacy ids {ids}: {what}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
