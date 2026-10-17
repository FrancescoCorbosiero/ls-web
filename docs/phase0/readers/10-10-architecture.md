# Phase 0 reader — chapter 10 · Architecture

Source: `docs/brief/10-architecture.md` (360 lines, read in full on 2026-10-16). Cross-checked against README, 09, 11, 02 §10, 06 §6.4/§8.3/§8.5, 08 §2.3–2.5/§4.7–4.9, 12a §1.2/§2.12/§5, 12b §1.8/§3.1/§3.4/§3.5, 12d §2/§6.2–6.4, PROMPT.md.

## 1. Scope

Chapter 10 says *how* to build: Gate 0 (Path A evolve vs Path B new monolith, assessment, reference stack), the modular monolith (§2: modules, state-machine summary, URL map, public site inside the app, magic-link sign-in, e-mail outbox, feature flags), infrastructure defaults (§3, VPS + Docker Compose + SES), security baseline (§4), privacy (§5), test layers and CI (§6), cut-over steps (§7) and the parallel-agent process (§8).
Reading map: *everyone* reads 10 in full. Phase 1 foundations: §2, §4–§6, §8. site: §2 "Public site inside the app". customers + sign-in: §2 "Customer authentication". back office: §4–§5. notifications + reports: §2 "E-mail", §3 (staging e-mail). infrastructure + cut-over: §3, §7. PROMPT.md Phase 3 lenses map to §4 (security) and §5 (privacy).

## 2. Requirement IDs touched

| ID | Where chapter 10 specifies or constrains it |
|---|---|
| PUB-01, PUB-02 | §2 "Public site inside the app" (assets copied unchanged from ls-home@9b78785, partials → fragments, `tools/sync.mjs` active-menu helper) |
| PUB-03 | §2 URL map (`/area-clienti/`, `/gestione/`, `/api/`, `/mono/` noindex, out of sitemap) |
| PUB-05, PUB-10 | §2 (keep `public/_headers` caching, ETag for HTML), §4 headers/CSP, §6 quality layer (axe, Lighthouse CI, CSP violation fails E2E) |
| PUB-06, PUB-07, PUB-11 | §2 `POST /api/form` same-origin; §4 CSRF exemption + honeypot + rate limit + Origin/Referer check |
| TRK-01…04 | §2 URL map and `TRACKING.endpoint` same-origin; §4 rate limit on tracking lookups; §2 state list |
| API-01, API-02, API-03 | §1 Path A details (only EP-13/19/20 survive; others 410, never act), §2 compatibility rows, §6 contract layer, §7 step 4 (`app.` host, old e-mail links) |
| CUS-01, CUS-02, CUS-03, CUS-06 | §2 "Customer authentication" (token flow, POST exchange, hashing, 15 min / 24 h, uniform answer, sessions, logout on decline/e-mail change) |
| CUS-04, CUS-05 | §2 URL map (`/ordini/nuovo/`, `/ordini/{codice}/`), §2 order state machine, money/time rules, §4 authorisation tests |
| ADM-01 | §4 (bcrypt/argon2, ≥ 12 chars, common-password check, lock-out, no seeded accounts outside local/test) |
| ADM-02, ADM-04, ADM-06, ADM-10 | §2 state machines (verified-only approval Q-28; `PENDING → APPROVED | DECLINED`; six tracking states, any order, every change an event, legacy values mapped exactly) |
| ADM-09 | §1 PDF library (OpenPDF), §4 spreadsheet-output rule, §2 reports module (authenticated download) |
| EML-01…09 | §2 "E-mail" (SESv2, outbox in the same transaction, encrypted raw token, retention, templates "tu", bounce/complaint via SNS→SQS), §3 staging guard |
| MIG-01, MIG-02, MIG-03 | §1 Path A migration note, §2 migration module, §6 migration layer, §7 steps 1–7 |
| NFR-01…07 | §3 (backups, environments, logs/alerts, deploy), §4 (whole baseline = NFR-02), §5 (= NFR-03), §6 (= NFR-06), §7 runbooks (NFR-07); NFR-04 health endpoint only |
| ENH-01, ENH-08, ENH-11, ENH-12 | §4 upload limits, content sniffing, pre-signed downloads, POI limits, text-only cells; ENH-09 (TOTP) §4 |
| All ENH-* | §2 "Feature flags" (table, cached service, env overrides, off by default, ADMIN toggle in `/gestione/impostazioni/`) |

