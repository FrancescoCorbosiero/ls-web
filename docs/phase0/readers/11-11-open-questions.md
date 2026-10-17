# Phase 0 reader — chapter 11 · Open questions for the owner

Source: `docs/brief/11-open-questions.md` (98 lines), read in full on 2026-10-16, with the sections it references in 02, 03, 04, 05, 06, 07, 08, 10, 12a–12d, `PROMPT.md` and `COME-USARLO.md`. Citations follow the brief (`repo@ref:path:line`; `J/` = `src/main/java/it/logisticasanchez/mono/` of `ls@migration`). No personal datum, secret or price figure is copied here; the two phone numbers quoted in Q-15 are referred to by place only.

## 1. Scope

Chapter 11 is the master list of the 53 questions only the owner can answer (Q-01…Q-53, all present), each with a recommended default, the gate by which an answer is useful (§0 urgent, Gate 0, Gate 1, Gate 2) and an empty "Decision" column; rows marked **Legacy:** state what the backend source says and only need confirmation. It fixes the working rule: implement the default behind one seam (a setting or one method with a `Q-xx` comment), list it at the next gate, never guess silently; owner answers go into the "Decision" column and the affected ADR in the same commit, and later sessions take decisions only from this file and `docs/adr/`, never from the chat.
It also indexes the detailed unknowns of the other chapters: `OP-U*` (03 §12), `CMS-*` (04 §8), `LPS-*` (05 §5–§6), `CU-*` and the probes `P-01…P-07` (02 §11), `DQ-*` (08 §5, 12b §5.2–5.3), `PR-01…14` (07 §8), `SEC-*` (12a §5), `F-*` (12d §3).
Reading map (README: "always", every agent). §0 → the first owner message (`PROMPT.md` "Start now", `COME-USARLO.md`) and infrastructure + cut-over. Gate 0 table → the planner (ADR 0001, Gate 0 summary) and each Phase 2 module: tracking + compatibility Q-03/04/38/39; customers Q-09/10/11/28/36; orders Q-05/06/13/24/25/26/35; back office Q-17; notifications Q-07/37/43; migration Q-02; site Q-15/51. Gate 1 table → reports Q-08; customers Q-12; platform/privacy Q-18; site Q-31; migration + cut-over Q-19/20/29/30/40/41/42/45/46/47/50; pricing Q-34. Gate 2 → pricing M2 Q-14/32/33; site extras Q-44.

## 2. Requirement IDs touched

| Family | IDs | Specified or constrained by |
|---|---|---|
| PUB | PUB-01, PUB-02, PUB-04, PUB-08, PUB-11 | Q-51 (monolith serves the pages), Q-31 (claims), Q-15 (contacts from one source), Q-29/Q-47/Q-50 (redirects and old hosts), Q-23 (never reuse the Maps key), Q-18 (lead retention) |
| TRK | TRK-01, TRK-02 | Q-03, Q-38 (answers and matching), Q-04 (ASSEGNATO wording), Q-06 (code normalisation) |
| API | API-01, API-02, API-03 | Q-03, Q-38, Q-39 (CORS), Q-25 (enumeration values), Q-27 (probes P-01…P-03), Q-21 (retired endpoints never act), Q-30, Q-50 (old `/mono/**` links) |
| CUS | CUS-01…06 | Q-11 (one login), Q-30 (links never confirm on GET), Q-09/Q-28 (states), Q-10 (who registers, VAT/tax code), Q-36 (phone), Q-13 (pick-up), Q-24/Q-25/Q-35 (order form), Q-05 (amount shown), Q-12 (change/cancel) |
| ADM | ADM-01, ADM-02, ADM-04, ADM-06, ADM-07, ADM-08, ADM-09, ADM-10 | Q-17 (roles), Q-28 (approval guard), Q-05/Q-06 (approval dialog), Q-04 (states), Q-26 (notes), Q-24 (packages), Q-08 (report), Q-25 (seed) |
| EML | EML-01…09 | Q-07 (ten legacy e-mails, EML-02/04 new), Q-37 (brand), Q-43 (office mailbox setting), Q-08 (EML-07 rules), Q-30 (EML-01 links), Q-16 (SES region) |
| MIG | MIG-01, MIG-02, MIG-03 | Q-02 (dump), Q-05/06/09/25/26/42 (mapping), Q-16/19/20/29/40/45/46/47/48/50 (cut-over), Q-21/Q-53 (legacy write block, credential rotation) |
| NFR | NFR-02, NFR-03, NFR-04, NFR-05, NFR-07 | Q-21/22/23/53 (what not to reproduce), Q-18 (retention), Q-43/Q-46 (processors), Q-48 (backups), Q-49 (TLS-expiry check), Q-53 (key-rotation runbook) |
| ENH | ENH-01…04, ENH-09, ENH-10, ENH-11, ENH-19, ENH-22 | Q-34 (other price lists), Q-14/Q-32/Q-33 (pricing rules, public estimate, classes), Q-17 (2FA in M2), Q-44 (cut-off times), Q-31 (counters) |

