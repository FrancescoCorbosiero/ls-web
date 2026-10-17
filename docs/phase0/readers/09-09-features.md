# Phase 0 reader — chapter 09 · Features

Source: `docs/brief/09-features.md` (168 lines), read in full on 2026-10-16, with the sections it points to in 02, 03, 04, 05, 06, 08, 10, 11, 12a–12d and `PROMPT.md`. Citations keep the brief's convention (`repo@ref:path:line`; `J/` = `src/main/java/it/logisticasanchez/mono/` of `ls@migration`). No personal datum, secret or price figure is copied here.

## 1. Scope

Chapter 09 is the requirement list with stable IDs: M1 parity + customer area (PUB-01…11, TRK-01…04, API-01…03, CUS-01…06, ADM-01…10, EML-01…09, MIG-01…03, NFR-01…07), M2 enhancements behind flags (ENH-01…22) and the experimental backlog (no code, extension points only). Every M1 ID needs a tagged automated test (or a dated rehearsal record for NFR-05/06/07, MIG-02 on real data, MIG-03; `PROMPT.md` rule 4); "Fix" rows deliberately drop a legacy behaviour and each needs an ADR. It decides *what the new app does*; chapter 12 and the code decide *how the legacy works*.
Reading map (README): the whole chapter is "always"; Phase 1 reads NFR-*; site PUB-*; tracking + compatibility TRK-*, API-*, ADM-06, ADM-10; customers + sign-in CUS-01…03, CUS-06, EML-01…03; orders CUS-04…05, ADM-04; back office ADM-*; notifications + reports EML-*, ADM-09, NFR-04; migration MIG-* (via 08/12b); pricing (M2) ENH-01…04, 11; infrastructure + cut-over MIG-03, NFR-05…07.

## 2. Requirement IDs touched

| Family | IDs | Specified in 09 | Constrained by ("Source" column and the reading map) |
|---|---|---|---|
| PUB | PUB-01…11 | §M1 "Public site" | 06 §3, §5.3, §5.5–5.7, §6.2–6.4, §6.8, §8; 05 §2; 08 §2.3 `lead`; 10 §2 "Public site inside the app", §4 |
| TRK | TRK-01…04 | §M1 "Tracking" | 06 §5.7; 02 §10.2; 12a §1.4 D1; 12b §3.7; Q-03, Q-38 |
| API | API-01…03 | §M1 "Legacy API kept" | 02 §9, §10.1–10.4; 05 §3.7; 12a §1.4 D, H; 12c §2; Q-39, Q-50 |
| CUS | CUS-01…06 | §M1 "Customer area" | 03 §5–§8, §10–§11; 10 §2 "Customer authentication"; 08 §2.3–2.5; 12a §1.4 B–C; 12b §3.1–3.3 |
| ADM | ADM-01…10 | §M1 "Back office" + "Not ported" note | 04 §2–§5, §8–§9; 12a §1.4 E–G, §2, SEC-14…16; 12b §2, §3.1–3.6; 12c §3, §4.2, §6; 08 §3.9 |
| EML | EML-01…09 | §M1 "E-mails" (preamble + table) | 12c §1.1–1.6, §2; 10 §2 "E-mail", §3 staging guard; 06 §5.6; Q-07, Q-37, Q-43 |
| MIG | MIG-01…03 | §M1 "Data migration" | 08 §4.2–4.9, §5; 12b §1, §5.6, §6; 12d §6.4–6.5; 10 §7 |
| NFR | NFR-01…07 | §M1 "Non-functional" | 10 §3–§6; 12a SEC-*, §2.12; 12d §6.2 |
| ENH | ENH-01…22 | §M2 (flag names, notes) | 07 §3, §6, §6.8, §2.4; 10 §2 "Feature flags", §4 (uploads, spreadsheet cells); Q-14, Q-31, Q-34, Q-44 |

## 3. Rules to keep (M1 parity)

