# Phase 0 reader — chapter 08 · Domain model and data migration

Source: `docs/brief/08-domain-model-and-migration.md` (1,182 lines), read in full on 2026-10-16. Citations keep
the chapter's convention (`ls-order@dev` = dbe7292, `ls-cms@dev` = f0c881c, `ls-home@fe4b056` ≈ head 9b78785,
`ls@migration` = ca1b0d0). Chapter 12b wins over §1, §3 and §5 and 12d §6.5 over §4.7–4.9; this reader reports
the chapter as corrected. The office e-mail and WhatsApp number embedded in §4.7 step 2 are referred to by place.

## 1. Scope

The chapter gives the legacy entities as the frontends see them (§1), the target PostgreSQL model for customers,
logins, staff, orders, lines, services, tracking events, notes, leads and audit with state machines and identifier
rules (§2), the legacy→target mapping and normalisation rules (§3), the migration plan — dump first, API fallback,
canonical snapshot, idempotent importer, reconciliation report, rehearsal, cut-over, rollback, decommissioning (§4) —
and the data-quality list DQ-01…24 (§5). Reading map: **Phase 1 foundations** §2.1–2.5 (the complete M1 schema is a
Phase 1 deliverable, 10 §8); **site** §2.3 `lead`; **tracking** §2.3 `tracking_state`, `tracking_event`, §2.4, §2.5;
**customers** §2.3 `customer`, `customer_user`, `login_token`, §2.4; **orders** §2.3 `shipment_order`, `order_line`,
`accessory_service`, §2.4, §2.5; **back office** §2.3 `staff_user`, `order_internal_note`, `audit_log*`;
**migration** §1, §3–§5 with 12b §5.5–5.6 as the authoritative mapping; **cut-over** §4.7–4.9; **pricing M2** §2.6.

## 2. Requirement IDs touched

| ID | Sections that specify or constrain it |
|---|---|
| PUB-06, PUB-07, PUB-11 | §2.3 `lead` (columns mirror `form.mjs`, honeypot not stored, `retain_until`), §2.4 lead states |
| PUB-04 | §4.7 step 6 (DNS and 301s for apex, `www`, `app.`, `ordini.`, `dashboard.`, S3 buckets) |
| TRK-01, TRK-02, TRK-03 | §2.3 `tracking_state` (labels, `public_title`), `tracking_event` (`location`, fixed `public_note`), §2.5 lookup rule, §3.6 matcher |
| API-01, API-02, API-03 | §2.3 `accessory_service.legacy_json` served verbatim, §2.5, §4.7 step 7 (EP-13/19/20 parity checks, 410 paths) |
| CUS-01, CUS-02, CUS-03, CUS-06 | §2.3 `customer`, `customer_user`, `login_token` (purposes, 15 min / 24 h, hashed, single use), §2.4 customer machine, §3.8 normalisation |
| CUS-04, CUS-05 | §2.3 `shipment_order` (snapshots, `temp_code`, `idempotency_key`, `package_count`, `recipient_email`), `order_line` (formulas), §2.4 order, §2.5 URL by code |
| ADM-01 | §2.3 `staff_user` (roles, lock-out), §3.9 (no staff in migrations or seeds; `create-staff-user` command) |
| ADM-02, ADM-03 | §2.4 customer guards (verified e-mail, Q-28; decline with reason), §3.1 list → status mapping |
| ADM-04, ADM-05, ADM-08 | §2.4 approve guard (code unique ignoring case, amount > 0, comma accepted), `legacy_payload` for detail, §2.5 codes |
| ADM-06, ADM-10 | §2.3 `tracking_state` seed read-only, §2.4 tracking (any order, event per change, EML-09 on `CONSEGNATO`) |
| ADM-07 | §2.3 `order_internal_note` (one per order, 5,000 chars, edits audited), §3.7 |
| ADM-09 | §2.3 supporting table `report_run`; §4.2 (dates are needed for the date-range report) |
| EML-01…06, EML-09 | §2.4 side-effects column (which e-mail fires on which transition); §4.1 importer never writes the outbox |
| MIG-01, MIG-02, MIG-03 | §3 (mapping), §4.1–4.6 (importer, decisions file, report), §4.7–4.9 (freeze, switch, rollback, retirement), §5 |
| NFR-02, NFR-03, NFR-04, NFR-05 | §2.3 `audit_log` / `audit_log_detail` (append-only role, personal values split, purge), `login_token.requested_ip_hash`, §4.1 encrypted snapshots, §4.9 credentials rotation |
| ENH-05, ENH-06, ENH-07, ENH-09, ENH-13, ENH-14, ENH-15, ENH-02 | §2.3 M2 columns (`location`, `customer_address`, `totp_secret`, `source = BULK`), §2.4 lead machine, §2.6 one authority for the amount |

