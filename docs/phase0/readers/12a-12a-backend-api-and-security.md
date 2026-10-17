# Phase 0 reader — chapter 12a · Legacy backend: API layer and security

Read in full (1,473 lines) on 2026-10-16. Precedence: legacy code > 12a > chapters 02–08; chapter 09 and the ADRs decide the target. Path abbreviations as in 12a: `J/` = `src/main/java/it/logisticasanchez/mono/`, `R/` = `src/main/resources/`, `T/` = `src/test/`; all citations are `ls@migration` (`ca1b0d0`) unless stated.

## 1. Scope

12a documents, from the backend source, all 36 controller mappings (§1: request, validation, statuses, side effects, e-mails), the security model as enforced (§2: filter chain, accounts, JWT, refresh, CORS, logging, committed secrets, behaviour changes by date), the error model (§3), the reconciliation with chapter 02 (§4: EP-01…20, CU-01…24, 16 endpoints chapter 02 did not know, corrections to 02/08/11) and 21 security findings SEC-01…21 with a one-line fix each (§5). It is the reference for the compatibility endpoints, the parity tests and the urgent Q-21/Q-53 message to the owner.
Reading map: **Phase 1 foundations** §2 and §5 (the model not to reproduce) · **tracking + compatibility** §1.4 D and H, §4.1 · **customers + sign-in** §1.4 B · **orders** §1.4 C · **back office** §1.4 E–G, §2 · **notifications/reports** §1.5 (with 12c) · **migration** §4.4 point 7, §2.16 · **security review (Phase 3)** §2, §3, §5 as the checklist.

## 2. Requirement IDs touched

| ID | Sections of 12a |
|---|---|
| API-01 | §1.4 D1 (answers, 400 on empty, collation), §2.10 CORS, §2.16 fingerprint, §4.1 EP-13, §4.4 points 5–6 |
| API-02 | §1.4 H1–H4 (`{id,name,label}`, ids = ordinal; also `tipoPallet`, `statoConferma`), §4.1 EP-19/20 |
| API-03 | §1.2 rows 34–36 and static/view rows, §1.5 link host (apex by default), §4.3 (16 unknown endpoints, old `GET /mono/**` links on both hosts) |
| TRK-01, TRK-02 | §1.4 D1 (label, TEMP/declined/NULL → "Non gestito", exact SQL match) |
| TRK-03, TRK-04 | §2.8 (what the open lists expose), §2.15 (no rate limit), SEC-01…03, SEC-10 |
| CUS-01 | §1.4 B1 (enumeration oracle), I1 (`GET` confirm, never expires, raw e-mail in link), SEC-11, SEC-12, SEC-19 |
| CUS-02, CUS-03 | §1.4 B (life cycle NEW→PENDING→REGISTERED; decline → NEW), B2 fields/limits/phone pattern, I1 |
| CUS-04 | §1.4 C1 (request, validation, per-unit rows, totals, TEMP code, office-only e-mail), §1.3 `Spedizione`/`DettaglioColli`/`Pallet` |
| CUS-05 | §1.4 C5 (lists keyed on a client e-mail: SEC-02 → derive from session), §1.3 (no `createTms`/customer id exposed) |
| ADM-01 | §2.2–2.9 (users table, BCrypt, roles ADMIN/CUSTOMER, HS256 6 h, permanent refresh id, no logout, 500 vs 401), SEC-04, 08, 09, 10 |
| ADM-02, ADM-03 | §1.4 B3–B6 (no window, no `ORDER BY`; approval needs PENDING; decline from any state) |
| ADM-04 | §1.4 C6 (code unique 409, `importo` > 0 required, tracking forced PRESA, `importoContrassegno` bug), C7 (decline → NEW, recipient from request), SEC-14 |
| ADM-05 | §1.4 C3 (6-month window), C8 (all orders of a customer), C9 (free update, not ported) |
| ADM-06 | §1.4 D2 (any transition, timestamps, no history, e-mail on CONSEGNATO only, non-transactional) |
| ADM-07, ADM-08 | §1.4 E1/E2 (customer note overwritten, literal "Nessuna nota presente"), F1 (`OrderDetailRepr` four keys, `importo` fallback) |
| ADM-09 | §1.4 G1–G3 (open, `onlyDelivered` NPE, links not attachments, any recipient, PDF columns and filename), SEC-03, SEC-05 |
| ADM-10 | §1.4 H1–H2 (exact values), §2.16 (ids 0-based since 2024-05-06) |
| EML-01, 03, 05, 06, 07, 09 | §1.5 table (trigger, recipient, subject, template, in/out of transaction), §1.1 sender and `enableAutomaticEmails` |
| MIG-01, MIG-02 | §1.1 JSON mapper (epoch seconds vs ISO), §1.4 C6 history (`importoAssicurato` → `importo`), §2.16, §4.4 points 4 and 7 (API export: 6 months, `cms/order?email=`, NEW invisible), DQ-07 |
| MIG-03 | §2.8 point 3 (nginx stopgap), §2.16 (identify the live build), §4.3 (old links on apex and `app.`), §6 |
| NFR-02 | §2 whole, §3, §5 SEC-01…21 |
| NFR-03, NFR-04 | §2.13 body logging (SEC-07), §2.8 exposure, §2.12 actuator (SEC-17), §2.14 inert prod profile (SEC-20) |
| NFR-06, NFR-07 | SEC-06 (secret scanning), SEC-21 (contract tests with auth-negative cases), §2.14 (key rotation runbook) |
| PUB-04 | §1.5 (`ordini.` and `dashboard.` links in e-mails), LinkUtil |
| ENH-05, ENH-09, ENH-13 | D2 "no history is kept"; §2.2 no lockout/2FA; SEC-16 no audit on deletes |

