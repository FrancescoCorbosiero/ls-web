# Phase 0 reader — chapter 12d · Legacy backend: infrastructure, configuration, deployment, repository hygiene, branch history

Source: `docs/brief/12d-backend-infra-and-repo.md` (965 lines, read in full on 2026-10-16). Cross-checked against README, 01 (sources, diagram), 02 §7 (EP table), 08 §4.7–4.9, 09 (API, ADM, EML, MIG, NFR, PUB-04, TRK-01), 10 §1, §3, §4, §7, 11 (§0 urgent, Q-01/02/16/18/19/20/21/29/30/39/45…53), 12 §2–§5, 12a §1 (#12–13), 12b §1.1/§5 (timestamps) and PROMPT.md. Citations are `ls@migration` = `ca1b0d0` (the local checkout `legacy/ls` is at that commit); `M/` = `src/main/java/it/logisticasanchez/mono/`.

## 1. Scope

The chapter reads the legacy backend's hosting and operations from the repository: the Contabo VPS with Docker Compose (nginx → Spring Boot 3.2.2 `/mono` → MySQL 8.0.35 `lsdb`), the configuration that really applies in production (part of it lives only on the server), TLS, health checks, logging, backups, monitoring, the deploy scripts (§1), the end-of-life stack (§2), nineteen hygiene and security findings F-01…F-19 (§3), the branch map proving `migration@ca1b0d0` is production (§4), what `ls-web@abe31e4` was (§5), and the corrections it made to chapters 01, 08, 10 and 11 plus the new questions Q-45…Q-53 (§6).
It is an operations and cut-over chapter: it carries almost no functional parity rule, but it fixes the infrastructure default of the new app (§6.2), the freeze/import/DNS/rollback steps (§6.4–6.5) and the urgent actions on the live system (F-01, F-02, F-08, F-09, Q-48, Q-49, Q-53).
Reading map: **infrastructure + cut-over** reads §0, §1, §6 (with 10 §3, §7 and 08 §4.7–4.9); **migration** reads §1.7, §1.11, §6.4–6.5; **Phase 1 foundations** reads §6.2 (and §1.9–1.10, §3 for what not to repeat); not in the map but needed: **notifications** §1.8 (SES identity, link hosts), **tracking + compatibility** §1.2, §1.6 (hosts, CORS, old links), **back office / security** §3 F-01 (the two open CMS endpoints), the planner §2 (ADR 0001 assessment) and §4.3 (which commit is production).

## 2. Requirement IDs touched

| ID | Where 12d specifies or constrains it |
|---|---|
| API-01 | §1.2, §1.4 public base URL `https://app.logisticasanchez.com/mono/…` (the new app must answer on `app.`); §1.6 CORS = exact origins of `ALLOWED_ORIGINS` with credentials (template: apex, `www`, `app.`), §6.6 Q-39 |
| API-02 | §6.4 step 2: EP-19 and EP-20 stay open during the freeze |
| API-03 | §1.8 the link paths in e-mails already sent (`/mono/email-confirm`, `/mono/report`, `/mono/download-report`, `/mono/img/logistica-sanchez-logo.jpg`), §6.4 step 4 (served on `app.`), §6.6 Q-29/Q-50 (apex too; `http://`, S3 and EB forms from before 2025-11-16), §6.4 step 2 the retire list to block at the freeze |
| PUB-04, TRK-01 | §6.1, §6.6 Q-29: the delivery e-mail links `https://www.logisticasanchez.com/#tracking`, so `www` → apex must keep the fragment and the home page must handle `#tracking`; `ordini.` and `dashboard.` hosts redirect (§6.4 step 4) |
| ADM-09 | §3 F-01: EP-18 (report e-mail) is unguarded today; §1.8 report links never expire; §2 iText 2.1.7 must be replaced (PDF library choice) |
| EML-01…EML-09 | §1.8 SES eu-north-1 with a static key through SDK v1, no outbox, no bounce handling; the configured SES sender (`EMAIL_AUTHOR`, 12d §1.8; a verified SES identity, inferred); link host = `EMAIL_ORIGIN` (name mismatch with the `.env` template, F-14, Q-50); §6.2 SESv2 with a dedicated IAM user and the staging e-mail guard |
| MIG-01 | §1.7 engine MySQL 8.0.35, `lsdb`, `utf8mb4`, Hibernate-owned schema; §1.11 the `mysqldump` flags the importer must accept; §6.4 step 3, §6.5 §4.2 (dump inside the container, transferred encrypted) |
| MIG-02 | §1.7 the October-2025 timezone change (`TZ=Europe/Rome`, `serverTimezone=Europe/Rome`) to check in the dump before mapping timestamps |
| MIG-03 | §1.15 facts the owner must collect; §6.4 every cut-over step (TTLs for five names, nginx freeze snippet, dump, DNS incl. `dashboard.` → `/gestione/`, smoke test of an old e-mail link and the logo, rollback without `down -v`, retirement list); §6.5; §6.6 Q-19 (avoid 02:00 and 03:00) |
| NFR-02 | §1.5 no CSP, no rate limit, no forwarded-header strategy (F-12); §1.6/§3 secrets as committed defaults (F-03, F-04, F-05, F-07); §6.2 secrets, hardening, least-privilege IAM; §6.3 rate limits keyed on one trusted proxy hop |
| NFR-03 | §1.10/§3.1 bodies and personal data in logs without a cap (F-02, F-10); §6.2 logs without bodies, 30-day retention; Q-53 GDPR register |
| NFR-04 | §1.9 no health endpoint (HTTP 500 probe, F-11), management `'*'`/`env`/`configprops` exposure (F-13); §1.12 monitoring ineffective; §6.2 external uptime and TLS-expiry check, disk and backup-age alerts |
| NFR-05 | §1.11 local-only, unencrypted, 7-day backups with `.env` inside, untested restore (F-09, Q-48); §6.2 encrypted off-site backups, 7/4/12 rotation, monthly restore test, 26 h age alert |
| NFR-06 | §1.13 `deploy.sh` broken, no CI, tests skipped in the image build (F-11, F-18); §6.2 CI builds a versioned image to GHCR, `compose pull && up -d`, manual approval for production |
| NFR-07 | §1.11 restore, §1.13 deploy, §6.4 cut-over and rollback, §6.4 step 7 key rotation: the runbooks must cover each |
| ADM-01, CUS-01 | indirectly: F-02/F-06 (logged and committed staff passwords → rotate at cut-over); §1.8 verification links never expire (fixed by CUS-01, chapter 09) |

## 3. Rules to keep (M1 parity)

* API-01, API-03: the backend's public base is host `app.logisticasanchez.com`, prefix `/mono/`; `/` redirects to `/mono/` (ls@migration:nginx/conf.d/app-https.conf:28,54-74,77-79; src/main/resources/application.yml:4). The new app answers `app.` for the kept and the retired paths.
* API-01, Q-39: CORS allows exactly the configured origins, credentials on, methods GET/POST/PUT/DELETE/OPTIONS (M/configuration/security/WebSecurityConfig.java:58-67). Keep `https://logisticasanchez.com` for EP-13.
* API-03, EML-01/07, PUB-04: link shapes in e-mails already in inboxes — `https://{EMAIL_ORIGIN}/mono/email-confirm?email=…&verificationCode=…`, `/mono/download-report?email=…&from=…&to=…&onlyDelivered=…`, `/mono/report?…`, `/mono/img/<logo>`, and the fixed `https://ordini.logisticasanchez.com/`, `https://dashboard.logisticasanchez.com/` (M/business/util/LinkUtil.java:14-40; M/configuration/constant/PathConstants.java:59-61,64). Before 2025-11-16 the same paths were `http://` and pointed at the S3 hosts (§1.8). Answer all of them on the apex and on `app.` (Q-50), never confirming or serving data.
* PUB-04, TRK-01, EML-09: the delivery e-mail links `https://www.logisticasanchez.com/#tracking` (ls@migration:src/main/resources/templates/tracking-state.html:48): `www` → apex 301 keeps the fragment; the home page must open the tracking box on `#tracking`.
* EML-*: e-mail leaves from SES eu-north-1 under the configured sender identity (`EMAIL_AUTHOR`, 12d §1.8) (M/configuration/AwsMailConfig.java:20-26; docker-compose.yml:82). Reuse the verified identity and the sender address; only the key changes.
* MIG-01: the source is MySQL 8.0.35, database `lsdb`, user `LSADMIN`, `utf8mb4_unicode_ci`, in the `mysql_data` volume (docker-compose.yml:6,11-12,17-18,22-23); the dump is `mysqldump --single-transaction --routines --triggers --events --add-drop-database --databases lsdb` gzipped (scripts/backup.sh:40-56). The importer reads exactly that format.
* MIG-02: JVM `TZ=Europe/Rome` and JDBC `serverTimezone=Europe/Rome` since October 2025 (Dockerfile:34; docker-compose.yml:68); AWS-era rows may differ — verify in the dump (12b says the `Instant` columns are bound as UTC regardless; still check one row from each era).
* MIG-03, API-02: the app is reachable only through the legacy nginx (ports bound to `127.0.0.1`, docker-compose.yml:93), so the freeze is an nginx snippet that blocks the write paths and keeps EP-13/19/20 open (§6.4 step 2, README maintenance recipe README.md:377-408); never `docker compose down -v` on the legacy host (docker-compose.yml:17-18,147-149); do not run `scripts/deploy.sh` (deploy.sh:55,108); stay away from the 02:00 backup and 03:00 certbot crons (README.md:50; scripts/ssl-setup.sh:196).
* MIG-03: five names need TTL lowering, certificates and DNS at the switch: apex, `www`, `app.`, `ordini.`, `dashboard.` (§6.4, §6.5); `dashboard.` → 301 `/gestione/`, `ordini.` → `/area-clienti/`; keep the two S3 bucket names as redirects for good (LPS-S4).

## 4. Deliberate fixes (legacy NOT reproduced) — proposed ADR titles

| Legacy behaviour (12d) | Fix (chapter 09/10) | ADR title |
|---|---|---|
| EOL stack: Boot 3.2.2, SDK v1, Spring Cloud AWS 2.2.6, iText 2.1.7, MySQL 8.0.35 (§2); Elastic Beanstalk → VPS done outside git (§1.1, §4.2) | Path B on current Java + Spring Boot, PostgreSQL, one new EU VPS with Compose, AWS only for SES/S3 (10 §1, §3; Q-01, Q-52) | ADR 0001 "Path B on current Java/Spring Boot; PostgreSQL; new VPS at the same provider" |
| Hibernate `ddl-auto: update` owns the production schema; no Flyway (§1.7) | Flyway, forward-only, `validate` (10 §1, §3) | "Schema owned by Flyway migrations, never by Hibernate DDL" |
| Permit-all with a dead URL rule; EP-09, EP-18 (and `order/active`, `order/delivered`, report GETs) open (§0.5, F-01) | Deny by default, method security, a test per route; legacy paths answer 410 / a safe page (10 §4; API-03) | "Deny-by-default authorisation and 410 for retired legacy endpoints" |
| Every request/response body logged, no log cap, nginx logs with e-mails in URIs (§1.10, F-10) | Structured logs without bodies or personal data, 30-day retention (NFR-03, 10 §3) | "Logging policy: no bodies, no personal data, bounded retention" |
| No health endpoint; probes hit HTTP 500; `exposure '*'`, `env`, `configprops` configured (§1.9, F-11, F-13) | Actuator health only, no details, the only public management endpoint (NFR-04, 10 §4) | "Health endpoint only; management endpoints off the internet" |
| Secrets committed as defaults; `.env` tracked; `.gitignore` incomplete (F-03…F-05, F-07) | Configuration from the environment, redacted `.env.example`, gitleaks/secret scanning in CI (10 §3, PROMPT.md) | "Secrets only from the environment; secret scanning in CI" |
| One static IAM key for SES, S3 backups and (potentially) more; SDK v1; no outbox, no bounces (§1.8, §1.11) | SESv2, dedicated IAM user with `ses:FromAddress` condition, SQS bounce queue, outbox (10 §2–§4; NFR-04, EML) | "SESv2 through an outbox with a dedicated least-privilege IAM user" |
| No rate limiting in the live nginx; forwarded headers not configured (§1.5, F-12) | Rate limits in the app keyed on `X-Forwarded-For` from exactly one proxy hop (10 §4, §6.3) | "Rate limits keyed on one trusted proxy hop" |
| No CSP; HSTS only on `app.`; mixed `http://` origins in CORS (§1.5, F-15, F-16) | Strict CSP and the header set of 10 §4 on every host; same-origin app | "Security headers and CSP on every host" |
| Local, unencrypted, 7-day backups that include `.env`; untested restore (§1.11, F-09) | Encrypted off-site backups, 7/4/12 rotation, age alert, monthly restore test (NFR-05) | "Encrypted off-site backups with a tested restore" |
| Certbot standalone renewal that cannot run; private key `chmod 644` (§1.5, F-08) | Caddy automatic HTTPS, or nginx + webroot/DNS-01 renewal with a deploy hook (10 §3) | "TLS termination with automatic renewal" |
| `deploy.sh` pulls `main`, fake rolling update, tests skipped in the image, no CI (§1.13, F-11, F-18) | CI builds versioned images; deploy by tag with manual approval; rollback = previous tag + restore point (NFR-06) | "CI-built images, deploy by tag, rollback by tag" |
| `nohup` monitoring loop, silent mail alerts, nothing external (§1.12) | External uptime/TLS check, disk and backup-age alerts (NFR-04, 10 §3) | fold into the health ADR |
| `EMAIL_ORIGIN` host ambiguous (apex vs `app.`), `.env` name mismatch (F-14, Q-50) | One explicit base-URL setting; old `/mono/*` links answered on both hosts (API-03) | "Legacy e-mail links answered on apex and `app.`" (with Q-30) |

## 5. Unknowns by ID (recommended default)

| ID | Question raised in 12d | Default the brief gives |
|---|---|---|
| Q-45 | What runs on the VPS: proxy (nginx or Caddy), deployed commit, local changes, key names of `.env` / `application-prod.yml`, Ubuntu, VPS size | Not needed to build; before the cut-over the owner runs the read-only commands of §1.15 and shares names only |
| Q-46 | AWS-era leftovers: EB UAT, two RDS instances and snapshots, S3 buckets, IAM users/keys (pair B); personal data in them? | Inventory now; snapshot and delete what is unneeded; keep the S3 names as redirects (Gate 1) |
| Q-47 | Where `ordini.` and `dashboard.` are hosted and which backend they call; is the CMS really at `dashboard.`? | Assume both call only `https://app.logisticasanchez.com` |
| Q-48 | Backup cron installed? Off-site copy? Last restore test? | Encrypted off-site backups and a test restore before any migration work (urgent, Gate 1 at the latest) |
| Q-49 | `app.` certificate expiry and renewal | Check the expiry now and fix the renewal (urgent) |
| Q-50 | Production `EMAIL_ORIGIN` host (apex or `app.`) | Answer the `/mono/*` link paths on both hosts; until the switch the static site passes them on |
| Q-51 | What `ls-web@abe31e4` was | Closed: unrelated; the monolith serves the public pages (see contradiction 3) |
| Q-52 | New VPS, shared legacy VPS or managed AWS; PostgreSQL or MySQL | A new VPS at the same provider; PostgreSQL (MySQL 8.4 only if ADR 0001 keeps the engine) |
| Q-53 | Purge history (logs, keys, Postman) now? GDPR assessment of the personal data in committed and production logs? | Purge now, record in the register; production logs follow Q-18 (urgent) |
| Q-16 | partly answered: Contabo VPS + SES; DNS manager and hosting of apex/`ordini.`/`dashboard.` unknown | New app on a new VPS at the same provider; A records at the current DNS provider |
| Q-18 | retention of logs and backups | 30 days for logs/outbox bodies/IP hashes; 7/4/12 backup copies |
| Q-19 | cut-over window | Saturday afternoon, under one hour, away from 02:00 and 03:00 |
| Q-20 | retire Netlify | Yes, once DNS points to the new app |
| Q-21 | staff reads open? **yes** (EP-09 and more) | nginx stopgap today, `@PreAuthorize` fix, export access logs, GDPR assessment (urgent) |
| Q-29, Q-30 | old URL and e-mail-link inventory; must old links keep working? | 301 for every URL; a safe page on `/mono/**` links that never confirms or serves data |
| Q-39 | CORS origins | Only `https://logisticasanchez.com` until P-03 says otherwise |
| Q-01, Q-02 | answered/narrowed: source available (Java 17, Boot 3.2.2); engine MySQL 8.0.35 `lsdb` | Path B; `mysqldump --no-data` from the owner is optional |
| F-01…F-19 | the hygiene findings (§3): live exposure (F-01), committed logs (F-02), AWS pair B (F-03), JWT default (F-04), tracked `.env` (F-05), Postman passwords (F-06), DB default (F-07), TLS renewal (F-08), backups (F-09), log growth (F-10), deploy (F-11), rate limits (F-12), management exposure (F-13), config drift (F-14), CORS (F-15), plain HTTP (F-16), server hygiene (F-17), repo organisation (F-18), EOL stack (F-19) | Owner actions on the legacy system, "now" for F-01…F-09; the new app must not repeat any of them (§4 above); F-19 → ADR 0001 |
| LPS-S4 | S3 website bucket names | keep them as redirects for good |

## 6. Contradictions and spot check

1. **Scope of the open endpoints (code wins).** 12d §0.5 and F-01 name only EP-09 `order/pending` and EP-18 `cms/order/report`; the code also leaves `POST /mono/api/order/active` and `/delivered` without `@PreAuthorize` (ls@migration:M/api/controller/OrderController.java:68-78), as 12a §1 rows 12–13, 12 §3, 11 Q-21 and PROMPT.md state, plus the two report `GET`s. The F-01 nginx stopgap (two paths) is incomplete: follow Q-21's action (four `POST` paths without a Bearer header, the two report links).
2. **Freeze block list.** 12d §6.4 step 2 omits `/mono/cms/order/update` and `GET /mono/email-confirm`, which 10 §7 step 2 and 12b §5.6 add. Chapter 10 decides the new app's procedure: use its list in MIG-03.
3. **`ls-web` is no longer what §5 describes.** §5 and Q-51 describe `ls-web@abe31e4` (a generic Astro-site prompt). This repository is `ls-web` re-initialised at `8be833c` with the brief, PROMPT.md, CLAUDE.md and COME-USARLO.md: it *is* the monolith's repository ("You are building, in this repository…", PROMPT.md). Q-51's substance (the monolith serves the public pages) stands; §5's description and its Lighthouse 90-vs-95 remark are stale.
4. **Timezone break (12b narrows 12d).** §1.7 fears Europe/Rome vs UTC session timezones before and after October 2025; 12b §1.1 and §5 (brief 12b:88,1013) found Hibernate binds `Instant` columns as UTC wall-clock through a UTC calendar whatever the JVM zone. Keep the dump check 12d asks for, but expect UTC.
5. **Refresh-token lifetime (nuance).** §1.6 says the refresh token "lives shorter (2 h) than the access token (6 h)"; 12a:69-70 says it slides 2 h on each refresh and 10 §4 calls it "permanent … without logout". Both are right; the new app replaces it anyway.
6. **Stale line references, no live conflict.** §6.1–6.5 quote the pre-correction text of 01, 08 and 10 (e.g. "10:168-183 … Elastic Beanstalk"); the corrections are applied (10 §3 now starts at line 176 with the VPS default; 01 sources paragraph and diagram, 08 §4.7–4.9 updated). Treat §6 as history, read 01/08/10 for the current text.
7. **Q-46 placement.** §6.7 default "inventory now" while chapter 11 lists Q-46 at Gate 1; harmless, the planner lists it at Gate 0 as information to request.
8. PROMPT.md and 12d agree on `ca1b0d0` as production, MySQL 8.0.35 `lsdb`, and the files never to open; CLAUDE.md's "stop before touching production" means every "Action now" of §3 is an owner action, listed at Gate 0, never performed by an agent.

Spot check of citations in `legacy/ls` (checkout at `ca1b0d0`; no secrets or personal data read):

| Citation | Verdict | Evidence |
|---|---|---|
| ls@migration:nginx/conf.d/app-https.conf:28, 54-74, 77-79 | VERIFIED | line 28 `server_name app.logisticasanchez.com`, line 54 `location /mono/ {` with proxy headers and 60 s timeouts, line 77 `location = /` → 301 `/mono/` |
| ls@migration:Dockerfile:71-73 (HEALTHCHECK commented out) | VERIFIED | lines 72-73 are the commented `#HEALTHCHECK … /mono/actuator/health`; line 71 is the `# Health check` comment |
| ls@migration:M/business/util/LinkUtil.java:24-30 (`ordini.`, `dashboard.`) | VERIFIED | `buildPortalLink` at 24-26 returns `https://ordini.logisticasanchez.com/`; `buildCmsLink` at 28-30 returns `https://dashboard.logisticasanchez.com/`; the other builders use `https://%s/mono…` with the configured origin |
| F-01: M/api/controller/OrderController.java:55-59 and CmsController.java:79-80 without `@PreAuthorize`; WebSecurityConfig.java:41-42 | VERIFIED (incomplete) | `pendingOrders` at 55-59 and the report mapping at 79 carry no `@PreAuthorize` while 61/80 (`registered`, `detail`) do; `requestMatchers(MONO_CMS_ALL)…` then `.anyRequest().permitAll()` at 41-42. Also unguarded: `activeOrders` 68-72, `deliveredOrders` 74-78 (contradiction 1) |
| ls@migration:scripts/deploy.sh:55 (`git pull origin main`), 108 (`--scale app=2`) | VERIFIED | line 55 `git pull origin main || {`; line 108 `docker-compose up -d --no-deps --scale app=2 app`; line 151 scales back to 1 |

## 7. Planned tests

| ID | Tests (layer · assertion · tag) |
|---|---|
| API-01 | contract: EP-13 fixtures of 02 §10.4 answered on the apex and on `app.` (Host header); CORS preflight from `https://logisticasanchez.com` allowed, from `https://ordini.logisticasanchez.com` refused — `@Tag("API-01")` |
| API-02 | contract: EP-19/EP-20 JSON unchanged on `app.`; rehearsal: the freeze snippet keeps them answering (MIG-03 rehearsal checklist) — `@Tag("API-02")` |
| API-03 | integration: every retired `POST /mono/**` → 410 JSON, nothing written, no e-mail queued; `GET /mono/email-confirm?email=…&verificationCode=…` (both `http` → 301 → `https`, apex and `app.`) → the safe page, customer state unchanged; `/mono/report`, `/mono/download-report` → safe page, no PDF, no data; `/mono/img/logistica-sanchez-logo.jpg` → 200 image — `@Tag("API-03")`; E2E `[API-03]` one old link from a fixture e-mail |
| PUB-04, TRK-01 | E2E `[PUB-04]` `https://www.…/#tracking` → apex with fragment kept and the tracking box focused; `[PUB-04]` `ordini.` → `/area-clienti/`, `dashboard.` → `/gestione/` (301) |
| EML-01…09 | integration: every e-mail link uses the single base-URL setting (no `EMAIL_ORIGIN` ambiguity), sender = the configured identity, Reply-To set; staging guard: app refuses to start in the staging profile without mail catcher or allow-list; a non-allow-listed recipient is dropped and logged — `@Tag("EML-0x")`, `@Tag("NFR-04")` |
| MIG-01 | integration: importer loads a synthetic `mysqldump --databases lsdb` with `--add-drop-database`, routines/triggers/events sections and `utf8mb4` into the staging schema; idempotent on a second run — `@Tag("MIG-01")` |
| MIG-02 | unit: `datetime(6)` → `timestamptz` mapping asserts UTC reading; a synthetic row dated before and one after 2025-10-20 reconcile to the expected Europe/Rome display; reconciliation report counts/sums — `@Tag("MIG-02")` |
| MIG-03 | rehearsal (ready-for-owner): the runbook's nginx snippet tested on a local nginx container: write paths 503/410, EP-13/19/20 200; the DNS table lists five names; rollback section forbids `down -v`; dated rehearsal record on staging — `@Tag("MIG-03")` |
| NFR-02 | integration: deny-by-default route test (every route needs an explicit rule); rate limit keys on `X-Forwarded-For` from one hop only, socket address and client-set headers ignored; headers incl. CSP on apex, `app.`, customer area, back office; no credential in repository (gitleaks job) — `@Tag("NFR-02")` |
| NFR-03 | integration: a request with a body and an e-mail in the query leaves no body and no e-mail in the log output; log retention setting ≤ 30 days — `@Tag("NFR-03")` |
| NFR-04 | integration: `/actuator/health` 200 without details; `/actuator/env`, `/actuator/configprops` 404 from the public port; structured JSON log line format; bounce message handled without a public endpoint — `@Tag("NFR-04")` |
| NFR-05 | quality/rehearsal: backup script dry run produces an encrypted archive without `.env`; age-alert fires on a stale fixture; restore runbook rehearsed and dated (ready-for-owner) — `@Tag("NFR-05")` |
| NFR-06 | quality: CI workflow builds the image with tests run (not `-DskipTests`), pushes by tag, production job requires manual approval; compose uses pinned image tags and no `container_name` collision with a rolling update — `@Tag("NFR-06")` |
| NFR-07 | quality: the Italian runbooks exist for local setup, deploy, cut-over (12d §6.4 steps), rollback, restore, key rotation; lint checks the referenced commands exist in `deploy/` — `@Tag("NFR-07")` |
| ADM-09 | integration: the report download needs a session (never a public link); PDF produced by the maintained library with the 13 legacy columns — `@Tag("ADM-09")` |

## 8. Notes for the planner

* **Module ownership (10 §2).** `platform`: health endpoint, security headers/CSP, rate limits with one trusted proxy hop, structured logging without bodies, feature flags, secrets from the environment (NFR-02/03/04). `tracking`: `app.` host and `/mono/*` compatibility paths, CORS, 410 for retired paths, the safe page for old links and the logo file (API-01/02/03, Q-29/30/50). `site`: `www`→apex with fragment, `#tracking`, `ordini.`/`dashboard.` redirects (PUB-04). `notifications`: SESv2 + dedicated IAM user, outbox, bounce queue, staging guard (EML, NFR-04). `migration`: dump format, timestamp check, freeze/rollback runbook (MIG-01/02/03). Infrastructure (`deploy/` folder, compose, proxy, backups, CI, runbooks: NFR-05/06/07) belongs to the integrator in Phase 1, rehearsed with the owner at Gate 1.
* **Phase 1 foundations this chapter needs.** `deploy/` with compose (proxy + app + db on an internal network, only 80/443 published, pinned images, no `container_name` that blocks scaling), a health endpoint from the first commit, Flyway, the `.gitignore`/gitleaks guard, a redacted `.env.example` listing every key, a mail catcher and SES/S3 fakes locally, the staging profile guard, logrotation and log policy, forwarded-header strategy set to one hop.
* **Risks.** (1) The live system is exposed today (F-01) and may lose its certificate (F-08) or its data (F-09) before the cut-over: these are owner actions, not ours, but a legacy outage changes the timeline. (2) The production configuration is partly unknown (Q-45, Q-50): the `/mono/*` link host and CORS list are guesses until the owner looks at one e-mail. (3) The real schema was generated by Hibernate and may drift from the entities (12b §1.8): the importer must run drift checks on the dump. (4) AWS-era resources (Q-46) could still accept orders during the freeze. (5) Secrets in the legacy history reach every clone (Q-53): the agents' checkout must never be pushed anywhere and the session copy is to be discarded. (6) A new VPS and GHCR/S3 usage cost money: owner approval before provisioning.
* **For ADR 0001.** Record the §2 assessment verbatim in spirit: Spring Boot 3.2 fails the "supported" criterion; Path A would require SDK v2/SESv2, an iText replacement, dropping `javax.mail`, Jackson alignment, actuator and a Flyway baseline. Decide Path B on current Java/Spring Boot, PostgreSQL (Q-52), one new EU VPS with Docker Compose at the current provider, AWS only for SESv2 (+S3 optional), Caddy or nginx with working renewal, CI-built images deployed by tag.
* **For the Gate 0 summary (Italian).** Urgent owner actions on the legacy: F-01/Q-21 (nginx stopgap, `@PreAuthorize`, access-log export, GDPR assessment), Q-49 (certificate expiry), Q-48 (off-site backup and restore test), Q-53/F-02…F-06 (rotate the JWT key, SES key, staff passwords; purge history; check IAM for pair B), F-11 (never run `deploy.sh`). Decisions to confirm: hosting and engine (Q-52, cost of a new VPS), Q-16 (who manages DNS), Q-18 (retention), Q-19 (window), Q-20 (Netlify), Q-47 (what `ordini.`/`dashboard.` call), Q-50 (link host), Q-46 (AWS inventory). Information to collect before Gate 1: the §1.15 table (names only).
