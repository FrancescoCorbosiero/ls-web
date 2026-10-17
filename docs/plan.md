# Build plan — Logistica Sanchez web app

The working plan of the build (`PROMPT.md`, "How to run it"). Modules and owners of every
requirement ID, phases and workflows, discrepancies found between the brief and the code,
the defaults applied to chapter 11, risks, and the open decisions. English; the Gate
summaries for the owner are in Italian under `docs/gates/`.

## Status

* **Current phase:** Phase 1 — Foundations, on branch `phase-1` (stage 1 of 11 done on 2026-10-17; `docs/phase1/log.md`).
* **Last completed workflow:** Phase 1 stage 1 — Maven skeleton (Spring Boot 4.1.1, JDK 21, wrapper 3.9.11), `Application`, profiles, bootstrap security chain (health only), `scripts/local-db.sh`, `TEST_DATABASE_URL` test support with the Testcontainers fallback, `ApplicationContextIT`, `HealthEndpointIT` (`NFR-04`), CI workflow (build-and-test on a `postgres:16` service, guards: tracked-file guard + gitleaks), Dependabot, README. `./mvnw -B -ntp verify` green with the local database.
* **Next step:** Phase 1 stage 2 — Flyway `V001…` with the complete M1 schema of chapter 08 §2.3 (§5 "Phase 1" item 4); then stages 3–11 in the order of §5. Phase 0 closing steps 1–4 below were carried out by the integrator (`phase-0` holds the Phase 0 documents; `phase-1` branches from it); the Gate 0 items stay open for the owner.

  **The owner asked the agents to proceed autonomously** («puoi procedere in autonomia», 2026-10-17; `docs/gates/gate-0.md` §6), so the build does not wait at Gate 0: Phase 1 starts with Path B and the stack of ADR 0001 as proposed (ADR 0001 status "Proposed", with a Note line on the go-ahead). The go-ahead is not an answer to Q-01 or Q-52: both stay open, the owner can still change any item by answering with its ID, and nothing that spends money, touches production, DNS, real data or credentials is done without the owner (`PROMPT.md` rule 6). **Record of the go-ahead:** this paragraph, `docs/gates/gate-0.md` §6, ADR 0001 (Note line and "What the owner confirms at Gate 0") and, from step 2 on, the chapter 11 note, where `PROMPT.md` rule 7 says to look.
* **Open gate:** Gate 0 (presented; answers welcome at any time, the defaults stand meanwhile).
* **Blockers and cautions:**
  * **The GitHub repository `FrancescoCorbosiero/ls-web` is PUBLIC** (GitHub API on 2026-10-16, re-checked on 2026-10-17: `visibility: public`, `private: false`) while it holds `docs/brief/07-pricing.md`, `docs/brief/data/` and the description of the open vulnerabilities of the production system (chapters 11, 12). `PROMPT.md` says nothing is pushed until the owner confirms it is private; **the owner decided on 2026-10-17 (chapter 11, Q-54) to proceed on the public repository and let the agents push to it**, so pushes to this repository's branches are allowed and the confidential files stay until the owner decides otherwise. The exposure remains an open risk (§9, R-01); the Gate 0 summary repeats the steps to make the repository private.
  * **Confidential figures in the pushed history.** Two reader notes (`docs/phase0/readers/07-07-pricing.md`, `docs/phase0/readers/12c-12c-backend-emails-reports-pricing.md`) carried one line each with two monthly-discount percentages of chapter 07; those lines were pushed to the public repository in commits `8e1cacf` and `5d09b99` of `claude/great-einstein-4mfvfz` (the remote head `7054fe0` contains both). The working tree no longer holds them (correction of 2026-10-17, uncommitted), but the branch history does. Consequences: `phase-0` is built from `main` without that ancestry (Next step, step 3); nothing more is pushed to the session branch; the owner may delete it on GitHub once `phase-0` exists — GitHub may keep the orphaned commits reachable by SHA until its support purges them (R-01; `docs/gates/gate-0.md` §0).
  * No application code exists yet; Phase 1 writes the first migration and skeleton.
  * **Stack pins to re-check at Phase 1 start** (ADR 0001): Spring Boot 4.1.1 (newest GA on 2026-10-17; 4.2.0-M2 is a milestone), Thymeleaf 3.1.5.RELEASE through `spring-boot-starter-thymeleaf` with `thymeleaf-spring6` and `thymeleaf-extras-springsecurity6` (Boot BOM; no `thymeleaf-spring7` artifact exists), OpenPDF 3.0.5, AWS SDK v2 BOM 2.56.0, htmx 2.0.11, `@lhci/cli` 0.15.1, `@axe-core/playwright` 4.14.0, Playwright 1.56.1 (browsers installed here), PostgreSQL 16, JDK 21.
  * No Docker daemon in this environment (§1): tests run against a local PostgreSQL through `TEST_DATABASE_URL`; Testcontainers and Compose stay for CI and the owner's machine.
  * Owner-side urgent actions on the live system (Q-21, Q-53, Q-48, Q-49, Q-22, Q-23) are not blockers for the build but are repeated at every gate until done.

## 1. Environment check

Verified by the integrator on 2026-10-16, re-checked on 2026-10-17.

| Item | Found | Consequence |
|---|---|---|
| JDK | OpenJDK 21.0.12.1 (Ubuntu 24.04 package, `/usr/lib/jvm/java-21-openjdk-amd64`); `JAVA_TOOL_OPTIONS` carries the session proxy settings | Pin **JDK 21** here and in CI (`actions/setup-java`, `temurin` 21). JDK 25 is not available. |
| Maven | Apache Maven 3.9.11 (`/opt/maven`); the legacy Maven wrapper (3.9.5) also works through the proxy | Maven with wrapper (ADR 0001). |
| Gradle | 8.14.3 (`/opt/gradle`) | Not used. |
| Node | Node 22.22.0, npm 10.9.4, pnpm present (`/opt/node22`) | Playwright, axe, Lighthouse CI only; no bundler. |
| Playwright | 1.56.1 with `chromium-1194` and `chromium_headless_shell-1194` at `/opt/pw-browsers`; `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` | Pin Playwright 1.56.1; **never run `playwright install`** here; CI installs the same version. |
| PostgreSQL | 16.15 server binaries (`/usr/lib/postgresql/16/bin`: `initdb`, `pg_ctl`, `postgres`) and `psql` | Local PostgreSQL started with `initdb`/`pg_ctl` for integration and E2E tests; `TEST_DATABASE_URL` set → the test setup skips Testcontainers. Per-module databases `ls_<module>` (§5). |
| MySQL | **No client or server binaries** | The importer's MySQL path (restored `lsdb` dump) can only be run in CI (Testcontainers `mysql:8.0.35`) or on the owner's machine; locally the synthetic fixtures load into H2 in MySQL mode (ADR 0001, Phase 1 confirms). |
| Docker | Docker CLI 29.8.2 installed, **no daemon** (no `/var/run/docker.sock`, no `dockerd`) | No Testcontainers, no Docker Compose here: in-process fakes for SES (and S3 in M2), a mail catcher process for e-mail; Testcontainers and Compose kept for CI and the owner's machine. No test layer is dropped. |
| Hardware | 4 CPUs, 15 GB RAM, about 31 GB writable disk | Enough for the app, a local PostgreSQL, Playwright and Lighthouse; parallel Phase 2 worktrees share the same PostgreSQL instance with one database each. |
| Network | Outbound HTTPS through the session proxy; Maven Central reachable (`spring-boot-starter-parent` metadata: newest GA 4.1.1, newest milestone 4.2.0-M2); npm registry reachable; Docker Hub registry API answers 401 (anonymous) | Dependencies resolve; no image pulls here anyway. |
| Git | Branch `claude/great-einstein-4mfvfz` tracks `origin`; push credentials for `FrancescoCorbosiero/ls-web` configured; the Phase 0 commits `8e1cacf`…`7054fe0` are pushed there, the skeptic-round corrections are in the working tree only (§Status) | Per `PROMPT.md`: `phase-<n>`, `module/<name>`, `m2` branches — the session branch is outside that list, so `phase-0` (created from `main`, §Status step 3) becomes the branch of record; never force-push or rewrite `main`. |
| Repository visibility | **public** (GitHub API, 2026-10-16 and 2026-10-17) | Owner decision Q-54 (2026-10-17): proceed on the public repository. Risk R-01. |
| GitHub Actions | Available for the repository (public repository: standard minutes are not metered; GHCR package visibility for the app image and any storage cost are verified at Phase 1) | CI on pull requests and `main`; stop and tell the owner if minutes run out (`PROMPT.md`). |

## 2. Legacy checkouts

Read-only, gitignored, under `legacy/`. Cited as `repo@ref:path:line`.

| Repository | Branch cited | Commit cited by the brief | Checked out | Later commits on the branch |
|---|---|---|---|---|
| `ls` (backend) | `migration` | `ca1b0d022857977b212b774e0247d2218477e1f8` (2025-11-16) | `ca1b0d0` | none (branch tip) |
| `ls-home` (new static site) | `claude/determined-lovelace-ggeitw` | `9b78785` (brief cites its ancestor `fe4b056`; only `README.md`, `public/_redirects`, `public/js/tracking.js`, `public/tracking/index.html` changed since — verified with `git diff --stat`) | `9b78785` | none on the branch; **PR #3 merged into `main` as merge commit `13bcc73`**, so `main` now contains `9b78785` (the clone has no `main` ref; the "never port `main`" rule is verified on `bb5528c`, the `main` the brief cites) |
| `ls-home-v1` (old site) | `v1` | `82017161a0ed51365813b7857663145d126acaf2` | `8201716` (grafted: its parent `e5b9298` is not in the clone, D-31) | not verifiable: the clone has no `v1` ref (`git show-ref` lists only the ls-home branch `claude/determined-lovelace-ggeitw` = `9b78785`, 15 commits in all); it also holds the template import `0732746` (root `bca0776`, not an ancestor of `8201716`) used by D-30 and D-33 |
| `ls-order` (order portal) | `dev` | `dbe7292216bb44b514a3d652ea99f6813ee01d0b` (2024-06-30) | `dbe7292` | none (branch tip) |
| `ls-cms` (CMS) | `dev` | `f0c881cc564d5ee966372641eae85e6484d9597a` | `f0c881c` | none (branch tip) |

**Sanitisation of the local `ls` clone** (never undone, never pushed anywhere): `logs/`,
`api/`, `.env`, `.env.local` and `local-setup.sh` deleted from the working tree; every value
in `src/**/*.yml` and `*.properties` replaced by `<value redacted>` (key names kept); two
example-key lines in `README.md` and `scripts/backup.sh` redacted. Readers never ran `git
show`, `git log -p`, `git stash`, `git checkout` or `git restore` on that clone; claims of
the brief that rest on logs, `.env` or YAML values are reported as NOT CHECKED, not as
re-verified (`docs/phase0/readers/legacy-ls-api-security.md` §3 item 4). The clone is a
single-branch fetch (`migration` only): the branch map of 12d §4.1 could not be re-checked.

**Precedence.** How things work today: the legacy code wins over chapter 12, and chapter 12
wins over chapters 02–08 (written from the frontends). What the new app does is decided by
chapter 09 and the ADRs; the owner's decisions (chapter 11 "Decision" column, ADRs) win over
all of them. Every disagreement found is listed in §7.

## 3. Path and stack

**Path B** — a new monolith on Java 21 + Spring Boot 4.1.1 (Maven with wrapper), Thymeleaf +
htmx 2.0.11, PostgreSQL 16 + Flyway, AWS SDK v2 (SESv2 in eu-north-1; SQS only with bounce
handling; no S3 in M1), OpenPDF 3.0.5, Playwright 1.56.1 + axe + Lighthouse CI,
Testcontainers in CI with the `TEST_DATABASE_URL` fallback here; hosting on **a new Contabo
VPS** with Docker Compose and Caddy 2; `legacy/ls` as the primary reference for every rule
and text. Assessment of Path A, exact pins, hosting, database engine, consequences and the
items the owner confirms: **`docs/adr/0001-path-and-stack.md`**. The deliberate fixes
collected by the readers are reserved as planned ADRs 0002…0028 in `docs/adr/README.md`.

## 4. Modules and owners

### 4.1 Module layout (chapter 10 §2) and packages