| Rule | Citation (as the chapter or its source gives it) | ID |
|---|---|---|
| Serve every ls-home page at the same URL with the same `<title>`, canonical, OG, JSON-LD and assets; differences only from the allow-list | ls-home@9b78785 tree (= `fe4b056` + `ff58d4f` + `9b78785`); 06 §3.1 | PUB-01 |
| Every `_redirects` 301, `/index.html` → `/`, client-side anchors `/#chisiamo` `/#contattaci` `/#tracking`, `www` → apex, directory URLs → trailing slash | ls-home@9b78785:public/_redirects:2-11; ls-home@fe4b056:public/js/main.js:218-221; 06 §6.8 | PUB-04 |
| Headers and CSP at least as strict as `_headers`; fonts immutable 1 year, images 1 week | ls-home@9b78785:public/_headers:1-16 | PUB-05 |
| Quote/contact forms: same fields, values, rules, prefill, WhatsApp hand-off; JSON for `fetch`, 303 → `/grazie/` without JS; Reply-To = customer | ls-home@fe4b056:netlify/functions/form.mjs:52-152, 209; 06 §5.5–5.6 | PUB-06/07, EML-08 |
| CAP → region → delivery time and coverage map with today's data | ls-home@fe4b056:public/js/eta.js (06 §5.3 test cases) | PUB-08 |
| Tracking page: six states, progress bar, legend; `Non gestito` shown as the neutral "no updates yet" message | ls-home@9b78785:public/js/tracking.js (06 §5.7, `ff58d4f`) | TRK-01 |
| `POST /mono/api/order/trace/state`: `status` = label; unknown or TEMP code → `200 {"status":"Non gestito"}`; empty → 400 | ls@migration:J/api/controller/OrderController.java:33-39; J/business/service/crud/SpedizioneService.java:116-119; J/api/req/OrderDetailReq.java:16-18 | API-01, TRK-02 |
| Enumerations: six tracking states and six accessory services, ids 0–5, item 0 = none, `{id,name,label}` | ls@migration:J/business/model/enumeration/state/TrackingState.java:15-20; …/ServizioAccessorioEnum.java:16-21; J/api/controller/EnumController.java:25-64 | API-02, ADM-10 |
| E-mail first; a new e-mail leads to registration; three statuses with their outcomes; only approved customers order | ls-order@dev:js/function/state-machine.js:146-168 (OP-R1, OP-R2); ls@migration:J/business/service/crud/SpedizioneService.java:78-79 | CUS-01, CUS-03 |
| Registration fields (name, address, province, city, CAP, phone, e-mail), summary, verification mail, then office approval; phone required by the backend | 03 §5.3–5.6 (OP-R3); ls@migration:J/api/req/CreateSoggettoReq.java:21-49 (12a B2) | CUS-02 |
| Only a verified (PENDING) customer can be approved; the pending list shows PENDING only | ls@migration:J/business/service/crud/SoggettoService.java:117-121; J/api/controller/CustomerController.java:41-46 | ADM-02 |
| Order lines (type Pallet/Epal, h, l, w cm, kg per unit, quantity, ≥ 1 line); totals weight = Σ peso×q, volume = Σ round₂(h×l×w/10⁶×q); DDT required; colli optional; one service, default none; recipient name/address/province/city/CAP required, phone optional; pending order with a TEMP code; legacy wording | ls-order@dev:js/function/data-handler.js:88-115 (OP-R4…R9); ls@migration:J/business/service/OrderService.java:71-108; SpedizioneService.java:82-84; J/business/util/TextCodeFactory.java:8-10 | CUS-04 |
| Approve = unique code (409) + `importo` > 0 + tracking PRESA, only from PENDING; decline keeps the row | ls@migration:J/business/service/crud/SpedizioneService.java:152-173; J/api/req/crud/PatchSpedizioneReq.java:21-28; OrderService.java:135-152 | ADM-04 |
| Tracking: any of the six states after any other, on approved orders only; delivery e-mail on `CONSEGNATO` only | ls@migration:J/business/service/crud/SpedizioneService.java:236-259; J/api/controller/CmsController.java:111-123 | ADM-06, EML-09 |
| Report: one customer's approved orders by creation date, `to` inclusive, optionally only delivered; A4 landscape, customer block, 13 columns; office always, customer optionally | ls@migration:J/business/service/crud/SpedizioneService.java:210-223; J/business/util/HorizontalPdfGenerator.java:41-222; J/business/factory/ReportFactory.java:37-51; CmsController.java:104-106 | ADM-09, EML-07 |
| Ten e-mails: triggers, recipients, subjects ("Sanchez Logistica - …") and bodies reused | ls@migration:J/business/util/EmailReqFactory.java:39-186; templates `src/main/resources/templates/*.html` (12c §1.3) | EML-01…07, 09 |
| The legacy note column is the customer's order note (kept on the order) | ls@migration:J/business/service/OrderService.java:170-203 | ADM-07 |
| Migration source = the `lsdb` dump (seven tables), as `backup.sh` takes it | ls@migration:scripts/backup.sh:42-50; 12b §1.3 | MIG-01/02 |