## 3. Rules to keep (M1 parity)

| Rule | Citation (as given by the chapter) | Serves |
|---|---|---|
| The e-mail is the customer's business key; `id` is display-only | ls-order@dev:js/function/data-handler.js:8-10; ls-cms@dev:js/function/component-handler.js:339-341, 374-376 | CUS-01, MIG-02 |
| Registration fields: "Ragione Sociale/Nominativo", via, provincia, città, CAP, telefono, e-mail | ls-order@dev:js/constant/language-messages.js:39; js/rest/dto/customer-request.js:13 | CUS-02 |
| Approval only for a customer whose e-mail is verified; decline keeps the record, re-registration allowed | §2.4 citing 12b §3.1 (Q-28, Q-09) | ADM-02, CUS-02 |
| Order payload: recipient block, lines, DDT, accessory service, note; recipient e-mail = customer's (never a recipient datum) | ls-order@dev:js/rest/dto/order-request.js:8-22 | CUS-04, DQ-23 |
| Line fields and types: h/l/w in cm, `peso` per unit, `quantity`; `"0"` = "Pallet Generico/Colli", `"1"` = "Epal" | ls-order@dev:js/rest/dto/order-request.js:32-36; js/constant/costant.js:86-90 | CUS-04, ADM-08 |
| Totals: weight = Σ peso × quantità; volume = Σ round₂(h × l × w / 1 000 000 × quantità), per line (OP-R5) | ls-order@dev:js/function/data-handler.js:88-115 | CUS-04, MIG-02 |
| Pending orders carry a `TEMP` code shown as "-" in the CMS; approval = staff type the code and the amount, tracking → `PRESA` | ls-cms@dev:js/components/types/data-tables-component.js:242; js/function/component-handler.js:408, 421-425 | ADM-04 |
| Amount at approval required and > 0 (`@Positive`); the e-mail/report show it | §2.4 citing 12c §4.2 | ADM-04, EML-05 |
| Six tracking states ids 0–5 with the legacy names and labels; any state may follow any; EML-09 when `CONSEGNATO` | chapter 02 §9.1; chapter 04 §4.6; 12c §1.3 L-E8 | ADM-06, ADM-10, TRK-01 |
| Six accessory services ids 0–5, item 0 = "Nessun servizio accessorio" (never a choice) | §3.5 citing 12b §2.2 | ADM-10, API-02, CUS-04 |
| One note per order, read and replaced by code; "Nessuna nota presente" is a placeholder, not data | ls-cms@dev:js/rest/rest-caller.js:88-96; CMS-11 | ADM-07, MIG-02 |
| CMS list columns (ID, Ragione sociale, …, Email confermata; Codice, Cliente mittente, …, Stato, Tracking) | ls-cms@dev:js/components/types/data-tables-component.js:57-65, 150-162 | ADM-02…05 |
| Public tracking: unknown and `TEMP` codes answer "Non gestito"; tracking state matched by id, name or label ignoring accents/case/spaces | §2.5 (CU-02, Q-38); ls-home@fe4b056:public/js/tracking.js:23-30, 81-91 | TRK-01, TRK-02, API-01 |
| Quote-form values kept as lead attributes (`tipo_pallet` eur/industriale/mezzo/quarto/altro, services, extras) | ls-home@fe4b056:netlify/functions/form.mjs:26, 52-152 | PUB-06, PUB-11 |
| Legacy ids and codes kept in their own columns so old links, documents and e-mails keep working | §2.1 principle 1 (chapter 10 §2) | MIG-02, API-03 |
| Migration is read-only towards the legacy; writes blocked by path in the VPS nginx; dump via `scripts/backup.sh`; `users`/`user_session` dropped from every copy | §4.1, §4.2 step 1, §4.7 step 2 (12d §6.4–6.5) | MIG-01, MIG-03, NFR-03 |

