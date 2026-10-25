# Phase 0 reader — chapter 12 · The legacy backend, from its source

Source: `docs/brief/12-legacy-backend.md` (127 lines, read in full on 2026-10-16). Cross-checked against README, 09, 10 §1–§3/§7, 11, PROMPT.md and the sections of 12a–12d that chapter 12 cites (12a §0, §1.4 D, §2.7–2.9, §5; 12b §0–§3, §5.3, §5.6, §5.9, §6–§7; 12c §1, §3, §4, §6–§7; 12d §0, §3, §4.3, §6.7). Path abbreviations below: `J/` = `src/main/java/it/logisticasanchez/mono/`, `R/` = `src/main/resources/`, `T/` = `R/templates/` in `ls@migration` (= `ca1b0d0`).

## 1. Scope

Chapter 12 is the entry page of the backend study: precedence (code > 12 > 02–08; chapter 09 and the ADRs decide the new app), the files of `legacy/ls` that must never be opened (§2), the key facts on one screen (§3: security, M1 behaviour, data, infrastructure, the CMS in the repo is not the live one), what changed in the other chapters (§4), what only the owner or the server can tell (§5) and the ID families introduced by 12a–12d (§6).
Reading map: *everyone* reads 12 in full (README "always"). Then per module: Phase 1 foundations 12a §2 and §5 (the security model not to reproduce), 12d §6.2; tracking + compatibility 12a §1.4 D and H, §4.1; customers 12a §1.4 B, 12b §3.1; orders 12a §1.4 C, 12b §3.2–3.6, §4, 12c §4.2; back office 12a §1.4 E–G, §2, 12c §6; notifications + reports 12c §1–§3, §5; migration 12b §1, §2, §5, §6, 12d §1.7, §1.11, §6.4–6.5; pricing (M2) 12c §4; infrastructure + cut-over 12d §0, §1, §6. Gate 0 reads 12d §6.

## 2. Requirement IDs touched

| ID | Where chapter 12 specifies or constrains it |
|---|---|
| TRK-01, TRK-02 | §3 "Tracking": `status` = label, unknown and TEMP codes → `200 {"status":"Non gestito"}`, empty code → 400 (Q-03, Q-38); §5 collation unknown (case rule) |
| API-01, API-02, API-03 | §3 tracking and enumerations (ids 0–5, item 0 = none); §3 e-mail links `https://{EMAIL_ORIGIN}/mono/…` on the apex by default (Q-50) and the `dashboard.` host the backend links to (Q-47); §4 "09: API-01/02" |
| PUB-04 | §3 `https://dashboard.logisticasanchez.com/` is linked by the backend (redirect target); §4 "01: `dashboard.`" |
| CUS-01, CUS-02, CUS-03 | §3 "Customers": NEW (unverified) → link → PENDING → approve → REGISTERED; decline resets to NEW, row kept (DECLINED before 2024-07-22); §4 "CUS-02 (phone required)" |
| CUS-04, CUS-05 | §3 "Orders": e-mail must belong to a REGISTERED customer; one `pallet` row per unit plus an aggregate row; the amount typed by staff is seen by customers (§3 "Pricing", §4 "07") |
| ADM-01 | §3 "Security": JWT model, permit-all default, 500 without token / 401 invalid — not to reproduce; §5 staff accounts unknown (Q-17) |
| ADM-02, ADM-04, ADM-05, ADM-06, ADM-07, ADM-09, ADM-10 | §3: only verified customers approved; approval = unique code + `importo` > 0 + tracking PRESA, decline → NEW; lists see only the last 6 months; tracking any→any on approved orders, e-mail on CONSEGNATO only, no history; CMS note = customer's note; report PDF A4 landscape via public never-expiring links; enumerations stored as labels; §4 "09: ADM-02/04/06/07/09/10" |
| EML-01…EML-09 | §3 "E-mails": ten automatic, HTML only, no Reply-To, office recipient hard-coded, links on `EMAIL_ORIGIN`; §4 "EML-01…09 (EML-09 delivery e-mail in M1)" |
| MIG-01, MIG-02, MIG-03 | §3 "Data": seven tables of `lsdb`, `create_tms`/`update_tms`, amounts `DOUBLE`, `ddl-auto: update` drift, the dump is the source, lists see 6 months only; §5 DECLINED rows, duplicate e-mails, null `importo`, collation; §4 "08: cut-over on the legacy VPS", "10: cut-over steps" |
| NFR-02, NFR-03, NFR-04, NFR-05, NFR-07 | §3 security model and body logging (Q-53); §3 "Infrastructure": no health check, backups only on the server, broken deploy script, end-of-life stack (Q-48, Q-49); §2 files with personal data |
| ENH-01…04, ENH-11 (pricing), ENH-05 (history), ENH-09 (2FA) | §3 "Pricing: none in the code"; §4 "07: unused pricing code, `PalletEnum` vs the class image (PR-02)"; §3 no tracking history; Q-17 default 2FA in M2 |