## 3. Rules to keep (M1 parity)

| Rule (from the **Legacy:** rows) | Citation | ID |
|---|---|---|
| `POST /mono/api/order/trace/state`: `status` is the label; unknown codes and TEMP codes of pending orders → `200 {"status":"Non gestito"}`; empty code → 400 | ls@migration:J/api/controller/OrderController.java:33-39; J/business/service/crud/SpedizioneService.java:116-119; J/api/req/OrderDetailReq.java:16-18 (Q-03, Q-38; 12a §1.4 D1, 12b §3.7) | API-01, TRK-01, TRK-02 |
| Approval needs code + `importo` > 0 + tracking; code unique (409); only from PENDING; tracking becomes PRESA; older rows hold `importo_assicurato` | ls@migration:J/business/service/crud/SpedizioneService.java:152-154, 163-173; J/api/req/crud/PatchSpedizioneReq.java:21-28 (Q-05; 12b §3.5, 12c §4.2) | ADM-04, MIG-02 |
| Temporary code = `TEMP` + random UUID at submission; definitive code typed by staff, unique ignoring case; customers see it in e-mails and tracking | ls@migration:J/business/service/crud/SpedizioneService.java:84; J/business/util/TextCodeFactory.java:8-10 (Q-06; 12b §3.3) | CUS-04, ADM-04 |
| Only a customer in PENDING (e-mail verified) can be approved; the pending list shows PENDING only | ls@migration:J/business/service/crud/SoggettoService.java:117-121; J/api/controller/CustomerController.java:41-46 (Q-28; 12b §3.1) | ADM-02, CUS-02 |
| A decline keeps the record (customer back to NEW; order to NEW with its TEMP code); a declined customer may register again | ls@migration:J/business/service/CustomerService.java:70-77; J/business/service/OrderService.java:135-152 (Q-09; 12b §3.1–3.2) | CUS-02, ADM-02, ADM-04, MIG-02 |
| Phone required at registration (the portal label said optional) | ls@migration:J/api/req/crud/CreateSoggettoReq.java:41-44 (Q-36; 12a §1.4 B2) | CUS-02 |
| Ten automatic e-mails: triggers, recipients, subjects and bodies reused without their defects | 12c §1.2–1.3 verbatim; ls@migration:J/business/util/EmailReqFactory.java (Q-07) | EML-01, EML-03, EML-05…07, EML-09 |
| Report: one customer's approved orders created in the range, optionally delivered only; PDF A4 landscape, customer block, 13 columns; office always, customer if asked | 12c §3.1–3.4, §3.7 (`HorizontalPdfGenerator`) (Q-08) | ADM-09, EML-07 |
| Six accessory services, ids 0–5, id 0 = «Nessun servizio accessorio»; six tracking states with their labels (ASSEGNATO = «Assegnato») | 12b §2.2; ls@migration:J/business/model/enumeration/ServizioAccessorioEnum.java:16-21; …/state/TrackingState.java:15-20 (Q-25, Q-04) | ADM-10, API-02, CUS-04 |
| The legacy note is the customer's own order note: keep it on the order | ls@migration:J/business/service/OrderService.java:170-203 (Q-26; 12b §3.6) | ADM-07, CUS-04, MIG-02 |
| CORS from the `ALLOWED_ORIGINS` list; the public site's origin must stay allowed | ls@migration:J/configuration/security/WebSecurityConfig.java:58-66 (Q-39; 12a §2.10) | API-01 |
| Pick-up address = registered address (the legacy has no pick-up field); DDT required in the latest portal | 03 §7.4; ls-order@dev (OP-U7) (Q-13, Q-24) | CUS-04 |
| Staff login by username + password (table `users`, BCrypt); SES in eu-north-1 | 12a §2.2 (`UserEntity.java:11-25`); 12d §6.2 (`AwsMailConfig.java:25`) (Q-17, Q-16) | ADM-01, EML-* |