## 4. Deliberate fixes (each needs an ADR)

| Fix (legacy not reproduced) | Where | Proposed ADR title |
|---|---|---|
| Explicit customer states `PENDING_VERIFICATION / PENDING_APPROVAL / ACTIVE / DECLINED` instead of `NEW/PENDING/REGISTERED` with NEW doubling as "declined" | §2.4, 12b §3.1 | Customer lifecycle states and declined records |
| Declined orders kept as `DECLINED` (legacy resets to `NEW` with the TEMP code) | §2.4, 12b §3.2 | Order states: pending, approved, declined are final |
| Tracking history as append-only events with time and user; re-setting the same state creates no event (legacy overwrites three timestamps, no history) | §2.3, §2.4 | Tracking events replace in-place tracking timestamps |
| Customer note and internal staff note split (legacy: one shared column overwritten by staff) | §2.3, §3.7, Q-26 | Separate internal notes from the customer's order note |
| Amount as `numeric(10,2)` + VAT flag; Italian comma accepted at input (legacy: `DOUBLE`, "180,50" → 500) | §2.1, §2.3, §2.4 | Money as decimal with an explicit VAT flag |
| E-mails trimmed and lower-cased with uniqueness; DQ-01 duplicates merged through the decisions file (legacy locks such customers out) | §2.1, §3.8, §4.5 | E-mail normalisation and duplicate-customer merge |
| Lines stored as lines with quantity, decimal totals (legacy: one row per unit + aggregate with whole-m³ volume) | §2.3, §3.4, 12b §5.5 | Order lines regrouped from legacy unit rows |
| Staff users not migrated; roles `ADMIN`/`OPERATOR`; accounts via command + one-time link; no staff in migrations or seeds | §3.9 | Staff accounts: fresh creation, no legacy passwords |
| New temporary code `TEMP-` + 8 base-32 chars (legacy `TEMP` + UUID); definitive code unique trimmed and case-insensitive, raw kept | §2.5, 12b §5.4 | Order codes: temporary format and uniqueness rule |
| Login tokens hashed, single-use, consumed by `POST`; registration link for unknown e-mails (legacy link never expires, confirms on `GET`) | §2.3 `login_token`, 10 §2 | Magic-link authentication for customers |
| Sender and recipient snapshots on orders; `recipient_email` never copied from the customer | §2.1, §2.3, §3.2 | Address snapshots on orders |
| Leads stored on submission (legacy stores nothing); audit log append-only with personal values in `audit_log_detail` | §2.3 `lead`, `audit_log` | Leads storage; audit log design and retention |
| Legacy admin tools and `POST /mono/cms/order/update` not ported; corrections after approval `ADMIN`-only with audit | §2.4 | Post-approval corrections (ADMIN, audited) |
| Public tracking code matching trimmed and space-stripped (legacy: no trim); no personal data in the answer | §2.5, TRK-02/03 | Tracking lookup normalisation and public payload |

## 5. Unknowns by ID (recommended default from chapter 11 / this chapter)