## 3. Rules to keep (M1 parity)

* TRK-01/02, API-01: `POST /mono/api/order/trace/state` answers the label; unknown, declined and TEMP codes → `200 {"status":"Non gestito"}`; empty code → 400 (ls@migration:J/api/controller/OrderController.java:33-38; J/business/service/crud/SpedizioneService.java:116-119; J/api/req/OrderDetailReq.java:16-18; 12a §1.4 D1).
* API-02, ADM-10: six tracking states and six accessory services, ids 0–5 in declaration order, item 0 "Nessun servizio accessorio" is a real item; JSON `{id, name, label}`; the database stores the label (J/business/model/enumeration/state/TrackingState.java:15-20; J/business/model/enumeration/ServizioAccessorioEnum.java:16-21; J/api/controller/EnumController.java:25-64; 12b §2).
* CUS-02, ADM-02 (Q-28): approval requires `stato = PENDING`, reached only through the verification link; unverified customers never appear in the staff list (J/business/service/crud/SoggettoService.java:117-121; J/api/controller/CustomerController.java:41-46; 12b §3.1 C6–C7).
* CUS-02 (Q-36): phone required at registration, as the backend requires it (12a §1.4 B2).
* CUS-02/CUS-03 (Q-09): a declined customer keeps the row and may register again (J/business/service/CustomerService.java:70-77; 12b §3.1 C8).
* CUS-04: only a REGISTERED customer may order; totals weight = Σ peso × quantità and volume from h × l × w / 1 000 000 (the legacy rounds to whole m³: chapter 09 keeps two decimals, a fix) (J/business/service/OrderService.java:53-112, 80-104; 12b §4.3).
* ADM-04 (Q-05, Q-06): approval needs `codice` (unique → 409 `CODE_409`), `importo` (`@Positive`, so 0 and negatives are refused) and sets `REGISTERED` + tracking `PRESA` + `confirmation_ts` whatever tracking is sent; decline → `NEW`, TEMP code kept (J/business/service/crud/SpedizioneService.java:152-173; J/api/req/crud/PatchSpedizioneReq.java:21-28; 12b §3.2 O2–O3; 12c §4.2).
* ADM-06, EML-09: tracking changes only on REGISTERED orders, any state to any state; `TRANSITO` sets `shipping_ts`, `CONSEGNATO` sets `delivery_ts`; e-mail "Consegna effettuata per ordine N. {codice}" on `CONSEGNATO` only (J/business/service/crud/SpedizioneService.java:236-259; J/api/controller/CmsController.java:111-123; 12b §3.4).
* ADM-07 (Q-26): the staff note is the order's `note` column, i.e. the customer's text; "Nessuna nota presente" when empty (J/business/service/OrderService.java:170-203; 12b §3.6) — content kept, overwriting not.
* ADM-09, EML-07 (Q-08): report = one customer's REGISTERED orders by `create_tms` in an inclusive range, optionally only `CONSEGNATO`, PDF A4 landscape, customer block + 13 columns; the office always receives it, the customer only if asked (J/business/service/crud/SpedizioneService.java:210-229; 12c §3.1–3.4).
* EML-01…09 (Q-07, Q-37): the ten e-mails' triggers, recipients, subjects ("Sanchez Logistica - …") and bodies are the copy to reuse (12c §1.2–1.3); L-E3 links `ordini.`, L-E8 links `https://www.logisticasanchez.com/#tracking` (PUB-04).
* MIG-01/02: the `lsdb` dump (`scripts/backup.sh`, `mysqldump --single-transaction`) is the only complete source; the API sees only the last 6 months (J/business/service/crud/SpedizioneService.java:177-180 `findAllByStatoAndCreateTmsBetween`; 12b §5.6); timestamps are UTC `datetime(6)` (12b §1.1).
* MIG-03: the legacy app is reachable only through its nginx on the VPS; block the write paths there at the freeze (12d §6.4; chapter 10 §7 step 2).