## 3. Rules to keep (M1 parity)

| Rule | Citation | ID |
|---|---|---|
| `POST /mono/api/order/trace/state {"codice"}` → `200 {"status": "<label>"}`; unknown, TEMP, declined or NULL-tracking code → `200 {"status":"Non gestito"}`; `""`/`{}` → 400 | J/api/controller/OrderController.java:33-39; J/business/service/crud/SpedizioneService.java:116-119; J/api/req/OrderDetailReq.java:16-18 | API-01, TRK-01/02 |
| Enumerations as `[{id, name, label}]`, `id` = ordinal 0–5; six services (0 = "Nessun servizio accessorio"), six tracking states; enums on the wire are label strings | J/api/controller/EnumController.java:25-67; J/business/model/enumeration/ServizioAccessorioEnum.java:16-21; state/TrackingState.java:15-27 | API-02, ADM-10 |
| Customer: register → NEW; e-mail link → PENDING; staff approve PENDING → REGISTERED (unknown or non-PENDING refused); decline keeps the row | J/business/service/crud/SoggettoService.java:53-70, 117-121; J/business/service/CustomerService.java:57-77 | CUS-02/03, ADM-02, Q-28 |
| Registration fields all required incl. `telefono` (pattern); limits 50/50/50/50/9/20/50 | J/api/req/crud/CreateSoggettoReq.java:21-49; J/business/model/constant/SoggettoConst.java:5-25 | CUS-02, Q-36 |
| Order only for a REGISTERED sender; created PENDING with `TEMP`+UUID and tracking NON_GESTITO; one row per physical unit; totals pallet/epal/peso/volume; dimensions and weight > 0; `type` 0/1; service by ordinal; `note` ≤ 255; DDT ≥ 1 char; only the office is e-mailed | SpedizioneService.java:75-88, 132-143; J/business/service/OrderService.java:53-112; J/api/req/CreateOrderReq.java:22-39; OrderPalletReq.java:16-37; J/business/util/TextCodeFactory.java:8-10 | CUS-04, ADM-08, EML-06 |
| Approval: `codice` required and unique (409), `importo` required > 0, only PENDING orders, tracking → PRESA, `confirmationTs` set, e-mail "Conferma ordine N. {codice}" to the customer | SpedizioneService.java:145-175; OrderService.java:114-121 | ADM-04, EML-05 |
| Decline: order → NEW + NON_GESTITO, row and TEMP code kept, e-mail "Aggiornamento ordine rifiutato" | OrderService.java:135-152 | ADM-04, EML-05 |
| Tracking set on REGISTERED orders only; any transition; TRANSITO stamps `shippingTs`, CONSEGNATO stamps `deliveryTs` (+`shippingTs` if empty); e-mail "Consegna effettuata…" on CONSEGNATO only | SpedizioneService.java:236-259; J/api/controller/CmsController.java:111-123 | ADM-06, EML-09 |
| Detail = `spedizione` + `mittente` + `dettaglioColli` + `palletList` (one entry per unit) | J/api/repr/OrderDetailRepr.java:20-27; SpedizioneService.java:183-196 | ADM-08 |
| Report: one customer's REGISTERED orders by creation instant, `to` inclusive, optional only-CONSEGNATO; office always; A4 landscape, customer block + 13 columns; filename `<local-part>-dd_MM_yyyy_dd_MM_yyyy.pdf` | SpedizioneService.java:206-234; J/business/util/HorizontalPdfGenerator.java:79-175; ReportUtil.java:113-126 | ADM-09, EML-07 |
| Ten e-mails: subjects, recipients and whether they sit in the DB transaction (§1.5 table); sending switchable by a setting; `From` from configuration | J/business/service/AwsEmailService.java:37, 53, 71; J/business/util/EmailReqFactory.java:25-189 | EML-01…09 |
| Staff log in with a username (not an e-mail) and a BCrypt password | J/persistence/model/security/UserEntity.java:11-25; J/configuration/security/DatabaseAuthenticationConfig.java:22-29 | ADM-01 (accounts not migrated) |
| List windows of the API export: order lists = last 6 months; customer lists = none; `cms/order?email=` = all orders, no window | J/persistence/repository/SpedizioneRepository.java:39-45; SoggettoRepository.java:19; SpedizioneService.java:261-266 | MIG-01 (fallback) |

