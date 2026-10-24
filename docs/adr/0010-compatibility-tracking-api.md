# 0010 · Compatibility tracking API: contract, matching, public payload, CORS, rate limit

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** API-01, API-02, TRK-02, TRK-03, TRK-04, ADM-10 · **Questions:** Q-03, Q-38, Q-39, PD-09 · **Findings:** 12a §1.4 D1, 12a CU-02, plan D-62, D-65, D-117

## Context

The new site's tracking widget, the old template site and possibly unknown third parties
call `POST /mono/api/order/trace/state` with `{"codice": "…"}` and read `status` as a label
(chapter 02 §7.4, §10.2). The legacy answered `200 {"status":"Non gestito"}` for unknown codes
and for the temporary codes of pending orders, `400` for an empty code, `500` for an unknown
JSON field, and allowed CORS with credentials for the origins of `ALLOWED_ORIGINS` (12a §2.10).
The two enumeration endpoints have no known caller after the cut-over but cost nothing (API-02).
Chapter 09 adds what the legacy lacked: a per-IP rate limit (TRK-04) and a payload without
personal data (TRK-03).

## Decision

* **Contract kept** (`CompatApiController`, `tracking` module): `POST /mono/api/order/trace/state`
  answers `200 {"status": "<label>"}` for an **approved** order whose definitive code matches,
  `200 {"status": "Non gestito"}` for everything else (unknown code, temporary code of a pending
  order, declined order — the legacy did the same, 12a §1.4 D1; D-62: never 404), `400` with the
  platform error schema (ADR 0002) when `codice` is missing, not a string or blank. The answer
  is served on every host the app answers (apex, `app.`, staging). `status` is never null.
* **One extra field**: `updatedAt` (ISO-8601 UTC, `shipment_order.tracking_updated_at`) when
  the state has changed at least once. Nothing else in M1: the TRK-03 allow-list is
  `{status, updatedAt}` today, `eta`, origin/destination city and `events {time, status,
  location}` may follow with ENH-05; names, addresses, phones, notes, DDT and document links
  never (`CompatApiIT` checks the keys and the absence of the synthetic order's data).
* **Matching** (`ShipmentCode`, `TrackingLookupService`): every whitespace removed from the input
  and from the stored code (`regexp_replace(code, '\s', '', 'g')`), then compared
  case-insensitively by default — the Q-03 seam is `ls.tracking.match.case-insensitive`.
  Leading spaces therefore match too (D-65: deliberate leniency over the legacy collation).
  The Q-38 seam `ls.tracking.temp-codes-visible` stays false: pending orders are not looked up.
* **Lenient JSON** (PD-09): unknown fields are ignored; `ls.compat.strict-json=true` makes them a
  400. The legacy's 500 is not reproduced.
* **Rate limit** (TRK-04): `tracking.lookup`, 60 lookups per minute per client IP (the trusted
  `X-Forwarded-For` hop), `429` with `Retry-After` through the platform limiter. Chapter 06 §5.7
  notes that `tracking.js` shows any non-2xx as "not found"; the E2E stage decides whether to
  treat 429/5xx as the error block (PD-01).
* **CORS** (Q-39, `CompatCorsFilter`): only on the three kept paths; exact origins from
  `ls.compat.cors.allowed-origins` (default `https://logisticasanchez.com`), methods
  `GET, POST, OPTIONS`, header `Content-Type`, `Access-Control-Max-Age: 3600`, **no
  credentials**; a preflight from an allowed origin is answered 204 by the filter, any other
  origin gets no `Access-Control-Allow-Origin` at all. Retired `/mono/**` paths never carry CORS
  headers (ADR 0011).
* **Enumerations** (API-02, ADM-10): `GET /mono/api/enumeration/statoTracking` and
  `/servizioAccessorio` serialise the seeded rows (`tracking_state` by id, `accessory_service`
  by `sort_order`) as `[{id, name, label}]` with numeric ids, byte-equal to chapter 02 §9. The
  `tracking` module owns both tables through `TrackingState` and `AccessoryService`.
* The lookup reads `shipment_order` with one SQL statement inside the `tracking` module: the
  `orders` module does not exist yet; when it does, the query stays here (tracking owns the
  tracking columns, plan §4.1) or moves behind an `orders` service — a one-method change.

## Consequences

* Legacy clients see the same answers for the same inputs, minus the 500s; a client sending
  `{"codice": "  ls-2024-0118 "}` is served (TRK-02).
* The public endpoint cannot leak personal data even if the row gains columns: the SELECT
  names two columns and the response record has two fields.
* `CompatApiIT` carries the fixtures FX-01…FX-12 of chapter 02 §10.4 on `logisticasanchez.com`
  and `app.logisticasanchez.com`; `ShipmentCodeTest` the matching rules.

## References

Chapter 02 §9, §10.1–10.2, §10.4; chapter 06 §5.7; chapter 09 API-01, API-02, TRK-02…04,
ADM-10; chapter 11 Q-03, Q-38, Q-39; ADR 0002 (error model, rate limiter), ADR 0011. Code:
`it.logisticasanchez.web.tracking.*`, `CompatApiIT`, `ShipmentCodeTest`.
