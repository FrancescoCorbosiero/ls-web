# 0001 · Path and stack

* **Status:** Proposed
* **Note:** implemented from Phase 1 on the owner's go-ahead of 2026-10-17 («puoi procedere in autonomia»), which is not an answer to Q-01 or Q-52: items 1–4 of "What the owner confirms at Gate 0" stay open (answers welcome at any time; the defaults stand meanwhile)
* **Date:** 2026-10-17
* **Requirement IDs:** all M1 and M2 IDs (the stack carries every one); directly NFR-02, NFR-04, NFR-05, NFR-06, MIG-01, ADM-09
* **Questions:** Q-01, Q-52 (decided here), Q-02, Q-16, Q-45, Q-48, Q-49, Q-53 (context) · **Findings:** F-19 (end-of-life stack), F-08, F-09, F-11, F-13, SEC-06, SEC-07, SEC-17, SEC-20

## Context

Chapter 10 §1 asks for one decision before any code: evolve the legacy backend (Path A) or
build a new monolith on the same stack with the legacy source as the reference (Path B).
The legacy backend is `ls@migration` = `ca1b0d0` (2025-11-16): Java 17, Spring Boot 3.2.2,
MySQL 8.0.35 `lsdb`, one Contabo VPS with Docker Compose (nginx → Spring Boot `/mono` →
MySQL), AWS SES in eu-north-1 through the AWS SDK for Java v1 (chapter 12; 12d §1–§2).
Phase 0 verified the assessment of chapter 10 §1 in the local checkout
(`docs/phase0/readers/legacy-ls-build-infra.md`, `legacy-ls-api-security.md`,
`legacy-ls-domain-data.md`) and recorded the build environment (`docs/plan.md` §1).

Whatever the path, the public contract after the cut-over is the same: API-01 and API-02
are kept, every other legacy endpoint answers 410 and never performs its action (API-03),
and the security model of the legacy (a URL rule that never matches, `permitAll` by
default, JWT with a permanent refresh id, request and response bodies logged) is not
reproduced (chapter 10 §4; 12a §5).

### Path A assessment (chapter 10 §1 criteria) as verified in Phase 0