## 4. Deliberate fixes (each needs an ADR)

| Fix (legacy not reproduced) | IDs | Proposed ADR title |
|---|---|---|
| Public site served by the app: allow-listed HTML differences (form endpoints, `config.js`, "Area clienti" links, privacy/cookie pages, Q-31 copy); `/mono/*` Netlify proxy replaced by local answers | PUB-01, PUB-04, API-03 | "Public site port: allowed differences from ls-home@9b78785" |
| Over-long values and malformed CAPs rejected with 422 instead of truncated (06 §5.5, form.mjs:39) | PUB-06/07 | "Public forms reject instead of truncating" |
| Tracking answer without personal data; per-IP rate limit (429); matching trimmed, spaces removed, case-insensitive (legacy: leading spaces never match); retired paths 410 JSON; old `GET /mono/**` links → page, never confirm or serve PDFs; logo served as a file | TRK-02…04, API-01…03 | "Compatibility endpoints, tracking contract and retired legacy paths" |
| Authenticated entry: uniform answer for any e-mail, token used only by the confirmation page's POST, expiry, rate limits (fixes OP-S1, OP-S2, SEC-11, SEC-12, SEC-19) | CUS-01, EML-01 | "Customer sign-in by one-time link" |
| Registration: 5-digit CAP, Italian/E.164 phone, trimmed lower-cased e-mail, phone required (OP-08…11, Q-36); explicit `DECLINED` with re-registration data stored apart (legacy resets to NEW; Q-09); honeypot | CUS-02, CUS-03 | "Registration rules and declined customers" |
| Order wizard: idempotent submission, stable line ids, type required per line, totals without float artefacts, `colli` stored, optional recipient e-mail distinct from the sender, `TEMP-` + base-32 code (OP-01…07, OP-12…16; 08 §2.5) | CUS-04 | "Order wizard rules and temporary codes" |
| Staff auth: hashed passwords ≥ 12 chars, common-password list, lock-out, server sessions, OPERATOR role, no seeded accounts, first ADMIN by command (CMS-01…03, SEC-08, SEC-10; 08 §3.9) | ADM-01 | "Staff authentication, roles and account creation" |
| Approval: amount as `numeric(10,2)` with VAT flag, Italian comma accepted, > 0; explicit order state machine (no `promote` without code, no decline from APPROVED); decline e-mail to the record's customer, with the code (SEC-14; 12c §1.6) | ADM-04, EML-05 | "Order approval: amount, codes and state machine" |
| Tracking history as `tracking_event` rows (legacy keeps only two timestamps); same state again = no event (decide whether EML-09 is re-sent) | ADM-06, ENH-05 | "Tracking events instead of timestamps" |
| Internal notes separate from the customer's note (Q-26) | ADM-07 | "Internal order notes" |
| Report: authenticated download, recipient allow-list, audit, no public never-expiring links (SEC-03, SEC-05); date boundaries in Europe/Rome (legacy UTC), real delivery date in "Dt/Cons.", volume with decimals, drop the fixed partner header text (12c §3.4 block 1) — the last three to confirm with the owner | ADM-09, EML-07 | "Report delivery, recipients and columns" |
| Not ported: hard deletes, free-text SES e-mail, Postman-only order update (12c §6, SEC-15/16); corrections after approval as audited ADMIN actions | ADM (note), NFR-03 | "Legacy admin tools retired; audited corrections and anonymisation" |
| E-mails: text + HTML, Reply-To, outbox, configurable office address (Q-43), EML-06 once at verification (not on every click), EML-02/04 added, brand name (Q-37), no raw enum/double output, L-E4 subject fixed | EML-01…09, NFR-04 | "E-mail catalogue and the outbox" |
| Deny-by-default authorisation, no body logging, CSRF, session rules (SEC-01…07, SEC-09, SEC-17, SEC-18) | NFR-02 | "Security baseline" (or a section of ADR 0001) |
| Import mapping: amount = `COALESCE(importo, importo_assicurato)`, totals recomputed from units, legacy values in `legacy_payload`, synthetic `LEGACY_IMPORT` events, DQ decisions file | MIG-01/02 | "Legacy import mapping rules" |