* Q-02 dump and schema — optional; build against 12b §1.7, drift checks on the real dump; API export only as fallback (§4.2–4.3).
* Q-05 meaning of the amount / VAT — price charged, VAT excluded, decimal + flag, > 0; legacy rows `amount_vat_included` null until answered (§2.3, §3.2, DQ-13).
* Q-06 codes — staff type them; uniqueness trimmed + case-insensitive; **no proposal of the next code** (11 default; §2.5 proposes one, see §6).
* Q-09 declines — keep the record as `DECLINED`; re-registration allowed, new data applied when the link is used (§2.4).
* Q-10 who registers / VAT — companies and persons; `vat_number`, `tax_code` optional. Q-11 users per customer — one `OWNER` in M1.
* Q-13 pick-up address — the customer's registered address, snapshotted as `sender_*` (DQ-14 for legacy rows).
* Q-17 staff and roles — `ADMIN`, `OPERATOR`; 2FA in M2 (§2.3, §3.9). Q-24 DDT — mandatory for new orders; "Numero Colli" optional count.
* Q-18 retention — leads/requests 24 months, shipments 10 years, audit 24 months, outbox bodies and IP hashes 30 days (`retain_until`, purge jobs).
* Q-19 / Q-40 / Q-41 cut-over window (Saturday afternoon, < 1 h), rollback until the end of the first working day, customer notice by the owner.
* Q-20 Netlify site, Q-46 AWS-era resources, Q-53 old credentials — retire / inventory / rotate after cut-over (§4.9). Q-42 DQ-01 merges — decisions file.
* Q-26 notes split — yes (ADR). Q-28 approve only verified — yes. Q-38 TEMP codes answer "Non gestito" — yes. Q-03 matching — trimmed, case-insensitive.
* Q-35 line limits — integers ≥ 1, no maximum, warning above 240 cm / 1,200 kg. Q-36 phone — required (overrides §2.3 "optional").
* CU-02, CU-03, CU-05, CU-08, CU-09, CU-10, CU-16, CU-17, CU-18, CU-19, CU-24 — settled by 12b §5.8 (labels on the wire, same note column, decline → NEW, no history, units per row). CU-13 staff auth — settled (roles ADMIN/CUSTOMER in `users`). CU-21 production CMS build — still unknown (owner).
* OP-01/02/04/07/08/09/10/11/12/14/15, OP-R1, OP-R5 — portal quirks and rules folded into the DQ list and CUS-02/CUS-04 validation (defaults as chapter 03).
* CMS-04, CMS-07, CMS-11, CMS-12, CMS-13, CMS-14, CMS-20, CMS-25 — CMS defects mapped to DQ-19, DQ-18, DQ-18, DQ-22, DQ-12, DQ-08/09, DQ-24 (impossible), DQ-20.
* LPS-S4 — keep both S3 bucket names as redirects for good. F-11 (via 12d §6.5) — never `scripts/deploy.sh` or `docker compose down -v` during rollback.
* DQ-01…24 (§5) with 12b §5.2 verdicts: DQ-08/10/12/16/17/24 cannot happen (assertions); DQ-25…37 added by 12b §5.3 (NEW ambiguity, per-unit lines, coarse totals, untrimmed codes, timestamp meaning, drift, hard deletes).
* PR-*, SEC-* — none raised directly; §2.6 defers the pricing tables to chapter 07 §6.2 and the amount authority to the pricing ADR.

## 6. Contradictions and spot checks