## 4. Deliberate fixes (legacy NOT reproduced) — proposed ADR titles

1. **Deny-by-default authorisation with a staff session on every back-office route** — the dead `/mono/cms/**` rule and `anyRequest().permitAll()`; open `order/pending`, `order/active`, `order/delivered`, `cms/order/report`, `/mono/report`, `/mono/download-report` (SEC-01…05, F-01; ADM-01, NFR-02).
2. **Server-side staff sessions instead of JWT with a permanent refresh token** (SEC-08; ADM-01).
3. **Explicit HTTP error model (401/403/404/405) and no exception text to clients** — legacy answers 500 without a token and 401 with raw library text (SEC-09; NFR-02); API-01 keeps its 200/400 contract.
4. **No request/response body logging; structured logs without personal data** (SEC-07, F-10, Q-53; NFR-03, NFR-04).
5. **One-time tokens consumed by `POST`, expiring, URL-encoded, no third-party link wrappers** — the legacy verification `GET` changes state, never expires, e-mails the office on every click (SEC-11, SEC-19; CUS-01, EML-01, EML-06).
6. **Sender taken from the session, never from a client-supplied e-mail** — order portal and `customer/state` oracle retired (SEC-12; CUS-04, API-03).
7. **Explicit `DECLINED` state for customers and orders** instead of a reset to `NEW` (Q-09; 12b §3.1–3.2; CUS-03, MIG-02).
8. **Internal notes separate from the customer's order note** (Q-26; ADM-07).
9. **Tracking history as events with time and user** — the legacy keeps only `shipping_ts`/`delivery_ts` (12b §3.4; ADM-06, ENH-05).
10. **Report delivered by authenticated download, recipients allow-list, audit** — never public never-expiring links; `email` no longer both filter and recipient (SEC-03, SEC-05; ADM-09, EML-07).
11. **E-mail settings and format**: office address and link origin as settings, text + HTML, Reply-To, EML-02/EML-04 added, office notice once at verification, decline e-mail with the order code, enum labels and € amounts formatted (Q-43, Q-50; 12c §1.6; EML-*).
12. **Money as `numeric(10,2)` with a VAT flag; migration `COALESCE(importo, importo_assicurato)` rounded half-up; Italian comma accepted at approval** (Q-05; 12b §3.5; ADM-04, MIG-02).
13. **Shipment code normalisation: trimmed, unique case-insensitively, empty refused; raw value kept for migration** (Q-06, DQ-29; TRK-02, ADM-04).
14. **Order lines with a quantity, volumes to two decimals** instead of one row per unit and whole m³ (12b §4.3, DQ-27/28; CUS-04, MIG-02).
15. **Full history in back-office lists with server-side pagination** instead of the 6-month window (12b §5.6; ADM-05).
16. **Admin tools not ported; erasure = anonymisation, no hard deletes; no free-text SES e-mail** (SEC-15, SEC-16; 12c §6; NFR-03).
17. **Flyway migrations, no `ddl-auto`; schema drift checked on the restored dump before import** (12b §1.1, §1.8; MIG-01, ADR 0001).
18. **Retired legacy endpoints answer 410 and the old `GET /mono/**` links a small page on apex and `app.`** (API-03; 12c §7 "API-03").

## 5. Unknowns by ID (recommended default)