## 4. Deliberate fixes (each needs an ADR; proposed titles)

| Fix (chapter 11 default that departs from the legacy) | Proposed ADR |
|---|---|
| Q-01/Q-52: Path B, Java + Spring Boot current, new VPS, PostgreSQL | 0001 "Path B on Java + Spring Boot, PostgreSQL, new VPS" (required by PROMPT.md) |
| Q-21, Q-30, Q-50: deny by default; retired endpoints answer 410 and never act; old `/mono/**` GET links show a page, never confirm or serve data, on apex and `app.` | "Retired legacy endpoints and the fate of already-sent links" (API-03) |
| Q-03/Q-38 default: matching trimmed and case-insensitive (legacy: exact SQL equality under the collation, no trim) | "Tracking code normalisation and the compatibility answer" (API-01, TRK-02) |
| Q-05: amount as `numeric(10,2)`, > 0, with a VAT flag; migration `COALESCE(importo, importo_assicurato)` | "Order amount: meaning, precision, VAT flag and migration rule" |
| Q-06: uniqueness enforced trimmed + case-insensitive; new TEMP format (08 §2.5); no proposal of codes | "Shipment codes: temporary format and uniqueness" |
| Q-07, Q-37, Q-43: EML-02 and EML-04 added; office notified once per verification; Reply-To; brand «Logistica Sanchez»; office address a setting | "E-mail catalogue: additions, defect fixes, brand and office mailbox" |
| Q-08: authenticated downloads, recipient allow-list kept by ADMIN, every send audited | "Report delivery without public links" (ADM-09) |
| Q-09, Q-28: explicit `DECLINED` state, re-registration allowed, approval only after verification | "Customer and order state machines" (08 §2.4) |
| Q-11, Q-30: one login per customer by one-time link; the link opens a page whose POST uses the token | "Customer sign-in by one-time link" (CUS-01, fixes OP-S1/OP-S2) |
| Q-17: roles ADMIN/OPERATOR, no CUSTOMER role, no account in migrations, lock-out | "Staff roles and account provisioning" (ADM-01) |
| Q-22: escape all output, validate charsets and lengths | "Output escaping policy" (NFR-02) |
| Q-26: internal notes separate from the customer's note | "Internal notes" (ADM-07) |
| Q-35, Q-36, Q-10: integer line values ≥ 1 with warnings, phone required, Italian formats, optional VAT/tax code | "Registration and order validation rules" (CUS-02, CUS-04) |
| Q-39: CORS apex only, no credentials (legacy: list with credentials) | "Compatibility endpoint CORS policy" |
| Q-18: retention periods; Q-12: no self-service change or cancel | "Data retention" (NFR-03); note in the customer-area ADR |

## 5. Unknowns by ID (recommended default in force until the owner answers)