## 3. Rules to keep (M1 parity)

Chapter 10 cites sections, not code; code citations below come from the chapters it points to and were opened where marked (v).
* API-01/TRK-01: `POST /mono/api/order/trace/state` keeps its contract and is served same-origin and on `app.`; label in `status` (ls@migration:src/main/java/it/logisticasanchez/mono/api/controller/OrderController.java:33-39 (v); 02 §10.4 FX-01…14).
* API-02/ADM-10: the two enumeration endpoints keep shape and values (12a §1.2 #15, #18; 12b §2.3).
* ADM-02/Q-28: only a verified customer can be approved (12b §3.1 C7, SoggettoService.java:117-121).
* CUS-02/Q-09: a declined customer may register again (12b §3.1; 08 §2.4).
* ADM-04: approval = code typed by staff, unique ignoring case, amount > 0 (12b §3.5, SpedizioneService.java:152-173; 08 §2.4).
* ADM-06: six tracking states, any order, as the legacy (12b §3.4, SpedizioneService.java:245-256); map legacy values exactly (08 §3.6).
* MIG-02/TRK-02: legacy ids and order codes kept in their own columns; old links and documents stay valid (08 §2.5).
* PUB-01/PUB-05: assets and `public/_headers` rules unchanged (ls-home@9b78785:public/_headers:1-16 (v)); PUB-02: active menu = `aria-current` logic of ls-home@9b78785:tools/sync.mjs:92-96 (v).
* PUB-06/07, EML-08: `/api/form` behaves as ls-home@9b78785:netlify/functions/form.mjs (SESv2, Reply-To to the sender, brand style) (v, lines 2-28, 209-219).
* EML-*: SES in eu-north-1, reusing the verified identity (12d §1.8; ls@migration:…/configuration/AwsMailConfig.java:25 `Regions.EU_NORTH_1`, opened).
* API-03/Q-30: `GET /mono/email-confirm`, `/mono/report`, `/mono/download-report` answered on apex and `app.` with a 410 page; logo served as a file (02 §10.3).
* MIG-03: the legacy app is reachable only through its nginx (ls@migration:docker-compose.yml:93 `127.0.0.1:8080` (v)); never `docker compose down -v` there (`mysql_data`, docker-compose.yml:17-18, 147-149 (v)).
* Money: legacy amounts are `Double` (12b §3.5) → `numeric(10,2)` with `amount_vat_included` flag (08 §2.3); import rounds half-up and flags disagreements.

## 4. Deliberate fixes (legacy NOT reproduced) — proposed ADR titles

| Fix (chapter 10 section; legacy evidence) | ADR title |
|---|---|
| Path B, current Java/Spring Boot, PostgreSQL, Flyway forward-only instead of `ddl-auto: update` (§1; 12d §2, 12b §1.8) | 0001 Path and stack |
| Deny-by-default authorisation with a test per route; explicit 401/403/404; no body logging (§4; SEC-01/02/04/07/09) | Authorisation model and error/log policy |
| Customer identity by one-time token (hashed, 15 min/24 h, POST exchange, uniform answer) instead of e-mail-only identity and GET confirmation (§2; SEC-11/12/19, OP-S1/S2) | Customer sign-in by magic link |
| Server-side sessions with idle/absolute timeouts, logout, `__Host-` cookie instead of JWT + permanent refresh token (§2; SEC-08) | Sessions for staff and customers |
| Reports: authenticated download, recipients allow-list, audit; no public never-expiring links; `/mono/sendEmail` not ported (§2, §4; SEC-03/05/15) | Report delivery and retired e-mail endpoint |
| Retired endpoints answer 410 and never act (EP-08 above all); old e-mail links get a page (§1, §7; 02 §10.3) | Compatibility endpoints and retired paths (API-03 list) |
| Explicit state machines: `DECLINED` kept (legacy resets to NEW), `APPROVED`/`DECLINED` final, decline only from `PENDING` (§2; 12b §3.1–3.2, SEC-14) | Customer and order state machines |
| Tracking changes stored as events (legacy keeps no history; 12b §3.4) (§2) | Tracking events |
| Outbox in the business transaction, retries, SNS→SQS bounces (legacy sends directly, no outbox; 12d §1.8) (§2) | E-mail outbox and bounce handling |
| Health endpoint only; no `env`/`configprops`; no exposure `*` (§4; SEC-17) | Management endpoints |
| Hard-delete admin utilities not ported; erasure = anonymisation (§4, §5; SEC-16) | Erasure and retention |
| CORS exact HTTPS origins, no credentials; rate limits and lock-out; `/api/form` CSRF-exempt with Origin check (§4; SEC-10/18, 06 §8.5) | Public endpoints: CORS, CSRF exemption, rate limits |
| New VPS, proxy with working renewal, dedicated IAM user, secrets outside git, off-site encrypted backups (§3; F-08, Q-48, Q-53) | Infrastructure and deployment baseline |
| Public site served by the monolith, same-origin tracking (`connect-src 'self'`), forms via outbox instead of Netlify function (§2) | Public site in the monolith |
| Report date range in Europe/Rome (chapter 10 time rule) vs legacy UTC day boundaries (12b §3.4) — decide explicitly | Time zone of dates and ranges |

## 5. Unknowns by ID (recommended default from chapter 11 / the chapter)

* Q-05 amount meaning/VAT — price charged, VAT excluded, decimal + VAT flag, > 0 (§2: "store a flag, do not guess").
* Q-09 declined customer — keep the record with explicit `DECLINED`; registering again allowed.
* Q-11 logins per company — one login (the customer's e-mail) in M1; model ready for more.
* Q-18 retention — requests/leads 24 months, shipments 10 years, audit 24 months, outbox bodies 30 days, IP hashes 30 days; §3 also cites Q-18 for backup copies 7/4/12 (not in Q-18's default: see §6).
* Q-20 Netlify — retire once DNS points to the new app; delete its IAM key.
* Q-28 approval of unverified customers — refused, as today.
* Q-29 URL inventory — crawl the live site before cut-over with the owner's go-ahead; 301 for every URL; never delete old S3 buckets.
* Q-40 rollback — until the end of the first working day after go-live; triggers as 08 §4.8.
* Q-43 office mailbox (Gmail, no DPA) — business mailbox before go-live; address is a setting; list Gmail in the privacy page meanwhile.
* Q-46 AWS-era resources — inventory now; snapshot and delete; keep S3 names as redirects.
* Q-48 legacy backups — encrypted off-site backups and a tested restore now; no production work before (Gate 1 at the latest).
* Q-49 TLS renewal of `app.` — check expiry now and fix renewal.
* Q-52 hosting and engine — new VPS at the same provider; PostgreSQL.
* Q-53 committed secrets/logs — check IAM for the committed pair, rotate SES key and `JWT_KEY`, change staff passwords, purge history with the owner; agents never open those files.
* CMS-04 stored XSS — fix: template escaping everywhere. SEC-17 actuator — health only. SEC-21 tests — contract tests per route with auth-negative cases. SEC-01…21 (§4 "leftovers to drop") — all fixed as in 12a §5.
* No ID: session timeouts (customers 60 min/12 h, staff 30 min/10 h), token lifetimes, backup schedule, Caddy vs nginx, Maven vs Gradle — defaults stated in §1–§3, "the owner may change".

## 6. Contradictions and spot check

* 10 §2 vs 06 §8.5 (caching): 06 recommends `Cache-Control: no-cache` + `ETag` for CSS/JS/HTML because file names carry no hash; 10 §2 says keep `_headers` rules and add ETag/short caching "for public HTML only", leaving CSS/JS to browser heuristics (stale assets after deploy). Resolve in favour of 06 §8.5 (ETag + no-cache on CSS/JS too, or fingerprint) — ADR "Public site in the monolith".
* 10 §3 cites Q-18 for backup copies "7 daily, 4 weekly, 12 monthly", but Q-18's default (11:77) covers data retention only. Add backup retention to Q-18's default or a new Q; Gate 0 item.
* 10 §7 step 2 vs 12d §6.4 step 2: 12d's block list omits `/mono/cms/order/update` (12a §1.2 #29, an unknown client, 13 log hits) and `GET /mono/email-confirm`; 10 and 08 §4.7 include them. Use the superset of 10/08 in MIG-03.
* 12d §6.3 "§1 Path A: import only `src/main` and templates, leave out `logs/`, `api/`, `.env*`, test configs with keys" is only partly carried into 10 §1 ("scan for secrets, no history"). Moot under Path B; record the explicit exclusion in ADR 0001 for completeness. 12d §6.2–6.4 line references (10:168-183, 10:198, …) are stale: the corrections are already applied in chapter 10.
* 10 §2 time rule (`timestamptz`, shown in Europe/Rome) vs legacy report filter on UTC day boundaries (12b §3.4): a behaviour change near midnight for ADM-09; not a contradiction but an undeclared deliberate fix (ADR above).
* Internal: URL map writes `/area-clienti/accesso` without the trailing slash every other route has; pick one convention (06 §6.8 trailing-slash rules) in Phase 1.
* Internal/02 §10.3: 10 §1 says every endpoint except EP-13/19/20 "answers 410", while 02 §10.3/API-03 serve `/mono/img/logistica-sanchez-logo.jpg` as a file (200). Minor; API-03 wording wins.
* PROMPT.md Phase 1 adds the "staging e-mail guard" to the Phase 1 list of 10 §8 (which puts it in §3 only); PROMPT Phase 3 fixes "three skeptics, keep if two confirm" where 10 §8 says "independent skeptics". No conflict; PROMPT is the stricter version.
* MySQL 8.0 EOL "April 2026" (10 §1) is marked "verify" in 12d §2; keep the "verify" in ADR 0001.

Spot check (legacy/ opened at the cited lines; no values quoted):

| Citation | Result | Evidence |
|---|---|---|
| ls@migration:pom.xml:8, :17 (Boot 3.2.2, Java 17; 10 §1 table) | VERIFIED | `spring-boot-starter-parent` 3.2.2 at line 8; `java.version` 17 at line 17; `aws-java-sdk-ses` 1.12.341, `itext` 2.1.7 also present |
| ls@migration:src/test/java/it/logisticasanchez/mono/business/service/crud/IntegrationTests.java:109-111 (`@Disabled`, SEC-21) | VERIFIED | `@Disabled` at line 110 |
| ls@migration:pom.xml:22-49 no actuator; src/main/resources/application.yml:59-63 exposure (SEC-17) | VERIFIED (key) / NOT CHECKED (value) | zero `actuator` matches in pom.xml; `management.endpoints.web.exposure.include` key present at line 63, value redacted; `ddl-auto` key at line 32, value redacted (12b §1.1 is the source) |
| ls-home@9b78785:public/_headers:1-16 (10 §2, §4) | VERIFIED | CSP with `connect-src 'self' https://app.logisticasanchez.com`, HSTS, `/fonts/*` immutable 1 year, `/img/*` and `/*.png` 604800 |
| ls@migration:docker-compose.yml:93 and :17-18, :147-149 (10 §7 steps 2 and 6) | VERIFIED | `app` ports `127.0.0.1:8080:8080` (line 93); `db` ports `127.0.0.1:3306`; named volume `mysql_data` declared at lines 17-18 and 148 |

## 7. Planned tests (per ID touched; tag = JUnit `@Tag("ID")` or Playwright prefix `[ID]`)

| ID | Tests |
|---|---|
| PUB-01/02 | E2E `[PUB-01]` smoke over every chapter 06 URL, HTML equal to ls-home modulo fragments; unit `@Tag("PUB-02")` active-menu helper (`aria-current` page/true) |
| PUB-03 | Integration `@Tag("PUB-03")`: sitemap excludes private areas; `X-Robots-Tag: noindex, nofollow` on `/area-clienti/`, `/gestione/`, `/api/`, `/mono/` |
| PUB-05/10 | Integration `@Tag("PUB-05")` header matrix (CSP ≥ `_headers`, caching rules, ETag on HTML/CSS/JS); quality `[PUB-10]` Lighthouse ≥ 95, axe; E2E fails on any CSP violation |
| PUB-06/07/11 | E2E `[PUB-06]` JS on/off; integration `@Tag("PUB-11")`: CSRF-exempt `/api/form` rejects foreign `Origin`, honeypot, 429 on rate limit, lead + outbox row in one transaction |
| TRK-01…04 | Contract `@Tag("API-01")` FX-01…FX-10; `@Tag("TRK-04")` 429 keyed on `X-Forwarded-For` (one hop trusted; client-set header ignored); `@Tag("TRK-03")` response keys allow-list |
| API-02/03 | Contract `@Tag("API-02")` FX-11/12; `@Tag("API-03")` FX-13/14 (410, nothing written), old `GET /mono/**` links → 410 page on apex and `app.`, logo 200 |
| CUS-01 | Integration `@Tag("CUS-01")`: two GETs then POST signs in, second POST refused; HEAD never consumes; expiry 15 min / 24 h; token stored hashed; same page/status for known and unknown e-mail; rate limit per e-mail and IP; `no-store` + `no-referrer` headers; redirect strips token |
| CUS-02/03/06 | Unit `@Tag("CUS-02")` customer state machine incl. `DECLINED → PENDING_VERIFICATION`; E2E `[CUS-03]` per status; `@Tag("CUS-06")` e-mail change ends all sessions |
| CUS-04/05 | Unit `@Tag("CUS-04")` order state machine refuses illegal moves, totals; `@Tag("CUS-05")` customer A cannot read B's order/document/profile (403/404) |
| ADM-01 | Integration `@Tag("ADM-01")`: bcrypt/argon2, ≥ 12 chars, common password refused, lock-out, logout; test fails if any migration inserts into `staff_user` or a seed user exists outside local/test; session idle/absolute timeouts; `__Host-` cookie flags |
| ADM-02/04/06/10 | Unit `@Tag("ADM-02")` unverified approval refused; `@Tag("ADM-06")` any-to-any tracking move creates one event, same state creates none; `@Tag("ADM-10")` seeded enumerations equal 12b §2.2 |
| ADM-09 | Integration `@Tag("ADM-09")`: PDF via OpenPDF, authenticated download only, allow-list, audit row; spreadsheet cell `=HYPERLINK(` stays text |
| EML-* | Integration `@Tag("EML-01")` outbox row in same transaction, raw token encrypted and wiped after send, no body in logs; `@Tag("EML-06")` fake SES receives; outbox retry on SES failure; `@Tag("NFR-04")` SQS bounce marks address, staff can clear |
| MIG-01/02/03 | Integration `@Tag("MIG-01")` importer idempotent on synthetic fixtures; `@Tag("MIG-02")` reconciliation counts per state and amount sums; rehearsal record for MIG-03 (freeze list = superset of 10 §7 / 08 §4.7) |
| NFR-02 | Integration `@Tag("NFR-02")` deny-by-default route matrix (no session, wrong role) for every `/gestione/**` and `/area-clienti/**` route; CSP without `unsafe-*` in private areas; upload type/size/ownership refusals (M2) |
| NFR-03 | Integration `@Tag("NFR-03")`: after erasure no table holds e-mail/phone/name except order snapshots; audit log carries ids only; privacy page lists processors and cookies |
| NFR-04/05/06/07 | `@Tag("NFR-04")` only `/health` reachable, no details, `env`/`configprops` absent; `@Tag("NFR-05")` backup-age alert logic unit test + dated restore rehearsal; `@Tag("NFR-06")` CI pipeline runs all layers; `@Tag("NFR-07")` runbooks exist (ready-for-owner) |
| Staging guard | Integration `@Tag("NFR-02")`/`@Tag("EML-06")`: app refuses to start with `staging` profile without mail catcher or allow-list; non-allow-listed recipient dropped and logged |
| ENH-* flags | Unit `@Tag("ENH-01")` etc.: with flag off, behaviour identical to M1 (snapshot test of routes/templates); flag toggled only by ADMIN with audit |

## 8. Notes for the planner

* Module ownership (§2): `platform`+`auth` → Phase 1 integrator (NFR-02 sessions/headers/rate-limit core, ADM-01, CUS-01 sign-in, feature flags, audit, outbox skeleton). `site` → PUB-01…11, EML-08 producer, leads storage. `tracking` → TRK-01…04, API-01…03, ADM-06 service, ADM-10. `customers` → CUS-02/03/06, ADM-02 rules, EML-01…03 producers. `orders` → CUS-04/05, ADM-04/07/08 rules, EML-04/05 producers. `backoffice` → ADM-* screens and E2E (rules stay in domain modules). `notifications`+`reports` → EML-* delivery, ADM-09, NFR-04 bounce handling. `migration` → MIG-01…03. Rate limits: `platform` owns the limiter, each module its keys. NFR-03 tools: `customers` (export/erasure service) with `platform` retention job. NFR-05/06/07: integrator (`deploy/`, CI, runbooks).
* Phase 1 foundations this chapter needs: complete 08 §2.3 schema incl. `email_outbox`, `feature_flag`, `audit_log*`, `login_token`; Spring Security with deny-by-default and one extension point per module; forwarded-header trust of exactly one hop; HTMX config meta (`includeIndicatorStyles=false`, `allowEval=false`, `allowScriptTags=false`, `selfRequestsOnly=true`) and CSP without `unsafe-*`; `__Host-` session cookie, idle/absolute timeouts; magic-link flow finished; outbox worker with encrypted token column; staging profile guard (PROMPT Phase 1); `TEST_DATABASE_URL` switch; per-module `ls_<module>` databases and ports for Phase 2 worktrees; CI tag → parity-matrix generator; `deploy/` skeleton (compose, proxy, `.env.example`).
* ADR 0001 contents: Path B; JDK pinned to what exists here and in CI (environment check); Maven vs Gradle; PostgreSQL (Q-52); VPS + Compose + SES (+ S3 or local volume); Caddy vs nginx; Path A criteria assessment with the 12d §2 EOL table ("verify" MySQL EOL); explicit exclusion of `logs/`, `api/`, `.env*`, test configs if A were ever chosen.
* Gate 0 decisions/questions: confirm Path B and stack (Python alternative rejected); Q-52 new VPS + PostgreSQL; spending gate for the new VPS, S3 bucket, GHCR, external uptime check, off-site backup storage; session and token lifetimes (no Q-id); backup retention 7/4/12 (add to Q-18); Q-05 amount/VAT flag before migration and e-mail/report wording; Q-43 business mailbox (new processor) before go-live; Q-29 crawl go-ahead and Q-27 probes; urgent owner-only actions Q-48, Q-49, Q-53; report date-range time zone (Europe/Rome vs legacy UTC); freeze block list = superset.
* Risks: production schema drift (`ddl-auto: update`, 12b §1.8) breaks the importer until a schema-only dump (Q-02) is seen; legacy `Double` amounts vs `numeric(10,2)` — reconciliation must use the same rounding; MySQL session time zone unknown → dates shifted when shown in Europe/Rome; "newest stable" stack vs JDK available in this environment and CI; HTMX under strict CSP unproven until E2E runs with violation reporting; old static site/Netlify keeps calling `app.` during transition, so CORS must stay exact (FX-07/08); unknown clients of `order/active`, `order/update` may break at freeze; rollback relies on manual re-entry (keep the window short, Q-40); SES identity reuse and DKIM/SPF/DMARC state are inferred; NFR-05/06/07 and MIG-03 can only reach "ready-for-owner" until the owner provides VPS and staging.