* **Q-45** deployed commit, key names of the server `.env`/`config/application-prod.yml`, proxy, Ubuntu, VPS — not needed for the build; owner runs the read-only commands of 12d §1.15 before the cut-over, names only.
* **Q-21** (urgent, confirmed in the source) open endpoints with personal data — nginx stopgap on the legacy VPS the same day, access-log counts (P-06), GDPR art. 33 assessment with the adviser; the new app denies by default.
* **Q-53** (urgent) committed SES keys, JWT key, DB passwords, Postman passwords, 11 MB of logs — rotate now, purge history with the owner, secret scanning; agents never open `logs/`, `api/`, `.env*`, `local-setup.sh` or YAML values.
* **Q-48** backups only on the VPS, 7 days, untested — encrypted off-site backups and a test restore before Gate 1. **Q-49** TLS renewal of `app.` probably broken — check expiry and fix now.
* **Q-50** `EMAIL_ORIGIN` host of the e-mailed links (repo default = apex, which the static site did not serve before `9b78785`) — answer `/mono/*` link paths on both the apex and `app.`.
* **Q-47** which CMS build runs in production and where `ordini.`/`dashboard.` are hosted — not needed; new back office from chapter 04 screens + chapter 12 rules; assume both call `https://app.logisticasanchez.com`.
* **Q-17** staff accounts (table `users`, roles `ADMIN`/`CUSTOMER`) — two roles ADMIN/OPERATOR, staff not migrated, 2FA in M2 (ENH-09).
* **Q-46** AWS-era leftovers (Beanstalk, RDS, S3, IAM pair B) — inventory now; snapshot and delete; keep S3 names. **Q-51** `ls-web` — closed, unrelated. **Q-52** hosting and engine — new VPS at the same provider, PostgreSQL.
* **PR-02** `PalletEnum` (HP 150 kg; ULP/LP/FP 150 cm) contradicts the class image of 07 §3.1 — keep chapter 07; the owner confirms (Gate 2).
* **SEC-01…SEC-21** (12a §5) — each row carries its one-line fix; default = apply all in the new app. **F-01…F-19** (12d §3) — actions on the legacy system for the owner; the build does only "do not reproduce". **DQ-25…DQ-37** (12b §5.3) — detection query and handling per row; DQ-29 empty codes and unknown enum labels are blocking. **L-E1…L-E11** (12c §1.2) — reuse content without the defects of 12c §1.6; L-E11 not ported. **CU-01…CU-24** verdicts in 12a §4.2 (settled from the source; P-01…P-04 only confirm the live build, Q-27 default: agents run none without the go-ahead).
* Without an ID: the production collation (case and trailing-space matching of codes and e-mails) and the effective `ddl-auto` — default case-insensitive matching (Q-03), drift queries of 12b §1.8 on the restored dump (attach to Q-02/Q-45).

## 6. Contradictions and spot check

* **Internal (12 §1 vs §6):** §1 says 12c holds "the ten automatic e-mails L-E1…L-E10", §6 lists "L-E1…L-E11": L-E11 is the free-text admin tool `/mono/sendEmail`, not automatic (12c §1.2). Harmless; count ten for EML parity.
* **12 §3 vs 12b §3.2 (12b wins):** "approval requires a code (unique) and `importo` > 0" omits the promote path: EP-11 without `req` sets REGISTERED keeping the TEMP code and no timestamp (O2b, DQ-32). The importer must flag such rows; the new app has no promote path.
* **12 §3 vs 12c §1.4 (nuance):** "the links use `https://{EMAIL_ORIGIN}/mono/…`, by default the apex" holds for the prod profile and compose defaults; the base `application.yml` default is `localhost:9050` and the production value is unknown (Q-50). Not a conflict; keep both hosts.
* **12 §4 vs 09 (omission):** §4 does not list TRK-02, although chapter 09 TRK-02 now cites the legacy `Non gestito` answer for TEMP codes (Q-38) and 02 §10.2/§10.4 were corrected to 200 "Non gestito" (02:597, 630, 954, 1006, 1009). No conflict in content.
* **12 vs 10 §2 (deliberate):** chapter 10 models `DECLINED` explicitly and `DECLINED → PENDING_VERIFICATION`; the legacy resets to `NEW` (Q-09 default). Fix 7 above; not a contradiction.
* **12 vs 08 §2.3 (design):** 08 maps legacy item 0 "none" to `accessory_service_id = null` with an `is_none` row, while ADM-10/API-02 require the seed and the enumeration answer to keep item 0. Compatible only if the API-02 contract test asserts id 0 is still returned.
* **PROMPT.md vs 12a (trivial):** PROMPT cites `OrderController.java:33-38`, 12a `:33-39` (closing brace). PROMPT's approval example says "tracking 1 = PRESA": the backend forces PRESA whatever is sent (SpedizioneService.java:171). Consistent.
* **No contradiction found** between 12 §3 and chapter 11 rows Q-03, Q-05, Q-06, Q-09, Q-21, Q-25, Q-26, Q-28, Q-36, Q-38, Q-43, Q-45…Q-53, nor with chapter 01 (VPS, `dashboard.`), 03/04 banners, 06 (`ff58d4f`, `9b78785`), 07 PR-02, 08 §4.2 (dump as the plan), 10 §3/§7.