1. §2.3 `customer.phone` "optional unless the owner decides otherwise (OP-08)" vs CUS-02 and Q-36 (phone **required**, as the legacy backend requires it): chapter 09 wins; column stays nullable only for legacy rows.
2. §2.5 "the app proposes the next code when the imported codes follow a recognisable sequence" vs chapter 11 Q-06 default ("proposes nothing until you say how codes are made") and 12b §5.4 ("do not assume a sequence"): no proposal in M1.
3. §3.1 last paragraph: unverified customers are listed "under In Approvazione with Email confermata: NO, as today" vs 12b §3.1 (EP-04 lists only `PENDING`; the column is always "SI"; CMS-20/DQ-24 impossible). Whether the new back office shows `PENDING_VERIFICATION` customers is a decision, not parity (Gate 0).
4. §2.4 tracking: "setting the current state again creates no event" vs 12b §3.4 (legacy re-stamps `shipping_ts`/`delivery_ts` and, per O4, re-sends the delivery e-mail on every `CONSEGNATO` call). Deliberate fix: ADM-06 e-mail once per transition; record in the tracking ADR.
5. §3.6 "one `tracking_event` per approved order, `occurred_at` best known date else import time" and the §3.3 example (`time_is_estimated: true`) vs 12b §5.5 (two events: `PRESA` at `confirmation_ts` exact, plus the current state with its own timestamp): 12b wins; §3.3 is superseded by 12b §5.7.
6. §4.5 blocking issues (DQ-01, DQ-08, DQ-10, DQ-12) and importer keys ("e-mail when there is no id", "code when there is no id") vs 12b §5.6 (blocking: DQ-01, DQ-29 empty codes, unknown enum labels, violated assertions; ids always exist in the dump): 12b wins; the API-fallback rules stay only for §4.3.
7. §4.7 step 2 proposes a maintenance text with the office Gmail address and WhatsApp number verbatim, while PUB-02 and Q-43 make contacts one configuration value: the maintenance page must read them from configuration.
8. Internal: §1.2 "`NEW` = no record for that e-mail" and §1.3 "`importoAssicurato` free text" are corrected by the chapter's own banner (NEW = registered but unverified or declined; amounts are `DOUBLE`); §1.3 "orders approved before e5a72fc may lack an amount" becomes DQ-31 (`importo_assicurato` only).
9. Gap, not contradiction: §2.4 says a declined customer's new registration data are "stored apart" until the link is used, but §2.3 has no column or table for them; `email_outbox`, `report_run`, `feature_flag`, `import_run`, `import_issue` are named without columns; 12b §5.4 adds `customer.legacy_payload`. Phase 1 must define all of these.
10. PROMPT.md: no conflict found; §4.2 step 2's `docs/migration/legacy-schema.md` depends on the owner's dump (Q-02) and is a Phase 2 migration deliverable.

Spot checks (files opened at the cited lines):

| Citation | Verdict | Evidence |
|---|---|---|
| ls-order@dev:js/rest/dto/order-request.js:8-22, 32-36 | VERIFIED | `destinatario` block with `email: userData.email` at line 15; `dettaglioColli: { palletList }` 17-19; `note` 22; `altezza…quantity` 32-36 |
| ls-cms@dev:js/function/component-handler.js:408, 421-432 | VERIFIED | `importoAssicuratoTxt` read at 408; request `{id, req:{codice, importoAssicurato, tracking: 1}}` 421-425; `updateShippingTs`/`updateDeliveryTs` commented at 431-432 |
| ls-cms@dev:js/components/types/data-tables-component.js:57, 65, 152, 159, 161, 242 | VERIFIED | `'ID': customer.id` 57, `'Email confermata'` 65, `'Cliente mittente': order.email` 152, `'Importo'` 159, `'Stato'` 161, `codice.includes("TEMP") ? "-"` 242 |
| ls-home@fe4b056:public/js/tracking.js:23-30, 81-91 | VERIFIED (at 9b78785) | `keyOf` strips accents/spaces and upper-cases (23-24); `findState` matches id, name or label (26-30); `normalize` accepts `{status}` or an enum object (81-91) |
| ls@migration:scripts/backup.sh (§4.2 step 1 `mysqldump` flags) | DIFFERS (minor) | Line 42-50 adds `--add-drop-database` to the listed flags: a restore drops and recreates `lsdb`, so the isolated-container rule of §4.2 is mandatory, never a shared server |

## 7. Planned tests