## 5. Unknowns by ID (recommended default)

* Q-03 (TRK-02, API-01): keep the legacy answers; match trimmed and case-insensitive. Q-38: TEMP codes answer `Non gestito`. Q-39 (implied, CORS): only `https://logisticasanchez.com`. Q-30/Q-50 (API-03, implied): answer old `/mono/**` links on the apex and `app.` with the generic page.
* Q-05 (ADM-04, CUS-05, EML-05): price charged, VAT excluded, decimal + VAT flag, > 0. Q-06 (ADM-04, implied): staff type the code; uniqueness trimmed, case-insensitive; the app proposes nothing.
* Q-07 (EML preamble): reuse legacy content without defects, add EML-02/04, owner reviews copy at the next gate. Q-37: «Logistica Sanchez». Q-43: office address is a setting; business mailbox before go-live.
* Q-08 (ADM-09): PDF via authenticated downloads; office always, the customer if chosen, others only from the ADMIN allow-list; every send audited.
* Q-09 (CUS-02): explicit `DECLINED`, re-registration allowed on the same record. Q-28 (ADM-02): only verified customers approvable. Q-36 (CUS-02): phone required. Q-17 (ADM-01, implied): roles ADMIN/OPERATOR, 2FA in M2.
* Q-18 (PUB-11, NFR-03): leads 24 months, shipments 10 years, audit 24 months, outbox bodies 30 days, IP hashes 30 days.
* Q-24 (CUS-04): DDT mandatory; "Numero Colli" stored as an optional count (OP-U7). Q-35: integers ≥ 1, no maximum, warning above 240 cm / 1,200 kg per unit. Q-25/OP-U4 (ADM-10): answered by the source, seed the six services.
* Q-26 (ADM-07): separate internal notes. Q-29 (PUB-04): crawl the live site before the cut-over (LPS-U1, LPS-U5). Q-31 (PUB-01, ENH-22): keep only confirmed claims.
* Q-34 (ENH-01): MOSCA layout only. Q-44 (ENH-19): no countdown until cut-offs are given. Q-14/PR-01…14 (ENH-02…04, 10): chapter 07 §8 defaults, public estimate off.
* Quirk IDs the chapter settles: OP-S1/S2, OP-01…16, OP-25 (fix); OP-R1…R9 (keep); CMS-01…03 (fix); CMS-20 (superseded by 12b §3.1: legacy already blocks); SEC-15/16 (not ported). CU-02/CU-17 (code matching, collation) remain unknown until the dump. DQ-01, DQ-08, DQ-10, DQ-12 are the blocking classes for MIG-02 (08 §4.5); DQ-28 (integer volumes) affects CUS-04 totals of imported orders. No F-* raised.

## 6. Contradictions and spot check