Spot check of chapter 12's own citations (opened in `legacy/ls`; no values or personal data reproduced):

| Citation | Result | Evidence |
|---|---|---|
| `ls@migration` = `ca1b0d0…` (2025-11-16), Java 17, Spring Boot 3.2.2, MySQL 8.0.35 (12 §0) | VERIFIED | `git rev-parse HEAD` = ca1b0d022857977b212b774e0247d2218477e1f8, commit date 2025-11-16; pom.xml:8 `3.2.2`, :17 `17`; docker-compose.yml:6 `mysql:8.0.35`. `ddl-auto` key present at R/application.yml:32 but its value is redacted locally: NOT CHECKED |
| ls@migration:J/api/controller/OrderController.java:33-38 — `status` is the label (12 §3) | VERIFIED | `.status(state.getLabel())` at :36; `@RequestBody @Valid OrderDetailReq` → 400 on empty |
| ls@migration:J/business/service/crud/SpedizioneService.java:152-173 — code unique, `importo` required > 0, tracking PRESA (12 §3) | VERIFIED | `Objects.requireNonNull` on codice/importo/tracking :152-154; `findByCodice … CODE_409` :163; PENDING guard :164; `TrackingState.PRESA.ordinal()` :171; `@Positive importo` at J/api/req/crud/PatchSpedizioneReq.java:26-27 |
| ls@migration:J/configuration/security/WebSecurityConfig.java:41-42 — URL rule never matches, everything else permitted (12 §3) | VERIFIED (rule text; "never matches" is an inference) | `.requestMatchers(PatternConstants.MONO_CMS_ALL).hasRole(…)` then `.anyRequest().permitAll()`; `MONO_CMS_ALL = "/mono/cms/**"` (PatternConstants.java:6); `context-path` key at R/application.yml:4 (value redacted); `pendingOrders`, `activeOrders`, `deliveredOrders` (OrderController.java:55-78) and `report` (CmsController.java:79-80) carry no `@PreAuthorize` |
| ls@migration:J/business/util/EmailReqFactory.java:159, 185 — office recipient hard-coded (12 §3) | VERIFIED | both lines are `.toAddress("<literal address>")` with a string literal, no property; address not reproduced |

## 7. Planned tests (tag = JUnit `@Tag("ID")` or Playwright title prefix `[ID]`)

| ID | Tests |
|---|---|
| TRK-01, TRK-02 | Contract: fixtures FX-01…FX-14 of 02 §10.4 (label, `Non gestito` for unknown/TEMP/declined codes, 400 for `""`/`{}`); unit: code normalisation (trim, inner spaces, case-insensitive). E2E `[TRK-01]` six states + unknown code. |
| API-01, API-02, API-03 | Contract `@Tag("API-01")` on apex and `app.` hosts + CORS preflight; `@Tag("API-02")` exact JSON of `statoTracking`/`servizioAccessorio` (ids 0–5, item 0 present); `@Tag("API-03")` 410 JSON for every retired path, Italian page for `GET /mono/email-confirm`, `/mono/report`, `/mono/download-report` on both hosts, no state change, logo served as a file. |
| CUS-01, CUS-02, CUS-03 | Integration: `GET` never consumes a token, expiry, same answer for known/unknown e-mails; unit: state machine `PENDING_VERIFICATION → PENDING_APPROVAL → ACTIVE/DECLINED`, approval of an unverified customer refused `@Tag("ADM-02")`; phone required `@Tag("CUS-02")`; E2E register → verify → pending → approve → order. |
| CUS-04, CUS-05 | Unit: totals (weight, volume two decimals, no float artefacts), sender = session; integration: stored lines with quantity match input, TEMP code unique; E2E happy path; authorisation test `[CUS-05]` customer sees only own orders. |
| ADM-04, ADM-06, ADM-07 | Unit: approval requires unique code (trimmed, case-insensitive) and amount > 0 (comma accepted), sets PRESA and `approved_at`; decline → DECLINED with EML-05; tracking any→any on approved orders only, event stored, EML-09 on CONSEGNATO only; notes: customer note untouched by staff note. E2E per screen. |
| ADM-05, ADM-09, ADM-10 | E2E `[ADM-05]` > 1 page, orders older than 6 months visible; `@Tag("ADM-09")` report of A excludes B, inclusive date range in Europe/Rome, only-delivered filter, 13 columns, recipient not on allow-list refused, download needs the right session, audit row; seed test `@Tag("ADM-10")` exactly the legacy labels and ids. |
| ADM-01, NFR-02 | Route matrix test: every `/gestione/**` and `/area-clienti/**` route without a session → 302/401, wrong role → 403, no 500; no body logging (log capture test); error model test. |
| EML-01…09 | Integration per e-mail: recipient, subject, Reply-To, key content (order code in decline, € formatting, labels not enum names, URL-encoded links); office notice once per verification; staging guard test (NFR-04/10 §3). |
| MIG-01, MIG-02 | Integration on a synthetic `lsdb` fixture (MySQL via Testcontainers or SQL fixture): idempotent twice; enum label assertion blocking; DQ-25…DQ-37 detection rows; amount `COALESCE` rounding; units regrouped to lines (Σ quantity = units); DECLINED/NEW mapping; reconciliation report counts per state × tracking and Σ amount equal. Drift-check script tested against a fixture with a leftover column. |
| MIG-03, NFR-05, NFR-07 | Rehearsal records (ready-for-owner): freeze block list incl. `/mono/cms/order/update`, `/mono/utils/**`, `GET /mono/email-confirm`; restore test; runbooks in Italian. |
| PUB-04 | Redirect test: `dashboard.` → `/gestione/`, `ordini.` → `/area-clienti/`, `www…/#tracking` → tracking. |
| ENH-01/02, ENH-05 | M2: flag-off tests prove M1 unchanged; `@Tag("ENH-02")` suggested vs typed amount both stored. |