One deployable, one database, base package `it.logisticasanchez.web`; one package per
module, each exposing services (never another module's repositories), with its own
templates under `templates/<module>/` and message keys under `<module>.*`.

| Module | Package | Owns (tables of chapter 08 §2.3 amended by 12b §5.4; routes) | Delivered by |
|---|---|---|---|
| `platform` | `…web.platform` | configuration and typed settings (every `Q-xx` seam), security filter chain (deny by default, one extension point per module), headers/CSP, forwarded-header trust (one proxy hop), rate limiter (modules register their keys), error model and JSON error schema, `feature_flag`, `audit_log` + `audit_log_detail`, structured logging without bodies, health endpoint, host routing (apex, `www`, `app.`, `ordini.`, `dashboard.`), business configuration bean (PUB-02), retention job scheduler, in-process domain event publisher (the only extension point for the chapter 09 "Later" backlog: events customer verified/approved/declined, order created/approved/declined, tracking changed, lead stored; no subscriber code in M1/M2 — ADR 0025), static resources of ls-home@9b78785, layout fragments and design-system assets, message-bundle structure, `dev-seed` command | Phase 1 (integrator) |
| `auth` | `…web.auth` | `staff_user`, `customer_user`, `login_token`; staff form login, roles, lock-out, `create-staff-user` command; customer magic-link sign-in (`/area-clienti/`, `/area-clienti/accesso`) finished; sessions | Phase 1 (integrator) |
| `site` | `…web.site` | the 26 public pages as templates, `/api/form`, `lead`, sitemap/robots/manifest/security.txt, redirect table, 404 and `/grazie/`, `config.js`, the CAP widget dataset | Phase 2 · site |
| `tracking` | `…web.tracking` | `tracking_state`, `tracking_event`, `accessory_service` seeds (ADM-10), `/tracking/`, `POST /mono/api/order/trace/state`, the two enumeration endpoints, the `/mono/**` 410 catch-all and the Italian page for old links, tracking transitions (ADM-06 rules) | Phase 2 · tracking + compatibility |
| `customers` | `…web.customers` | `customer`, pending re-registration data (new table, Phase 1), customer state machine, registration/verification/approval/decline rules, profile and e-mail change, export and erasure tools (NFR-03) | Phase 2 · customers |
| `orders` | `…web.orders` | `shipment_order`, `order_line`, `order_internal_note`; wizard, totals, temporary codes, idempotency, approval/decline contract (ADM-04 rules), detail DTO, customer order list | Phase 2 · orders |
| `backoffice` | `…web.backoffice` | `/gestione/**` controllers and templates (ADM-02…ADM-09 screens, `/gestione/impostazioni/` flags and allow-list), E2E of the screens; rules stay in the domain modules | Phase 2 · back office |
| `notifications` | `…web.notifications` | `email_outbox`, templates (text + HTML) of EML-01…EML-09, SESv2 sender, worker with retries, staging guard, office address and allow-list settings, bounce/complaint handling (NFR-04) | Phase 2 · notifications + reports |
| `reports` | `…web.reports` | `report_run`, ADM-09 selection and PDF (OpenPDF), authenticated download, EML-07 payload | Phase 2 · notifications + reports (same worktree) |
| `migration` | `…web.migration` (CLI profile) | `legacy_key_map`, `import_run`, `import_issue`, drift check, importer, decisions file, reconciliation report, probe script (`tools/probes/`, never run without Q-27), cut-over runbook with the integrator | Phase 2 · migration |
| `leads` (M2) | `…web.leads` | inbox UI over `lead` (ENH-06), saved quotes (ENH-20) | Phase 5 |
| `pricing` (M2) | `…web.pricing` | price lists, Excel import, engine, suggestions, margin view (ENH-01…04, ENH-10, ENH-11) | Phase 5 |

### 4.2 Owner of every requirement ID (exactly one per ID)

"Owner" is the module/agent responsible for the ID reaching green in `docs/parity-matrix.md`
(tests tagged with the ID). "Rules from" names the domain module whose service the owner
calls; those modules tag their unit tests with the same ID.

| ID | Owner | Phase | Rules from / notes |
|---|---|---|---|
| PUB-01 | site | 2 | parity harness (DOM-normalised diff vs ls-home@9b78785) with the allow-list of ADR 0012 |
| PUB-02 | site | 2 | business configuration bean from `platform` (ADR 0027); fragments from Phase 1 |
| PUB-03 | site | 2 | route registry; `X-Robots-Tag` filter from `platform` |
| PUB-04 | site | 2 | redirect table (paths) + host rules (`platform` routing, Phase 1); Q-29 crawl appended as data |
| PUB-05 | site | 2 | header/CSP filter and caching rules from `platform` (Phase 1); site asserts them on public routes |
| PUB-06 | site | 2 | `/api/form` validation, honeypot, Origin/Referer check; rate limiter from `platform`; e-mail through `notifications` (EML-08) |
| PUB-07 | site | 2 | as PUB-06 |
| PUB-08 | site | 2 | server-side zone dataset equal to `eta.js`; Maps consent |
| PUB-09 | site | 2 | 404 page with status 404; `/grazie/` noindex |
| PUB-10 | site | 2 | Lighthouse CI and axe jobs created in Phase 1; site keeps them green |
| PUB-11 | site | 2 | `lead` row + outbox row in one transaction |
| TRK-01 | tracking | 2 | `/tracking/` page = ls-home widget (site assets) over the tracking service |
| TRK-02 | tracking | 2 | code normalisation (Q-03), TEMP codes (Q-38) |
| TRK-03 | tracking | 2 | public payload allow-list |
| TRK-04 | tracking | 2 | rate limiter from `platform`, key `tracking.lookup` |
| API-01 | tracking | 2 | contract fixtures of 02 §10.4 on both hosts; CORS (Q-39) |
| API-02 | tracking | 2 | enumeration endpoints over the ADM-10 seeds |
| API-03 | tracking | 2 | 410 JSON for the 33 retired mappings, Italian page for old `GET /mono/**` links, logo from site assets; host `app.` from `platform` |
| CUS-01 | auth | 1 | finished in Phase 1 (magic link, uniform answer, `REGISTER` link); `customers` later reuses the link for registration |
| CUS-02 | customers | 2 | registration form and rules (Q-10, Q-36), verification, pending; EML-01/02/06 through `notifications` |
| CUS-03 | customers | 2 | status pages per state |
| CUS-04 | orders | 2 | wizard, lines, totals, temporary code, idempotency; EML-04/06 through `notifications` |
| CUS-05 | orders | 2 | customer order list and detail; authorisation test |
| CUS-06 | customers | 2 | profile, `CHANGE_EMAIL` token (from `auth`), audit |
| ADM-01 | auth | 1 | staff login, roles, lock-out, `create-staff-user`, no seeded accounts outside local/test |
| ADM-02 | backoffice | 2 | rules from `customers` (Q-28, Q-09) |
| ADM-03 | backoffice | 2 | lists and customer page; search |
| ADM-04 | backoffice | 2 | rules from `orders` (approval contract, Q-05, Q-06); EML-05 through `notifications` |
| ADM-05 | backoffice | 2 | pagination, filters, search from `orders` queries |
| ADM-06 | backoffice | 2 | rules from `tracking` (events, EML-09 trigger) |
| ADM-07 | backoffice | 2 | rules from `orders` (internal note, Q-26) |
| ADM-08 | backoffice | 2 | detail DTO from `orders` (lines regrouped) |
| ADM-09 | reports | 2 | selection, PDF, allow-list, authenticated download; the form screen is a `backoffice` template calling `reports` |
| ADM-10 | tracking | 2 | seed test of both enumerations (the seed migration itself is written by the integrator in Phase 1) |
| EML-01 | notifications | 2 | templates and outbox; triggers from `auth`/`customers` |
| EML-02 | notifications | 2 | new (Q-07); trigger from `customers` |
| EML-03 | notifications | 2 | trigger from `customers` (approve/decline) |
| EML-04 | notifications | 2 | new (Q-07); trigger from `orders` |
| EML-05 | notifications | 2 | trigger from `orders` (approve/decline); decline quotes the code |
| EML-06 | notifications | 2 | office notice once per verification and per order |
| EML-07 | notifications | 2 | payload from `reports` |
| EML-08 | notifications | 2 | content ported from `form.mjs`; data from `site` |
| EML-09 | notifications | 2 | trigger from `tracking` on `CONSEGNATO` only |
| MIG-01 | migration | 2 | importer, dry-run, idempotency |
| MIG-02 | migration | 2 | mapping rules (ADR 0021), reconciliation report; **ready-for-owner at most** on real data |
| MIG-03 | migration | 2 | cut-over and rollback runbook with the integrator's `deploy/`; nginx block-list test; **ready-for-owner at most** |
| NFR-01 | platform | 1 | layout fragments usable at phone width, axe in the E2E harness; each module's E2E runs at phone width |
| NFR-02 | platform | 1 | security baseline of 10 §4: deny-by-default route matrix, CSRF, headers/CSP, rate limiter, secrets, IAM scope, audit log |
| NFR-03 | customers | 2 | export and erasure tools, retention hooks; privacy/cookie page texts by `site` under PUB-01; retention scheduler from `platform` |
| NFR-04 | notifications | 2 | bounce/complaint handling and alert e-mails; the health endpoint and structured logging are Phase 1 foundations whose tests carry the tag |
| NFR-05 | integrator (infrastructure) | 1 + Gate 1 | backup/restore scripts in `deploy/`, age alert; **ready-for-owner at most** |
| NFR-06 | integrator (infrastructure) | 1 | CI pipeline, image build, deploy jobs; **ready-for-owner at most** for the deploy part |
| NFR-07 | integrator (infrastructure) | 1 + 4 | runbooks in Italian (`docs/runbooks/`); **ready-for-owner at most** |
| ENH-01 | pricing | 5 | flag `pricing.lists` |
| ENH-02 | pricing | 5 | flag `pricing.suggestedAmount`; hook in `orders` approval |
| ENH-03 | pricing | 5 | flag `pricing.customerEstimate` |
| ENH-04 | pricing | 5 | flag `pricing.publicEstimate`; Q-14 |
| ENH-05 | tracking | 5 | flags `tracking.history`, `tracking.notifyCustomer` |
| ENH-06 | leads | 5 | flag `leads.inbox` |
| ENH-07 | orders | 5 | flags `customers.addressBook`, `orders.repeat`; `customer_address` table |
| ENH-08 | backoffice | 5 | flags `admin.dashboard`, `admin.exports` (cells as text) |
| ENH-09 | auth | 5 | flag `security.adminTotp` |
| ENH-10 | pricing | 5 | flag `delivery.unifiedTimes`; swaps the `site` zone dataset |
| ENH-11 | pricing | 5 | flag `pricing.marginView` |
| ENH-12 | orders | 5 | flag `documents`; S3 arrives here (ADR 0001) |
| ENH-13 | backoffice | 5 | flag `admin.audit` |
| ENH-14 | backoffice | 5 | flag `admin.enumerations`; enumeration owned by `tracking` |
| ENH-15 | tracking | 5 | flag `orders.bulkTracking` |
| ENH-16 | customers | 5 | flag `customers.profileEdit` |
| ENH-17 | site | 5 | flag `site.noticeBanner` |
| ENH-18 | site | 5 | flag `site.openNow` |
| ENH-19 | site | 5 | flag `site.pickupCutoff`; Q-44 |
| ENH-20 | leads | 5 | flag `quotes.saved` |
| ENH-21 | reports | 5 | flag `documents.monthlyReport` |
| ENH-22 | site | 5 | flag `site.liveCounters`; Q-31 |

### 4.3 Cross-cutting items and their owners

| Item | Owner | Notes |
|---|---|---|
| Rate limits | `platform` owns the limiter (keyed on the one trusted `X-Forwarded-For` hop); each module registers its keys: `tracking.lookup` (TRK-04), `form.submit` (PUB-06/07), `signin.link` per e-mail and IP (CUS-01), `staff.login` (ADM-01), `registration` (CUS-02) | Phase 1 delivers the limiter and the first keys (CUS-01, ADM-01) |
| NFR-02 headers and CSP | `platform` | one filter set by the app (not the proxy) so tests assert it; public pages: the `_headers` set; private areas: no `unsafe-*`, HTMX configured |
| NFR-03 privacy tools (export, erasure) | `customers` | erasure = anonymisation (10 §5); retention job scheduler in `platform` |
| NFR-04 observability | `notifications` | health endpoint, structured logs and alert plumbing from Phase 1; bounce/complaint queue and alert e-mails by the module |
| NFR-05 / NFR-06 / NFR-07 | integrator | `deploy/`, CI, runbooks; rehearsed with the owner at Gate 1 |
| PUB-04 redirects | `site` | path table; host rules in `platform` routing |
| API-03 410 pages | `tracking` | `/mono/**` catch-all on apex and `app.` |
| Feature flags | `platform` (table, cached service, ADMIN toggle screen in `backoffice`) | Phase 1 registers every ENH flag key, all off (ADR 0025) |
| Audit log | `platform` (table, append-only role, `AuditService`) | every staff action in `backoffice`/`reports` writes it |
| Outbox | `notifications` (table, worker, staging guard; the table and a stub `NotificationService` are Phase 1 skeleton) | importer never writes it |
| Business configuration (PUB-02, Q-15, Q-43) | `platform` | one bean feeding fragments, JSON-LD, `config.js`, e-mail footers, error pages, `security.txt` |
| Host routing (apex, `www`, `app.`, `ordini.`, `dashboard.`) | `platform` | Caddy forwards every host; the app decides (301 for `www`/`ordini.`/`dashboard.`, same app on apex and `app.`) |
| `dev-seed` | `platform` | staff users with random passwords, customers in every state, orders in every order × tracking state, one REGISTERED legacy-style row with no amount and no approval time (ADM-09 tolerance) |
| Parity-matrix generator | integrator | reads JUnit `@Tag` and Playwright title prefixes; regenerates `docs/parity-matrix.md` in CI from Phase 1 by merging the hand-maintained `docs/parity-plan.md` (the Phase 0 table frozen at Phase 1 start: ID, milestone, owner, planned tests, notes; edited only by the integrator, on a module's proposal) with the tag results — it overwrites only the Status column and the list of passing tagged tests per ID, never the planned tests or the notes |

### 4.4 Phase 1 (foundations) versus Phase 2 modules

Phase 1 delivers, alone and finished: the skeleton and build, CI, the local environment
(`TEST_DATABASE_URL`, mail catcher, fakes), the **complete M1 schema** of chapter 08 §2.3
amended by 12b §5.4 as the first migrations (including the tables chapter 08 names without
columns: `email_outbox`, `report_run`, `feature_flag`, `legacy_key_map`, `import_run`,
`import_issue`, plus a table for a declined customer's pending re-registration data and
`customer.legacy_payload`, `shipment_order.amount_raw`, `order_line.legacy_type_raw`), the
seeds of both enumerations, staff login and the customer magic-link sign-in (CUS-01, ADM-01),
layout fragments and design-system assets, the security configuration with one extension
point per module, message bundles, feature flags, audit log, the outbox table and worker
skeleton, the staging e-mail guard, host routing, the error model, the rate limiter, the
header/CSP filter, structured logging, the health endpoint, `deploy/` (Compose, Caddyfile,
`.env.example`, bootstrap script), the `dev-seed` command, and the skeleton of every module
(public service interfaces, DTOs, domain errors, stubs). Phase 2 modules implement their
IDs on top and never add migrations or edit shared files (§6).

## 5. Phases and workflows

### Phase 1 — Foundations (one agent, sequential; `PROMPT.md`, chapter 10 §8)

Checklist (CI green before Phase 2):

1. Maven skeleton with the pins of ADR 0001; Enforcer; a formatter/linter; Dependabot; secret scanning and a tracked-file guard (CI fails if `legacy/`, dumps, `.env*`, spreadsheets or logs are tracked, except the `.gitignore` exceptions); a confidential-figure guard: CI fails if a distinctive numeric token of `docs/brief/07-pricing.md` or `docs/brief/data/` (thresholds, percentages, rates; the token list is derived at run time from those files and never stored elsewhere) appears in a tracked file outside `docs/brief/`, except the documented worked examples under the test resources.
2. CI workflow: build, lint, unit, integration (PostgreSQL service or Testcontainers), contract, E2E (Playwright 1.56.1 against the app started with Compose in CI), axe, Lighthouse CI; parity-matrix generator; image build on every pull request; push to GHCR by tag only after the Phase 1 check shows that the package costs nothing (a public package, or within the free quota of the public repository), otherwise build only and wait for the owner (spending gate, `PROMPT.md` rule 6; ADR 0001 item 2, R-16); deploy jobs added once the owner provides staging.
3. Local environment: `TEST_DATABASE_URL` switch, local PostgreSQL scripts (`initdb`/`pg_ctl`), mail catcher, in-process SES fake, per-module databases and ports (§5 Phase 2 table), `dev-seed`.
4. Flyway migrations `V001…`: the complete M1 schema (§4.4) with the `CHECK` constraints and the indexes of chapter 08 §2.3 (incl. the unique indexes on the normalised shipment code, Q-06, and on the normalised customer e-mail, Q-11), seeds `tracking_state` and `accessory_service` (ids 0–5, item 0 `is_none`), `feature_flag` rows for every ENH flag (off), the append-only database role for `audit_log`.
5. Security: deny-by-default chain written against real request paths with a generated route × role matrix test, with one registration/extension point per module (a `SecurityContributor` or equivalent, chapter 10 §8); one JSON error schema (400/401/403/404/405/409/415/422/429, never 500 for client errors; Italian `reason` texts of the legacy `ErrorEnum` reused); forwarded-header trust of one hop; rate limiter; headers and CSP filter (public set of `_headers`; private areas without `unsafe-*`); HTMX configuration meta; CSRF on every customer-area and back-office form; `/api/form` exemption with Origin/Referer check.
6. `auth`: staff form login (bcrypt/argon2, ≥ 12 chars, common-password list, lock-out, `__Host-` cookie, idle/absolute timeouts of 10 §2, logout, `create-staff-user` one-time link); customer magic link (`login_token` hashed, 15 min / 24 h, `GET` renders the «Accedi» page, `POST` consumes; uniform answer; `REGISTER` link for unknown e-mails; `VERIFY_EMAIL`, `CHANGE_EMAIL` purposes).
7. `platform`: typed settings with every `Q-xx` seam of §8, business configuration (ADR 0027), host routing, feature-flag service, `AuditService`, structured logging without bodies, health endpoint only, retention scheduler, and the in-process domain event publisher (events customer verified/approved/declined, order created/approved/declined, tracking changed, lead stored, published by the owning modules); the ADR 0025 note states that the chapter 09 "Later" backlog (WhatsApp, driver app, RKS, e-invoicing) subscribes there and that no subscriber code exists in M1/M2 (`PROMPT.md` "Later": extension points only).
8. Layout: static resources copied unchanged from ls-home@9b78785, the four fragments (`head`, `header`, `cta`, `footer`) with the `aria-current` helper, page-key registry, message bundles seeded with the copy decks of chapters 03, 04, 06 (keys per screen), `config.js` generated from configuration.
9. `notifications` skeleton: `email_outbox`, `NotificationService` stub with the outbox contract, worker skeleton, staging guard (app refuses to start in `staging` without a mail catcher or an allow-list).
10. Module skeletons: public service interfaces, DTOs, domain errors, stubs, empty E2E specs with the title prefixes; `deploy/` folder; ADRs 0002, 0003, 0004, 0018, 0019, 0020, 0022, 0025, 0027.
11. Freeze the Phase 0 parity table as `docs/parity-plan.md`, regenerate `docs/parity-matrix.md` from the tags merged with it (§4.3), update `docs/plan.md` §Status and the `CLAUDE.md` commands section.

### Phase 2 — M1 modules in parallel

One agent per module in its own git worktree on `module/<name>`, with its own database and
HTTP port (the local PostgreSQL instance is shared; one database per module; test
databases `ls_<module>_test`):

| Worktree / branch | Module(s) | Database | Port | IDs |
|---|---|---|---|---|
| `module/site` | site | `ls_site` | 8081 | PUB-01…PUB-11 |
| `module/tracking` | tracking + compatibility | `ls_tracking` | 8082 | TRK-01…TRK-04, API-01…API-03, ADM-10 |
| `module/customers` | customers | `ls_customers` | 8083 | CUS-02, CUS-03, CUS-06, NFR-03 |
| `module/orders` | orders | `ls_orders` | 8084 | CUS-04, CUS-05 |
| `module/backoffice` | backoffice | `ls_backoffice` | 8085 | ADM-02…ADM-09 screens (ADM-09 service in `reports`) |
| `module/notifications` | notifications + reports (one worktree, as `PROMPT.md`; merged twice, see below) | `ls_notifications` | 8086 | EML-01…EML-09, ADM-09, NFR-04 |
| `module/migration` | migration tool | `ls_migration` | 8087 | MIG-01…MIG-03 |

The integrator's own worktree (`phase-2`, database `ls_web`, port 8080) holds the
integration branch. **Integration order**, running the whole suite after every merge:
**notifications → customers → orders → tracking + compatibility → reports → back office;
site and migration whenever green.** Schema changes and shared-file changes requested by a
module are applied by the integrator after the merge (§6). Each module writes its planned
ADRs (`docs/adr/README.md`) and proposes message-bundle keys and settings in its result.

**Reports inside the notifications worktree.** `module/notifications` holds both modules and
is merged twice: the first merge (first in the order) takes the outbox, templates, sender,
worker and staging guard with the `reports` package untouched (the Phase 1 skeleton only);
the second merge (fifth, after tracking + compatibility) takes the `reports` package, which
needs the tracking events for «Dt/Cons.» and the order queries for the selection. The agent
keeps the reports work in commits that touch only `…web.reports`, `templates/reports/` and
the `reports.*` message keys, so that the second merge is clean.

### Phase 3 — Review until dry

Review workflow over the integrated code with distinct lenses: parity against chapters
02–06 ID by ID; security (10 §4; 12a §5 as the checklist); privacy (10 §5); data integrity
and migration (12b §5–§6, DQ list); accessibility and performance budgets; code quality.
Every finding is checked by three independent skeptics trying to refute it and kept if at
least two confirm. A round is dry when it confirms no new blocker or major finding; minor
findings go to `docs/backlog.md`. Stop after two consecutive dry rounds or after round 4,
whichever comes first; what remains is listed in the Gate 1 summary.

### Phase 4 — Release readiness → Gate 1

Full E2E suite; Lighthouse and axe budgets; migration dry-run with the reconciliation
report on synthetic data (and on the anonymised copy if the owner provided it); runbooks in
Italian (local setup, deploy, cut-over, rollback, restore, key rotation); staging deploy if
the owner provided the VPS. Gate 1 package in Italian: the parity matrix; the E2E,
Lighthouse and axe reports; the reconciliation report; the runbooks; the defaults still in
force (Q-IDs); a UAT checklist for the office (one working week on staging, with the
people who use the CMS daily, Q-47); open risks. The owner tests on staging and sets the
cut-over date; the cut-over is run with the owner (chapter 10 §7; MIG-03).

### Phase 5 — M2 → Gate 2

Only after the cut-over and the rollback window (Q-40); until then M2 work stays on branch
`m2` and only Gate 1 fixes reach `main`. One workflow per flag group, each behind its
flag, off by default, with a "flag off = M1" regression test: pricing (ENH-01…04, ENH-11);
tracking history and notifications (ENH-05); leads and saved quotes (ENH-06, ENH-20);
customer conveniences (ENH-07, ENH-12, ENH-16, ENH-21); staff tools (ENH-08, ENH-09, ENH-13,
ENH-14, ENH-15); site extras (ENH-17, ENH-18, ENH-19, ENH-22); delivery times (ENH-10). The
owner turns flags on one by one from `/gestione/impostazioni/`.

## 6. Migrations, shared files and branches

* **Migrations.** Only the integrator creates Flyway migrations; versions are strictly
  sequential (`V001`, `V002`, …) and forward-only; a migration that is merged is never
  edited; per-module version ranges are not used. Phase 2 agents describe the schema change
  they need in their result; the integrator writes the next migration on the integration
  branch after the merge. `ddl-auto: validate` in every profile.
* **Shared files, edited only by the integrator:** `CLAUDE.md`, `docs/brief/11-open-questions.md`
  ("Decision" column: owner answers only; the planner-defaults table PD-01…PD-17 under its Gate 0 section is added by the integrator at the close of Phase 0, §Status step 2), `application*.yml` and the security
  configuration, the shared layout fragments and design-system assets, the message bundles,
  `docs/parity-matrix.md` (generated), `docs/plan.md`, `.github/workflows/*`, `deploy/`.
  Agents put proposed changes (keys, settings, fragments) in their result.
* **Branches:** `phase-<n>` per phase (`phase-0`, `phase-1`, …), `module/<name>` per Phase 2
  worktree, `m2` for M2 — the `PROMPT.md` list. Phase 0 was written and pushed on the session
  branch `claude/great-einstein-4mfvfz`, which is outside that list and whose history holds
  two reader-note lines with confidential chapter 07 figures (§Status, R-01): at the close of
  Phase 0 the integrator creates `phase-0` from `main` (`8be833c`) with **one fresh commit** of
  the corrected Phase 0 files (no ancestry from the session branch), pushes it and opens the
  PR `phase-0` → `main` (§Status); nothing more is pushed to the session branch, and
  `phase-0` is the branch of record. At the end of each phase a PR `phase-<n>` → `main` is
  opened and merged when CI is green; at a gate it stays open for the owner. `main` is never
  force-pushed or rewritten; pushes go only to this repository.
* **CI** runs on pull requests and on `main` with caching; if GitHub Actions minutes run
  out, stop and tell the owner (paid minutes are not enabled by the agents).
* **Parity matrix** generated from the tags (JUnit `@Tag("CUS-04")`, Playwright title prefix
  `[CUS-04]`); statuses planned · in progress · green · ready-for-owner · blocked (Q-xx);
  NFR-05, NFR-06 (deploy), NFR-07, MIG-02 on real data and MIG-03 reach at most
  ready-for-owner until a dated rehearsal record (who, when, result) is added under
  `docs/rehearsals/`. Never green on a placeholder test.

## 7. Discrepancies found

Every disagreement the Phase 0 readers reported (`docs/phase0/readers/*.md`), with the
citation and the resolution. "Code wins" = how the legacy works; "09 decides" = what the
new app does per chapter 09/11; "ADR" = the planned ADR that records the choice. Legacy
citations are `repo@ref:path:line`; `J/` = `src/main/java/it/logisticasanchez/mono/` of
`ls@migration`. Nothing below changes a decision the owner has taken.

### 7.1 Brief versus legacy code (the code wins on how the legacy works)

| # | Where the brief says | What the code says | Resolution |
|---|---|---|---|
| D-01 | 12d §0.5 and F-01 name only EP-09 `order/pending` and EP-18 `cms/order/report` as unguarded | `activeOrders` and `deliveredOrders` also lack `@PreAuthorize` (J/api/controller/OrderController.java:68-78), as do `GET /mono/report` and `/mono/download-report` (J/web/WebController.java:39-68) | Six open paths, as chapter 11 Q-21 lists; the owner's nginx stopgap uses Q-21's wording (four `POST` paths without Bearer, the two report `GET`s), not F-01's two paths |
| D-02 | 12a G2, 12c §3: `GET /mono/download-report` fails only on a future `from` | Every report row is validated (`@Valid ReportRepr`: `dataSpedizione` and `importo` not null, `peso` positive; the `@Validated` `HorizontalPdfGenerator.generateHorizontalPdf(@NonNull @Valid ReportRepr)`, J/business/util/HorizontalPdfGenerator.java:22-25, called from J/business/service/ReportService.java:42; J/api/repr/ReportRepr.java:43-74; J/business/factory/ReportFactory.java:33, 51 set `importo` only when non-null): a REGISTERED order with null `importo` still in PRESA, or with no `confirmationTs`, makes the whole PDF answer 400 | ADM-09 renders "-" for a missing amount or date, never fails; MIG-02 counts such rows; `dev-seed` includes one (ADR 0016, 0021) |
| D-03 | 12c §4.2, 09 ADM-04, 12 §3: approval requires code and `importo` > 0 | Only when the body carries `req`: `OrderConfirmationReq.req` has no `@NotNull` (J/api/req/OrderConfirmationReq.java:22); without it `confirm` takes the `promote` path (J/business/service/crud/SpedizioneService.java:107-114, 150): REGISTERED with the `TEMP…` code, no amount, no `confirmationTs`, tracking `NON_GESTITO`, and L-E6 "Conferma ordine N. TEMP…" sent. `promote` adds 1 to any non-REGISTERED ordinal, so a declined (`NEW`) order re-pends and a `DECLINED` row answers 500 | The new app has no promote path (ADR 0007); the importer expects REGISTERED rows with TEMP codes and null amount/approval time (DQ-32) and lists them for the owner (ADR 0021) |
| D-04 | 12a §1.5 (line 895): the order-decline e-mail "shows the order id" | `T/error.html:47` renders only `${request}`; `codice` is put in the context and never used (J/business/util/EmailReqFactory.java:94-96): 12c L-E7 is right | EML-05 decline quotes the order code — new behaviour (ADR 0015) |
| D-05 | 12c §1.6, 09 EML-09: no rule on notes in the delivery e-mail | `T/tracking-state.html:103-104` prints `note`, the same column staff overwrite from the CMS notes dialog (J/business/service/OrderService.java:181-203): staff notes reach the customer at delivery | EML-09 never prints internal notes; the customer's own note only (ADR 0009, 0015) |
| D-06 | 12c §1.2 L-E7 | `OrderService.decline` checks that the e-mail belongs to a customer (:140) but not that the order belongs to that customer (:141): staff could decline order X and notify customer Y | ADM-04 decline derives the recipient from the order's customer (ADR 0007) |
| D-07 | 12c §3.6–3.7 understate `POST /mono/cms/order/report` | Unauthenticated (J/api/controller/CmsController.java:79-80) and `email` need not be a customer's (J/api/req/ReportReq.java:20-23): with `sendToCustomer=true` anyone can make the company's SES identity e-mail "Report ordini" links to any address | Added to the Q-21 block list in the Gate 0 message and to the MIG-03 freeze list (ADR 0023) |
| D-08 | 12c §3.4 col 4 "Dt/Cons." = the time the order first left PRESA | For delivered orders it prints the stored `shippingTs`, re-stamped on every TRANSITO (J/business/service/crud/SpedizioneService.java:246), set once on other states only if null (:248-255); for undelivered orders `updateTms`, then `confirmationTs` (:224-229). `deliveryTs` is never printed | ADM-09 "Dt/Cons." = the time of the `CONSEGNATO` event, else "-" (ADR 0016); the owner sees a sample at Gate 1 |
| D-09 | 12b §3.6: a note over 255 characters from EP-16 fails in MySQL with 500 | `PatchSpedizioneReq.note` has `@Size(max=255)` validated by the `@Validated` service (J/api/req/crud/PatchSpedizioneReq.java:35-36; SpedizioneService.java:41, 90) → 400. Codes have no request-side limit: `SpedizioneConst.CODICE_CHAR_MAX = 50` is applied only to the never-validated output model (J/business/model/constant/SpedizioneConst.java:5-6; J/business/model/Spedizione.java:27-29), so codes up to 255 characters exist | `shipment_order.code` at least `varchar(255)` (08 §2.3 says 64) and `amount_raw` wide enough for three doubles; the importer asserts lengths (Phase 1 schema, ADR 0020) |
| D-10 | 08 §3.2 and §5 DQ-13: when `importo` and `importoAssicurato` differ, use `importoAssicurato` ("what staff typed") | Since `d7732eb` the typed amount is `importo` (SpedizioneService.java:153, 168); `importo_assicurato` is optional and `importo_contrassegno` is overwritten with it (:166-167). 07 §1.1, 12b §3.5 and 12c §7 already say `importo` first | Migration rule `COALESCE(importo, importo_assicurato)` rounded half-up, both raw values kept, differences flagged DQ-13 (ADR 0021) |
| D-11 | 08 §3.4: line `type` null/missing → GENERIC + DQ-15 | `type` is `@NotNull` on the wire (J/api/req/OrderPalletReq.java:36-37) and NOT NULL as a label (J/persistence/model/PalletEntity.java:34-35); the only ambiguity is EPAL written by the portal fallback `palletTypeId = 1` (ls-order@dev:js/function/data-handler.js:24-25 vs js/constant/costant.js:87-88), also reached whenever the one `typeSelected` flag was ever set (js/function/component-handler.js:11, 107, 133) | DQ-15 = EPAL over-representation, undetectable; reconciliation counts only (ADR 0021) |
| D-12 | 08 §3.4 and DQ-16: line values 0 | `@Positive` on every dimension and weight (OrderPalletReq.java:16-29): zeros cannot exist from the `migration` build; non-integer centimetres/kilograms can (DOUBLE columns) | DQ-16 = non-integer dimensions; `order_line` keeps a lenient `CHECK` for legacy rows only; new input integers ≥ 1 (Q-35) |
| D-13 | 08 §2.3 `line_volume_m3` per-line `round₂` (portal formula); 08 §4.6 compares Σ m³ | The backend sums unrounded unit volumes and rounds the order total to a whole m³ (J/business/service/OrderService.java:89-94; J/business/util/MathUtil.java:6); the portal shows `toFixed(2)` per line and never sends totals (ls-order@dev:js/function/data-handler.js:88-115) | CUS-04 keeps the portal's per-line two decimals in decimal arithmetic; reconciliation recomputes totals from `pallet` rows and never compares with `dettaglio_colli.volume` (DQ-28; ADR 0006, 0021) |
| D-14 | 08 §3.2: match orders to customers by normalised e-mail; no match → DQ-10 blocking | `spedizioni.mittente_id` is a NOT NULL foreign key (J/persistence/model/SpedizioneEntity.java:88-90) | Join on the FK; DQ-10 is an assertion (12b §5.2) |
| D-15 | 12b §3.4 report fallback | Besides `shipping_ts := update_tms` when `delivery_ts` is null, the report also sets `shipping_ts := confirmation_ts` when still null (SpedizioneService.java:224-229), in memory only | Documented for the ADM-09 parity test on synthetic data (ADR 0016) |
| D-16 | 08 §2.3 `amount_raw varchar(50)` "legacy text as typed" | The source holds three doubles, never text (SpedizioneEntity.java:50-55) | Store the three values as text (12b §5.4); column widened (Phase 1) |
| D-17 | 08 §3.1: `emailConfirmed` → the dump's date if any | No verification timestamp exists; `update_tms` is the link time only for PENDING rows (approval overwrites it) | `email_verified_at` estimated for ACTIVE rows, flagged (ADR 0021) |
| D-18 | 08 §5 DQ-11 origin limited to portal builds `e183526`→`1c84556` | The backend always accepted a missing DDT (J/api/req/CreateOrderReq.java:33-34 `@Size(min = 1)` only; column nullable) | DQ-11 detection stays `IS NULL`, any period |
| D-19 | 12a §3.3: controller parameter constraint → 400 `ErrorRepr` with Italian text | `WebController` is not `@Validated` (J/web/WebController.java:24-27): Spring MVC method validation raises `HandlerMethodValidationException` with the framework's English reason; the status is still 400 | Nothing to port; the new error model is explicit (ADR 0002) |
| D-20 | 12a J: `POST /mono/utils/order/deleteBeforeDate` deletes delivered orders confirmed before a date | `@PastOrPresent` on a DTO parameter (J/business/service/AdminToolsService.java:63) → `UnexpectedTypeException` → 500 on every call: unreachable | Not ported anyway (ADR 0028) |
| D-21 | 03 banner: phone exactly 10 digits until 2025-01-07 | Not in 12a §1.4 B2 nor §2.16; at HEAD the loose pattern applies (J/business/model/constant/SoggettoConst.java:22) | Unverified; the importer accepts any phone (DQ-06) |
| D-22 | 03 §3: the deployed portal `HOST` differs from the repository "(inferred)" | A page on `https://ordini.…` cannot `fetch` an `http://` host (mixed content): the live build necessarily uses an `https` host (ls-order@dev:js/constant/rest-constant.js:2) | Certainty; which host remains OP-U6/Q-47 |
| D-23 | 03 §6.3, 09 CUS-04: totals "round₂" | `Number.prototype.toFixed(2)` on binary doubles, not decimal HALF_UP (ls-order@dev:js/function/data-handler.js:92, 107): 3 × (10×10×35 cm) = 0.0105 → 0.01; 1.005 → 1.00 | CUS-04 uses decimal HALF_UP per line; half-cent edge cases may differ from the legacy display (ADR 0006) |
| D-24 | 03 OP-S5: only `label` is injected unescaped | The enumeration `id` is also interpolated unescaped into an unquoted attribute (ls-order@dev:js/components/types/dropdown-component.js:38, 60) | Escape ids and labels (ADR 0018) |
| D-25 | 03 §1: `ls-order@main` = `origin/main` @ `e9d2072` | The clone has no `main` ref; `e9d2072` is the second commit of `dev` | Cosmetic; cite `e9d2072` |
| D-26 | 04 §1 note, 12c §4.2: `ls-cms@dev` approval fails with 500 | `importoAssicurato` is `Double @Positive` (J/api/req/crud/PatchSpedizioneReq.java:24-25): "180,50" fails JSON binding (400); only an all-digit string reaches `requireNonNull(importo)` (SpedizioneService.java:153) → 500. Either way the repository CMS cannot approve | Q-47 stands; ADM-04 follows the backend contract |
| D-27 | 04 §1.5: no refresh endpoint exists | True of the CMS; the backend exposes `POST /mono/auth/refresh-token` (J/configuration/constant/PathConstants.java:17; J/api/controller/AuthController.java:26) | In the API-03 retired list (ADR 0011) |
| D-28 | 04 §4.9: what the backend sends for the report is unknown | `ReportReq` also takes `sendToCustomer` and `onlyDelivered` (J/api/req/ReportReq.java:30-31); `ls-cms@dev` sends neither (ls-cms@dev:js/rest/rest-caller.js:110-114), so its report fails with an NPE (12c §3.6) | Q-47; ADM-09 follows 12c §3.7 |
| D-29 | 04 §4.6: tracking sent as the string `"2"` | Accepted only through Jackson coercion into `TraceReq.tracking` (`@NotNull Integer`, J/api/req/TraceReq.java:22-23) | New DTOs are typed (ADR 0017) |
| D-30 | 05 §5 LPS-S1 locate command uses `origin/v1` | No such ref in the checkouts; the chapter 11 Q-23 form (`git -C legacy/ls-home-v1 grep -c 'maps/api/js?key=' HEAD`) works: 4 files, 1 occurrence each, same at the template import | Use the Q-23 form; never reuse the key |
| D-31 | 05 §1.1, §3.1, §3.4, LPS-10 cite `ls-home@dev` (`203c11a`), `8d6fa0a`, `e5b9298` | None is fetchable from the local clones | Taken on trust; no M1 decision depends on them (the v1 box is not ported) |
| D-32 | 05 §2.1 `/fonts/…` row | The URLs are `/fonts/fontawesome-webfont.{eot,woff2,woff,ttf,svg}?v=4.7.0` (ls-home@v1:css/font-awesome.min.css:4); `.eot`/`.svg` are not in the repository; `/css`, `/js`, `/fonts` directories are used by the new site | The 404 rule matches v1 file names and ignores the query string (ADR 0013) |
| D-33 | 05 §4.3: the about photo is the template's image | The template shipped a 92,656-byte `about-img.jpg`, replaced in v1 by a 736,543-byte forklift photo (`git diff --stat 0732746 8201716`) | Owner question under Q-31 before reuse |
| D-34 | 05 §2.2: `#tracking` is a v1-only relic | The production delivery e-mail links `https://www.logisticasanchez.com/#tracking` (ls@migration:src/main/resources/templates/tracking-state.html:48) | M1-critical: `www` → apex keeps the fragment; the home maps `#tracking` → `/tracking/` client-side (PUB-04, ADR 0013) |
| D-35 | 06 §1 JS weight 47,112 B; §5.7/§9 line numbers | At `9b78785`: 47,682 B (tracking.js grew in `ff58d4f`); `!res.ok` is `tracking.js:181`, `renderAsk` 140-168, `run` 205-243, `NON_GESTITO` guard 224-228; README sections moved | Cite `9b78785` with these lines |
| D-36 | 06 §9 quirk 1: `Non gestito` draws an empty progress bar | At `9b78785` a backend `Non gestito` never reaches `renderShipment` (ls-home@9b78785:public/js/tracking.js:226-227); only demo events could | TRK-01 asserts the neutral message |
| D-37 | 06 §3.1/§3.2: `/grazie/` and 404 have no Open Graph tags | Both carry `og:site_name` and `og:locale` from `partials/head.html:13-14` | PUB-01 parity expects the two partial tags on every page |
| D-38 | 06 §5.6 step 2 and the `telefono` rule | The 20,000-character limit re-checks only url-encoded bodies (ls-home@9b78785:netlify/functions/form.mjs:250-258); `telefono` is cut to 30 then matched with `{6,20}`, so 21–30 characters are rejected, not truncated (:33, :56) | Recorded for `/api/form` (ADR 0014) |
| D-39 | 06 §6.8 omits Netlify runtime behaviour | Compression, `ETag`/304, HTTP/2 and `/404.html` served as a real file are platform features, not in the repository | ADR 0012: compression and ETag on; `/404.html` answers status 404 at its own URL (planner default, §8 item PD-03) |
| D-40 | 06 §4.10/§8.2 active menu: `data-nav="home"` in the header | Only in the drawer (partials/header.html:69); the header buttons carry `data-nav="tracking"`/`"preventivo"` (:53-54) and get `aria-current="page"`; the drawer auto-open depends on it | The helper applies the rule to every `data-nav` of the whole header partial |
| D-41 | 12d §5 and Q-51: `ls-web@abe31e4` is a generic Astro-site prompt | This repository is `ls-web` re-initialised at `8be833c` with the brief: it **is** the monolith's repository | Q-51's substance stands (the monolith serves the public pages); 12d §5 is history |
| D-42 | 12d §1.7: possible Europe/Rome vs UTC break of stored timestamps in October 2025 | Hibernate binds the `Instant` columns as UTC wall-clock whatever the JVM zone (12b §1.1, §5) | Expect UTC; verify on one order the owner can date, from each era (ADR 0021) |
| D-43 | 12d §1.3 quotes the Dockerfile and Compose JVM flags side by side | `docker-compose.yml:58-65` `JAVA_OPTS` replaces `Dockerfile:55-63` entirely (no container-aware flags in production); `./backup/mysql` and `./init-scripts` mounts are unused | Legacy sizing note only; nothing to port |
| D-44 | 12d §4.1 branch map; §1.13 merge-conflict claim | The local clone fetches only `migration`: not re-verifiable | Verification gap, recorded; no decision depends on it |
| D-45 | 12d §6.2 "SES stays in eu-north-1" | Rests on `J/configuration/AwsMailConfig.java:25` alone; identity and key are server facts (Q-45) | Marked inferred; owner confirms the identity before Gate 1 |
| D-46 | 10 §1 and 12d §2: Spring Boot 3.2 OSS support ended November 2024 | 2024-12-31 (commercial 2026-02-28) as of training knowledge | Conclusion unchanged (ADR 0001) |
| D-47 | 10 §1: "Builds and starts locally: Plausible"; "Framework and runtime supported: No" | Builds: VERIFIED (38 s, JDK 21, `--release 17`); starts: NOT CHECKED; the runtime (Java 17) is supported, the framework is not | ADR 0001 records the split |
| D-48 | 08 §4.2 step 1 `mysqldump` flags | `scripts/backup.sh:42-50` also passes `--add-drop-database`: a restore drops and recreates `lsdb` | Restore only in an isolated container, never on a shared server (ADR 0021, runbook) |
| D-49 | 12c §5.4: 255-character limit on e-mail content | Bypassed by self-invocation (J/business/service/AwsEmailService.java:129, 134): a bug, not a rule | The outbox carries no such limit (ADR 0015) |
| D-50 | 02 §5.2: `https://ordini.logisticasanchez.com` must be allowed by CORS | Not in any committed `ALLOWED_ORIGINS` list (12a §2.10) | Live list unknown (P-03); no impact: the portal is retired |
| D-51 | 12a §1.2 "seen in logs", §2.13 counts, §2.14 secrets, SEC-06/SEC-07 | NOT CHECKED here (forbidden paths; values redacted) | Kept as 12a's own observations, not downgraded, not re-verified |
| D-52 | 12a §1.3, 12d: `MethodLoggerAspect`, `JwtAuthFilter` | The aspect's pointcut never matches (classes live under `.mono.`); the filter is also registered as a plain servlet filter; audit columns hold only `ROLE_ADMIN`/`CUSTOMER` (J/persistence/audit/*AuditListener.java:18, 24) | No staff attribution can be migrated (ADR 0021); body logging remains the only sensitive channel (ADR 0019) |
| D-53 | 12b, 12c on e-mail failures | `register`/`confirm`/`decline` send inside `@Transactional` methods: an SES failure rolls the row back; only `CmsController.trace` commits before sending (J/api/controller/CmsController.java:113-121); with `enableAutomaticEmails=false` sends are skipped silently | Outbox in the same transaction, failures never roll back the action (ADR 0015); DQ-25 may hide "never e-mailed" NEW rows |
| D-54 | `scripts/backup.sh:155` shows `<value redacted>` in the checkout | Local redaction of an environment-variable reference (`git status`: `M scripts/backup.sh`), not a secret | Not a finding |
| D-55 | 12a: `POST /mono/api/order/state {id}` | Returns an order's state by numeric id without a token (OrderController.java:41-47): an id oracle | In the 410 list (ADR 0011) |

### 7.2 Chapter versus chapter (chapter 09 and 11 decide what the new app does)

| # | Disagreement | Resolution |
|---|---|---|
| D-56 | 01:116-117 principle 4 "docs are English" vs NFR-07, `PROMPT.md` rule 3, `CLAUDE.md`: runbooks, gate summaries and the UAT checklist are Italian | PROMPT and 09 win; principle 4 = code and design documents |
| D-57 | 01:60-61 "NEW, PENDING or REGISTERED" vs 12a §1.4 B1, `ConfirmationState.java:15-18`: `DECLINED` also exists for rows declined before 2024-07-22 | Importer maps DECLINED rows (Q-09); CUS-03 shows the state |
| D-58 | 01:112-113 "reused as they are" vs PUB-01 allow-list of differences | 09 is the precise rule (ADR 0012) |
| D-59 | 01:74 "ten automatic e-mails" vs 12 §6 "L-E1…L-E11"; 12 §1 vs §6 | L-E11 is the free-text admin tool, not automatic: ten e-mails to reuse, L-E11 not ported |
| D-60 | 01:72-74 omits `/mono/sendEmail` and `/mono/utils/**` | Retired by API-03 |
| D-61 | 06 §7.3 (06:1227): `/preventivo/` aside and `/contatti/` phone card show hours without Saturday vs 01:25 and `config.js` (Sat 8–12) | Owner data (01) wins through the PUB-02 configuration; an allow-listed difference and a Q-31 item |
| D-62 | 12a §4.4 point 5 and 12b §3.7: chapter 02's target for FX-03 is 404 | Stale: 02 §10.2 rule 3, FX-03/FX-06, 09 API-01, Q-03/Q-38 say `200 {"status":"Non gestito"}`; ADR 0010 states 200 explicitly |
| D-63 | 02 §9.3 / CU-19 "pallet-type enumeration (unknown)" vs 12a §1.4 H3, 12b §2.3: `GET /mono/api/enumeration/tipoPallet` and `/statoConferma` exist, no known caller, not in 02 §10.1 | Not in 09 API-02 → answer 410 (planner default, §8 item PD-05); reversible if the owner's log export (probe P-06) shows callers |
| D-64 | 02 body text under EP-03, EP-06/07, EP-08, EP-15, EP-17 keeps pre-source wording | The "From the source" banners win |
| D-65 | 02 §10.2 rule 1 / FX-04 accept padded codes vs 12b §3.7: leading spaces never match in the legacy | Deliberate leniency, recorded in ADR 0010 |
| D-66 | 02 §7.1 roles ADMIN/CUSTOMER vs 09 ADM-01 ADMIN/OPERATOR | Intended new model; staff accounts not migrated (ADR 0003) |
| D-67 | 03 §10 OP-08 "optional as labelled" and 08 §2.3 `customer.phone` optional vs 09 CUS-02 and Q-36 required (`J/api/req/crud/CreateSoggettoReq.java:41-44`; 12a cites the path without `crud/`) | 09 wins: phone required; column nullable for legacy rows only; integrator aligns 03/08 wording after Gate 0 |
| D-68 | 03 §5.6 "an approved customer may have an unconfirmed e-mail" vs 12b §3.1 C7 | 12 wins (Q-28); DQ-24 cannot occur |
| D-69 | 03 §5.2 "NEW = e-mail unknown" vs 12a B1: NEW also = unverified or declined since 2024-07-22 | CUS-01/CUS-02 treat an unverified e-mail as known (sign-in link verifies it) |
| D-70 | 03 §6.2 "0 passes", 08 §2.3 `CHECK (≥ 0)` vs 12a C1 `@Positive` | Lenient CHECK for imported rows only (D-12) |
| D-71 | 03 OP-09 "CAP regex accepts ZIP+4" vs 12a B2 `cap` 1–9 characters | A ZIP+4 got 400 server-side; DQ-03 only from 5–9-character typos |
| D-72 | README reading map assigns no module to 03 §1/§3 (hosts, `HOST`, CORS, retired paths) and OP-17…OP-24 | §4: tracking + compatibility and `platform` routing; OP-17…24 → shared wizard components in Phase 1 |
| D-73 | 04 §9.1 rule 1 and CMS-20 (approval without verification) vs 12a B5, 12b §3.1 | 12 wins; ADM-02 and Q-28 follow it |
| D-74 | 04 §4.6 / §9.1 rule 6 "any typed code, any time" vs 12a D2 (404 unknown, 500 not REGISTERED) | Tracking on approved orders only; ADM-06 "any order" = any sequence of states (ADR 0008) |
| D-75 | 04 §4.8 three detail keys, type `"0"/"1"` vs 12a F1 four keys, `PALLET`/`EPAL`, one entry per unit | 12 wins; ADM-08 regroups units into lines |
| D-76 | 04 §3.4 "Cliente mittente" source unknown vs 12a C1: `email` = `destinatario.email` = the customer's own address (OP-14) | Lists show the customer through the FK; importer drops the duplicate (DQ-23) |
| D-77 | 04 CMS-26 "list grows without limit" vs 12a C2: lists return only the last 6 months | ADM-05 shows everything, paginated (ADR 0017) |
| D-78 | 04 §4.7 "Nessuna nota presente" written by the CMS vs 12a E1: the server answers the literal too | Both layers substitute it; the importer maps the literal to empty (CMS-11) |
| D-79 | 04 §4.4 free-text amount vs 12a C6 number > 0 | ADM-04: decimal > 0, Italian comma accepted at input (ADR 0007) |
| D-80 | 04 §9.1 rule 9 "from 2024-01-01" | Only the date picker's `min`; not ported |
| D-81 | 08 §2.5 "the app proposes the next code when imported codes follow a sequence" vs 11 Q-06 and 12b §3.3 | Propose nothing (Q-06); integrator aligns 08 §2.5 |
| D-82 | 08 §3.1 lists unverified customers under "In Approvazione" with "Email confermata: NO" vs 12b §3.1 (EP-04 lists PENDING only; the column is always "SI") | Decision, not parity: planner default = parity (PENDING_APPROVAL only), §8 item PD-06 |
| D-83 | 08 §2.4 "setting the current state again creates no event" vs 12b §3.4/O4 (legacy re-stamps and re-sends L-E8 on every CONSEGNATO call) | Deliberate fix: no event and no EML-09 for the same state (ADR 0008; §8 item PD-07) |
| D-84 | 08 §3.6 one `LEGACY_IMPORT` event per order vs 12b §5.5 (PRESA at `confirmation_ts` plus the current state) | 12b wins; 08 §3.3 example superseded by 12b §5.7 |
| D-85 | 08 §4.5 blocking set (DQ-01/08/10/12) and importer keys vs 12b §5.6 (DQ-01, DQ-29, unknown labels, violated assertions; ids always present) | 12b wins; decisions-file keys trimmed, a key added for DQ-29 codes |
| D-86 | 08 §4.7 step 2 maintenance text embeds the office e-mail and WhatsApp number vs PUB-02/Q-43 | Read from configuration (ADR 0027) |
| D-87 | 08 §1.2 "NEW = no record", §1.3 "`importoAssicurato` free text", "orders approved before `e5a72fc` may lack an amount" | Corrected by the chapter's banner; the last becomes DQ-31 |
| D-88 | 08 §2.3 has no home for a declined customer's stored-apart re-registration data; `email_outbox`, `report_run`, `feature_flag`, `import_run`, `import_issue` have no columns; 12b §5.4 adds `customer.legacy_payload` | Phase 1 defines them (§4.4) |
| D-89 | 08 §3.7 (legacy note → internal note only) vs 12b §5.5 (also `customer_notes` for PENDING orders with `update_user = 'CUSTOMER'`) | ADR 0009; planner default = 12b §5.5 (§8 item PD-08) |
| D-90 | 07 §1.1, 12c §4.2, 09 ADM-04 require > 0 vs 08 §2.3 `amount ≥ 0` | Column CHECK for legacy rows; form rule > 0 |
| D-91 | VAT asked twice (Q-05 and Q-14) | Answered once under Q-05; Q-14 references it |
| D-92 | 07 MOSCA "PREAVVISO TELEFONICO" vs enumeration "Prenotazione telefonica" | M2 import rule-confirmation step asks (ADR 0026) |
| D-93 | 07 §3.1 class image vs `J/business/model/enumeration/PalletEnum.java:15-21` | Chapter 07 wins (PR-02); the owner confirms at Gate 2 |
| D-94 | 07 cites `ls-home@fe4b056`; 09/PROMPT cite `9b78785` | Lines verified at `9b78785` |
| D-95 | 09 ADM-09 "legacy columns" vs the integer m³ (D-13) and "Dt/Cons." (D-08); 12c §3.2 UTC day boundaries vs 04 CMS-22 Europe/Rome; 09 omits the PDF's fixed partner header (12c §3.4 block 1) | ADR 0016: period on the order's creation time as today (`createTms`) and as 09 ADM-09 ("approved orders created in a date range"; §8 item PD-11), Europe/Rome boundaries, `from ≤ to` (422), volume with two decimals recomputed from lines, partner header dropped (confidential), owner reviews a sample at Gate 1 |
| D-96 | 12c §1.5 heading "EML-01…08" and §7 "add EML-09" | Stale: 09 already has EML-09 in M1 |
| D-97 | 09 EML-06 "once at verification" vs legacy L-E2 on every click (`J/business/service/CustomerService.java:38-41`) | Deliberate fix (ADR 0004, 0015) |
| D-98 | 09 PUB-04 "every redirect of `public/_redirects`" vs line 16 (`/mono/*` → `app.` proxy, status 200) | A proxy, not a redirect: excluded from the PUB-04 test; `/mono/*` served by the app (ADR 0011) |
| D-99 | 09 TRK-01 "E2E for each of the six states" vs 06 §5.7 (`ff58d4f`): state 0 renders the neutral message | Five state renderings plus the neutral one |
| D-100 | 10 §2 URL map `/area-clienti/accesso` without trailing slash vs 06 §6.8 slash rule | `accesso` is a route, not a directory: the exception is named in ADR 0004, which owns the route in Phase 1 (planner default, §8 item PD-14) |
| D-101 | Caching: 10 §2 (ETag/short caching for public HTML only) vs 06 §8.5 (`no-cache` + ETag for CSS/JS/HTML) vs 09 PUB-05 ("same caching rules", which cover only fonts and images) | ADR 0012: 06 §8.5 (planner default, §8 item PD-02) |
| D-102 | 09 PUB-01 allow-list omits the speculation-rules exclusion (06 §8.5, edits `partials/head.html:17` on every page) and `/index.html` → `/` is missing from 06 §6.3 | Both added to the allow-list/redirect ADRs (0012, 0013) |
| D-103 | 06 quirk 5 (decimal `numero` accepted) vs 08 `lead.quantity numeric(5,2)` vs 09 PUB-06 (fixes only over-long values and CAPs) | Parity kept: decimal comma accepted, stored `numeric(5,2)` (planner default, §8 item PD-04) |
| D-104 | 10 §3 cites Q-18 for backup copies 7/4/12 which Q-18's default does not state | Added to the Q-18 default in §8 (backup retention) |
| D-105 | 10 §7 step 2 freeze list vs 12d §6.4 step 2 (omits `/mono/cms/order/update` and `GET /mono/email-confirm`) | MIG-03 uses the superset of 10 §7, 08 §4.7 and 12b §5.6 (ADR 0023) |
| D-106 | 12d §6.3 Path A import exclusions only partly in 10 §1 | Moot under Path B; recorded in ADR 0001 |
| D-107 | 10 §1 "every endpoint except EP-13/19/20 answers 410" vs 02 §10.3 (logo served as a file) | API-03 wording wins |
| D-108 | Q-21 "four handlers" vs 12d F-01 "both handlers" | Chapter 11 is complete (D-01) |
| D-109 | Q-35 (integers ≥ 1) vs 03 OP-12 (decimals for weight); legacy stores doubles | New input integers; the importer accepts decimals (DQ-16) |
| D-110 | Q-10 and 08 §2.3 (`vat_number`, `tax_code` optional) vs 09 CUS-02 field list (no VAT/tax code) | Show the two optional fields (chapter 11 default; §8 Q-10) |
| D-111 | Q-03 "trimmed and case-insensitive" vs TRK-02, 02 §10.2 rule 1, 08 §2.5 "spaces removed"; legacy does not trim (12b §3.7), so DQ-09 codes with leading whitespace | One normalisation method (trim, inner spaces removed, case-insensitive); legacy codes trimmed at import with the raw value kept (ADR 0010, 0021) |
| D-112 | Q-05 "VAT excluded" vs 10 §2 "store a flag, do not guess" | The flag's default comes from a setting (`Q-05` seam, §8) |
| D-113 | 12a §4.4 point 9 (Q-28 default "No") and 12c §7 (Q-28 default wrong) | Stale; chapter 11 Q-28 reads "Yes, as today" |
| D-114 | `importo` requirement date: 12a §0.6/C6/§2.16 say 2024-07-18 (`d7b2c56`), 11 Q-05, 12b §1 and 12c §4 say 2024-07-16 (`d7732eb`); the 12a reader found the column in `d7732eb` and `requireNonNull` in `d7b2c56`, the e-mails reader's `git log -S` on the exact string found only `d7732eb` | The rule is date-independent (`COALESCE`); the "unexpected null" flag uses 2024-07-18 as the conservative boundary (§8 item PD-15); verified on the dump (ADR 0021) |
| D-115 | 12c §1.4 "the apex has no `/mono` route" (at `fe4b056`) vs Q-50 / 02 §10.3 (`9b78785` proxies `/mono/*`) | Both true at their commits; the app answers `/mono/*` on both hosts |
| D-116 | 12c §0 finding 5 (office-only reports work) vs §3.6 (`ls-cms@dev` fails with an NPE) | Either a newer CMS runs or the report is broken today (Q-47) |
| D-117 | 12 vs 08 §2.3: item 0 "none" mapped to `accessory_service_id = null` with an `is_none` row vs ADM-10/API-02 keeping item 0 in the answer | Compatible: the API-02 contract test asserts id 0 is returned |
| D-118 | 12d §6.1–6.5 quote pre-correction line numbers of 01, 08, 10; 12d §6.7 files Q-46 "now" while chapter 11 puts it at Gate 1 | History; the current chapters carry the corrections; Q-46 requested now as information |
| D-119 | PROMPT.md cites `OrderController.java:33-38` (method spans 33-39); "tracking 1 = PRESA" (the backend forces PRESA whatever is sent) | Trivial; consistent |
| D-120 | Chapter 11 internal: Gate 2 table lacks "Why it matters"; Q-12 has no Ref; Q-07 "ten" vs 12c §1.5 title | Cosmetic |
| D-121 | 05 §3.2 and LPS-U3/LPS-U9 still call the `status` format unknown | Answered by 12a §1.4 D1 (label); residual: production collation (P-02) and the live CORS list (P-03) |
| D-122 | 05 §2.1 `/css/…`, `/js/…`, `/fonts/…` answer 404 vs 06 §8.1 / 10 §2 (the new site serves those directories) | v1 file names only (D-32) |
| D-123 | 05 §2.5 two S3 buckets vs 12a §2.10 / 12d (three, incl. `sanchez-logistica-cms`); 05 §2 omits `www` → apex and `api.logisticasanchez.com` | LPS-S4 covers all three buckets; `api.` added to the dangling-DNS check of MIG-03 |
| D-124 | 06 §6.1 README "backend accepts only the apex" vs 12a §2.10 committed list (apex, `www`, `app.`, credentials true) | 02 §10.2 rule 5 decides: apex only, no credentials (Q-39) |
| D-125 | 02 §10.4 / 12a: the legacy answers 500 to an unknown extra JSON field on builds ≥ 2024-07-27 (custom `ObjectMapper`) | API-01 ignores unknown fields (lenient; planner default, §8 item PD-09) |
| D-126 | 05 §3.6 cites `tracking_section.txt:1-38` (37 lines); 05 LPS-05 alt text on three tiles; 04 §7.1 line 101-107 | Cosmetic |

## 8. Defaults applied

Every Gate 0 question of chapter 11, the default applied (chapter 11's recommended default,
never a new business rule) and the seam where it lives: a typed setting under `ls.*`
(bound by `@ConfigurationProperties` in `platform`) or one method carrying a `// Q-xx`
comment. The owner changes a default by answering with the ID; the integrator records
the answer in chapter 11 (the Q row, or the PD row of the planner-defaults table that the
close of Phase 0 adds under its Gate 0 section, §Status step 2), updates the seam, the tests
and the ADR in one commit.

### 8.1 Gate 0 questions (chapter 11)

| Q | Default applied | Seam (setting or method) | Owner module |
|---|---|---|---|
| Q-01 | Path B: new monolith on Java 21 + Spring Boot 4.1.1, `legacy/ls` as the reference | ADR 0001 (no runtime seam) | integrator |
| Q-02 | Schema dump optional: importer built against the 12b §1.7 DDL; drift queries of 12b §1.8 run in the importer pre-flight; API export only as an emergency fallback | `ls.migration.source-url` (read-only JDBC to the restored copy); `LegacySchemaAssertions` (`// Q-02`); `tools/migration/drift-check.sql` | migration |
| Q-03 | Legacy answers kept (label; `200 Non gestito`; 400 on empty); matching trimmed, inner spaces removed, case-insensitive | `TrackingCodeNormaliser.normalise()` (`// Q-03`); `ls.tracking.match.case-insensitive=true` | tracking |
| Q-04 | ASSEGNATO shown as «Affidata al mezzo per la consegna»; no exception state in M1 | message key `tracking.state.ASSEGNATO.title` (`// Q-04`); `tracking_state` seed unchanged | tracking |
| Q-05 | Amount = price charged to the customer, VAT excluded, `numeric(10,2)`, > 0; `shipment_order.amount_vat_included` stored, null on legacy rows | `ls.orders.amount.vat-included-default=false` (`// Q-05`); `AmountPolicy` (parser accepting `180,50` and `180.50`, > 0) | orders |
| Q-06 | Staff type the definitive code; uniqueness trimmed and case-insensitive; raw value kept; nothing proposed | `ShipmentCodePolicy` (`// Q-06`); `ls.orders.code.propose=false`; unique index on the normalised code | orders |
| Q-07 | Legacy e-mail content reused in the new style without the 12c §1.6 defects; EML-02 and EML-04 added; owner reviews copy at Gate 1 | `ls.notifications.customer-confirmations.enabled=true` (EML-02/04; `// Q-07`); templates under `templates/notifications/` | notifications |
| Q-09 | Explicit `DECLINED` kept with the record; re-registration allowed (`DECLINED → PENDING_VERIFICATION`); new data stored apart until the link is used | `CustomerStateMachine` (`// Q-09`); table `customer_pending_registration` (Phase 1) | customers |
| Q-10 | Companies and private persons may register; VAT number and tax code optional fields on the form | `ls.customers.registration.allow-private-persons=true`; `vat-number-required=false`, `tax-code-required=false` (`// Q-10`) | customers |
| Q-11 | One login per customer (the customer's e-mail) in M1; `customer_user.role = OWNER`; model ready for more | `ls.customers.max-users-per-customer=1` (`// Q-11`) | auth |
| Q-13 | Pick-up address = the customer's registered address, snapshotted on the order | `OrderSenderSnapshot.fromCustomer()` (`// Q-13`); no pick-up field in the wizard | orders |
| Q-15 | One WhatsApp/phone number (the official one) from configuration; the second number of the old site dropped | `ls.business.phone`, `ls.business.whatsapp` (`// Q-15`), one value each | platform |
| Q-16 | New app on a new VPS at the same provider; the owner changes the A records at the current DNS provider | `deploy/` targets one new VPS; `docs/runbooks/cut-over.md` DNS table (`// Q-16`) | integrator |
| Q-17 | Roles `ADMIN` and `OPERATOR`; staff accounts recreated (not migrated); 2FA in M2 | `StaffRole` enum (`// Q-17`); feature flag `security.adminTotp` (ENH-09, off: a `feature_flag` row, not an `ls.*` setting) | auth |
| Q-24 | Transport document code mandatory for new orders; «Numero Colli» stored as an optional count | `ls.orders.transport-document.required=true` (`// Q-24`); `shipment_order.package_count` nullable | orders |
| Q-25 | Seed exactly the six accessory services (ids 0–5, id 0 = none) and six tracking states | migration `V00x__seed_enumerations.sql`; seed test ADM-10 (`// Q-25`) | tracking |
| Q-26 | Separate internal notes (`order_internal_note`); the customer's note kept on the order | `OrderNotes` service (`// Q-26`); importer rule PD-08 below | orders |
| Q-27 | No probe is run by the agents; P-01…P-03 only after the owner's go-ahead; P-04…P-07 owner only; no credentials to agents | `tools/probes/` with a read-only allow-list; `ls.probes.enabled=false` (`// Q-27`) | migration |
| Q-28 | Only a customer with a verified e-mail can be approved | `CustomerApprovalPolicy.requiresVerifiedEmail=true` (`// Q-28`) | customers |
| Q-35 | Line values integers ≥ 1, no maximum; warning above 240 cm or 1,200 kg per unit; the importer accepts legacy decimals | `ls.orders.lines.integers-only=true`, `warn-height-cm=240`, `warn-weight-kg=1200`, `max-*` unset (`// Q-35`) | orders |
| Q-36 | Phone required at registration (as the backend requires); column nullable for legacy rows only | `ls.customers.registration.phone-required=true` (`// Q-36`) | customers |
| Q-37 | Customer-facing copy and every e-mail subject say «Logistica Sanchez» | message key `brand.name` (`// Q-37`) used by all templates | notifications |
| Q-38 | Temporary codes of pending orders answer `Non gestito`, like unknown codes | `TrackingLookup` (`// Q-38`); `ls.tracking.temp-codes-visible=false` | tracking |
| Q-39 | CORS on the compatibility endpoint: only `https://logisticasanchez.com`, `POST, OPTIONS`, `Content-Type`, no credentials | `ls.compat.cors.allowed-origins=https://logisticasanchez.com` (`// Q-39`) | tracking |
| Q-45 | Not needed for the build; before the cut-over the owner runs the read-only commands of 12d §1.15 and shares key names only | `docs/runbooks/cut-over.md` checklist (`// Q-45`) | integrator |
| Q-47 | Back office designed from chapter 04 screens and copy plus chapter 12 rules; for the cut-over `ordini.` and `dashboard.` are assumed to call only `https://app.logisticasanchez.com` | `deploy/caddy/Caddyfile` host rules; freeze snippet of ADR 0023 (`// Q-47`) | integrator / backoffice |
| Q-51 | Closed: the monolith serves the public pages | — | — |
| Q-52 | New VPS at the same provider (Contabo) with Docker Compose; PostgreSQL 16 | ADR 0001; `deploy/compose.yml` (`postgres:16`) | integrator |
| Q-54 (owner decision, 2026-10-17) | Proceed on the public repository; agents push; confidential files stay | — (risk R-01) | — |

Gate 1 and Gate 2 defaults also in force from Phase 1 (seams named so the owner can answer
early; owner module as §4.2):

| Q | Default applied | Seam (setting or method) | Owner module |
|---|---|---|---|
| Q-08 | PDF as today, only through the authenticated download; recipients = the office address plus an ADMIN-managed allow-list; the customer may receive it when chosen | `ls.reports.recipients.allow-list` (default the office address), `ls.reports.customer-can-receive=true` (`// Q-08`) | reports |
| Q-12 | A sent order is changed or cancelled only by asking the office, while it is pending | `ls.orders.customer-changes=OFFICE_ONLY` (`// Q-12`) | orders |
| Q-18 | Retention: leads and requests 24 months, shipments 10 years, audit log 24 months, outbox bodies and IP hashes 30 days (the `report_run` period is not part of Q-18's default: PD-16 below) **plus backup retention 7 daily / 4 weekly / 12 monthly** (from 10 §3, attached to Q-18 — D-104) | `ls.retention.leads-months=24`, `shipments-years=10`, `audit-months=24`, `outbox-bodies-days=30`, `ip-hashes-days=30` (`// Q-18`); `deploy/backup/.env.example` for the backup copies | platform (scheduler) and customers (anonymisation rules); integrator (backups) |
| Q-19, Q-40, Q-41 | Cut-over on a Saturday afternoon under one hour; rollback window until the end of the first working day; a customer e-mail after go-live with wording reviewed by the owner | `docs/runbooks/cut-over.md` (`// Q-19`, `// Q-40`, `// Q-41`) | migration (runbook with the integrator) |
| Q-20, Q-46 | Netlify site and AWS-era resources inventoried, then retired after the rollback window; S3 names kept as redirects | decommissioning checklist in `docs/runbooks/` (`// Q-20`, `// Q-46`) | integrator |
| Q-29 | Redirect table of the crawled legacy URLs kept as data, appended as the owner reports more | redirect data file read by the PUB-04 table (`// Q-29`) | site |
| Q-30 / Q-50 | Old `/mono/**` links answered on both hosts by the Italian page towards the customer area; nothing confirmed or served | `ls.compat.hosts=logisticasanchez.com,app.logisticasanchez.com` (`// Q-30`) | tracking |
| Q-31 | Chapter 11 default «Keep only what you confirm»: no claim is removed by the agents, so the unconfirmed claims stay on the pages until the owner answers at Gate 1 (the two pages without Saturday hours take the hours from configuration, D-61) | the PUB-01 allow-list entry «copy the owner changes under Q-31» (ADR 0012), empty until an answer; the claims are message keys under `site.claims.*` (`// Q-31`) | site |
| Q-42 | Decisions file outside git; the owner picks the record to keep per duplicate e-mail before the first rehearsal | `ls.migration.decisions-file` (`// Q-42`) | migration |
| Q-43 | Office mailbox as a setting; a business mailbox before go-live; the privacy page names the current one | `ls.business.office-email` (`// Q-43`) | platform |
| Q-14, Q-32, Q-33, Q-34, Q-44 | Nothing in M1; behind the M2 flags | `feature_flag` rows of ADR 0025 (`pricing.*`, `site.pickupCutoff`) | pricing (Q-14, Q-32, Q-33, Q-34), site (Q-44) |

### 8.2 Planner defaults without a chapter 11 question (confirm at Gate 0, each behind a seam)

Numbered `PD-01…PD-17` ("planner default"). The prefix `P-01…P-07` belongs to the production
probes of chapter 02 §11.2 (Q-27) and is never used for these, so an owner answer such as
«PD-03: ok» is unambiguous. They are owner questions that chapter 11 does not hold: at the
close of Phase 0 the integrator appends to chapter 11, under its Gate 0 section, the table
"Planner defaults (PD-01…PD-17)" with ID, question in one line, default applied, seam,
planned ADR and an empty Decision column (the format of the Q rows; §Status step 2). Owner
answers to PD items are recorded there and in the affected ADR in the same commit.

| # | Decision | Default | Seam | ADR |
|---|---|---|---|---|
| PD-01 | `tracking.js` on 429/5xx (TRK-04 vs 06 §5.7 quirk 2: every legacy client shows any non-2xx as "not found") | The small change: non-2xx other than 404 shows the "error" block («riprova tra poco»), listed in the PUB-01 allow-list | one line in `public/js/tracking.js`, allow-list entry | 0012 |
| PD-02 | Caching of HTML/CSS/JS (D-101) | `Cache-Control: no-cache` + `ETag` (304) for HTML, CSS and JS; `/fonts/*` immutable one year; `/img/*` and `/*.png` one week; compression on | `ls.site.cache.*` | 0012 |
| PD-03 | `/404.html` at its own URL (D-39) | Serves the 404 page with **status 404**; excluded from the PUB-01 "every URL answers 200" walk, content still compared | route table entry | 0012 |
| PD-04 | `/api/form` `numero` with a decimal comma (D-103) | Parity: accepted and stored as `numeric(5,2)`; only over-long values and malformed CAPs are rejected (PUB-06) | `ls.site.form.quantity-integer-only=false` | 0014 |
| PD-05 | `GET /mono/api/enumeration/tipoPallet` and `/statoConferma` (D-63) | 410 like the other retired paths (no known caller; not in 09 API-02); reversible if the owner's log export (probe P-06) shows callers | retired-path list | 0011 |
| PD-06 | Unverified (`PENDING_VERIFICATION`) customers in the back-office "In Approvazione" list (D-82) | Parity: the list shows `PENDING_APPROVAL` only ("Email confermata" always SI, as today); unverified customers are reachable through the ADM-03 search | `ls.backoffice.show-unverified-customers=false` | 0017 |
| PD-07 | Setting the same tracking state again (D-83) | No event, no EML-09; EML-09 once per transition to `CONSEGNATO` | `TrackingTransitions` (`// PD-07`) | 0008 |
| PD-08 | Import of the legacy shared note (D-89) | `customer_notes` when the order is PENDING and `update_user = 'CUSTOMER'`; otherwise `order_internal_note` with DQ-18; raw value in `legacy_payload` | importer `NoteRule` | 0009, 0021 |
| PD-09 | Unknown extra JSON field on API-01 (D-125) | Ignored (lenient); the legacy answered 500 | `ls.compat.strict-json=false` | 0010 |
| PD-10 | Staff lock-out threshold (10 §2 names none) | Lock-out after 10 failed logins for 15 minutes. The session and token lifetimes are chapter 10 §2's own figures, not planner choices: customers 60 min idle / 12 h absolute, staff 30 min / 10 h, sign-in tokens 15 min, registration links 24 h | `ls.security.staff.lockout.*`; the lifetimes live in `ls.security.sessions.*` and `ls.security.tokens.*` (seams of 10 §2) | 0003, 0004 |
| PD-11 | Report period and columns (D-08, D-95) | Rows = approved orders whose **creation time** falls in the range, as today (`createTms`, 12c §3.2) and as 09 ADM-09 ("approved orders created in a date range") — not the approval date; day boundaries in Europe/Rome (today UTC), `from ≤ to` (422), "Dt/Cons." = `CONSEGNATO` event time else "-", volumes with two decimals from lines, partner header dropped, "-" for a missing amount; the report is regenerated on demand for the authenticated download (no stored file, no S3) | `ls.reports.*` | 0016 |
| PD-12 | Post-approval corrections (legacy `/mono/cms/order/update`, used by an unknown client) | Not in M1 unless the owner asks (§10); if asked: ADMIN-only, audited, same validation as approval | — | 0028 |
| PD-13 | Proxy | Caddy 2 with automatic HTTPS (10 §3 default) instead of nginx + certbot | `deploy/caddy/` | 0022 |
| PD-14 | `/area-clienti/accesso` without trailing slash (D-100) | Kept as the 10 §2 URL map writes it (a route, not a directory); the public-site redirects stay in ADR 0013 | route table (`auth`, Phase 1) | 0004 |
| PD-15 | DQ-13 null-`importo` boundary (D-114) | 2024-07-18 for the "unexpected null" flag; the rule itself is date-independent | importer `AmountRule` | 0021 |
| PD-16 | Retention of `report_run` rows (Q-18 names none; the 30-day S3 lifecycle of 10 §2 is moot without S3, ADR 0001) | 24 months, like the audit log; the authenticated download stops working when the row is deleted | `ls.retention.report-runs-months=24` | 0016, 0024 |
| PD-17 | Generated values that differ from the static files of ls-home@9b78785 and are not in the 09 PUB-01 allow-list (chapter 06 proposes them: footer year rendered server-side with `data-year` kept, 06 §8 `partials/footer.html` row; sitemap `lastmod` from a per-page content date instead of git; `security.txt` `Expires` renewed) | Footer year at request time (`[data-year]` kept, so `main.js` still runs); sitemap `lastmod` = the content date of each page; `security.txt` `Expires` rolling, less than one year ahead; the three listed in the PUB-01 allow-list | `ls.site.generated.*` (year, sitemap dates, security.txt expiry) | 0012 |

## 9. Risks

Likelihood/impact: L/M/H. Owner of the mitigation in brackets.

| # | Risk | L | I | Mitigation |
|---|---|---|---|---|
| R-01 | **The repository is public** (confirmed 2026-10-17) and holds a customer's price list, the partner cost model and the description of the live vulnerabilities; the owner decided to proceed (Q-54). Anyone can read them; GHCR private images and some CI protections differ for public repositories. **Exposure already happened:** two monthly-discount percentages of chapter 07 reached two reader notes pushed on `claude/great-einstein-4mfvfz` (commits `8e1cacf`, `5d09b99`, contained in `7054fe0`); the corrected files no longer hold them, the branch history does | H | H | `phase-0` built from `main` without that ancestry (§Status step 3), nothing more pushed to the session branch, which the owner deletes on GitHub once `phase-0` exists (orphaned commits may stay reachable by SHA until GitHub support purges them); the confidential-figure CI guard of §5 Phase 1 item 1; Gate 0 summary repeats how to make it private (or move to a private repository, or purge `07-pricing.md` and `data/` from the history); no price, cost, secret or personal datum ever enters code, fixtures, logs or chat; secret scanning in CI from Phase 1; the Phase 5 pricing work waits for a private repository or a decision to keep the lists out of the repository [owner, integrator] |
| R-02 | Schema drift of the live `lsdb` (`ddl-auto: update`, October 2025 time-zone change, unknown external `application-prod.yml`): the importer built against 12b §1.7 may break on the real dump | M | H | Drift queries of 12b §1.8 in the importer pre-flight; ask for the schema-only dump with row counts (Q-02) early; rehearse on the anonymised copy before Gate 1; MIG-02 on real data is ready-for-owner at most [migration, owner] |
| R-03 | The production CMS build is unknown (Q-47): office habits, corrections after approval, report flags and `importo` population are unobserved; approvals or reports may already fail today | M | M | Back office designed from 04 + 12; ask at Gate 0 what the live CMS can do; one UAT week with the office at Gate 1; importer lists REGISTERED rows with null amount or TEMP code (DQ-13, DQ-32) [backoffice, migration, owner] |
| R-04 | Legacy stack end-of-life and live exposure (F-01, F-08, F-09, Q-21, Q-48, Q-49): an incident, an expired certificate or data loss before the cut-over changes the timeline and the GDPR position | M | H | Urgent owner actions in the Gate 0 message with `COME-USARLO.md`; the build never touches production; the freeze list and runbooks assume the legacy still runs [owner] |
| R-05 | No Docker daemon locally: Testcontainers, Compose and the MySQL path of the importer cannot run here | H | M | `TEST_DATABASE_URL` + local PostgreSQL, in-process fakes, H2 MySQL-mode fixtures for the importer; CI runs the Docker-based layers; no layer dropped (ADR 0001) [integrator] |
| R-06 | GitHub Actions minutes or runner limits (public repository: standard minutes are free, but Lighthouse + Playwright + Testcontainers jobs are long) | L | M | Caching, matrix kept small, E2E sharded only if needed; stop and tell the owner if minutes run out, never enable paid minutes [integrator] |
| R-07 | Data quality of the legacy data (DQ-01 duplicate e-mails blocking, DQ-07/15 undetectable service/type ambiguities, DQ-19 HTML payloads, DQ-25 NEW ambiguity, DQ-29 untrimmed/empty codes, DQ-31 null amounts, DQ-32 TEMP approvals) | H | M | Decisions file and flagged-record report outside git (08 §4.5–4.6, 12b §5.6); the owner merges DQ-01 before the first rehearsal (Q-42); reconciliation on units and amounts, not line counts or m³ [migration, owner] |
| R-08 | Production collation and code matching unknown (CU-02/03/17): codes or e-mails differing only by case or spaces collide on the normalised unique indexes | M | M | Importer reports collisions before the first rehearsal; P-02 only with the owner's go-ahead; one normalisation method (ADR 0010) [migration, tracking] |
| R-09 | E-mail deliverability: SES sandbox/identity state, DKIM/SPF/DMARC and the office Gmail mailbox (Q-43) are inferred; a new IAM key and SNS → SQS wiring need AWS console work by the owner | M | M | Owner checks the SES identity and creates the dedicated IAM user before Gate 1; staging guard prevents real sends; bounce handling via SQS only after the owner adds the component [notifications, owner] |
| R-10 | Owner availability at gates and for owner-only actions (DNS, dump, VPS, AWS, rotation): a gate waits indefinitely | M | M | Every question carries a default; the Gate 0 message lists only what cannot be defaulted; later sessions resume from `docs/plan.md` §Status [integrator] |
| R-11 | Context loss across sessions (many sessions, parallel agents) | H | M | `docs/plan.md` §Status updated at the end of every workflow; decisions only in chapter 11 and `docs/adr/`; the parity matrix generated from tags; `CLAUDE.md` kept current [integrator] |
| R-12 | Defaults most likely to be wrong and expensive after migration: Q-05 (amount meaning/VAT) and Q-06 (origin of the 12-digit codes) | M | H | Both behind one seam; the VAT flag is stored, null on legacy rows; reconciliation shows both legacy sums; asked explicitly at Gate 0 [orders, owner] |
| R-13 | Public-site parity through a template engine is brittle (serialisation, whitespace); any framework error page or inline style breaks the strict CSP; HTMX under CSP unproven until E2E runs | M | M | DOM-normalised parity harness and the allow-list from Phase 1; CSP-violation listener fails E2E; `th:inline="none"` guards [site, integrator] |
| R-14 | Old links already in inboxes (`/mono/email-confirm`, report links, `www…/#tracking`, `ordini.`, `dashboard.`) and unknown callers of `app.` (33 logged calls to `order/active`): tightening CORS or retiring paths may break them | M | L | API-03 pages on both hosts never confirm or serve data; `www` → apex keeps the fragment; nginx block at the freeze stops every caller; P-03/P-06 by the owner [tracking, owner] |
| R-15 | Lighthouse ≥ 95 and the gzip budgets rely on compression, HTTP/2 and ETag that Netlify provided | M | M | Compression and ETag in the app and Caddy (PD-02); Lighthouse CI from Phase 1 on the five pages [site] |
| R-16 | New VPS, GHCR, uptime check and off-site storage cost money and add Contabo as a processor | H | L | Spending and processor items listed in ADR 0001 for the owner; everything runs locally and in CI until approved [owner] |
| R-17 | Spring Boot 4.1 / Framework 7 / Hibernate 7 / Jackson 3 are young lines; a library (OpenPDF, Testcontainers 2, Thymeleaf integration) may lag | L | M | Phase 1 re-checks the pins and falls back to the newest GA line the libraries support, recorded in ADR 0001 [integrator] |
| R-18 | The office's confidential partner header in the legacy PDF and real personal data in legacy fixtures/comments could be copied by a careless port | L | H | Nothing is copied from `legacy/ls`; synthetic fixtures only; CI guard on tracked files; review lens "privacy" in Phase 3 [all] |

## 10. Open decisions for the owner

What cannot be defaulted or is cheaper to decide now (full list and wording in Italian in
`docs/gates/gate-0.md`):

1. **Q-54 / R-01** — make the repository private (or move to a private one, or purge `07-pricing.md` and `data/` from history). The owner's current decision is to proceed; the risk stays open.
2. **Q-01, Q-52, ADR 0001** — confirm Path B, the stack pins, the new Contabo VPS with Docker Compose and Caddy 2, PostgreSQL 16, AWS only for SESv2 (SNS → SQS as an added component), and the spending items (VPS, GHCR, uptime check, off-site backup storage).
3. **Q-05** — is the amount typed at approval the price charged (not an insured value)? VAT included or excluded? May it be 0?
4. **Q-06** — who makes the definitive shipment codes (a carrier's 12-digit numbers?); should the app ever propose one?
5. **Q-10 / Q-36** — private persons may register; VAT/tax code optional; phone required.
6. **Q-17** — how many staff accounts, names and roles (ADMIN/OPERATOR); confirmation that they are recreated, not migrated.
7. **Q-27** — may the agents run the read-only probes P-01…P-03 (default: no)?
8. **Q-02** — the schema-only dump with row counts (optional), later the anonymised copy.
9. **Q-16, Q-47, Q-50** — who manages the DNS; what `ordini.` and `dashboard.` call; the host in one recent verification e-mail.
10. **Post-approval corrections (PD-12)** — does the office correct amounts or codes after approval today? If yes, M1 needs an ADMIN-only audited correction.
11. **PD-01…PD-17** — the planner defaults of §8.2 (confirm or change; each listed in Italian in `docs/gates/gate-0.md` §5).
12. **Q-21, Q-53, Q-48, Q-49, Q-22, Q-23** — the urgent actions on the live system (owner only).
13. **Q-34, Q-31, Q-43** — price lists of other customers (M2 only), copy claims (the two pages without Saturday hours, stats, the about photo), the business mailbox before go-live.