1. 09 CUS-02 (phone required) vs 03 §10 OP-08 decision "optional as labelled" — 09 wins (12a B2: `CreateSoggettoReq.java:21-49` requires it); 03's decision column is stale.
2. 09 ADM-04 (staff type the code) + 11 Q-06 default ("proposes nothing") vs 08 §2.5 "the app proposes the next code when imported codes follow a sequence" — 12b §3.3 (12-digit external-looking codes) supports Q-06; 08 §2.5 should be aligned.
3. 09 CUS-04 volume = Σ round₂ per line (portal, OP-R5) vs the backend storing the total rounded to a whole m³ (`ls@migration:J/business/service/OrderService.java:89-94`, verified; 12b DQ-28) and printing it in the report (12c §3.4 col. 12). Code wins on how the legacy stores it; 09 decides two decimals for the new app, so imported orders must recompute totals from units (12b §5.5) — the "legacy columns" of ADM-09 cannot be bit-identical.
4. 09 ADM-09 "legacy columns": 12c §3.4 col. 4 "Dt/Cons." is not a delivery date and the period is cut at UTC midnight (12c §3.2) while 04 CMS-22 asks for Europe/Rome; 09 is silent — decide in the ADR. 09 also omits block 1 of the PDF (fixed partner header text, 12c §3.4): keep or drop is undecided.
5. 12c §1.5 title "Mapping to chapter 09 (EML-01…08)" and §7 row "Missing: add EML-09" are stale: 09 already has EML-09 in M1 (12 §4 confirms the update). No substantive conflict.
6. 09 EML-06 "once" vs legacy L-E2 on every click (`ls@migration:J/business/service/CustomerService.java:38-41`) — deliberate fix, covered by 12c §1.6; and 09 ADM-06 silent on re-setting `CONSEGNATO` while the legacy re-sends L-E8 each time and 08 §2.4 says "no event" — decide.
7. 09 PUB-04 "every redirect of `public/_redirects`" vs line 16 of that file (`/mono/*` → `app.` proxy, status 200, verified): a proxy, not a redirect; 06 §6.3 says it disappears. The PUB-04 test must exclude it.
8. 09 TRK-01 "E2E for each of the six states" vs 06 §5.7 (`ff58d4f`): state 0 renders the neutral message, not a state — five state renderings plus the neutral one.
9. 02 §10.4 FX-04 (leading spaces) and 12a D1 (leading spaces never match in the legacy) vs 09 TRK-02 trimmed matching — a deliberate leniency; list it in the API-01 ADR (02 §10.2 rule 1 already wants it).
10. 10 §2 URL map `/area-clienti/accesso` without trailing slash vs 09 PUB-04 / 06 §6.8 slash rule for directory URLs — harmless if `accesso` is a route, not a directory; name it in the ADR.
11. PROMPT.md: consistent with 09 (rule 4 statuses, Phase 5 flag groups cover ENH-01…22, 410 for retired paths); PROMPT cites `OrderController.java:33-38`, the method spans 33-39 (trivial).

Spot check (opened in `legacy/`, HEADs verified: ls `ca1b0d0`, ls-home `9b78785`):

| Citation | Result | Evidence |
|---|---|---|
| ls@migration:J/api/controller/OrderController.java:33-39 (+ OrderDetailReq.java:16-18, SpedizioneService.java:116-119) | VERIFIED | `status(state.getLabel())`; `@NotEmpty @Size(min=1) codice`; `orElse(TrackingState.NON_GESTITO)` |
| ls@migration:J/business/service/crud/SpedizioneService.java:152-173 | VERIFIED | `requireNonNull` on codice/importo/tracking; `findByCodice` → `CODE_409`; PENDING check; `tracking := PRESA`; `importoContrassegno := importoAssicurato` bug present |
| ls@migration:J/api/controller/CmsController.java:111-123 (+ EmailReqFactory.java:144) | VERIFIED | `if (CONSEGNATO.equals(confirmed.getTracking())) sendNotification(…TRACKING_STATE)`; subject "… Consegna effettuata per ordine N. " + codice |
| ls-home@9b78785:public/_headers:1-16 and public/_redirects:2-16 | VERIFIED | six headers + CSP as 06 §6.4; `/fonts/*` immutable 31536000, `/img/*` and `/*.png` 604800; ten 301 rules plus the `/mono/*` 200 proxy |
| ls-home@9b78785:netlify/functions/form.mjs:39, 79-83 | VERIFIED | `reader` does `.trim().slice(0, max)`; CAPs read with max 5 then tested with `/^\d{5}$/` — a 6-digit CAP is silently truncated and accepted (the 422 fix is justified) |