| ID | Tests (layer · assertion · tag) |
|---|---|
| CUS-01 | integration: `login_token` single use, `GET` never consumes, 15 min / 24 h expiry, `REGISTER` row with `email_hash` and null user; same HTTP status and page for known/unknown e-mail · `@Tag("CUS-01")` |
| CUS-02 | unit: `CustomerStateMachine` (every row of §2.4, illegal moves refused), normalisers of §3.8 (e-mail, CAP, phone E.164, province code); integration: re-registration of a `DECLINED` e-mail stores data apart and applies them on verification · `@Tag("CUS-02")` |
| CUS-03 | E2E `[CUS-03]` per status: `PENDING_VERIFICATION`, `PENDING_APPROVAL`, `DECLINED` sign in, see the status, cannot open `/area-clienti/ordini/nuovo/` |
| CUS-04 | unit: line totals in decimal (`round_half_up`, no float artefacts: 0.1+0.2 cases), temp-code generator (`TEMP-` + 8 base-32, uniqueness); integration: submission writes snapshots, `tracking_state_id = 0`, idempotency key refuses the second submit, EML-04/EML-06 outbox rows; DB `CHECK` constraints reject an `APPROVED` row without code · `@Tag("CUS-04")` |
| CUS-05, CUS-06 | integration: order URL by `code` or `temp_code` scoped to the owner (403 for another customer); `CHANGE_EMAIL` token flow with audit entry and sessions ended · `@Tag("CUS-05")`, `@Tag("CUS-06")` |
| ADM-01 | integration: no migration inserts into `staff_user` (scan of Flyway history + row count after migrate on an empty DB); `create-staff-user` prints a one-time link; lock-out after N failures · `@Tag("ADM-01")` |
| ADM-02, ADM-03 | unit: approve refused unless `email_verified_at` set; decline from `PENDING_*` only; E2E `[ADM-02]` lists with the legacy columns |
| ADM-04 | unit: `OrderStateMachine` (approve needs code unique ignoring case and trimmed, amount parses `180,50`/`180.50`, > 0; decline optional reason; `APPROVED`/`DECLINED` final); integration: approval inserts `tracking_event` source `APPROVAL` and EML-05 · `@Tag("ADM-04")` |
| ADM-06, ADM-10 | unit: any state → any state allowed, same state → no event, `tracking_state_id` equals latest event; integration: EML-09 queued only on the transition to `CONSEGNATO`; seed test: six states and six services equal chapter 02 §9 with `is_none` on id 0 · `@Tag("ADM-06")`, `@Tag("ADM-10")` |
| ADM-07 | integration: one note per order, 5,000 chars, line breaks kept, edit writes `audit_log` + `audit_log_detail` before/after · `@Tag("ADM-07")` |
| TRK-01/02/03, API-01/02 | unit: code normalisation (trim, spaces, case) and the §3.6 matcher (id, name, label, accents); contract: `TEMP` and unknown codes → 200 "Non gestito"; schema test that the public answer has no personal field; `legacy_json` served verbatim · `@Tag("TRK-02")`, `@Tag("API-01")` |
| PUB-11 | integration: valid submission → one `lead` + one outbox row in one transaction; honeypot → none; `retain_until` set per Q-18 · `@Tag("PUB-11")` |
| EML-01…06, EML-09 | integration per transition of §2.4: exactly the expected outbox row (recipient, subject, Reply-To); importer runs produce zero outbox rows · `@Tag("EML-0x")` |
| MIG-01 | integration on a synthetic MySQL 8 fixture (Testcontainers, 12b §1.7 DDL): two runs of the same snapshot change nothing (`legacy_key_map` checksums, row counts, `updated_at`); dry-run rolls back and still writes the full report; blocking issues stop a real run until the decisions file resolves them · `@Tag("MIG-01")` |
| MIG-02 | unit: every mapping row of §3 and 12b §5.5 (status table incl. `NEW` + `update_user`, amount `COALESCE` and comma/dot parsing, per-unit regrouping into lines, events from `confirmation_ts`/`shipping_ts`/`delivery_ts`, "Nessuna nota presente" dropped, DQ codes raised); integration: reconciliation report equals the fixture's counts per state and Σ amounts, units = Σ quantity · `@Tag("MIG-02")` |
| MIG-03 | rehearsal record (dated, Italian runbook): freeze, nginx block snippet refuses the write paths and keeps EP-13/19/20, final import into an empty schema, smoke tests of §4.7 step 7, rollback drill; quality: a test that the retired-path list of API-03 equals the nginx block list · tag `MIG-03` in the record |
| NFR-02, NFR-03 | integration: the app role cannot `UPDATE`/`DELETE` `audit_log` (SQL permission test); `before`/`after` of personal fields hold only field names; erasure tool purges `audit_log_detail` and anonymises, never deletes orders · `@Tag("NFR-02")`, `@Tag("NFR-03")` |