| Criterion | Chapter 10 §1 said | Phase 0 found | Evidence |
|---|---|---|---|
| Builds and starts locally | Plausible; verify | **Builds: yes.** `sh ./mvnw -B -q -DskipTests package` on the sanitised checkout: exit 0, 38 s including the download of Maven 3.9.5 and 141 MB of dependencies through the proxy, `target/mono-0.0.2-SNAPSHOT.jar` 81.6 MB with 104 jars; compiled under JDK 21 with `--release 17`. **Starts: not checked** (needs MySQL and builds the SES client at start-up; no Docker daemon here). The tests do not protect the contract: the integration test is `@Disabled` and the test client swaps its `uri`/`token` arguments. | `legacy-ls-build-infra.md` §2.1; ls@migration:src/test/java/…/IntegrationTests.java:110; …/util/client/CmsTestClient.java:34 |
| Framework and runtime supported | No | **No (framework).** Spring Boot 3.2.2: OSS support ended 2024-12-31 (chapter 10/12d say "November 2024"; the conclusion is unchanged), commercial 2026-02-28. Spring Framework 6.1.3, Spring Security 6.2.1, Hibernate 6.4.1, Tomcat 10.1.18 (≈ 15 later CVEs), AWS SDK for Java v1 1.12.341 + 1.11.792 (end of support 2025-12-31, already past), Spring Cloud AWS 2.2.6 (built for Boot 2), `javax.mail` 1.6.2 beside `jakarta.mail`, iText 2.1.7 (2009) with two BouncyCastle copies (1.64 and 138), Jackson 2.15.2 pinned with a duplicated `jsr310` 2.16.1, Lombok 1.18.30 (would not compile on JDK 25). The runtime Java 17 is still supported but two LTS behind. MySQL 8.0 end of life April 2026 is marked "verify" in 12d §2 and stays so. | ls@migration:pom.xml:8, 17, 55-77, 96-145; `dependency:tree`; `legacy-ls-build-infra.md` §2.2 |
| Dependencies upgradable with no known critical vulnerability left | Touches every layer | **Only by replacement, not by bumping.** Boot 3.2 → 4.x drags Framework 7, Security 7, Hibernate 7, Jackson 3; SES needs the SDK v2 (rewrite of `AwsMailConfig`/`AwsEmailService`); the PDF generator needs OpenPDF instead of iText 2.1.7; `javax.mail`, Spring Cloud AWS 2 and the duplicated Jackson modules must go. No critical CVE is known to be reachable today (nginx in front, no writable default servlet), but CVE-2025-41234 touches `WebController.downloadReport`. | `legacy-ls-build-infra.md` §2.2, §7 |
| Schema evolvable with forward-only migrations | No migrations; baseline possible | **Possible but blind.** Hibernate `ddl-auto: update` owns the production schema (key present at ls@migration:src/main/resources/application.yml:32, value redacted); no Flyway/Liquibase; the schema is known only from the entities (12b §1.7) until the owner's `mysqldump --no-data` (Q-02); 12b §1.8 lists probable drift (leftover columns, an extra unique key). A Flyway baseline on MySQL is feasible, after which the target model of chapter 08 §2 renames every table and column anyway. | `legacy-ls-domain-data.md` §2, §6; 12b §1.8 |
| Fit with the target | Most of M1 is new | **Confirmed.** 6.6 k lines of Java in 149 main files (7.3 k lines in 161 files with tests; `find src -name '*.java' | xargs cat | wc -l`); worth keeping under A: the business rules (services, enumerations, converters, four entities), the seven Thymeleaf e-mail templates behind the ten automatic e-mails (`email-confirmation`, `customer-success`, `error`, `order-detail`, `email-report`, `tracking-state`, `admin-notification`; `error` is also the page shown when `GET /mono/email-confirm` fails, ls@migration:src/main/java/it/logisticasanchez/mono/web/WebController.java:36) and the page templates `email-success` and `report` (`report-download.html` is referenced by nothing in `src/main` and is not a candidate for porting) and the PDF layout, 1.5–2.5 k lines. Retired by API-03, 10 §4 and 12d §3: the 36 controller mappings (33 retired), ≈ 45 DTOs, JWT/CMS security, the body-logging filter, every script. No background work, no outbox, no audit of staff identity (`create_user`/`update_user` hold a role name), no health endpoint, no CI. The public site, customer area, server-rendered back office, outbox, audit log, roles, migration importer, tests, CI and deploy are new on either path. | `legacy-ls-api-security.md` §4–§5; `legacy-ls-build-infra.md` §7 |

**Cost estimate (rough, from the build reader).** Path A adds, on top of everything Path B
also needs: the framework/SDK/PDF replacement (≈ 3–5 agent-days), the secret scan,
history-free import and owner rotation loop (≈ 1–2 plus owner time; the local checkout is
sanitised and modified, so an import would need a fresh export by the owner), the MySQL
8.0.35 → 8.4 LTS upgrade with a Flyway baseline on a drifted schema (≈ 3–5, with the same
dry-run and reconciliation duties as MIG-01/02), and the mapping of the `mono.*` packages
onto the modules of chapter 10 §2 (≈ 2–3). It saves the port of rules and templates
(≈ 2–4 days under B, with chapter 12 already written). Net: **Path A costs roughly 20–35 %
more than Path B** and carries the regression risk of an upgrade that touches every layer,
while the parity risk Path A was meant to reduce is already covered by chapter 12, the
parity matrix and the contract tests. Legacy security findings that would have to be fixed
in place under A (SEC-01…SEC-21) are "not reproduced" under B.

### Build environment (verified 2026-10-16/17; details in `docs/plan.md` §1)