## 8. Notes for the planner

* **Ownership (10 §2):** tracking → TRK-*, API-01…03, enumeration contract; customers → CUS-01…03, CUS-06, ADM-02 rules; orders → CUS-04/05, ADM-04/06/07 rules and state machines; backoffice → ADM screens and E2E; notifications → EML-01…06, 08, 09, outbox; reports → ADM-09, EML-07; migration → MIG-01/02 and the drift/reconciliation scripts; platform/auth (Phase 1) → ADM-01, NFR-02/03/04, error model, audit; site → PUB-04 redirects; infrastructure + cut-over → MIG-03, NFR-05/07.
* **Phase 1 foundations this chapter needs:** Flyway schema of 08 §2.3 with the two enumerations seeded (labels and ids of 12b §2); deny-by-default security with one extension point per module; explicit error model; no body logging; outbox + staging guard; settings for office address, e-mail origin, report allow-list; audit log; staff sessions; magic-link tokens exchanged by `POST`; feature flags; a `legacy_payload`/legacy-id column strategy for migration.
* **Risks:** (1) schema drift and collation known only from the real dump (MIG-02 cannot be green before the owner's rehearsal); (2) the production CMS source is unknown: approvals or reports may be failing today, so `importo` may be null on recent rows; (3) duplicate customer e-mails (DQ-01) need owner merge decisions; (4) `EMAIL_ORIGIN` unknown: old e-mail links may already be dead or hit the apex; (5) importing a MySQL dump into PostgreSQL needs a MySQL in CI and locally (Testcontainers; a SQL fixture path when no Docker daemon runs); (6) Q-21 is a live incident: the Gate 0 message must carry the stopgap, but agents never touch production; (7) the legacy repo holds secrets and personal data: keep the search exclusions in every tool call; (8) the amount's meaning and VAT (Q-05) and the origin of the 12-digit codes (Q-06) shape pricing and tracking design.
* **ADR 0001 / Gate 0:** Path B on Java + Spring Boot (current), PostgreSQL, new VPS (Q-01, Q-52), Flyway, no JWT; record the Path A assessment of 10 §1 with the versions verified above. Gate 0 summary (Italian) lists the defaults applied: Q-03/Q-38 tracking answers, Q-05 amount = price ex-VAT with flag, Q-06 codes typed by staff, Q-09 explicit DECLINED, Q-17 roles, Q-26 separate notes, Q-28 verified-only approval, Q-36 phone required, Q-07 copy incl. EML-02/04 and EML-09 in M1, Q-43 office address as a setting, Q-47/Q-50 hosts assumed, Q-27 probes not run; plus the urgent actions Q-21, Q-53, Q-48, Q-49 pointing to `COME-USARLO.md`, and the optional inputs Q-02 (schema dump with row counts) and Q-34.