## 8. Notes for the planner

* **Ownership (10 §2 modules):** `customer`, `customer_user`, `login_token` → customers (+ auth for tokens/sessions); `shipment_order`, `order_line`, `accessory_service`, `order_internal_note` → orders; `tracking_state`, `tracking_event` → tracking; `staff_user`, `audit_log*`, `feature_flag` → platform/auth; `lead` → site; `email_outbox` → notifications; `report_run` → reports; `legacy_key_map`, `import_run`, `import_issue` → migration. State machines and normalisers are domain services with unit tests; the back office only calls them.
* **Phase 1 foundations this chapter needs:** the complete M1 schema of §2.3 as the first migrations, amended by 12b §5.4 (`customer.legacy_payload`, `amount_raw` as text of the source doubles, `legacy_type_raw` = `PALLET`/`EPAL`), plus the tables the chapter names without columns (§6 item 9) and a home for a declined customer's pending re-registration data; the seeds of `tracking_state` and `accessory_service` (12b §2.2, six + six); the separate maintenance DB role for purges; `CHECK` constraints as written (status/code, status/amount with `source = 'LEGACY'`, `email = lower(email)`); the `Q-xx` seams for Q-05 (VAT flag), Q-06 (no code proposal), Q-24, Q-35, Q-36, Q-18 retention values.
* **Decisions for ADR 0001 / Gate 0:** PostgreSQL with `snake_case` tables, `varchar` + `CHECK` enums, `timestamptz` shown in Europe/Rome, `numeric(10,2)` money (confirms the chapter 10 defaults; Q-52 engine); the importer reads a restored MySQL 8.0.35 copy through a read-only JDBC user or the §4.4 snapshot (Testcontainers MySQL in CI); staff accounts only by command. Gate 0 questions: Q-05 (price vs insured value, VAT), Q-06 (who assigns codes), Q-09, Q-24, Q-26, Q-28, Q-36 defaults to confirm; whether `PENDING_VERIFICATION` customers appear in the back office list (§6 item 3); whether re-setting `CONSEGNATO` re-sends EML-09 (default no).
* **Risks:** the real schema may drift from 12b §1.7 (`ddl-auto: update`, October 2025 time-zone break) — only the real dump settles it, so plan the rehearsal early (MIG-03) and keep the drift queries of 12b §1.8 in the importer's pre-flight; amounts of orders approved before 2024-07-16 live in `importo_assicurato` (DQ-31) and the VAT meaning is unknown — the report must show both sums; per-unit regrouping merges adjacent identical lines (12b §5.5) — totals unaffected, line count may differ from the portal's; duplicate e-mails (DQ-01) block the import until the owner decides; the API fallback misses orders older than six months; `--add-drop-database` in the dump makes any restore outside an isolated container destructive; personal data in the flagged-record CSV and spot-check diffs must stay outside git (§4.6).
* **Dependencies:** migration needs notifications' outbox contract (to assert zero sends) and the state machines of customers/orders/tracking (it writes through their import entry points, never through the domain transitions that send e-mail); back office needs ADM-04 to accept the comma amount the legacy refused; tracking compatibility needs the `legacy_json` seed exactly equal to a live EP-20 capture taken at cut-over (§4.7 step 7).