## 7. Planned tests (tag = the ID; JUnit `@Tag("X")`, Playwright `[X]`)

| ID | Tests (layer: assertion) |
|---|---|
| PUB-01 | E2E: walk 06 §3 inventory + sitemap, 200, `<title>`, canonical, one `<h1>`; quality: HTML diff vs ls-home@9b78785 equals the allow-list |
| PUB-02 | Integration: change the phone in configuration → every page fragment changes (template test) |
| PUB-03 | Integration: sitemap schema-valid, excludes `/grazie/`, 404, private areas; `X-Robots-Tag` on `/area-clienti/`, `/gestione/`, `/api/`, `/mono/` |
| PUB-04 | Contract: each `_redirects` rule, `/index.html`, hosts `www`/`ordini.`/`dashboard.`, `/zone` → `/zone/` answer 301 + `Location`; E2E: anchors land on their page |
| PUB-05 | Contract: header set and CSP ≥ `_headers`; caching rules; E2E: zero CSP violations |
| PUB-06/07 | E2E JS on/off (JSON vs 303); integration: every server case of 06 §5.6 incl. 422 for over-long/6-digit CAP; e-mail content + Reply-To |
| PUB-08 | Unit: CAP cases of 06 §5.3; E2E `/zone/` |
| PUB-09 | Contract: unknown URL → 404 page, status 404; `/grazie/` noindex |
| PUB-10, NFR-01 | Quality: Lighthouse CI ≥ 95 ×4 on the five pages; axe no serious/critical; phone-width E2E of customer area and back office |
| PUB-11 | Integration: valid submission → 1 `lead` NEW + 1 outbox row in one transaction; honeypot → neither |
| TRK-01 | E2E: five state renderings, neutral `Non gestito`, unknown code, `?code=` deep link |
| TRK-02 | Unit: trim, inner spaces, case; TEMP code → `Non gestito` |
| TRK-03 | Contract: answer keys ⊆ {status, updatedAt, eta, origin/destination city, events{time,status,location}} (FX-10) |
| TRK-04 | Integration: over-limit → 429 keyed on the proxy's forwarded IP |
| API-01 | Contract: FX-01…09 of 02 §10.4 on apex and `app.`; CORS preflight 204 without credentials; `example.org` origin refused |
| API-02 | Contract: FX-11/12 byte-exact arrays |
| API-03 | Contract: FX-13/14 → 410 JSON and nothing written; `GET /mono/email-confirm|report|download-report` → page, no state change, no PDF; logo 200 image |
| CUS-01 | Integration: GET twice then POST signs in, second POST refused; expiry; same page/status for known and unknown e-mail; per-e-mail and per-IP limits |
| CUS-02 | E2E register → verify → pending → approve → order; known e-mail gives the same page; unit: CAP, phone, lower-case e-mail, honeypot; declined re-registration applied only on link use |
| CUS-03 | E2E: PENDING and DECLINED see their status, no order form |
| CUS-04 | Unit: totals (no float artefacts), validation, Q-35 warnings; integration: idempotency key; E2E happy path; stored order equals input |
| CUS-05 | E2E + authorisation: customer A never sees B's orders (list and detail URL) |
| CUS-06 | E2E profile; integration: `CHANGE_EMAIL` token, notice to the old address, sessions ended, audit row |
| ADM-01 | Integration: login, roles, lock-out, password policy; quality: no migration inserts into `staff_user`, no seed user outside local/test |
| ADM-02 | E2E approve/decline; unverified customer refused; EML-03 queued |
| ADM-03, ADM-05, ADM-08 | E2E lists (order, search, > 1 page, filters) and detail sections |
| ADM-04 | E2E approve/decline; integration: code uniqueness (case-insensitive), amount parsing `180,50`, > 0, tracking PRESA, EML-05 |
| ADM-06 | Unit: any transition allowed, event per change; E2E; EML-09 only on CONSEGNATO |
| ADM-07 | E2E: customer note untouched, internal note saved and audited |
| ADM-09 | Integration: report of A has no order of B; disallowed recipient refused; download needs the right session; PDF has 13 legacy columns; audit row; CSV/XLSX cells as text |
| ADM-10 | Seed test: exactly the twelve values of 02 §9 |
| EML-01…09 | Integration per e-mail: recipient, subject, Reply-To, key content, outbox row; staging guard drops real recipients |
| MIG-01 | Integration: synthetic fixtures imported twice → identical state; dry-run writes nothing |
| MIG-02 | Integration: reconciliation report equals the source (counts per state × tracking, Σ amounts, Σ quantity/kg) on synthetic fixtures; rehearsal record on the anonymised copy |
| MIG-03 | Rehearsal: runbook steps on staging; unit: nginx block list and DNS checklist generated from one source |
| NFR-02 | Integration: route matrix (no session / wrong role → 401/403), CSRF, rate-limit header trust, security headers in private areas |
| NFR-03 | Integration: erasure leaves no e-mail/phone/name outside retained order snapshots; export; logs/audit carry ids only |
| NFR-04 | Integration: health endpoint public without details, other actuator paths closed; bounce from the queue marks the address |
| NFR-05/06/07 | Quality: backup script + restore test locally; CI pipeline runs all layers; runbooks exist (rehearsal records make them green) |
| ENH-01…22 | Per flag: unit of the rule (e.g. cheapest fitting class, 07 §3.4 examples), E2E with flag on, and a "flag off = M1" regression test |