JDK 21.0.12.1 (Ubuntu package), Maven 3.9.11 and the legacy Maven wrapper both work
through the session proxy; Gradle 8.14.3; Node 22.22.0, npm 10.9.4, pnpm; Playwright 1.56.1
with chromium-1194 installed (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`); PostgreSQL 16.15
server binaries (`initdb`, `pg_ctl`, `postgres`) and `psql`; **no MySQL** client or server;
Docker CLI 29.8.2 **without a daemon** (no Testcontainers, no Compose here); Maven Central
reachable (the newest GA line of `spring-boot-starter-parent` is 4.1.1, the newest
milestone 4.2.0-M2, re-checked on 2026-10-17); GitHub Actions provides JDK 21 through
`actions/setup-java` (`temurin`).

## Decision

**Path B: a new monolith on Java + Spring Boot, with `legacy/ls` as the primary reference
for every rule, text and edge case (chapter 12), PostgreSQL, Flyway, one new VPS.** Chapter
10's Python alternative is not taken: the owner is a Java developer and the legacy rules
port one-to-one. Nothing is copied from `legacy/ls` (no file, no value, no security
configuration, no custom `ObjectMapper`, no body-logging filter, no committed profile):
rules and texts are ported by reading the code.

### Stack pins

Versions are pinned exactly in the build files. "Boot BOM" means the version managed by
`spring-boot-dependencies` 4.1.1, read from Maven Central on 2026-10-17. **The Phase 1 agent
re-checks Maven Central and npm before generating the skeleton and records any newer GA in
this table with the date; a newer major of Spring Boot is taken only if its GA is at least
two weeks old and the libraries below support it.**

| Component | Pin | Why |
|---|---|---|
| Java | **JDK 21** (LTS; 21.0.12.1 here; `actions/setup-java` `temurin` 21 in CI), Maven toolchain `release` 21 | It exists both here and in CI (`PROMPT.md` environment check: pin a JDK that exists in both). JDK 25 is not installed here. |
| Build | **Maven** with the wrapper (Maven 3.9.11; wrapper pinned to the same version), `spring-boot-starter-parent` as parent, Enforcer plugin (Java 21, no duplicate dependencies), plugin versions pinned | The legacy is Maven, the owner knows it, the wrapper was verified through the proxy; Gradle gives nothing the project needs. |
| Spring Boot | **4.1.1** (newest GA line on Maven Central on 2026-10-17; 4.2.0-M2 is a milestone and is not used) | "Current versions" (chapter 10 §1); the BOM below is consistent. |
| Spring Framework / Security / Data / MVC | Boot BOM: Framework 7.0.9, Security 7.1.1, Data 2026.0.1; Tomcat 11.0.24 | Form login for staff, one-time-token login for customers, CSRF, method security (10 §2, §4). |
| Server-rendered UI | Thymeleaf 3.1.5.RELEASE with `org.thymeleaf:thymeleaf-spring6` and `org.thymeleaf.extras:thymeleaf-extras-springsecurity6` as managed by the Boot BOM (through `spring-boot-starter-thymeleaf`; no `thymeleaf-spring7` artifact exists on Maven Central, checked 2026-10-17); **htmx 2.0.11** vendored as a static file (no CDN), configured for a strict CSP (`includeIndicatorStyles=false`, `allowEval=false`, `allowScriptTags=false`, `selfRequestsOnly=true`) | 10 §2; 06 §8.5. The public pages keep the new site's vanilla JS unchanged. |
| Persistence | **PostgreSQL 16** (16.15 here; `postgres:16` image in CI, staging and production), JDBC driver Boot BOM 42.7.13, Spring Data JPA + Hibernate (Boot BOM 7.4.5.Final, `ddl-auto: validate`), **Flyway** (Boot BOM 12.4.0, `flyway-database-postgresql`), Bean Validation | Q-52. One major everywhere: 16 is what runs here, is supported until November 2028, and consistency across test, CI and production matters more than novelty; a later major is a planned upgrade with a tested restore, never a drift. |
| AWS | **AWS SDK for Java v2**, BOM 2.56.0: `sesv2` in eu-north-1 with a dedicated IAM user (`ses:FromAddress` condition). **SQS only when bounce handling is built** (NFR-04, notifications module; the SNS → SQS wiring is an owner action because it adds an AWS component). **No S3 in M1**: the ADM-09 report is generated on demand for the authenticated download from the stored `report_run` parameters, so no bucket and no lifecycle rule are needed; S3 arrives with ENH-12 (documents) in M2. | 10 §2–§3; Q-52 (Gate 0 item); spending gate of `PROMPT.md` rule 6. |
| PDF | **OpenPDF 3.0.5** (`com.github.librepdf:openpdf`; fontconfig + DejaVu fonts in the image) | Maintained successor of the legacy iText 2.1.7 layout; the 13 legacy columns are reproduced (12c §3.4). |
| Office formats (M2) | Apache POI (version pinned in Phase 5) with the limits of 10 §4 | ENH-01, ENH-08. |
| QR code (M2) | **ZXing core 3.5.3** (`com.google.zxing:core`, Apache-2.0), encoding only, added 2026-10-26 | ENH-09: the QR code of the staff two-factor set-up, drawn as inline SVG (ADR 0039). |
| Tests | JUnit 5 and Spring Boot test (Boot BOM); **Testcontainers** (Boot BOM 2.0.5) for PostgreSQL in CI and on the owner's machine; **`TEST_DATABASE_URL` fallback** here: the test setup reads it and skips Testcontainers when set, running against a local PostgreSQL started with `initdb`/`pg_ctl`; in-process fakes for SES (and S3 in M2) plus a mail catcher; **Playwright 1.56.1** (the version whose browsers are installed here; CI installs the same) for E2E with a CSP-violation listener that fails the run; **@axe-core/playwright 4.14.0**; **Lighthouse CI (`@lhci/cli` 0.15.1)** on the five PUB-10 pages. For the migration importer: Testcontainers `mysql:8.0.35` in CI with the 12b §1.7 DDL and synthetic fixtures; where no Docker daemon runs, the same fixtures on H2 in MySQL compatibility mode (the Phase 1 agent confirms the fallback engine; no layer is dropped). | 10 §6; `PROMPT.md` environment check ("never drop a test layer"). |
| Observability | Spring Boot Actuator: only `/actuator/health` without details on the public port; structured JSON logs (Logback, Boot BOM) without bodies or personal data | NFR-04; SEC-17. |
| Front-end tooling | Node 22 only for Playwright, axe and Lighthouse CI; no bundler, no client framework | `PROMPT.md` rule 2. |

### Hosting and database engine (chapter 10 §3 default; Q-52)

* **One new Contabo VPS** (EU, supported Ubuntu LTS) so the legacy server stays intact for
  rollback; Docker Compose with three services: `proxy` = **Caddy 2** (automatic HTTPS for
  the apex, `www`, `app.`, `ordini.`, `dashboard.`; host rules in a committed `Caddyfile`;
  maintenance page), `app` = the monolith image (non-root, healthcheck on the health
  endpoint, `TZ=Europe/Rome`, fonts for the PDF), `db` = `postgres:16` with a named volume.
  Only the proxy publishes 80/443; `app` and `db` are on an internal network. Staging is a
  second Compose project on the same VPS behind basic auth, with the staging e-mail guard.
* AWS only for **SESv2 in eu-north-1** (the SES identity used today is reused — presumed
  verified: the owner confirms it in the AWS console before Gate 1, Q-45; D-45, R-09 — and a
  new key for a dedicated IAM user is created by the owner) and, in M2, S3 for documents.
* CI (GitHub Actions) builds a versioned image, pushes it to GHCR; deploy by tag with
  `docker compose pull && up -d`; production needs a manual approval; Flyway runs at
  start-up; rollback = previous tag plus a database restore point. Encrypted off-site
  backups (7 daily, 4 weekly, 12 monthly) with an age alert and a monthly test restore.
* **Database engine: PostgreSQL 16.** MySQL 8.4 would only have made sense under Path A.
  The legacy data are migrated by the importer of MIG-01 from the `lsdb` dump restored into
  an isolated MySQL 8.0.35 (owner's infrastructure or CI), read through a read-only JDBC
  user; `users` and `user_session` are dropped from every non-production copy.

### What Path B must not inherit from the legacy

The security configuration (dead `/mono/cms/**` rule, `permitAll`, JWT, permanent refresh
token), the request/response body logging, the custom `ObjectMapper` (epoch-second
timestamps, unknown fields rejected), the committed profiles and defaults, the hard-coded
office address, the admin utilities (`/mono/utils/**`, `/mono/sendEmail`,
`/mono/cms/order/update`), the pricing scaffolding (`PalletEnum`, `TariffarioConst`,
`EvasioneEnum`), the integer-m³ volume, the 6-month list window. Each is a planned ADR
(`docs/adr/README.md`).

## Consequences

* Parity is guaranteed by the parity matrix (`docs/parity-matrix.md`), contract tests on
  API-01/02/03 with the fixtures of chapter 02 §10.4 on both hosts, the E2E suite, and the
  migration reconciliation (counts per state, sums of amounts) on the anonymised copy.
* The legacy rules are ported from chapter 12 and the code; where chapter 12 and the code
  disagree, the code wins and the discrepancy is listed in `docs/plan.md` §7.
* Spring Boot 4.1 / Framework 7 / Hibernate 7 / Jackson 3 are young lines: the Phase 1
  agent re-checks the pins, and Dependabot keeps patch versions current. A newer Boot major
  is adopted only by a dated ADR amendment.
* No Docker daemon in this build environment: integration and E2E tests run here against
  the local PostgreSQL through `TEST_DATABASE_URL`; Testcontainers and Compose remain the
  path for CI and the owner's machine. The importer's MySQL path can only be rehearsed in
  CI or on the owner's machine (no MySQL binaries here).
* No S3 in M1 shortens the IAM policy and the privacy page; the report download
  regenerates the PDF deterministically from `report_run` (customer, range, filter,
  requested-at), so a report produced today and downloaded tomorrow may differ if the
  office changed an order in between: the download page says so.
* A new VPS, GHCR, an external uptime check and off-site backup storage cost money and add
  Contabo as a processor with a signed DPA: provisioning waits for the owner (gate on
  spending and processors). Until then everything runs locally and in CI.

### What the owner confirms at Gate 0

The owner asked the agents to proceed autonomously on 2026-10-17 («puoi procedere in
autonomia»; recorded in `docs/plan.md` §Status and `docs/gates/gate-0.md` §6): the items
below are implemented as proposed from Phase 1 and remain open for confirmation — the
go-ahead is not an answer to Q-01 or Q-52. In the Decision column of Q-01 and Q-52 of
`docs/brief/11-open-questions.md` the integrator writes the note «No answer. Go-ahead only
(«puoi procedere in autonomia», 2026-10-17, Gate 0): the recommended default is applied;
the question stays open», worded so that it cannot be read as an answer, in the same commit
that lands this ADR on `phase-0` (`docs/plan.md` §Status steps 2–3; shared file,
`docs/plan.md` §6), as `PROMPT.md` rule 7 asks; until that commit this ADR and plan §Status
are the record.

1. Path B on Java 21 + Spring Boot 4.1.1 + PostgreSQL 16 (Q-01, Q-52), or asks for Path A
   knowing the assessment above.
2. A **new** Contabo VPS with Docker Compose (not the legacy VPS, not managed AWS), Caddy 2
   as the proxy, and the related spending: VPS, GHCR (the image is published as a private
   package; package visibility and any storage cost are verified at Phase 1, see
   `docs/plan.md` §9 R-01), an external uptime/TLS check, EU object storage for encrypted
   backups.
3. AWS only for SESv2 (a dedicated IAM user created by the owner before Gate 1) and, in M2,
   S3; SNS → SQS bounce handling as an added AWS component (NFR-04).
4. That staff accounts are **not** migrated from the legacy `users` table (recreated by
   the `create-staff-user` command, ADM-01).

## References

* `docs/brief/10-architecture.md` §1–§3, §6; `docs/brief/12-legacy-backend.md`; `docs/brief/12d-backend-infra-and-repo.md` §2, §6.2.
* `docs/phase0/readers/legacy-ls-build-infra.md` (build attempt, dependency table, cost estimate); `legacy-ls-api-security.md` §5 (endpoint fates); `legacy-ls-domain-data.md` §7; `10-10-architecture.md` §8; `12d-12d-backend-infra-and-repo.md` §8.
* `docs/plan.md` §1 (environment), §3, §9; `docs/brief/11-open-questions.md` Q-01, Q-52.
* Chapter 09 corrections carried by this ADR: 12c §1.5/§7 "add EML-09 (M1)" is stale — EML-09 is already in M1 (`docs/plan.md` §7 D-96; detailed in ADR 0008); 12d §6.3 Path A import exclusions are moot under Path B (D-106).