| ID | Default |
|---|---|
| Q-21, Q-22, Q-23, Q-53, Q-48, Q-49 (§0, today's system) | Owner-side actions only (nginx stopgap, CSP meta on the CMS, Maps key check, IAM/JWT rotation and history purge, off-site backups, TLS renewal); the agents touch nothing in production and never open `logs/`, `api/`, `.env*` |
| Q-01, Q-52 | Path B; Java + Spring Boot current; new VPS at the same provider; PostgreSQL |
| Q-02 | Build the importer against 12b §1.7; drift checks and the real import run by the owner; API export only as fallback |
| Q-03, Q-38, Q-39 | Legacy answers kept (label, `Non gestito`, 400); trimmed, case-insensitive; CORS only `https://logisticasanchez.com` until P-03 |
| Q-04 | ASSEGNATO shown as «Affidata al mezzo per la consegna» (06 §5.7); no exception state in M1 |
| Q-05 | Price charged, VAT excluded, decimal + VAT flag, > 0 (seam: flag default from a setting) |
| Q-06 | Staff type the code; app enforces uniqueness (trimmed, case-insensitive); proposes nothing |
| Q-07, Q-37, Q-43 | Legacy content in the new style; EML-02/EML-04 added; «Logistica Sanchez»; office address a setting (Gmail named in the privacy page until replaced) |
| Q-09, Q-28 | Explicit `DECLINED`, re-registration allowed; approval only of verified customers |
| Q-10, Q-11, Q-13, Q-36 | Companies and private persons; VAT/tax code optional; one login per customer; pick-up = registered address; phone required |
| Q-15, Q-16, Q-17 | Only the first WhatsApp number of 05 §2.4; new VPS, DNS A records changed by the owner; roles ADMIN/OPERATOR, 2FA in M2 |
| Q-24, Q-25, Q-26, Q-35 | DDT mandatory, «Numero Colli» optional count; seed the six services; separate internal notes; integers ≥ 1, no maximum, warning above 240 cm / 1,200 kg |
| Q-27 | P-01…P-03 by agents only after the go-ahead (PROMPT.md: default no); P-04…P-07 owner only; no credentials to agents |
| Q-45, Q-47, Q-51 | Not needed for the build; assume `ordini.`/`dashboard.` call only `app.`; `ls-web` prompt unrelated |
| Q-08, Q-12 | PDF via authenticated download, allow-list, audit; changes only while pending, by asking the office |
| Q-18 | Requests/leads 24 months; shipments and accounting 10 years; audit 24 months; outbox bodies and IP hashes 30 days |
| Q-19, Q-20, Q-40, Q-41 | Saturday afternoon, < 1 h, away from the 02:00 backup and 03:00 certbot jobs; retire Netlify after DNS (delete its IAM key); rollback until end of first working day; go-live e-mail from the owner |
| Q-29, Q-30, Q-50 | Crawl the live site (with go-ahead) and 301 everything; old `/mono/**` links → page to `/area-clienti/` on apex and `app.`; never delete old S3 bucket names |
| Q-31, Q-34 | Keep only confirmed claims; ENH-01 accepts the MOSCA layout only |
| Q-42, Q-46 | Duplicate e-mails listed before the first rehearsal, owner picks the surviving record; AWS-era resources inventoried and removed |
| Q-14, Q-32, Q-33, Q-44 | VAT excluded, public estimate off, delivery times from the list; 07 §8 defaults; class names public without prices; no countdown |
| OP-U4, OP-U6, OP-U7 | Services known from the source (P-01 confirms); portal host/`HOST` assumed `app.`; DDT mandatory, colli stored |
| CMS-02, CMS-04, CMS-05, CMS-20 | Owner-side mitigation until cut-over; new app escapes output, requires a staff session on every read, refuses approval before verification |
| LPS-S1, LPS-U1, LPS-U5, LPS-U7, LPS-U8, LPS-U9 | Maps key never reused; URL inventory by crawl; keep S3 names; owner answers hosting/analytics; CORS apex only until P-03 |
| CU-01, CU-02, CU-07, CU-08, CU-10, CU-12, CU-13, CU-14, CU-16, CU-17, CU-21, CU-22, CU-24 | Settled from the source (12a §4.2, 12b §5.8) except CU-14 (P-03), CU-21 (Q-47), the live collation (P-02) and the staff count (Q-17) |
| DQ-01, DQ-19 (and DQ-* generally) | Duplicates by e-mail block until merged (Q-42); HTML in fields flagged; verdicts of 12b §5.2 |
| PR-01…PR-14 | Defaults of 07 §8 (cheapest class, inclusive limits, real weight, half-up unit rounding, manual quotes for parcels/oversize, discounts outside the app) — M2 only |
| SEC-01…SEC-07, SEC-13 | Not reproduced: deny by default, no e-mail-keyed access, authenticated reports, no committed secrets, no body logging, escaping |
| F-01, F-02, F-08, F-10 | Owner actions on the legacy VPS and repository; the new app sets log retention per Q-18 |

## 6. Contradictions

1. **Q-06 vs 08 §2.5** (`shipment_order.code`: "the app proposes the next code when the imported codes follow a recognisable sequence (Q-06 default)") — chapter 11 now says "proposes nothing until you say how codes are made" and 12b §3.3 (12-digit external-looking codes) supports 11. 11 is the master list: fix the wording of 08 §2.5 (integrator).
2. **Q-36 vs 03 OP-08 ("Fix: optional as labelled") and 08 §2.3 `customer.phone` ("optional unless the owner decides otherwise")** — 09 CUS-02 and 11 say required, as `ls@migration:J/api/req/crud/CreateSoggettoReq.java:41-44` (`@NotBlank` + pattern) requires. Keep required; the column stays nullable only for legacy rows.
3. **Q-35 vs 03 OP-12 ("decimals for weight")** — 11 and CUS-04 say integers ≥ 1; the legacy stores `Double` lines (12b DQ-16). New input integers; the importer must accept legacy decimals.
4. **Q-10 and 08 §2.3 (`vat_number`, `tax_code` optional) vs 09 CUS-02 field list** (no VAT/tax code) — decide at Gate 0 whether the form shows the two optional fields (default per 11: yes).
5. **Q-03 ("trimmed and case-insensitive") vs TRK-02, 02 §10.2 rule 1 and 08 §2.5 ("trimmed, spaces removed")** — inner spaces; one normalisation in one method (ADR). Legacy itself does not trim (12b §3.7), so imported codes with leading whitespace (DQ-09) must be trimmed at import or they become untrackable.
6. **Q-05 default "VAT excluded" vs 10 §2 ("store a flag, do not guess")** — implement the flag with its default read from a setting (`Q-05` seam), not as a hard-coded assumption.
7. **Q-21 "four handlers" vs 12d F-01 "both handlers" (EP-09, EP-18)** — 12a §2.7 lists `order/active` and `order/delivered` as public too; 11 is complete, F-01 is not.
8. **12b §3.7 says chapter 02's default is 404 for unknown codes** — stale: 02 §10.2 rule 3 and FX-03 now answer `200 Non gestito`, consistent with Q-03.
9. **12c §1.4 ("the apex has no `/mono` route", at `fe4b056`) vs Q-50 / 02 §10.3 (`9b78785` passes `/mono/*` on)** — both true at their commits: `ls-home@9b78785:public/_redirects:16` proxies `/mono/*` to `app.` (200).
10. **PROMPT.md** — no substantive conflict (same §0 list, same probe rule, same Q-rule); it cites `OrderController.java:33-38` where the method spans 33-39. Internal: the Gate 2 table drops the "Why it matters" column; Q-12 has no Ref; Q-07 says "ten automatic e-mails" while 12c §1.5 is titled EML-01…08 but already maps L-E8 → EML-09.

Spot check of legacy citations (opened in `legacy/`):

| Q | Citation | Result |
|---|---|---|
| Q-03/Q-38 | ls@migration:J/api/controller/OrderController.java:33-39; J/business/service/crud/SpedizioneService.java:116-119; J/api/req/OrderDetailReq.java:16-18 | VERIFIED — `status(state.getLabel())`; `orElse(TrackingState.NON_GESTITO)`; `@NotEmpty @Size(min = 1) codice` |
| Q-05 | ls@migration:J/business/service/crud/SpedizioneService.java:152-154, 163-173; J/api/req/crud/PatchSpedizioneReq.java:21-28 | VERIFIED — `requireNonNull(importo)`, `CODE_409` on a duplicate, PENDING check, tracking `PRESA`; `@Positive Double importo`; the `importoContrassegno := importoAssicurato` bug is at 166-167 |
| Q-06 | ls@migration:J/business/service/crud/SpedizioneService.java:84; J/business/util/TextCodeFactory.java:8-10 | VERIFIED — `setCodice("TEMP" + TextCodeFactory.uuid())`; the factory alone returns a bare `UUID.randomUUID()` |
| Q-28 | ls@migration:J/business/service/crud/SoggettoService.java:117-121; J/api/controller/CustomerController.java:41-46 | VERIFIED — `confirm` throws `INVALID_ENUM_500` unless `PENDING`; pending list = `getAllByState(PENDING)` under `ADMIN_PRE_AUTH` |
| Q-36 | 12a B2 / SEC-13: `J/api/req/CreateSoggettoReq.java:21-49` | DIFFERS (path only) — the file is `J/api/req/crud/CreateSoggettoReq.java`; lines 41-44 `@NotBlank @Pattern @Size telefono` confirm the rule |

## 7. Planned tests (per ID; tags `@Tag("ID")` / Playwright `[ID]`)

| ID | Tests |
|---|---|
| API-01, TRK-02 | Contract: FX-01…FX-06 of 02 §10.4 (label, `Non gestito` for unknown and TEMP codes, 400 for empty); unit: normalisation (trim, case, inner spaces per the ADR) with a `Q-03` seam test; CORS preflight apex allowed / `example.org` refused, no credentials (Q-39) — `@Tag("API-01")`, `@Tag("TRK-02")` |
| API-02, ADM-10 | Contract: exact arrays of 02 §9.1–9.2; seed test: six services ids 0–5, item 0 = none (Q-25) |
| API-03 | Contract: retired paths 410 and no DB write (FX-13/14); `GET /mono/email-confirm`, `/report`, `/download-report` on apex and `app.` → page, no state change, no PDF (Q-30, Q-50); logo served |
| TRK-01 | E2E `[TRK-01]`: six states incl. ASSEGNATO wording of Q-04; `Non gestito` neutral message |
| CUS-01, CUS-06 | Integration: one `customer_user` per customer (Q-11); GET never consumes the token; e-mail change flow |
| CUS-02, CUS-03, ADM-02 | Unit: phone required and formats (Q-36), optional VAT/tax code (Q-10), company or person (Q-10); state machine: `DECLINED` kept, re-registration allowed (Q-09), approval refused before verification (Q-28); E2E per status |
| CUS-04, ADM-08 | Unit: integers ≥ 1, warnings above 240 cm / 1,200 kg behind the `Q-35` seam; DDT required, colli optional (Q-24); pick-up = registered address (Q-13); service default none (Q-25); customer note kept (Q-26); E2E happy path |
| CUS-05, EML-05, EML-09 | Integration: amount shown with the VAT flag read from the `Q-05` setting; e-mails carry code and amount |
| ADM-04 | Unit: amount > 0, Italian comma accepted, `numeric(10,2)` (Q-05); uniqueness trimmed + case-insensitive, no proposal (Q-06); E2E approve/decline |
| ADM-01 | Tests: roles ADMIN/OPERATOR, lock-out, no seed outside local/test (Q-17) |
| ADM-06 | Unit: any transition among six states, event stored; EML-09 only on CONSEGNATO |
| ADM-07 | E2E: internal note does not touch the customer's note (Q-26) |
| ADM-09, EML-07 | Integration: office always, customer only if chosen, other address refused unless on the allow-list, download needs the right session, audit row, 13 legacy columns (Q-08) |
| EML-01…09 | Integration per e-mail: recipient, subject with «Logistica Sanchez» (Q-37), Reply-To, office address from configuration (Q-43), key content; EML-02/04 present (Q-07); staging guard |
| PUB-01, PUB-02, PUB-04 | Allow-list diff test; single contact source with only the kept WhatsApp number (Q-15); 301 tests for `ordini.`, `dashboard.`, `www`, `/mono/**` (Q-29, Q-47, Q-50) |
| PUB-11, NFR-03 | Integration: retention job anonymises leads/outbox/IP hashes at the Q-18 periods (clock-driven test) |
| MIG-01, MIG-02 | Importer on synthetic fixtures: amount rule `COALESCE(importo, importo_assicurato)` (Q-05), TEMP codes kept verbatim, codes trimmed and flagged (Q-06, DQ-09), decimal lines accepted (Q-35), NEW-after-decline → `DECLINED` (Q-09), notes → customer note (Q-26), duplicate e-mails block with a merge list (Q-42); reconciliation counts and sums |
| MIG-03, NFR-05, NFR-07 | Rehearsal records: cut-over window and rollback (Q-19, Q-40), legacy write block, DNS list incl. `ordini.`/`dashboard.` (Q-47), restore test (Q-48), credential rotation (Q-53) |
| NFR-02 | Route-by-route authorisation matrix (no `order/pending`-style gap, Q-21); output escaping on every template with the DQ-19 payloads (Q-22); secret scanning in CI (Q-53) |
| ENH-01…04, ENH-19 (M2) | Flag-off = M1 behaviour; MOSCA-only import with a clear rejection (Q-34); public estimate off (Q-14); no countdown without cut-offs (Q-44) |

## 8. Notes for the planner

* **Ownership (10 §2):** `platform` — Q-18 retention job, Q-05 VAT-flag setting, Q-43 office address, Q-15 contacts; `auth` — Q-11, Q-17, Q-30; `site` — Q-15, Q-29, Q-31, Q-47, Q-50, Q-51; `tracking` — Q-03, Q-04, Q-38, Q-39, Q-25; `customers` — Q-09, Q-10, Q-28, Q-36, Q-12; `orders` — Q-05, Q-06, Q-13, Q-24, Q-26, Q-35; `backoffice` — Q-17, Q-08 UI; `notifications` — Q-07, Q-37, Q-43; `reports` — Q-08; `migration` — Q-02, Q-42, Q-45–Q-50; `pricing` (M2) — Q-14, Q-32, Q-33, Q-34; infrastructure + cut-over — Q-16, Q-19, Q-20, Q-40, Q-41, Q-46, Q-48, Q-49, Q-52.
* **Phase 1 foundations this chapter needs:** a typed settings module with one entry per seam (`Q-05` VAT flag default, `Q-18` periods, `Q-35` warning thresholds, `Q-43` office address, `Q-39` CORS origins, `Q-15` contacts) and a `Q-xx` comment convention greppable for the gate summary; the "Decision" column stays owner-only, edited by the integrator; the schema of 08 §2.3 with `vat_number`/`tax_code` (Q-10), nullable `phone` for legacy rows (Q-36), `amount` + VAT flag (Q-05), explicit `DECLINED` (Q-09), `temp_code` (Q-06); the staging e-mail guard before any EML test; `tools/probes/` script with a read-only allow-list (Q-27), never run without the go-ahead.
* **Risks:** the amount's meaning and VAT (Q-05) and the origin of definitive codes (Q-06) are the two defaults most likely to be wrong and most expensive to change after migration; the real schema may drift from 12b §1.7 (Q-02) and the production CMS build is unknown (Q-47), so approvals and reports may already fail in production; duplicate e-mails (Q-42) block the import until the owner merges; Q-21/Q-53 are live incidents that the owner must handle outside this build (GDPR art. 33 clock); e-mail link hosts (Q-50) and DNS ownership (Q-16) are unknown until the owner looks; the brief's own tables disagree on phone, decimals, VAT fields and code proposals (§6 items 1–4), so the integrator should align 03/08 wording after Gate 0.
* **For ADR 0001:** Q-01, Q-52 (Path B, Java + Spring Boot, PostgreSQL, new VPS at the same provider), with the 10 §1 assessment. **For the Gate 0 summary (Italian):** every Gate 0 default above, plus the four decisions the owner can take cheaply now — Q-05 (meaning/VAT), Q-06 (who makes the codes), Q-10 (VAT/tax code fields and private persons), Q-36 (phone required) — and the question of Q-27 (probes). Repeat the §0 items with their owner-side actions (`COME-USARLO.md`) at the top of the message.