## 8. Notes for the planner

* Ownership (10 §2 modules): site → PUB-01…11, EML-08 content; tracking → TRK-01…04, API-01…03, ADM-10 seed; auth/customers → CUS-01 (Phase 1, finished), CUS-02/03/06, EML-01…03 triggers; orders → CUS-04/05, ADM-04 rules, ADM-07; backoffice → ADM-01 UI, ADM-02…09 screens and E2E; notifications → EML-* rendering, outbox, NFR-04 bounces; reports → ADM-09, EML-07; migration → MIG-01…03; platform (Phase 1) → NFR-02…07, ADM-01 login, audit log, flags.
* Phase 1 foundations this chapter needs: full M1 schema incl. `lead`, `tracking_event`, `order_internal_note`, `login_token` (types `SIGN_IN`, `REGISTER`, `VERIFY_EMAIL`, `CHANGE_EMAIL`), `audit_log`, outbox, `feature_flag`; the seeds of ADM-10; magic-link sign-in; staff login with lock-out and `create-staff-user`; rate-limit and forwarded-header configuration; staging e-mail guard; the tag → parity-matrix generator (every ID of this chapter must appear, 46 M1 + 22 ENH).
* Risks: (1) Q-05 meaning/VAT of the amount shapes CUS-05, EML-05, ADM-09 and M2 pricing; (2) integer legacy volumes and `Dt/Cons.` semantics make "legacy columns" of ADM-09 approximate; (3) the production CMS build is unknown (Q-47): ADM-04 parity rests on the backend, not the CMS repo; (4) CUS-01/02 uniform answers conflict with usability if copy is vague — owner copy review at Gate 1; (5) SES bounce handling via SNS → SQS is a new AWS component (owner gate: adding a service); (6) ENH-14 lets `/servizi/` read the enumeration while 02 §9.2 says the public list is marketing copy, not the six services — scope it carefully.
* For ADR 0001 / Gate 0 summary: Path B on Java + Spring Boot + PostgreSQL (Q-01, Q-52); confirm Q-05, Q-06, Q-09, Q-24, Q-26, Q-28, Q-35, Q-36 defaults; decide report time zone, "Dt/Cons." source and the fixed PDF header; decide whether re-setting `CONSEGNATO` re-sends EML-09; approve the retired-endpoint list and 410 wording (API-03); approve the PUB-01 difference allow-list; confirm EML-02/04 additions and Reply-To policy (Q-07); confirm the office mailbox move (Q-43).