## 4. Deliberate fixes (each needs an ADR)

| Fix (legacy → new) | Source | Proposed ADR title |
|---|---|---|
| Dead URL rule + permit-all, EP-09/`active`/`delivered`/EP-18/report links open → deny by default against real paths, staff session + role on every back-office route, route-by-route test matrix | §2.7–2.8, SEC-01, 02, 04 | "Authorisation: deny by default and route matrix" |
| Data keyed on a client e-mail, `customer/state` oracle → identity from the session, uniform answers | C5, B1, SEC-02, SEC-12 | "Customer identity from the session" |
| 500 for denials, wrong methods, bad JSON; 401 with raw library text → explicit 401/403/404/405/415/422, one JSON error schema, no exception text; ISO-8601 timestamps on new JSON | §2.9, §3.3, SEC-09; §1.1 | "HTTP error model and JSON conventions" |
| JWT 6 h without revocation, permanent per-user refresh UUID, no logout, no lockout, timing leak → server-side cookie sessions, idle/absolute timeouts, logout, lockout, constant-time failure | §2.3–2.5, SEC-08, SEC-10 | "Staff authentication and sessions" |
| Verification on `GET`, never-expiring code, raw e-mail in URL, Google safe-link wrappers, office e-mailed on every hit → tokens hashed/expiring/single-use consumed by `POST`, office notified once | I1, SEC-11, SEC-19; 09 CUS-01, EML-06 | "One-time links consumed by POST" |
| Public never-expiring report links, open EP-18, any recipient → authenticated download, allow-list, audit | G1–G3, SEC-03, SEC-05; Q-08 | "Report delivery through authenticated downloads" |
| Full request/response body logging, logs committed → structured logs with redaction, no bodies | §2.13, SEC-07 | "Logging and redaction policy" |
| Committed secrets, inert prod profile, actuator `*` → env/secret store only, validated config, fail fast, health only on an internal port, secret scanning in CI | §2.12, §2.14, SEC-06, 17, 20 | "Configuration, secrets and management endpoints" |
| CORS: configured list, all methods, credentials true → exact HTTPS origins, no credentials on the compatibility endpoint | §2.10, SEC-18; Q-39 | "CORS policy of the compatibility endpoint" |
| No rate limit anywhere (nginx limits deleted by the SSL script) → per-IP/per-account limits on login, tracking, public forms, sign-in links | §2.15, SEC-10, SEC-11 | "Rate limiting" |
| Decline/update without state or ownership checks; decline e-mail to the address in the request; `importoContrassegno` overwritten by `importoAssicurato` → explicit state machines, recipients from the record, amount stored as decimal with VAT flag | C6, C7, C9, SEC-14; Q-05 | "Order and customer state machines; approval contract" |
| Tracking overwrite with two timestamps → event per change with user and time | D2 | "Tracking history as events" |
| Staff note overwrites the customer's note; literal "Nessuna nota presente" → separate internal notes, literal mapped to null | E1, E2; Q-26 | "Internal notes separate from the customer note" |
| Free-text HTML accepted and rendered by the CMS → escape on output, validate length and charset | SEC-13; Q-22 | "Output escaping and input validation" |
| `sendEmail`, hard-delete utilities, `cms/order/update` → not ported; corrections as audited ADMIN actions, soft delete | I2, J, C9, SEC-15, SEC-16 | "Legacy admin utilities not ported" |
| 16 endpoints chapter 02 did not know (§4.3) → 410 like the other retired paths; decide `tipoPallet`/`statoConferma` (keep or 410) | §4.3; 09 API-03 | fold into "Retired legacy endpoints answer 410" |

## 5. Unknowns by ID (default the brief gives)

* **Q-21** open endpoints are an incident: same-day nginx stopgap on the legacy VPS (11 wording), owner-run P-04/P-06, GDPR art. 33 assessment · **Q-53** committed secrets/logs: rotate SES key, `JWT_KEY`, staff passwords; purge history with the owner · **Q-22** stored XSS confirmed server-side: CSP meta on the CMS until cut-over · **Q-03/Q-38** keep label, `200 Non gestito`, 400 on empty; match trimmed, case-insensitive · **Q-39** apex-only CORS until P-03 · **Q-05** `importo` = price charged, VAT excluded, > 0, VAT flag stored · **Q-06** staff type the code; uniqueness trimmed/case-insensitive · **Q-07** reuse the ten e-mails' content without defects; add EML-02/04 · **Q-08** PDF via authenticated download, allow-list, audit · **Q-09** explicit `DECLINED`, re-registration allowed · **Q-17** staff count unknown; roles ADMIN/OPERATOR, 2FA in M2 · **Q-25** answered (H1) · **Q-26** separate internal notes · **Q-28** only verified customers approved (yes, as today) · **Q-36** phone required · **Q-43** office mailbox becomes a setting; business mailbox before go-live · **Q-47** live CMS is not `ls-cms@dev`; assume `ordini.`/`dashboard.` call `app.` · **Q-50/Q-30** old `/mono/*` links answered on apex and `app.` with a page to `/area-clienti/`, never confirming or serving data · **Q-27** P-01…P-03 by agents after go-ahead; P-04…P-07 owner only · **Q-45** server `.env`/`application-prod.yml` key names only · **Q-02** production collation known only from the dump.
* **CU-01…CU-24** answered from the source (§4.2) except: **CU-02/CU-03/CU-17** collation (default: normalise in the app, verify on the dump), **CU-13** staff count (Q-17), **CU-14** live `ALLOWED_ORIGINS` (P-03), **CU-21** live CMS build (Q-47); **CU-15** inferred ascending id.
* **OP-S1/OP-S2** fixed by CUS-01/04; **OP-U2** answered (C1: 500 for non-REGISTERED, B2 duplicates 200/500/409); **OP-U3** answered (B2 link, never expires; no customer e-mail at order); **OP-U4** answered (H1). **CMS-03** no logout/expiry → sessions ADR; **CMS-05** answered (§2.8: partly protected); **CMS-11** literal note → null; **CMS-20** approval needs PENDING. **DQ-01** duplicate e-mails break B1/B5/C1 → importer lists them (Q-42); **DQ-07** May-2024 `1` stored as "Prenotazione telefonica" → importer flags, keeps label; **DQ-13** `importo` null on approvals before 2024-07-18 → fall back to `importoAssicurato`; **DQ-19** HTML in fields → escape, importer reports. **PR-***: none (12a has no pricing). **SEC-01…21**: fixes in §4 above (SEC-20/21 → NFR-06/07). **F-01** legacy `@PreAuthorize` fix: owner's action, never by the agents; **F-19** EOL stack → ADR 0001.

## 6. Contradictions

* 12a §4.4 point 5 says chapter 02's target for FX-03 is **404** ("a deliberate change, record it in the ADR"); 02 §10.4 FX-03/FX-06 (lines 1006, 1009), 09 API-01 and 11 Q-03/Q-38 say `200 {"status":"Non gestito"}`; 12b §3.7 (line 693) repeats the stale 404. The target is 200; the API-01 ADR must say so to stop a later reader reintroducing 404.
* 12a §4.4 point 9 says Q-28's recommended default is "No (legacy behaviour)"; 11 Q-28 now reads "Yes, as today". Stale note; chapter 11 is the master.
* `importo` date: 12a §0.6, C6 and §2.16 say required since **2024-07-18** (`d7b2c56`); 11 Q-05, 12b §1 (lines 52, 69, 151) and 12c §4 say **2024-07-16** (`d7732eb`). Code: the column arrived in `d7732eb` (07-16), the `requireNonNull(importo)` in `d7b2c56` (07-18) (`git log -S` on SpedizioneEntity/SpedizioneService). Both true of different commits; DQ-13 should use 07-18 as the null-`importo` boundary.
* Q-21 stopgap: 12a §2.8 point 3 proposes an office-IP `location` allow-list (and warns a plain block breaks the CMS); 11 Q-21 proposes refusing the four `POST`s without a Bearer header and IP-restricting the two `GET` links; 12d F-01 lists both. Use chapter 11's wording in the owner message.
* Volume: 12a §1.3 (MathUtil.java:5-7) stores `volume` rounded to a whole m³; 09 CUS-04 adopts the portal's Σ round₂ per line (03 OP-R5); 12b DQ-28 recomputes from units at import. Target consistent, but ADM-08 and the MIG-02 reconciliation must never compare the legacy integer with the new total.
* 12a §2.2 roles `ADMIN`/`CUSTOMER` vs 09 ADM-01 `ADMIN`/`OPERATOR`: intended (new model; staff accounts not migrated, 08 §3.9).
* 04 CMS-05 (line 954) still says "whether the backend enforces the token there is unknown"; 03 OP-U2 (line 726) still asks whether `order/register` refuses non-REGISTERED e-mails. Both answered by 12a §2.8 and CU-05 (the 03 §1 banner carries it; the 04 row does not). 12 wins.
* PROMPT.md "Legacy at a glance" agrees with 12a on every point (EP-13 answers and line range, EP-08 refusal, EP-11 body, the open endpoints). Internal 12a: consistent (36 − 20 = 16 unknown endpoints; 20 `Spedizione` keys; §2.5's refresh window shorter than the access token is a legacy misconfiguration, not a brief error).

Spot check of citations in `legacy/ls` (HEAD = `ca1b0d0`; Java sources only):

| Citation | Result | Evidence |
|---|---|---|
| J/configuration/security/WebSecurityConfig.java:37-49 (+ constant/PatternConstants.java:6) | VERIFIED | `.requestMatchers(PatternConstants.MONO_CMS_ALL).hasRole(…)` then `.anyRequest().permitAll()` at :41-42; `MONO_CMS_ALL = "/mono/cms/**"` at PatternConstants.java:6; CSRF disabled :44, STATELESS :45 |
| J/api/controller/OrderController.java:55-78 | VERIFIED | `pendingOrders` (:55-59), `activeOrders` (:68-72), `deliveredOrders` (:74-78) carry no `@PreAuthorize`; `registeredOrders` (:61) and `detail` (:80) do |
| J/api/controller/CmsController.java:79 | VERIFIED | `@PostMapping(PathConstants.REPORT_URI) report(…)` at :79-80 has no `@PreAuthorize`, unlike `sendEmail` (:44), `confirm` (:51, :58) |
| J/business/service/crud/SpedizioneService.java:116-119, 145-175 | VERIFIED | `findTrackingByCodice(codice).orElse(TrackingState.NON_GESTITO)` :117-118; `requireNonNull` on `codice`/`importo`/`tracking` :152-154; `CODE_409` :163; not-PENDING → `internalServerError()` :164; `setImportoContrassegno(getImportoAssicurato())` :167; PRESA :170; flags :171-173 |
| J/api/error/ErrorController.java:30-33; J/configuration/security/JwtAuthFilter.java:33-45 | VERIFIED | `@ExceptionHandler(Exception.class)` → `GENERIC_500` :30-33; the filter passes through without `Bearer ` or on `/mono/auth/login` :33-36; `catch` → status 401, JSON content type, `e.getMessage()` body :42-44 |

## 7. Planned tests

| ID | Layer · what it asserts · tag |
|---|---|
| API-01 | contract: D1 matrix (known label, unknown, TEMP, declined, NULL tracking → `Non gestito`; `""`, `{}`, missing body → 400; extra JSON field → 200 not 500 — a documented leniency); both `Host`s; preflight per Q-39. `@Tag("API-01")` |
| API-02 | contract: H1/H2 byte-equal arrays; decision test for H3/H4 (kept or 410); no auth; preflight. `@Tag("API-02")` |
| API-03 | contract, parametrised over the 16 endpoints of §4.3 plus chapter 02's 17: 410 JSON, no row written, no e-mail queued; `GET /mono/email-confirm`, `/mono/report`, `/mono/download-report` → Italian page, no state change, on apex and `app.`. `@Tag("API-03")` |
| TRK-02, TRK-03, TRK-04 | unit normalisation; integration: answer keys allow-list (none of the 20 `Spedizione` fields leak); N+1 lookups → 429. tags per ID |
| CUS-01, CUS-02 | integration: identical status/body for NEW/PENDING/REGISTERED/unknown e-mails; `GET` never consumes a token; legacy lengths accepted, phone required, CAP 5 digits (422); re-registration of a declined customer allowed. tags per ID |
| CUS-04, CUS-05 | integration: body naming another `clienteEmail` ignored (sender = session); 0 dimension and `null` service rejected 422; TEMP code format; office-only e-mail; a customer never sees another customer's order (direct URL → 404). tags per ID |
| ADM-01, NFR-02 | integration route matrix generated from the router: every `/gestione/**` route and every legacy ADMIN path without a session → 401/403/redirect, never 500/200; expired session, OPERATOR on ADMIN-only routes; lockout after N failures; logout ends the session. `@Tag("ADM-01")`, `@Tag("NFR-02")` |
| ADM-02, ADM-04 | unit + E2E: approve refused for unverified; duplicate code (normalised) → 409 shown in Italian; `importo` with comma accepted, `0` refused; tracking → PRESA + event; decline e-mail to the stored customer address, not a request field. tags per ID |
| ADM-05, ADM-06, ADM-07, ADM-08 | E2E: lists have no 6-month window and paginate; any tracking transition stored as event with user/time, EML-09 on CONSEGNATO only, tracking refused on pending orders; customer note read-only beside internal notes; detail shows sender, lines per unit and totals. tags per ID |
| ADM-09, EML-07 | integration: selection by creation date, `to` inclusive, only-delivered; customer A's PDF contains no order of B; recipient not customer/allow-list → refused; download needs the right session; audit row; 13 columns. tags per ID |
| EML-01/03/05/06/09 | integration per e-mail: recipient, subject (legacy wording, brand per Q-37), Reply-To, outbox row in the same transaction; EML-06 once per verification, not per click. tags per ID |
| MIG-01, MIG-02 | unit: fallback exporter honours windows (`cms/order?email=` for all orders), epoch and ISO timestamps, `"Nessuna nota presente"` → null, `importo` null → `importoAssicurato` (DQ-13), DQ-07 flagged; reconciliation counts per state and amount sums. tags per ID |
| MIG-03 | config test + rehearsal (ready-for-owner): legacy-nginx block list covers every write path and the six open paths of §2.8, keeps EP-13/19/20; fingerprint script of §2.16 is read-only. `@Tag("MIG-03")` |
| NFR-03, NFR-04, NFR-06 | integration: no request/response body or token in logs (log-capture test on login and order creation); only `/health` reachable without details; CI secret-scan and tracked-file guard. tags per ID |

## 8. Notes for the planner

* **Ownership (10 §2).** `platform`: deny-by-default security configuration with one extension point per module, error model, CORS, rate limiting, sessions, logging redaction, config validation, `/mono/**` 410 catch-all. `auth`: ADM-01, lockout, CUS-01 token flow. `tracking`: API-01/02, TRK-*, ADM-06 rules, ADM-10 seed. `customers`: CUS-02/03, ADM-02/03 rules. `orders`: CUS-04/05, ADM-04/05/07/08 rules, totals. `backoffice`: ADM screens. `notifications`/`reports`: EML-*, ADM-09. `migration`: MIG-*, the fallback exporter, the §2.16 fingerprint and P-01…P-03 scripts.
* **Phase 1 foundations needed.** Security chain written against real request paths (no context-path trap) with a generated route inventory test; one JSON error schema and a global handler that never returns 500 for client errors; CORS per endpoint; a rate-limit filter trusting one proxy hop; cookie sessions (`__Host-`, rotation, logout); secrets from the environment with fail-fast validation; log redaction; outbox; a contract-test harness with FX fixtures and both virtual hosts; `tracking_state`/`accessory_service` seeds; `tracking_event`; `order.legacy_payload` for the legacy aggregates.
* **Risks.** (1) The live build and its config are unproven (§6): keep the §2.16 fingerprint and P-04 before cut-over. (2) Production collation unknown: normalised unique indexes may collide on import; report before the first rehearsal. (3) `EMAIL_ORIGIN` default is the apex: old links arrive on both hosts, and the apex must never confirm or serve data (API-03). (4) The live CMS is unknown (Q-47): the back office is designed from 04 + 12 rules, not from `ls-cms@dev`; parity is to the backend, not the CMS. (5) Stale 404/Q-28 notes in 12a and 12b may mislead a Phase 2 agent: cite 02 §10.4 and 11 as authoritative in the ADRs. (6) Any CORS tightening (Q-39) can break unknown callers of `app.` (C5 shows an unknown client: 33 logged calls). (7) Duplicate customer e-mails (DQ-01) are the one legacy state the importer cannot resolve alone (Q-42).
* **For ADR 0001 / Gate 0.** Confirm: Q-03/Q-38 (200 "Non gestito"), Q-39 (apex-only CORS), Q-05 (amount meaning, VAT flag), Q-06 (code format), Q-17 (roles, 2FA later), Q-26 (separate notes), Q-28 (verified only), Q-36 (phone required), Q-27 (probe go-ahead), keep/retire `tipoPallet`/`statoConferma`, the 410 page copy, session timeouts of 10 §2. Report Q-21/Q-53/Q-22 in the first owner message with chapter 11's wording. Record in ADR 0001 that Path B must not inherit the legacy's security configuration, custom `ObjectMapper`, body-logging filter or committed profiles, and that the legacy stack is EOL (F-19).
