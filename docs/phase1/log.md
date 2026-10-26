# Phase 1 — Foundations: stage log

One section per stage (`docs/plan.md` §5 "Phase 1"). Each section says what was built, how
to run it, the decisions taken, the open points and the changes proposed to shared files.

## Stage 1 — Skeleton, build, local environment, CI (2026-10-17)

### What was built

* **Maven project** `it.logisticasanchez:ls-web:0.1.0-SNAPSHOT`, parent
  `spring-boot-starter-parent` **4.1.1** (re-checked on Maven Central on 2026-10-17: newest
  GA of the 4.1 line; 4.2.0-M2 is a milestone and is not used), Java 21
  (`maven.compiler.release` 21), Maven wrapper 3.9.11 (`.mvn/wrapper`, `mvnw`, `mvnw.cmd`,
  wrapper 3.3.4 `only-script`). Dependencies, every coordinate verified with HTTP 200 on
  Maven Central: starters `webmvc`, `thymeleaf`, `data-jpa`, `security`, `validation`,
  `actuator`, `flyway` (+ `org.flywaydb:flyway-database-postgresql`), `org.postgresql:postgresql`
  (runtime), `org.thymeleaf.extras:thymeleaf-extras-springsecurity6` (the artifact the Boot
  4.1.1 BOM manages for Spring Security 7, 3.1.5.RELEASE; no `springsecurity7` artifact
  exists); tests: `spring-boot-starter-test`, `-security-test`, `-webmvc-test`,
  `-data-jpa-test`, `spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql`
  (the Testcontainers 2.x coordinate; `org.testcontainers:postgresql` does not exist for 2.0.5).
  Plugins: Enforcer 3.6.3 (Boot BOM; JDK `[21,22)`, Maven `[3.9,)`,
  `banDuplicatePomDependencyVersions`), Spotless 3.10.4 with palantir-java-format 2.102.0
  (`check` bound to `verify`, `sortPom` on `pom.xml`), Surefire 3.5.6 (`*Test`), Failsafe
  3.5.6 (`*IT`, `integration-test` + `verify` from the Boot parent), `spring-boot-maven-plugin`
  (`build-info`, layered jar).
* **Application** `it.logisticasanchez.web.Application`; profiles `local`, `test`, `staging`,
  `prod`. `application.yml` is driven by `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD`,
  `SERVER_PORT`, `LS_*` with no secret defaults; `ddl-auto: validate`, `open-in-view: false`,
  Flyway on `classpath:db/migration` (`fail-on-missing-locations: true`, `clean-disabled: true`;
  the location exists and is empty until stage 2 adds `V001`), management: only
  `/actuator/health` (`endpoints.access.default: none`, `exposure.include: health`,
  `show-details: never`, `show-components: never`, discovery page off, probes off).
  `application-local.yml`: port 8080, `ls_web` on 54329. `application-test.yml`: random port.
  Secure/HttpOnly/Lax session cookie in the base file, `secure: false` in `local` and `test`.
* **Bootstrap security chain** `platform.security.BootstrapSecurityConfig`: the health
  endpoint is `permitAll`, every other request `denyAll` with a 401 entry point. It stands in
  for the real deny-by-default chain of stage 5 (chapter 10 §4), which replaces it. The
  generated in-memory user of Boot is excluded (`spring.autoconfigure.exclude`
  `UserDetailsServiceAutoConfiguration`) until the auth stage provides the user store.
* **Local environment** `scripts/local-db.sh start|stop|status|reset|psql|createdb|env`:
  PostgreSQL 16 under `.local/pg` (gitignored), port 54329, role `ls`/`ls` (local only),
  databases `ls_web` and `ls_web_test`; `createdb ls_<module>` for the Phase 2 worktrees. When
  run as root (this build environment) the server runs as the `postgres` OS user
  (`LS_PG_USER`), because PostgreSQL refuses to run as root.
* **Test support** `support.TestDatabase` + `support.AbstractPostgresIT`: reads
  `TEST_DATABASE_URL` / `TEST_DATABASE_USER` / `TEST_DATABASE_PASSWORD`; when all three are
  set it uses them (the path exercised here and in CI), otherwise it starts a Testcontainers
  `postgres:16` once per JVM (the path for the owner's machine). Both paths are implemented;
  the switch logic is unit-tested (`TestDatabaseTest`).
* **Tests** (all real): `ApplicationContextIT` (context starts on PostgreSQL 16 with the
  `test` profile; Flyway ran, nothing pending, `flyway_schema_history` exists and matches the
  applied count; `ddl-auto` is `validate` and `clean` is disabled), `HealthEndpointIT`
  `@Tag("NFR-04")` (`/actuator/health` answers 200 `{"status":"UP"}` with no other property;
  `/actuator`, `/actuator/env`, `/actuator/configprops`, `/actuator/beans`, `info`, `metrics`,
  `mappings`, `loggers`, `heapdump`, `threaddump`, `health/db`, `health/liveness`,
  `health/readiness` answer 401 or 404, never 200, and never leak a status or property
  sources), `TestDatabaseTest` (unit).
* **CI** `.github/workflows/ci.yml` on `pull_request` and on push to `main`, `phase-*`,
  `module/*`, `m2`; `permissions: contents: read`; concurrency group per ref with
  cancellation. Job `build-and-test`: `actions/checkout@v7`, `actions/setup-java@v6`
  (temurin 21, Maven cache), service container `postgres:16` with `TEST_DATABASE_*` pointing
  at it, `./mvnw -B -ntp verify`, Surefire/Failsafe reports uploaded (`actions/upload-artifact@v7`,
  always). Job `guards`: `tools/ci/guard-tracked-files.sh` (fails on tracked `legacy/`,
  `logs/`, `*.sql` outside `**/db/migration/` and `**/src/test/resources/**`, `.env`/`.env.*`
  except `.env.example`, `*.xlsx`/`*.xlsm` except `*-synthetic.xlsx` under
  `src/test/resources`, `*.dump`, `*.sql.gz`, `*.bak`, `*.log`) and `gitleaks/gitleaks-action@v2`
  with `.gitleaks.toml` (default rules; allow-list limited to the documented local-only
  `ls` test password and `.env.example`). `.github/dependabot.yml`: maven (`/`), npm (`/e2e`),
  github-actions, weekly on Monday 06:00 Europe/Rome, Boot artifacts grouped.
* `README.md` (English): what the project is, prerequisites, local database, build, run,
  test, where the docs are. `.gitignore`: `.local/` added.

### How to run it

```sh
scripts/local-db.sh start
eval "$(scripts/local-db.sh env)"
./mvnw -B -ntp verify                      # build, Spotless check, unit + integration tests
./mvnw spotless:apply                      # format
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # http://localhost:8080/actuator/health
tools/ci/guard-tracked-files.sh            # the CI guard, locally
```

Verified here on 2026-10-17: `./mvnw -B -ntp verify` green with the local database
(3 unit tests, 18 integration tests), guard script ok, Spotless check ok.

### Decisions taken

* Testcontainers 2.x coordinate `org.testcontainers:testcontainers-postgresql` (package
  `org.testcontainers.postgresql.PostgreSQLContainer`): the 1.x artifact `org.testcontainers:postgresql`
  does not exist at 2.0.5.
* Formatter: palantir-java-format through Spotless (Google style with 120 columns, the more
  readable choice for Spring code); `removeUnusedImports` and `formatAnnotations` on.
* Boot 4 package moves recorded for later stages: `HealthEndpoint` is
  `org.springframework.boot.health.actuate.endpoint.HealthEndpoint`; `EndpointRequest` is
  `org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest`;
  `UserDetailsServiceAutoConfiguration` is `org.springframework.boot.security.autoconfigure.…`;
  JSON is Jackson 3 (`tools.jackson.databind`).
* `/actuator/health` on the public port answers plain `application/json` when the client
  asks for it (the vendor type `application/vnd.spring-boot.actuator.v3+json` otherwise); the
  test accepts any JSON media type.
* The CI workflow pins actions to their newest major tags (`checkout@v7`, `setup-java@v6`,
  `upload-artifact@v7`, read from the GitHub tags on 2026-10-17); `gitleaks-action@v2` as the
  task asked (v3.0.0 exists; Dependabot proposes the bump).
* The GitHub API through `gh` is not reachable from this session (403: repository access not
  enabled), so the GHCR package-cost check of `docs/plan.md` §5 item 2 and R-16 is left to the
  stage that adds the image build; nothing is pushed to GHCR before it.

### Open points

* The confidential-figure guard of `docs/plan.md` §5 item 1 (tokens derived at run time from
  `docs/brief/07-pricing.md` and `docs/brief/data/`) is not part of this stage's task; a later
  stage adds it to the `guards` job.
* `dependabot.yml` names `/e2e` for npm before the directory exists: Dependabot logs a
  "directory not found" notice for that entry until the E2E stage adds `e2e/package.json`.
* `gitleaks` is not installed here, so the secret scan was not run locally; it runs in CI.
* The E2E, Lighthouse and image jobs are added by their stages (not stubbed).
* No pin of ADR 0001 changed; the ADR needs no amendment for this stage.

### Proposed changes to shared files (for later stages / the integrator)

* `CLAUDE.md` "Commands": the commands of "How to run it" above (filled in stage 6 per plan).
* `application.yml`: the `spring.autoconfigure.exclude` of `UserDetailsServiceAutoConfiguration`
  is removed by the auth stage once a `UserDetailsService` bean exists.
* `BootstrapSecurityConfig` is deleted by the security stage (stage 5) when the real chain lands.

### Re-run after owner decision Q-55 (2026-10-17, later the same day)

The workflow restarted stage 1 after the owner decided Q-55 (no migration of the legacy
data; `docs/plan.md` §Status, chapter 11). The stage-1 deliverables were re-checked against
that decision and re-verified; nothing in them concerns the legacy data, so no code changed.

* **Checked against Q-55.** No migration module, importer, MySQL/H2 dependency, legacy column
  or `MIG-*` test exists in the stage-1 code, build or CI (`pom.xml`, `application*.yml`,
  `ci.yml`, the guard script, the tests). The ADR 0001 line "the Phase 1 agent confirms the
  fallback engine (H2 in MySQL mode)" for the importer is moot: no importer is built, no
  fallback engine is chosen, no `mysql:8.0.35` job is added to CI. The integrator trims the
  importer wording of ADR 0001, `docs/plan.md` §4/§5/§8 and chapter 10 §6 "Migration" row at
  the end of Phase 1 (plan §Status).
* **Working tree cleaned.** An aborted stage-2 run had left untracked files in the tree:
  `src/main/resources/db/migration/V001…V013` (among them `V011__migration_tool.sql`, which
  Q-55 forbids), JPA entities under `auth/`, `notifications/`, `platform/audit/`,
  `platform/flags/`, schema tests and `src/test/resources/schema/*.txt`, plus an uncommitted
  edit of `ApplicationContextIT`. None of it was committed. The tracked test was restored
  and the untracked files were moved out of the repository (to the session scratchpad, not
  deleted); stage 2 starts again from this commit and designs the schema under Q-55 (no
  legacy columns, no migration-tool tables).
* **Local database reset.** That run had applied its `V001…V013` to `ls_web_test`;
  `scripts/local-db.sh reset` rebuilt the instance (the `reset` and `createdb` commands were
  thereby exercised). Lesson recorded: Maven's resources plugin does not remove deleted files
  from `target/classes`, so after removing or renaming a migration run `./mvnw clean verify`
  (a stale `target/classes/db/migration` copy was applied by Flyway on the first attempt and
  made `ApplicationContextIT` fail); CI always builds from a clean checkout.
* **Re-verified on 2026-10-17:** `tools/ci/guard-tracked-files.sh` ok (78 tracked files),
  `./mvnw -B -ntp clean verify` green (Enforcer rules passed, Spotless check ok, 3 unit
  tests, 18 integration tests: `ApplicationContextIT`, `HealthEndpointIT` `NFR-04`,
  `TestDatabaseTest`), `git status` clean after the commit.
* No pin of ADR 0001 changed.

## Stage 2 — The complete M1 schema, seeds, Phase 1 entities (2026-10-17)

Restarted from commit `297d13b` after owner decision Q-55 (no data migration); the
leftovers of the aborted run were not restored — the schema was designed again from chapter
08 §2.3 amended by 12b §5.4, `docs/plan.md` §4.4 and §8.1, without any legacy column.

### What was built

* **Flyway migrations** `src/main/resources/db/migration/V001…V009` (one per area, README
  there): `V001` the `set_updated_at()` trigger function; `V002` `staff_user`; `V003`
  `customer`, `customer_pending_registration` (Q-09, D-88), `customer_user`, `login_token`;
  `V004` `tracking_state`, `accessory_service`; `V005` `shipment_order`, `order_line`,
  `order_internal_note`, `tracking_event`; `V006` `lead`; `V007` `email_outbox`,
  `report_run`; `V008` `feature_flag`, `audit_log`, `audit_log_detail` with the
  `audit_log_immutable()` trigger; `V009` seeds. 17 tables, every one with `COMMENT ON`;
  `bigint` identities (legacy ids kept only for the two enumerations); `timestamptz`;
  `numeric(10,2)` money plus `amount_vat_included`; named CHECKs for every state enumeration
  and rule (`ck_<table>_<rule>`), unique indexes on `lower(btrim(email))` and
  `lower(btrim(code))` (`ux_*`), partial unique indexes for one OWNER per customer, one
  `is_none` service and the idempotency key; indexes for the planned queries (customer
  status/created, order customer/status/tracking state/created, tracking history, outbox
  queue and purge, lead status/retention, audit occurred/entity/actor). No `legacy_*`,
  `*_raw`, `legacy_json` or `data_quality` column, no import table (Q-55). Full list and
  reasons: **ADR 0020**.
* **Seeds** (`V009`): the six tracking states (ids 0–5, `NON_GESTITO…CONSEGNATO`, legacy
  labels re-read in `ls@migration` `TrackingState.java:15-20`, plus the public page's titles
  and steps of chapter 06 §5.7), the six accessory services (`ServizioAccessorioEnum.java:16-21`,
  `is_none` only on id 0, identity restarted at 6 for ENH-14), the 25 flag keys of chapter
  09 §M2 with their ENH ids, all off. No staff user, customer or order anywhere.
* **Audit immutability** (`V008`, ADR 0020): a `BEFORE UPDATE OR DELETE` trigger on
  `audit_log` and `audit_log_detail` raises `insufficient_privilege` on every UPDATE and on
  every DELETE outside a maintenance transaction (`SET LOCAL ls.audit_maintenance = 'on'`,
  used only by the retention job and the erasure tool). Works with the single application
  role; the production option of `REVOKE UPDATE, DELETE` plus a maintenance role is
  documented in the ADR for the `deploy/` stage.
* **JPA entities and repositories** for the Phase 1 tables only (plain classes, no Lombok):
  `auth.StaffUser`/`StaffRole`/`StaffUserRepository`, `auth.CustomerUser`/`CustomerUserRole`/
  `CustomerUserRepository` (holds `customer_id` as a plain column: no `Customer` entity in
  Phase 1, the `customers` module adds it), `auth.LoginToken`/`LoginTokenPurpose`/
  `LoginTokenRepository`, `platform.flags.FeatureFlag`/`FeatureFlagRepository`,
  `platform.audit.AuditLog` (builder, no setters)/`AuditLogDetail`/`AuditActorType` and their
  repositories, `notifications.EmailOutbox`/`EmailOutboxStatus`/`EmailOutboxRepository`
  (`findDue`, `findSentBeforeWithBodies`, `countByStatus`). Enums map with
  `@Enumerated(STRING)` + `@JdbcTypeCode(VARCHAR)`, JSON with `@JdbcTypeCode(JSON) String`,
  `bytea` with `byte[]`, `timestamptz` with `Instant`; `ddl-auto: validate` passes.
* **Tests** (all real; failsafe `*IT` on the local database, surefire `*Test`):
  `schema.MigrationsIT` — all nine migrations apply to a clean schema of their own, a second
  run applies nothing, `validate` passes, the app schema is current; `@Tag("NFR-02")` the
  audit trigger refuses UPDATE and DELETE on both tables and allows DELETE only inside a
  maintenance transaction. `schema.EnumerationSeedIT` `@Tag("ADM-10")` — exactly the six
  states and six services with ids, names, labels, order, `is_none`, and the identity
  continuing at 6. `platform.flags.FeatureFlagSeedIT` (untagged on purpose) — the seeded
  keys equal the flags parsed from the M2 table of `docs/brief/09-features.md`, all off, with
  the right ENH id. `schema.SchemaConformanceIT` — every M1 table exists, no legacy/raw/
  data-quality column, every column of `src/test/resources/schema/expected-columns.txt` has
  the expected type and nullability (and no M1 column is unlisted), every CHECK, unique
  constraint and unique index of `expected-constraints.txt` exists, the audit triggers
  exist, every table has a comment. `schema.PhaseOneEntitiesIT` — writes and reads every
  Phase 1 entity through its repository (rolled back). `schema.NoSeededStaffAccountsTest`
  `@Tag("ADM-01")` — fails if a migration writes `staff_user`. `schema.
  NoSeedDataOutsideLocalAndTestProfilesTest` `@Tag("ADM-01")` — the migrations insert only
  into the three seeded tables, no Spring `data.sql`/`schema.sql`/`import.sql` exists under
  the main resources, `spring.sql.init` is not enabled in the base, staging or prod
  configuration. `ApplicationContextIT` now asserts the current version is `009`.
* **ADRs**: `docs/adr/0020-data-model-conventions.md` and `docs/adr/0025-feature-flags.md`
  (both Accepted, 2026-10-17), indexed in `docs/adr/README.md`.

### How to run it

```sh
scripts/local-db.sh start                  # once; reuse if running
eval "$(scripts/local-db.sh env)"
./mvnw -B -ntp verify                      # 7 unit + 27 integration tests, Spotless check
./mvnw -B -ntp clean verify                # after adding, renaming or removing a migration
scripts/local-db.sh psql ls_web_test       # \dt, \d+ customer, select * from feature_flag
```

Verified here on 2026-10-17: `./mvnw -B -ntp verify` green (BUILD SUCCESS, 34 tests),
`tools/ci/guard-tracked-files.sh` ok, `git status` clean after the commit.

### Decisions taken

* Everything chapter 08 relaxed "for legacy rows" is strict now (Q-55): `customer.phone`,
  `registered_at`, `transport_document_code`, `submitted_at` NOT NULL; five-digit CAPs;
  line dimensions ≥ 1 and quantity ≥ 1; APPROVED orders always carry code, amount and
  approval time. `amount >= 0` at the column, `> 0` in the approval service (the owner may
  allow 0 without a migration, 12c §4.2).
* Enumeration ids are the primary keys (`tracking_state.id smallint`, `accessory_service.id
  integer` identity seeded 0–5 and restarted at 6): the row *is* the EP-20/EP-19 item, so
  no `legacy_id`/`legacy_json` is needed to serve API-02 verbatim.
* `tracking_state.public_title`/`step_label` seeded from chapter 06 §5.7 as chapter 08
  asks; the Q-04 message-key seam of `docs/plan.md` §8.1 can still override the ASSEGNATO
  title in the tracking module without a migration.
* `audit_log_detail` is append-only by the same trigger as `audit_log` (the task asked for
  INSERT only on both), with DELETE allowed in a maintenance transaction so the erasure tool
  (NFR-03) and the retention job (Q-18) still work with one database role.
* Hashes are `varchar(64)` with a length CHECK instead of `char(64)` (same content, no
  Hibernate `bpchar` validation issue).
* `feature_flag.requirement_id` added to the chapter 10 §2 columns so the ADMIN screen can
  show the ENH id without a code table; `email_outbox` and `report_run` columns designed
  from chapter 10 §2 "E-mail" and ADM-09/12c §3 (no stored report file, ADR 0001).
* `CustomerUser` holds `customerId` as a plain column: no read-only `Customer` entity in
  Phase 1 (the task allowed deferring it; the `customers` module owns the entity).
* pgjdbc returns `smallint` columns as `Integer`: `EnumerationSeedIT` asserts integers.
* `MigrationsIT` applies the migrations to a schema of its own (`migrations_it`, dropped
  afterwards) through a programmatic Flyway, because `clean` is disabled on the app's
  Flyway and the app schema must stay for the other tests.
* Commit author kept as "Phase 1 agent" with the owner's e-mail, as in stage 1; nothing
  pushed.
* **Verifier fix (second run, 2026-10-17):** the first attempt left `shipment_order.code` at
  `varchar(64)` (chapter 08 §2.3) without recording that `docs/plan.md` §7.1 D-09 asks for at
  least `varchar(255)`. `V005__orders.sql` now declares `code varchar(255)` (D-09's reason —
  legacy codes up to 255 characters — is moot under Q-55, but the plan's figure costs nothing
  and the approval form sets the practical maximum); the choice is recorded in ADR 0020
  ("Code lengths") and in `db/migration/README.md`. `V005` was not merged, so editing it is
  allowed; the local instance was reset because `ls_web_test` carried the old checksum.

### Open points

* The staff one-time set-password link (ADM-01) needs a token row: stage 6 (auth) adds
  `V010` (`staff_setup_token`, or a `STAFF_PASSWORD` purpose on a dedicated table) and
  records it in ADR 0003; `staff_user.password_hash` is already nullable for it.
* `CHANGE_EMAIL` tokens (CUS-06) have no column for the new address yet; the customers
  stage decides (a column on `login_token` or a `customer_pending_email` row) with a
  migration.
* The retention and erasure code must run `SET LOCAL ls.audit_maintenance = 'on'` in its own
  transaction (ADR 0020); Phase 3 adds a test that no other code path does.
* `FeatureFlagSeedIT` reads `docs/brief/09-features.md` from the working directory: the
  build must run from the repository root (Maven does; CI does).
* The confidential-figure guard, gitleaks locally, the `/e2e` Dependabot entry and the
  owner-side production items remain as in stage 1.

### Proposed changes to shared files (for later stages / the integrator)

* `docs/plan.md` §4.4 and §5 item 4 still name `legacy_key_map`, `import_run`,
  `import_issue`, `customer.legacy_payload`, `shipment_order.amount_raw`,
  `order_line.legacy_type_raw` and "the append-only database role": trimmed by the integrator
  at the end of Phase 1 with the other Q-55 wording (§Status). §Status updated now.
* `CLAUDE.md` "Commands": add `scripts/local-db.sh psql ls_web_test` and the `clean verify`
  note for migrations (stage 6/11 fills the section).
* `deploy/` stage: document the optional `REVOKE UPDATE, DELETE` on the audit tables and the
  maintenance role in the bootstrap script (ADR 0020).

## Stage 3 — platform: settings, security chain, error model, headers, rate limiter, host routing, flags, audit, logging, retention; notifications skeleton (2026-10-23)

Resumed from the uncommitted work of an interrupted attempt (branch `wip/phase-1-stage3`
holds an older snapshot of it; nothing was taken from that branch): the working tree was
reviewed file by file against the stage instructions, 24 failing tests were fixed, the parts
the task asked for and the attempt had not finished were completed, and the five ADRs were
written. Owner decision Q-55 applies: no `ls.migration.*` / `ls.probes.*` seams exist.

### What was built

* **Typed settings** (`it.logisticasanchez.web.platform.config`, `@ConfigurationProperties`
  records, `@Validated`, registered by `PlatformPropertiesConfiguration`, which also
  provides the application `Clock`): `BusinessProperties` (`ls.business.*`: legal and brand
  name, slogan, VAT number, address, phone, WhatsApp, office e-mail, hours, Instagram, site
  URL — ADR 0027, defaults from chapter 01), `SecurityProperties` (`ls.security.sessions.*`
  customer 60 m/12 h, staff 30 m/10 h; `tokens.*` sign-in/verify/change 15 m, registration
  24 h; `staff.lockout.*` 10 failures / 15 min (PD-10); `staff.totp.enabled=false` (Q-17);
  `proxy.trust-forwarded-headers`; `hsts.enabled`; `ip-hash-key`), `SiteProperties`
  (`ls.site.hosts.*`, `ls.site.cache.*` PD-02, `ls.site.form.quantity-integer-only` PD-04),
  `CompatProperties` (`ls.compat.hosts` Q-30, `cors.allowed-origins` Q-39, `strict-json`
  PD-09), `CustomersProperties` (`max-users-per-customer=1` Q-11, registration rules Q-10 /
  Q-36, `approval.requires-verified-email` Q-28), `OrdersProperties` (Q-05, Q-06, Q-12,
  Q-24, Q-35), `TrackingProperties` (Q-03, Q-38), `BackofficeProperties` (PD-06),
  `ReportsProperties` (Q-08), `RetentionProperties` (Q-18, PD-16, login tokens 24 h, cron,
  enabled), `RateLimitProperties` (`ls.ratelimit.<key>.permits/window`), `FlagsProperties`
  (`cache-seconds`, `override.*`), `NotificationsProperties` (`sink`, `allow-list`,
  `customer-confirmations.enabled` Q-07, `from-*`, `token-encryption-key`, `directory.path`,
  `ses.region`, `worker.*`). Each seam carries its `// Q-xx` / `// PD-xx` comment.
  `RequiredSettingsCheck` (staging/prod) fails the start-up naming the missing variable:
  `LS_SECURITY_IP_HASH_KEY`, `LS_TOKEN_ENCRYPTION_KEY`, proxy trust; in `prod` also the SES
  sink and no flag overrides. `SettingsDefaultsIT`, `RequiredSettingsCheckTest`.
* **Security chain** (`platform.security`, ADR 0002): deny by default with the
  `RouteRegistry` built from every `SecurityRouteContributor` bean (the one extension point
  per module); `PlatformRouteContributor` declares the URL-map baseline (static resources,
  `/actuator/health`, `/error`, the sign-in entries of both areas, `POST /api/form`,
  `/mono/**`, the two CSRF exemptions, `noStore` for the link page); `Access`
  PUBLIC/CUSTOMER/STAFF/ADMIN; method security on; CSRF with the session repository; session
  fixation → new session; `SessionLimitsFilter` (idle per class, absolute invalidation);
  cookie `__Host-LS_SESSION` (plain `LS_SESSION` in local/test); `PlatformAuthenticationEntryPoint`
  (302 to the area's sign-in, 401 JSON for HTMX/JSON, 404 on undeclared paths) and
  `PlatformAccessDeniedHandler` (404 undeclared, 403 CSRF, 403 role);
  `ForwardedHeadersFilter` + `ClientIpResolver` (last `X-Forwarded-For` hop, one proxy);
  `HostRoutingFilter` (www → apex 301 keeping path and query; ordini. → `/area-clienti/`;
  dashboard. → `/gestione/`; apex and app. same app); `SecurityHeadersFilter` (public
  `_headers` set with `connect-src 'self'`; private nonce CSP, `no-store`; PD-02 caching;
  HSTS prod only) and `PublicEtagFilter` (ETag + 304 on public HTML/CSS/JS); `IpHasher`
  (HMAC-SHA-256 for `ip_hash` columns); `CspNonce`. Tests: `RouteMatrixIT` (generated from
  the handler mappings against `src/test/resources/security/route-matrix.tsv`),
  `DenyByDefaultIT`, `CsrfIT`, `SessionLimitsIT`, `ForwardedHeadersIT`, `HostRoutingIT`,
  `PublicHeadersIT` (reads the verbatim copy `src/test/resources/security/ls-home-public_headers`
  of `legacy/ls-home/public/_headers` and compares it with the checkout when present), `PrivateHeadersIT`,
  `PublicPagesCookieFreeIT` (real port: no `Set-Cookie` outside the private areas).
* **Error model** (`platform.web.error`, ADR 0002): `ErrorCode` (status, code, Italian
  reason; the legacy `ErrorEnum` texts reused), `ApiError` JSON schema
  `{status, code, reason, detail?, requestId, timestamp}`, `ErrorResponder` (JSON for
  `/api/**`, `/mono/**`, `/actuator/**`, HTMX, JSON-sending and JSON-accepting requests;
  HTML `templates/error/{401,403,404,429,error}.html` otherwise), `PlatformExceptionHandler`
  (400/404/405/409/415/422/429 explicit, 500 generic with the trace in the log),
  `PlatformErrorController` (`/error`, whitelabel off), `ClientErrorException`. `ErrorModelIT`.
* **Request id and access log** (`platform.web`): `RequestIdFilter` (MDC `requestId`,
  `X-Request-Id`, one access line without query string or body); `PlatformModelAttributes`
  (`business`, `cspNonce`, `htmxConfig`, `hxHeaders`); `HtmxConfig`;
  `fragments/htmx.html`. Logback ECS JSON in staging/prod
  (`logging.structured.format.console`). `LoggingWithoutBodiesIT`.
* **Rate limiter** (`platform.ratelimit`): in-memory token bucket keyed by (key, subject);
  keys `signin.link.email`, `signin.link.ip`, `staff.login`, `tracking.lookup`,
  `form.submit`, `registration` registered from `ls.ratelimit.*`; `register()` for module
  keys; `require()` → 429 with `Retry-After`. `RateLimiterTest`, `RateLimit429IT`.
* **Feature flags** (`platform.flags`, ADR 0025): `FlagKey` enum of the 25 seeded keys,
  `FeatureFlags`/`FeatureFlagService` (cache `ls.flags.cache-seconds`, start-up check that
  every key has a row, `ls.flags.override.<key>` refused in prod, ADMIN-only audited
  `toggle`, `evictCache`, `all`). `FeatureFlagServiceIT`.
* **Audit** (`platform.audit`): `AuditEntry` builder and `AuditService.record` (ids and
  non-personal values in `audit_log`, personal values only in `audit_log_detail`, request
  context when present). `AuditServiceIT` incl. the append-only trigger.
* **Domain events** (`platform.events`, ADR 0025): sealed `DomainEvent` (eight records,
  ids only) and `DomainEvents.publish`. `DomainEventsIT` (each event published once).
* **Retention** (`platform.retention`, `platform.scheduling`): `RetentionJob` interface,
  `RetentionRunner` (cron `ls.retention.cron`, off when `ls.retention.enabled=false`),
  `SchedulingConfiguration`; first job `auth.ExpiredLoginTokenRetentionJob` (24 h after
  expiry, skipping rows an undelivered e-mail references). `ExpiredLoginTokenRetentionJobIT`.
* **Notifications skeleton** (`notifications`, ADR 0015 Proposed): `EmailMessage`,
  `NotificationService.enqueue` / `OutboxNotificationService` (row in the caller's
  transaction, stamped with the application clock, raw token only as AES-256-GCM ciphertext
  through `TokenCipher`), `OutboxWorker` (batch, per-row transaction, exponential backoff,
  dead after `max-attempts`, token substituted and wiped), `OutboxWorkerScheduler`,
  `RecipientPolicy` (staging guard), `MailSender` port with `SesV2MailSender` (AWS SDK v2
  BOM 2.56.0, `sesv2` + `url-connection-client`, Netty/Apache clients excluded),
  `DirectoryMailSender` (`.local/mail/*.eml`), `InMemoryMailSender`; `MailSenderConfiguration`
  picks one by `ls.notifications.sink`. `EmailOutbox` gained `queuedAt`; `LoginTokenRepository`
  skips referenced rows. `OutboxWorkerIT`, `RecipientAllowListIT`, `StagingMailGuardIT`.
* **Configuration files:** `application.yml` (whitelabel off, compression, cookie, forwarded
  strategy none, `ls.*` env mappings), `application-local.yml` (plain cookie, directory sink,
  dev keys that are not secrets), `application-test.yml` (memory sink, worker and retention
  off, small Hikari pool), `application-staging.yml` (ECS logs, proxy trust, directory sink
  unless `LS_NOTIFICATIONS_SINK=ses` with `LS_NOTIFICATIONS_ALLOW_LIST`),
  `application-prod.yml` (ECS logs, proxy trust, HSTS, SES).
* **ADRs** 0002, 0018, 0019, 0027 (Accepted) and 0015 (Proposed), indexed.
* **Test support:** `FixtureController`/`FixtureRoutes`/`TestFixtures` (routes that exist
  only in tests, declared like a module's), `Sessions`, `MutableClock`.

### How to run it

```sh
scripts/local-db.sh start && eval "$(scripts/local-db.sh env)"
./mvnw -B -ntp clean verify                # 14 unit + 95 integration tests, Spotless check
./mvnw spotless:apply                      # format
./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls"
curl -i http://localhost:8080/actuator/health          # 200, {"status":"UP"}
curl -i http://localhost:8080/non-esiste               # 404 HTML page, X-Request-Id, CSP
curl -i -H 'Accept: application/json' http://localhost:8080/api/x   # 404 JSON schema
ls .local/mail                                         # .eml files of the directory sink
```

Verified here on 2026-10-24: `./mvnw -B -ntp clean verify` green (BUILD SUCCESS, 14 unit
and 95 integration tests), `tools/ci/guard-tracked-files.sh` ok, the packaged jar started
with the `local` profile answers health, the 404 page/JSON, the private-area redirect and
the `www` 301 without setting a cookie on public paths, `git status` clean after the commit.

### Extension points for the modules

* **Routes:** one `@Component implements SecurityRouteContributor` per module, e.g.
  `routes.permitGet("/tracking/").customer(HttpMethod.GET, "/area-clienti/ordini/**")
  .staff(HttpMethod.POST, "/gestione/ordini/{id}/approva").admin(HttpMethod.POST,
  "/gestione/impostazioni/flag")`; a route not declared answers 404. Add the line(s) to
  `src/test/resources/security/route-matrix.tsv` (`METHOD PATTERN anon CUSTOMER OPERATOR
  ADMIN`, `|` for alternatives) or `RouteMatrixIT` fails. CSRF exemptions only for
  `POST /api/form` and `/mono/**`; `noStore(...)` for a public path that must not be cached.
* **Errors:** throw `new ClientErrorException(ErrorCode.X, detail?)`; add an `ErrorCode`
  (4xx, Italian reason) when none fits; never catch-and-500.
* **Rate limits:** `rateLimiter.require(RateLimitKeys.TRACKING_LOOKUP,
  clientIpResolver.resolve(request))`; a module key: `rateLimiter.register("orders.create",
  new Limit(…))` at start-up (or a new `ls.ratelimit.*` entry proposed to the integrator).
* **Audit:** `auditService.record(AuditEntry.of(STAFF, staffId, "order.approve")
  .entity("shipment_order", id, code).change(before, after).personal(beforePd, afterPd))` in
  the business transaction.
* **Flags:** inject `FeatureFlags` and read `isEnabled(FlagKey.X)` at the single entry point
  of the feature; tests set `ls.flags.override.[key]=true`.
* **Events:** `domainEvents.publish(new DomainEvent.OrderApproved(orderId, staffId))` in the
  owning service's transaction; no subscriber in M1/M2.
* **E-mail:** build the `EmailMessage` (kind `EML-0x`, both bodies, Reply-To; `{{token}}`
  placeholder and `withTokenLink(loginTokenId, rawToken)` for links) and call
  `notificationService.enqueue(message)` inside the business transaction.
* **Retention:** a `@Component implements RetentionJob` per rule; the runner collects it.
* **Settings:** inject the record (`OrdersProperties`, …); never read `ls.*` from the
  `Environment`.
* **Templates:** private pages include `~{fragments/htmx :: meta}` in `<head>`, set
  `<body th:attr="hx-headers=${hxHeaders}">` and give inline scripts
  `th:attr="nonce=${cspNonce}"`; `${business}` is available everywhere.

### Decisions taken

* **Outbox statuses stay `QUEUED` / `SENT` / `FAILED`** (stage 2 schema) instead of the five
  states named in the stage text (NEW/SENDING/SENT/FAILED/DEAD): one worker instance with one
  transaction per row needs no `SENDING` lock, a retry is a `QUEUED` row with `attempts > 0`,
  and `FAILED` is the dead state; recorded in ADR 0015 with the upgrade path. No migration
  was edited.
* **Token-key variable is `LS_TOKEN_ENCRYPTION_KEY`** (as the stage text names it), bound to
  `ls.notifications.token-encryption-key`; the IP-hash key is `LS_SECURITY_IP_HASH_KEY`.
* **`ls.notifications.sink`** has three values — `ses`, `directory`, `memory`; the local
  "mail catcher" is the directory sink (`.eml` files), so no SMTP dependency is added now;
  an SMTP catcher adapter is a Phase 2 option if staging runs one (ADR 0015).
* **An undeclared path answers 404 even when the CSRF filter refused it first** (a `POST`
  without token to a path that does not exist): the route does not exist, nothing is
  revealed.
* **A request that sent JSON gets JSON back** in the error model, also without an `Accept`
  header (API clients and HTMX rarely send one).
* **JSON error bodies carry `Content-Type: application/json` without a charset parameter**
  (JSON is UTF-8 by definition).
* **The outbox row is stamped with the application `Clock`** (`EmailOutbox.queuedAt`), so
  tests drive the worker with `MutableClock` and no row's `next_attempt_at` depends on the
  wall clock.
* **Public pages set no cookie:** two causes found by a smoke run of the packaged jar (MockMvc
  cannot show the container's session cookie): the `hxHeaders` model attribute read Spring
  Security's deferred CSRF token on every handler, actuator included, which creates the
  session that stores it — now computed only inside the private areas; and the default saved
  request (`HttpSessionRequestCache`) created a session on every refused anonymous request
  whose `Accept` was not exactly `application/json` (curl's `*/*`, a browser's 404) — the
  chain now uses `NullRequestCache` (after sign-in the user lands on the area's home). Hence
  the real-port test `PublicPagesCookieFreeIT` over several paths and `Accept` headers.
* **Error-page fragments are named `pageHead` / `pageBody`**: a fragment named `body`
  collides with the `<body>` element in Thymeleaf's fragment selector (the cause of the
  interrupted attempt's template failures).
* **Hikari pool of 3 in the test profile:** the cached test contexts share one PostgreSQL
  with `max_connections = 100`; the default 10 per context exhausted it.
* **`StagingMailGuardIT` starts the real servlet app on port 0** with the staging profile and
  command-line arguments standing for the environment variables; the SES client is never
  built in a test (the allow-list path is proven on `RecipientPolicy` alone).
* **New Italian texts** (owner review at the next gate): the CSRF, 405, 415 and 429 reasons
  and the five error-page titles/leads (ADR 0002).
* HSTS is sent only in `prod` (`ls.security.hsts.enabled`), where TLS terminates at Caddy;
  `includeSubDomains` waits for the deploy stage.
* The stage-1 bootstrap `BootstrapSecurityConfig` was replaced by `SecurityConfig`.

### Open points

* The `auth` stage (6) adds the login handlers to the chain (staff form login on
  `POST /gestione/login`, customer one-time-token `POST /area-clienti/accesso`), stamps
  `SessionLimitsFilter.AUTHENTICATED_AT` at login, registers the `UserDetailsService` and
  removes the `UserDetailsServiceAutoConfiguration` exclusion; the per-account lock-out
  (PD-10) lives there with `staff.login` as the per-IP limit.
* `/gestione/` and `/area-clienti/` are declared public (sign-in entries) but have no handler
  yet: they answer 404 until stage 6; the matrix table gains their lines then.
* `ls.notifications.sink=catcher` (SMTP) is not implemented; `directory` is the local
  catcher. The retention job for outbox bodies (Q-18) and the `report_run`/audit jobs come
  with their modules on the `RetentionRunner`.
* `includeSubDomains` on HSTS, `X-Robots-Tag: noindex` on staging and the `REVOKE` option on
  the audit tables belong to the deploy stage (ADR 0022).
* The record-style accessors of `BusinessProperties` are read by Thymeleaf through SpEL
  (`${business.brandName}`): works on Spring Framework 7 (asserted by `ErrorModelIT`).
* `RouteMatrixIT` reads `src/test/resources/security/route-matrix.tsv` from the working
  directory: the build runs from the repository root (Maven and CI do). `PublicHeadersIT`
  needs no legacy checkout since the verification run of 2026-10-24: a verbatim copy of
  `ls-home@9b78785 public/_headers` ships as a test resource (the only kind of file that may
  be copied from the legacy) and is compared with the checkout when it is present.
* Still from stages 1–2: confidential-figure guard, gitleaks locally, `/e2e` Dependabot
  entry, Q-55 wording to trim in `docs/plan.md`, owner-side production items.

### Proposed changes to shared files (for later stages / the integrator)

* `deploy/.env.example` (stage 10): `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`,
  `SERVER_PORT`, `LS_SECURITY_IP_HASH_KEY`, `LS_TOKEN_ENCRYPTION_KEY`, `LS_NOTIFICATIONS_SINK`,
  `LS_NOTIFICATIONS_ALLOW_LIST`, `LS_NOTIFICATIONS_FROM_ADDRESS`, `AWS_REGION`,
  `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `LS_BUSINESS_*` overrides.
* `CLAUDE.md` "Commands": the `clean verify` note and `scripts/local-db.sh env` (stage 11).
* `docs/brief/11-open-questions.md`: nothing new; the stage applies the recorded defaults.
* Message bundles (stage 5): the error-page titles and leads can move from the templates to
  `messages.properties` keys `error.<status>.title` / `.lead` once the bundle exists.

## Stage 4 — auth: staff login (ADM-01) and the customer magic-link sign-in (CUS-01) (2026-10-24)

Owner decision of 2026-10-24 («alleggerire») applied: the stage builds exactly what the task
and the parity-matrix rows ask, with the lightest design that satisfies them.

### What was built

* **Schema** `V010__staff_setup_tokens.sql`: table `staff_setup_token` (SHA-256 of the
  one-time set-password token, 24 h, single use; ADR 0003); `email_outbox.kind` also accepts
  `EML-S1` (staff set-password e-mail) and the constraint `ck_email_outbox_token_pair` is
  dropped so a token ciphertext may stand without a `login_token` reference. Expectation
  files, `SchemaConformanceIT`, `MigrationsIT`, `ApplicationContextIT` and the migration
  README updated.
* **Staff authentication** (`it.logisticasanchez.web.auth`, ADR 0003): Spring Security form
  login on `POST /gestione/login` with the page `/gestione/` («Login», `username`/`password`,
  one error «Username o password errate.» for every failure, redirect `/gestione/?errore=1`);
  `StaffUserDetailsService` + `StaffPrincipal` (roles `ROLE_ADMIN`/`ROLE_OPERATOR` from
  `StaffRole`, disabled until a password is set, locked until `locked_until`); bcrypt cost 12
  behind a `DelegatingPasswordEncoder` (`{bcrypt}` prefix); `PasswordPolicy` (≥ 12 characters,
  not in `auth/common-passwords.txt`: SecLists 10k list + the ≥ 12-character entries of the
  NCSC 100k list, 11 190 entries, MIT); lock-out per account (`StaffAccountService.recordFailure`,
  PD-10 threshold/duration from `ls.security.staff.lockout.*`, reset on success) and per IP
  (`StaffLoginRateLimitFilter` with the platform key `staff.login`, 429 + `Retry-After` before
  any password check); `StaffLoginSuccessHandler` (counters cleared, `last_login_at`,
  `SessionLimitsFilter.AUTHENTICATED_AT` stamped, redirect `/gestione/`) and
  `StaffLoginFailureHandler`; session id rotated (`SessionFixationProtectionStrategy`, no
  attribute migrated); idle 30 min / absolute 10 h enforced by the stage-3 filter; logout
  `POST /gestione/logout` (CSRF) → `/gestione/`. `GET /gestione/` renders the login page for an
  anonymous visitor and a stub home («Gestione … in arrivo», logout button) for a staff session.
* **Provisioning**: `StaffAccountService.create` (account without password + setup link),
  `createByAdmin` (`@PreAuthorize("hasRole('ADMIN')")`, link e-mailed through the outbox as
  `EML-S1` with the raw token only encrypted, audited `staff.create`), `issueSetupLink`,
  `peekSetupLink` (the GET), `setPassword` (the POST: policy, hash, counters cleared, link
  consumed). Page `/gestione/imposta-password?t=…` (GET renders, POST consumes; «Il link non è
  più valido» otherwise; `Referrer-Policy: no-referrer`). Command **`create-staff-user`**
  (`CreateStaffUserCommand`, an `ApplicationRunner` + `ExitCodeGenerator`): `Application.main`
  detects the argument, starts the app once on an ephemeral loopback port with the outbox
  worker and retention off, prints `https://<site-url>/gestione/imposta-password?t=<token>`
  and exits (0 / 1 username taken / 2 bad arguments); never reads or prints a password.
* **Customer sign-in** (ADR 0004): `GET /area-clienti/` e-mail form (legacy copy adapted:
  «Inserisci la tua e-mail», hint without its second sentence, privacy link); `POST
  /area-clienti/` trims and lower-cases, rate-limits per IP (`signin.link.ip`) and per e-mail
  (`signin.link.email`), always renders the same page («Ti abbiamo inviato un'email: segui il
  link per continuare») and, in one transaction (`CustomerSignInService.issue`), issues a
  `SIGN_IN` token (15 min) + EML-01 sign-in e-mail for a known `customer_user` (verified,
  unverified or declined alike, D-69) or a `REGISTER` token (24 h, `email_hash` = HMAC of the
  e-mail through `EmailHasher`, no customer row) + registration e-mail for an unknown
  address; nothing for a disabled login. `LoginTokenService` (`issue`, `issueRegistration`,
  `peek`, `consume`, `validity`) serves every purpose incl. `VERIFY_EMAIL` and `CHANGE_EMAIL`
  for Phase 2; tokens are 256 random bits in base64url, stored as SHA-256 (`Tokens`), stamped
  with the application clock. `GET /area-clienti/accesso?t=…` renders the «Accedi» page
  without consuming (HEAD likewise); `POST /area-clienti/accesso` consumes, calls the
  `CustomerSignInListener` hooks (none in Phase 1; the `customers` module verifies the e-mail
  there), opens the session through `CustomerSessionService.signIn` (fixation strategy,
  registry, CSRF regenerated, context saved, `AUTHENTICATED_AT` stamped) and redirects to
  `/area-clienti/ordini/` (stub home with logout) without the token; used/expired → «Il link
  non è più valido» with the request form. `GET /area-clienti/registrazione/?t=…` validates a
  `REGISTER` token and renders a stub without consuming. Logout `POST /area-clienti/logout`.
  **`CustomerSessionService.endAllSessions(customerUserId)`** expires every session of the
  login in the `SessionRegistry`; the concurrent-session filter invalidates them at their
  next request and sends them to `/area-clienti/` (401 JSON for HTMX/JSON).
* **E-mails** (`AuthMails`): EML-01 sign-in and registration variants and EML-S1 from
  `messages.properties` (`mail.*`), text + minimal HTML, link with `{{token}}` substituted by
  the outbox worker; the branded templates come with Phase 2 `notifications` (ADR 0015).
* **Platform changes**: new extension point `SecurityChainContributor` applied by
  `SecurityConfig` after its baseline (`AuthChainContributor` adds the provider, form login,
  logouts for both areas, `maximumSessions(-1)` with the registry and the expired-session
  strategy, the shared `SecurityContextRepository` and `SessionAuthenticationStrategy`);
  `RouteRegistry.isSignInEntry` gains `/gestione/imposta-password`; the sign-in entries moved
  from `PlatformRouteContributor` to `AuthRouteContributor`; `SessionLimitsFilter.isAuthenticated`
  / `isStaff` public; `application.yml` no longer excludes `UserDetailsServiceAutoConfiguration`
  (it backs off because `StaffUserDetailsService` exists); `EmailMessage.withSecret` and
  `EmailOutbox.attachToken` with a null `login_token_id`; `LoginToken` factories with a
  `createdAt`; `RouteMatrixIT` exempts the sign-in entries from the "never 200 anonymous" rule.
* **Templates** `templates/auth/*.html` (minimal, restyled in stage 5) and the `auth.*` /
  `mail.*` keys of `messages.properties`.
* **Tests**: `PasswordPolicyTest`, `SessionCookieConfigurationTest` (unit, ADM-01);
  `StaffLoginIT` (login page, same redirect for every failure, bcrypt hash, rotation + stamp +
  idle 30 min, OPERATOR on an ADMIN route 403, logout, absolute 10 h clock-driven, lock-out
  clock-driven, 429 per IP, no login without a password), `StaffLoginRealServerIT` (cookie
  flags and rotation on a real server), `CreateStaffUserCommandIT` (link printed, GET renders,
  policy, mismatch, set once, login, link dead, 24 h expiry, exit codes),
  `StaffAccountServiceIT` (ADMIN creates, outbox EML-S1 encrypted + wiped, audit, OPERATOR
  refused) — all `@Tag("ADM-01")`; `CustomerSignInIT` (every bullet of the parity-matrix row:
  same page for known/unknown/unverified/declined, trimming, SIGN_IN vs REGISTER with
  `email_hash` and 24 h, hashed storage, two GETs + HEAD never consume, POST once, second POST
  refused, expiry, no-store/no-referrer, redirect without token, 429 per e-mail and per IP,
  registration page, logout, `endAllSessions`) and `LoginTokenServiceIT` (VERIFY_EMAIL /
  CHANGE_EMAIL) — `@Tag("CUS-01")`. `route-matrix.tsv` gained the nine auth lines.
  `./mvnw -B -ntp clean verify`: 23 unit + 121 integration tests green.

### How to run it

```sh
scripts/local-db.sh start && eval "$(scripts/local-db.sh env)"
./mvnw -B -ntp clean verify
./mvnw -B -ntp -DskipTests package
# first ADMIN (prints the one-time set-password link; never a password)
java -jar target/ls-web-0.1.0-SNAPSHOT.jar create-staff-user --username=admin --role=ADMIN --name="Nome Cognome" \
  --spring.profiles.active=local --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls
# or: ./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=create-staff-user,--username=admin,--role=ADMIN
# the link is printed as https://logisticasanchez.com/gestione/imposta-password?t=…: locally replace the
# origin with http://localhost:8080, open it, set a password (≥ 12 characters), then log in at /gestione/
SERVER_PORT=8080 java -jar target/ls-web-0.1.0-SNAPSHOT.jar --spring.profiles.active=local \
  --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls
# customer sign-in locally: open http://localhost:8080/area-clienti/, type an e-mail; within ~10 s the
# directory sink writes .local/mail/<timestamp>-<n>.eml — the link is in its text body:
grep -o 'https://[^ ]*?t=[A-Za-z0-9_-]*' "$(ls -t .local/mail/*.eml | head -1)"
# replace the origin with http://localhost:8080 and open it: GET shows «Accedi», the button signs in.
```

Verified here: the packaged jar created `smoke-admin`/`smoke-op` in `ls_web` and printed the
link (Tomcat on an ephemeral port, exit 0; duplicate username exit 1); the running app answered
`/area-clienti/` (200, no-store, private CSP, `LS_SESSION` HttpOnly SameSite=Lax), the request
`POST` with a spaced mixed-case unknown e-mail (200, «Ti abbiamo inviato un'email…»), the
directory sink wrote the registration e-mail with the normalised recipient, the registration
link rendered the stub with `no-referrer`, `/gestione/` rendered «Login», an unknown `t`
rendered «Il link non è più valido».

### Routes added (all in `route-matrix.tsv`)

| Route | Access | Handler |
|---|---|---|
| `GET /gestione/` | public (login page) / staff (stub home) | `StaffController.home` |
| `POST /gestione/login` | public, CSRF | Spring Security form login |
| `POST /gestione/logout` | staff, CSRF | Spring Security logout → `/gestione/` |
| `GET` / `POST /gestione/imposta-password?t=` | public, CSRF on POST | `StaffController` |
| `GET` / `POST /area-clienti/` | public, CSRF on POST | `CustomerSignInController` |
| `GET` / `HEAD` / `POST /area-clienti/accesso?t=` | public, CSRF on POST | `CustomerSignInController` |
| `GET /area-clienti/registrazione/?t=` | public | stub (Phase 2 `customers`) |
| `POST /area-clienti/logout` | customer, CSRF | Spring Security logout → `/area-clienti/` |
| `GET /area-clienti/ordini/` | customer | stub (Phase 2 `orders`) |

### Decisions taken

* **Staff set-password links have their own table** `staff_setup_token` instead of a
  `STAFF_SET_PASSWORD` purpose on `login_token` (whose CHECKs tie every non-REGISTER row to a
  `customer_user`); V010 also lets the outbox carry that token encrypted without a
  `login_token` reference and accept the kind `EML-S1` (ADR 0003; ADR 0015 to list it).
* **Form login through Spring Security** (filter, fixation strategy, CSRF strategy, context
  repository) instead of a hand-written controller; the customer link `POST` reuses the same
  `SessionAuthenticationStrategy` and `SecurityContextRepository` beans, so both sign-ins
  rotate the session and register it the same way. A second chain extension point
  (`SecurityChainContributor`) was added for this; the authorisation stays the route table.
* **Every staff login failure answers the same redirect and sentence** (wrong password,
  unknown user, locked, no password yet, deactivated): no enumeration, no lock-out signal.
* **Lock-out counts failures of existing accounts only**; unknown usernames are covered by the
  per-IP limit. After an expired lock the count restarts at one.
* **The common-password list** is the SecLists 10k list plus the ≥ 12-character entries of
  the NCSC 100k list (11 190 entries, 92 KB, MIT, attribution in the file header): the 12-character
  minimum would refuse almost every entry of the 10k list alone, so the long entries make the
  check meaningful. Compared case-insensitively after trimming.
* **`create-staff-user` runs the real servlet app on an ephemeral loopback port** rather than a
  non-web context: the modules' beans need the web context (`LocaleResolver` for
  `ErrorResponder` failed in a `WebApplicationType.NONE` run); the server lives for the seconds
  the command takes, the outbox worker and retention are off, the process exits with the
  command's code.
* **`EmailHasher` reuses the IP-hash key** (`ls.security.ip-hash-key`) with a domain separator
  for `login_token.email_hash`: no new environment variable (ADR 0004).
* **`CustomerSignInListener`** (an `auth` SPI, zero beans in Phase 1) is how the `customers`
  module learns that a sign-in link was used (sets `email_verified_at`, moves
  `PENDING_VERIFICATION` on); chosen over a new `DomainEvent` so that ADR 0025's "no subscriber
  in M1/M2" statement about the platform events stays true.
* **A disabled `customer_user` gets no e-mail** but the same page (the schema comment says a
  disabled login cannot ask for a link).
* **The invalid-link pages answer 200** with the request form (not an error status): the visitor
  did nothing wrong and the next step is on the page.
* **`?errore=1` / `?impostata=1`** query flags on the login page (a bare `?errore` is not a
  parameter to every container).
* Stub pages for `/gestione/` (staff home), `/area-clienti/ordini/` and
  `/area-clienti/registrazione/` say the area is coming; Phase 2 replaces them and their matrix
  lines.
* **New Italian texts** for the owner's review at the next gate: login page, set-password
  page, link-invalid pages, request page («Inserisci la tua e-mail», sent message), «Accedi»
  page, stub texts, the three e-mails (`mail.*`).

### Open points

* `dev-seed` (stage 6) creates staff users with random passwords printed once and customers in
  every state; the auth stage leaves no seeded account.
* The staff e-mail kind `EML-S1` is outside the chapter 09 catalogue: ADR 0015 lists it when the
  Phase 2 `notifications` module completes the catalogue and the branded templates replace
  `AuthMails`' minimal HTML.
* The `customers` module (Phase 2) implements `CustomerSignInListener` (verify on sign-in,
  chapter 08 §2.4), calls `CustomerSessionService.endAllSessions` on decline and e-mail
  change, consumes the `REGISTER` token in its registration `POST`, and replaces the
  `/area-clienti/registrazione/` stub; `orders` replaces `/area-clienti/ordini/`; `backoffice`
  replaces the `/gestione/` home and adds the account screen over `StaffAccountService`.
* An E2E `[CUS-01]` / `[ADM-01]` spec (request link → open page → «Accedi» → session; «Login»
  page with a real password field) comes with the E2E stage (Playwright) once the layout exists.
* The session registry is in memory (one instance, ADR 0001); a second instance would need
  Spring Session or a shared registry — not an M1 concern.
* The absolute customer-session limit (12 h) is enforced by the stage-3 filter and asserted
  for staff in `StaffLoginIT`; the customer case is `SessionLimitsIT` (stage 3) with a session
  stamped by the sign-in (asserted in `CustomerSignInIT`).
* Still from stages 1–3: confidential-figure guard, gitleaks locally, `/e2e` Dependabot entry,
  Q-55 wording to trim in `docs/plan.md`, owner-side production items.

### Proposed changes to shared files (for later stages / the integrator)

* `messages.properties`: the `auth.*` and `mail.*` keys were added by this stage (Phase 1
  agent); stage 5 may regroup them per screen with the copy-deck keys.
* `CLAUDE.md` "Commands" (stage 11): `java -jar app.jar create-staff-user --username=… --role=ADMIN`
  for the first ADMIN; `.local/mail` for sign-in links locally.
* `deploy/` runbook (stage 10): the first-ADMIN step runs the command inside the app container
  with the production environment and the owner opens the printed link once.
* ADR 0015: add `EML-S1` to the catalogue table.

## Stage 5 — Layout, static resources, `config.js`, message bundle, `dev-seed` (2026-10-24)

### What was built

* **Static resources of the legacy site** (`legacy/ls-home/public` at the commit of `PROMPT.md`)
  copied unchanged into `src/main/resources/static/`: `css/`, `js/` (except `config.js`, now
  generated), `img/`, `fonts/`, the icons and `apple-touch-icon.png`, `favicon.*`,
  `site.webmanifest`, `robots.txt`, `.well-known/security.txt` — same URLs, same bytes
  (`diff -r` against the checkout is clean). They are served by Spring Boot's static handler with
  the stage-3 cache rules (fonts immutable one year, images one week, the rest `no-cache` + ETag)
  and the public header set; `site.webmanifest` goes through `WebManifestController` only to get
  the `application/manifest+json` type the default type registry does not know.
* **`/js/config.js` generated from configuration** (`ConfigJsController`, PUB-02): the same
  exports as the legacy file (`BUSINESS`, `TRACKING`, `waLink`, `mailLink`, `escapeHtml`, `icon`)
  plus `FORMS`; values from `ls.business.*` (brand and legal name, VAT, address, phone, WhatsApp,
  office e-mail, hours, site URL), `TRACKING.endpoint = /mono/api/order/trace/state` (same
  origin, no more `app.` host) and `FORMS.endpoint = /api/form`. Values are emitted as escaped JS
  string literals; the answer is `text/javascript` whatever the `Accept` header, like a file.
* **Layout fragments** `templates/layout/public.html` — `htmlHead(seo)`, `siteHeader(nav)`,
  `cta`, `siteFooter` — ported from `legacy/ls-home/partials/{head,header,cta,footer}.html`:
  business data from `${business}`, every text from the message bundle, «Portale ordini» /
  «Effettua un ordine» → «Area clienti» `/area-clienti/` (chapter 06 §7.1), the active-menu rule
  of `tools/sync.mjs` as `Layout.ariaCurrent(page, key)` (`aria-current="page"` on the exact
  key, `"true"` on an ancestor section, attribute omitted otherwise) on every `data-nav` link,
  `{{year}}` as `Layout.year()`. `htmlHead(false)` leaves out the JSON-LD and the speculation
  rules (error pages); the speculation-rules script is also omitted under the private nonce CSP.
  `templates/layout/private.html` — `htmlHead`, `siteHeader(area, user, logoutUrl)`, `siteFooter`
  — is the base of the customer area and back office: same design system (`main.css`, fonts,
  icons), `noindex`, compact header with the brand and the area name («Area clienti» /
  «Gestione»), «Torna al sito» or the logout button, the HTMX meta of stage 3, no script at all
  (the strict CSP of ADR 0018 stays script-free until a page needs one with the nonce). The
  stage-4 `auth` pages and the error pages (`error/page.html`, now with the «notfound» block of
  the static 404; new `error/410.html` and `ErrorCode.GONE` for PUB-04) use them. `Layout` is a
  model attribute of every page (`PlatformModelAttributes`) and of the error renderer.
* **`messages_it.properties`** replaces `messages.properties` (`spring.web.locale=it` fixed, no
  system-locale fallback): every UI text of the layout (`layout.*`), the error pages (`error.*`),
  the auth screens and e-mails (`auth.*`, `mail.*`), seeded with the wording of the legacy
  partials and portal (reader notes 03, 04, 06). `MessageBundleTest` fails on any `#{key}` or
  `#messages.msg('key')` of a template that the bundle does not define.
* **`dev-seed` command** (`dev.DevSeedCommand`, `@Profile({"local","test"})`): `java -jar app.jar
  dev-seed --spring.profiles.active=local` creates `admin.seed` (ADMIN) and `operatore.seed`
  (OPERATOR) with random 20-character passwords printed once (bcrypt in the database; re-running
  rotates them), and two synthetic customers with their `customer_user` — `cliente.attivo@cliente.example`
  (ACTIVE) and `cliente.in-attesa@cliente.example` (PENDING_APPROVAL) — left alone when present.
  Outside `local`/`test` the bean does not exist: the app prints «dev-seed è disponibile solo con
  il profilo local o test.» and exits with code 2 (the required-settings check of the real
  profiles fails even earlier without the environment).
* **Tests**: `LayoutTest` (unit, PUB-02: `aria-current` on the 14 nav keys, ancestor rule, year as
  digits), `ConfigJsControllerTest` (unit: literal escaping incl. `</script>`), `MessageBundleTest`
  (unit), `LayoutIT` (PUB-02: a context with overridden phone, WhatsApp, office e-mail, VAT and
  site URL — header, footer and `config.js` show the overrides and never the defaults, one
  WhatsApp number per page, «Area clienti» link and no «Portale ordini», no `aria-current` on an
  error page, no SEO scripts there, the 13 static URLs with their types and cache rules, the
  private layout without `<script>` and with the HTMX meta), `DevSeedCommandIT` (ADM-01, CUS-01:
  profiles, passwords printed and valid for login, roles, bcrypt, customers in the two states with
  one login each, second run rotates and keeps). `route-matrix.tsv` gained `/js/config.js` and
  `/site.webmanifest`. `./mvnw -B -ntp verify`: 35 unit + 139 integration tests green.

### How to run it

```sh
scripts/local-db.sh start && eval "$(scripts/local-db.sh env)"
./mvnw -B -ntp verify
./mvnw -B -ntp -DskipTests package
# synthetic accounts for a local session (passwords printed once; local and test profiles only)
java -jar target/ls-web-0.1.0-SNAPSHOT.jar dev-seed --spring.profiles.active=local \
  --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls
SERVER_PORT=8080 java -jar target/ls-web-0.1.0-SNAPSHOT.jar --spring.profiles.active=local \
  --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls
# http://localhost:8080/gestione/ (admin.seed), http://localhost:8080/area-clienti/ (cliente.attivo@cliente.example:
# the link lands in .local/mail), http://localhost:8080/js/config.js, http://localhost:8080/pagina-che-non-esiste/
```

Verified here with the packaged jar: `dev-seed` printed the four lines and exit 0; the running
app served `/gestione/` and `/area-clienti/` with the private layout (nonce CSP, `no-store`), the
404 page with the public header, footer «© 2026 …» and no `aria-current`, `/js/config.js` as
`text/javascript` `no-cache`, `/site.webmanifest` as `application/manifest+json`, fonts
immutable, PNG one week, `security.txt` as `text/plain`.

### Decisions taken

* **Fragment names differ from tag names** (`htmlHead`, `siteHeader`, `siteFooter`): a Thymeleaf
  selector `head` matches the `<head>` element before the `th:fragment="head"` block and nested
  the whole head; found by the first run of the restyled pages.
* **Error pages carry no SEO script** (`htmlHead(false)`): no JSON-LD and no speculation rules on
  a `noindex` page; `main.js` stays so the menu works. `ErrorModelIT` now forbids inline scripts
  (`<script>` without `src`, `application/ld+json`) instead of any `<script`.
* **403 keeps the legacy sentence** «Operazione non consentita» as title (stage 3 wording, asserted
  by `DenyByDefaultIT`); the lead explains the expired-session case.
* **`config.js` is a controller, not a template**: the file is small, the values are a dozen
  strings, and a template engine for JavaScript would need its own escaping rules anyway.
* **`site.webmanifest` type**: `ResourceHandlerRegistration.setMediaTypes` no longer exists in
  Spring 7 and the default registries (Spring `mime.types`, servlet context) do not know
  `webmanifest`; a two-line controller is simpler than a custom resource handler.
* **The footer year is a string**: `#{…({0})}` is a `MessageFormat`, which prints an `int` as
  «2.026» in Italian.
* **`dev-seed` writes `customer` rows by SQL** (like the auth tests): the `customer` entity belongs
  to the Phase 2 `customers` module; the seed grows with the modules (customers in every state,
  orders in every order and tracking state — `CLAUDE.md`).
* **`.well-known/security.txt` stays the copied file** (task: copy unchanged); PUB-02 wants its
  contact to follow `ls.business.office-email` — the `site` module renders it from configuration
  when it takes over `robots.txt`, `sitemap.xml` and the redirect table.
* **New Italian texts** for the owner's review at the next gate: private header («Torna al
  sito», «Collegato come …»), error leads (401, 403, 410, 429, 500, support line), `dev-seed`
  console lines.

### Open points

* The public pages themselves (home, services, zones, blog, tracking, quote, contacts, 404 routing
  of `_redirects`, sitemap) are the Phase 2 `site` module: this stage ships the fragments they
  include and the assets they reference; `aria-current` is exercised by the unit test and by the
  error page (no key) until a public page passes a key.
* `security.txt` from configuration (see Decisions); the `Expires` line of the copied file is
  2027-10-01.
* E2E (Playwright) over the layout — drawer, mega menu, action bar at phone width, axe on
  `/gestione/` and `/area-clienti/` — comes with the E2E stage.
* `htmx.min.js` is not vendored yet under `/js/` (no page uses HTMX in Phase 1); the private
  layout includes the meta tags only. ADR 0001 pins htmx 2.0.11 for the module that first needs it.
* Still from stages 1–4: confidential-figure guard, gitleaks locally, `/e2e` Dependabot entry,
  Q-55 wording to trim in `docs/plan.md`, owner-side production items, ADR 0015 `EML-S1` line.

### Proposed changes to shared files (for later stages / the integrator)

* `CLAUDE.md` "Commands" (stage 11): `java -jar app.jar dev-seed --spring.profiles.active=local`
  next to `create-staff-user`.
* `docs/plan.md` §4.1 `platform` row: `config.js` moved from `site` to `platform` (generated here);
  `site` keeps `security.txt`, `robots.txt`, `sitemap.xml`, the redirect table and `/grazie/`.
* `messages_it.properties`: modules add their `<module>.*` keys; the file is the only locale.

## Step B1 — Public site, tracking and compatibility API (2026-10-24)

### What was built

* **`site` module — the 25 pages of the static site** (`it.logisticasanchez.web.site`, PUB-01):
  `tools/port-ls-home-pages.py` turns every `legacy/ls-home/public/**/index.html` into
  `templates/site/<key>.html` (and `404.html` into `templates/error/404.html`): the four partial
  blocks become the stage-5 fragment calls (`htmlHead`, `siteHeader(<data-page>)`, `cta`,
  `siteFooter`), the rest of the page — title, description, canonical, Open Graph, JSON-LD,
  `<body data-page>`, `<main>` — is verbatim. Business data go through `${business}` (canonical,
  `og:url`, `og:image` and every JSON-LD URL from `business.siteUrl`; `tel:`, `wa.me`, `mailto:`,
  visible phone/e-mail/VAT/Instagram, the contact page's meta description); the home hero's
  «Effettua un ordine» → «Area clienti» `/area-clienti/`. JSON-LD blocks render with text
  inlining and the new `Layout.json()` helper (Thymeleaf's JavaScript inlining writes `\/`).
  `--check` reports drift from a fresh port; the templates are committed, so the build never
  needs the checkout. `SitePages` is the inventory (path, key, template, indexable):
  `SitePageMappings` registers `GET <path>` for every page programmatically, `SiteRouteContributor`
  declares them, `SitePageController` renders the template (and sends `X-Robots-Tag` on
  `/grazie/`).
* **Redirects** (PUB-04, ADR 0013): `SiteRedirectFilter` after host routing — the ten
  `_redirects` rules (the `/mono/*` proxy line excluded), `/index.html` → `/`, every page's URL
  without the trailing slash and its `index.html` form, 301 keeping the query string. Host rules
  stay in the platform's `HostRoutingFilter`.
* **Generated files** (PUB-03): `SiteMetaController` — `sitemap.xml` (24 indexable pages, home
  first then alphabetical, priorities 1.0/0.3/0.8/0.6, `lastmod` = the inventory's content date,
  byte-equal to the legacy file modulo whitespace), `robots.txt` (legacy text, configured site
  URL), `/.well-known/security.txt` and `/security.txt` (`Contact` = `ls.business.office-email`,
  `Expires` = first day of the month eleven months ahead, renewed by the app). The copied static
  `robots.txt` and `security.txt` are removed. `SecurityHeadersFilter` now sends
  `X-Robots-Tag: noindex, nofollow` on `/area-clienti/`, `/gestione/`, `/api/`, `/mono/`.
* **404 page** (PUB-09): the legacy `404.html` is the app's `error/404.html` (through the error
  model, status 404 for every unknown URL and for `/404.html` itself), with `htmlHead(false)` and
  `siteHeader('404')`; `/grazie/` is `noindex` (meta + header) and out of the sitemap.
* **`POST /api/form`** (PUB-06, PUB-07, PUB-11, EML-08; ADR 0014): `FormController` runs the
  `form.mjs` pipeline — JSON mode on `Accept: application/json` (`{"ok":true}` /
  `{"ok":false,"error":…}`), native mode otherwise (303 → `/grazie/`, or the «Invio non riuscito»
  page `site/form-error.html` with the same status), 413 over 20,000 characters, honeypot
  `bot-field` → fake success, 400 `unknown_form`, 422 `invalid:<field>` in the legacy check
  order, 502 `send_failed`. New in the monolith: `Origin`/`Referer` check against the site's
  origins or the request's own origin (403 `forbidden`), rate limit `form.submit` per client IP
  (429 `too_many_requests` + `Retry-After`). `FormValidator` ports the field table of chapter
  06 §5.5–5.6 with the deliberate fix: over-long values and CAPs that are not exactly five
  digits are refused (422) instead of truncated; decimal comma in `numero` kept (PD-04 seam
  `ls.site.form.quantity-integer-only`). `LeadService` writes the `lead` row (`LeadRepository`,
  plain SQL: typed columns, `extras`, `payload` jsonb, `ip_hash`, `user_agent`, `retain_until`
  from `ls.retention.leads-months`) and queues the EML-08 e-mail (`FormMail` = `compose()` of
  `form.mjs`: subject, text, HTML with the Italian-mobile WhatsApp rule, Europe/Rome footer,
  Reply-To = customer, recipient = office e-mail) in one transaction, then publishes
  `DomainEvent.LeadStored`.
* **CAP dataset** (PUB-08): `site.zones.DeliveryZones` — prefixes, regions, zones, minor islands,
  Venice lagoon and `lookup()` as `eta.js`; `DeliveryZonesTest` parses `static/js/eta.js` and
  fails on any drift. Legends and tables stay verbatim HTML in M1.
* **`tracking` module — compatibility API** (`it.logisticasanchez.web.tracking`; ADR 0010, 0011):
  `TrackingState` and `AccessoryService` entities over the V004/V009 tables; `ShipmentCode`
  (whitespace removed, case-folded by default — Q-03 seam `ls.tracking.match.case-insensitive`);
  `TrackingLookupService` (one SQL statement over `shipment_order` joined with `tracking_state`,
  approved orders only → temporary codes of pending orders and declined orders answer nothing,
  Q-38; result = label + `tracking_updated_at`); `CompatApiController`: `POST
  /mono/api/order/trace/state` → `200 {"status": <label>}` (+ `updatedAt` when known), `200
  {"status":"Non gestito"}` for anything unmatched, 400 (platform JSON schema) for a missing,
  blank or non-string `codice`, rate limit `tracking.lookup` per IP (429), unknown JSON fields
  ignored (PD-09 seam `ls.compat.strict-json`); `GET /mono/api/enumeration/statoTracking` and
  `/servizioAccessorio` byte-equal to chapter 02 §9. `CompatCorsFilter` on the three kept paths
  only: exact origins of `ls.compat.cors.allowed-origins`, `GET, POST, OPTIONS`, `Content-Type`,
  max-age 3600, no credentials, 204 on an allowed preflight, no CORS header for any other origin.
  `MonoGoneController` on `/mono/**`: 410 JSON (`detail` = «Servizio non più disponibile: usa
  https://logisticasanchez.com/area-clienti/») for every retired path and method, the Italian
  page `compat/gone.html` (status 410, noindex, «Link non più valido» → «Vai all'area clienti»)
  for `GET /mono/email-confirm`, `/mono/report`, `/mono/download-report`,
  `/mono/request-success.html`, `/mono/mono/*.html` on every host, and
  `/mono/img/logistica-sanchez-logo.jpg` served as `image/png` from `img/brand/logo-square.png`.
* **`/tracking/`** is the ported page (site module) with the ls-home widget over the local
  endpoint of `config.js` (TRK-01 server side; the E2E over the six states is the E2E stage).
* **Shared files touched**: `BusinessProperties.phoneDisplay()` (phone with non-breaking spaces,
  as the pages print it), `Layout.json()`, `SecurityHeadersFilter` (`X-Robots-Tag`),
  `layout/public.html` (JSON-LD via `layout.json()`), `messages_it.properties` (`site.form.error.*`,
  `compat.gone.*`), `route-matrix.tsv` (+34 lines), `CsrfIT` (the exempt endpoints now have
  handlers: 400 instead of 404), `LayoutIT` (+1 test: PUB-02 on `/contatti/`).
* **Tests** (122 unit + 333 integration, `./mvnw -B -ntp verify` green on the local PostgreSQL):
  `SitePagesTest`, `DeliveryZonesTest` (PUB-08: the §5.3 cases + `eta.js` parity),
  `FormValidatorTest` (PUB-06/07: 47 cases — field rules, check order, over-long refusals,
  decimal comma seam), `FormMailTest` (EML-08: the chapter 06 plain-text sample verbatim,
  contact e-mail, escaping, WhatsApp rule, subject length), `ShipmentCodeTest` (TRK-02);
  `SitePagesIT` (PUB-01/03/05/09, TRK-01: every page 200 with its legacy title, description,
  canonical, one `<h1>`, `data-page`, active menu, JSON-LD ids, no inline script/style, and —
  when the checkout is present — `<title>`, description, canonical, `<h1>` and the whole `<main>`
  equal to the legacy file modulo the ADR 0012 allow-list; sitemap, robots, security.txt,
  `X-Robots-Tag`, the 404 page, the tracking widget), `SiteRedirectsIT` (PUB-04, table-driven
  over the filter's table plus the chapter 06 rules), `FormEndpointIT` (PUB-06/07/11, EML-08:
  JSON and native mode, lead + outbox rows and their columns, honeypot, the §5.6 server cases,
  the HTML error page, 413, Origin/Referer refusals, 429 per IP), `CompatApiIT` (API-01/02/03,
  ADM-10, TRK-02/03/04: FX-01…FX-14 on both hosts with synthetic orders written by SQL, CORS
  preflights, 33 retired mappings × 2 hosts writing nothing, the old-link page, the logo).
  Packaged jar smoke-tested (home, `/zone?cap=` 301, sitemap 24 URLs, robots, security.txt,
  `/404.html` 404, enumerations, trace, preflight 204, 410 JSON, old link 410 page, logo PNG,
  form 200/303/413/422).

### How to run it

```sh
scripts/local-db.sh start && eval "$(scripts/local-db.sh env)"
./mvnw -B -ntp verify
python3 tools/port-ls-home-pages.py --check      # templates still equal a fresh port of legacy/ls-home
./mvnw -B -ntp -DskipTests package
SERVER_PORT=8080 java -jar target/ls-web-0.1.0-SNAPSHOT.jar --spring.profiles.active=local \
  --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls
# http://localhost:8080/  /zone?cap=20831 (301)  /sitemap.xml  /robots.txt  /.well-known/security.txt  /404.html
# curl -H 'Content-Type: application/json' -d '{"codice":"LS-2024-0118"}' http://localhost:8080/mono/api/order/trace/state
# curl http://localhost:8080/mono/api/enumeration/statoTracking ; curl -i http://localhost:8080/mono/email-confirm?email=x
# curl -i -X POST http://localhost:8080/api/form -H 'Origin: http://localhost:8080' -H 'Accept: application/json' \
#   --data 'form-name=contatti&nome=Giulia+Bianchi&email=giulia@cliente.example&messaggio=Buongiorno%2C+vorrei+informazioni.&privacy=accettata'
# the office e-mail lands in .local/mail/*.eml once the outbox worker runs (local profile)
```

### Decisions taken

* ADR 0010 (compatibility tracking API), ADR 0011 (retired endpoints and old links), ADR 0012
  (public-site port and the PUB-01 allow-list), ADR 0013 (redirects), ADR 0014 (`/api/form`).
* **Pages are verbatim templates, not data-driven**: the simplest way to PUB-01; the zone and
  blog families stay five and four files. Owner copy changes (Q-31) are edits to the templates;
  from then on the port script is run with `--check` only on untouched pages, or retired.
* **JSON-LD through `layout.json()` with text inlining** instead of `th:inline="javascript"`,
  which escapes every `/` and would make the graph differ from the static site; the helper
  escapes `"`, `\`, control characters and `<`.
* **Handler mappings registered programmatically** from `SitePages` (`RequestMappingHandlerMapping
  .registerMapping`) rather than repeating 25 paths in an annotation; the route × role matrix test
  sees them like any mapping.
* **The form endpoint keeps the `form.mjs` answer shapes** (`{ok, error}` / HTML page) also for
  the two new refusals (403 Origin, 429 rate limit), so `forms.js` and `quote.js` keep reading
  `{ok:false}`; the platform error schema is for `/mono/**` and HTMX.
* **A request without `Origin` and `Referer` is refused**: the endpoint has no CSRF token;
  every current browser sends `Origin` on POST, and `strict-origin-when-cross-origin` sends the
  same-origin `Referer`.
* **`TrackingLookupService` reads `shipment_order` by SQL inside the `tracking` module**: the
  `orders` module does not exist yet; the lookup is one method to move behind an `orders` service
  if plan §4.1 prefers it.
* **Retired `/mono/**` paths carry no CORS headers**; CORS only on the three kept paths.
* **`GET` on a kept POST path falls into the 410 catch-all** (not 405); no legacy client does that.
* **`tracking_state.id` is mapped as `Short`** (`smallint`): Hibernate's schema validation refuses
  `Integer` on an `int2` column.
* **New Italian texts** for the owner's review: `compat.gone.*` («Link non più valido», lead,
  «Vai all'area clienti»), the 410 `detail` sentence, the form refusal «La richiesta non arriva
  dal nostro sito: torna al modulo e riprova.».

### Open points

* **E2E** (Playwright, `[PUB-01]`, `[PUB-04]` anchors, `[PUB-06]` wizard with JS on and off,
  `[TRK-01]` six states, `[API-03]` old link) and Lighthouse/axe (PUB-10) are the E2E stage; the
  429/5xx handling of `tracking.js` (PD-01) is decided there.
* **Lead retention job** (anonymisation after `retain_until`, Q-18) is not written yet: the
  `site` module registers it with the platform scheduler in the retention stage.
* Address and opening hours inside page bodies stay literal (header, footer, JSON-LD read the
  configuration); the WhatsApp prefilled texts stay in the pages (ADR 0027).
* The `/tracking/?code=` and `/zone/?cap=` results are client-side only (as on the static site);
  server-side rendering without JavaScript is optional per chapter 06 §5.3/§5.7.
* `multipart/form-data` posts to `/api/form` rely on the servlet's multipart parsing (no form on
  the site uses it); the body-length re-check after reading is not reproduced (the container's
  limit applies).
* `ls.compat.hosts` (Q-30) is declared but unused: the old-link page is served on every host.
* `/mono/api/order/trace/state` with a missing `Content-Type` answers 415 from Spring (the
  legacy clients send JSON); a `GET` on a kept POST path answers 410.
* Q-29 crawl list (redirects of the live site) still to be appended as data when it exists.

### Proposed changes to shared files (for later stages / the integrator)

* `docs/plan.md` §4.1: `tracking` row — the `/tracking/` page is rendered by `site` (verbatim
  page); `tracking` owns the API, the enumerations and the lookup. `site` row — `config.js` is
  the platform's (stage 5), `security.txt`/`robots.txt`/`sitemap.xml`/redirects/`/grazie/`/404
  are here now.
* `docs/parity-matrix.md`: PUB-01…09, PUB-11, TRK-02…04, API-01…03, ADM-10, EML-08 (form e-mail)
  have server-side tests; PUB-01/04/06/07, TRK-01 and API-03 keep an E2E part; the generator
  from test tags is still to be written (plan §5 item 11).
* `CLAUDE.md` Commands: `python3 tools/port-ls-home-pages.py --check` next to the build commands.

## Step B2 — Customers, orders and their e-mails (2026-10-24)

The working tree held an interrupted attempt at this step (V011 `pending_email`, the auth-side
`CustomerAccountService` and `CustomerEmailChangedListener`, the sign-in controller without
the Phase 1 stub pages); it was reviewed, kept and finished: the account service gained the
`REGISTER`-token checks and the login creation the registration needs, the two e-mail-change
pages and their message keys were written, the stub templates `auth/customer-registration.html`
and `auth/customer-home.html` were removed with their keys.

A second resumption found the step complete but unverified in the working tree (code, tests,
ADRs 0005–0009 and this section with placeholders, nothing committed): the build was run
(green), the packaged jar was smoke-tested, the one gap found (the typed e-mail was checked
against the link only at confirm) was closed with a field error, and the step was committed.

### What was built

* **V011** `customer_user.pending_email` (CUS-06): the new login e-mail awaiting its
  `CHANGE_EMAIL` link, normalised by a CHECK, cleared when applied. `expected-columns.txt` and
  `expected-constraints.txt`, `MigrationsIT` and `ApplicationContextIT` count eleven migrations.
* **`auth` additions**: `CustomerAccountService` — the customers module's door to the login
  identities (`findByEmail`, `isEmailInUse`, `isUsableRegistrationToken`,
  `registrationTokenMatches` (HMAC of the typed e-mail against the `REGISTER` token),
  `registerVerified` (consumes the token, creates the OWNER login, returns the principal to sign
  in), `sendSignInLink`, `requestEmailChange` / `peekEmailChange` / `confirmEmailChange`);
  `CustomerEmailChangedListener` hook; `CustomerSessionService.require()` (401 instead of an
  NPE for a non-customer principal); `GET/POST /area-clienti/cambio-email` with the templates
  `auth/customer-change-email.html` and `customer-change-email-done.html`; `AuthMails.changeEmail`
  and `changeEmailNotice` (EML-01, keys `mail.changeEmail.*`, `mail.changeEmailNotice.*`).
* **`notifications.MailLayout`** (ADR 0009): the shared e-mail shape — title, paragraphs,
  label/value tables, one button, signature, footer — rendered as plain text and inline-styled
  HTML from the same blocks; `customer()` sets Reply-To the office, `office()` Reply-To the
  customer; subjects «Logistica Sanchez - …».
* **`platform.validation.ItalianFormats`**: CAP (five digits), phone normalised to E.164
  (Italian landlines and mobiles, `0039`, international), e-mail normalisation, VAT number, tax
  code, decimal parsing with comma or point, positive integers, Italian number/euro/date
  formatting in Europe/Rome; exposed to templates as `fmt` (`platform.web.Formats`).
  `RuleViolationException` (422 with an Italian detail) for refused business moves.
* **`tracking.TrackingCatalog`**: the seeded states and accessory services as the other modules
  read them (no repository crosses a module); `TrackingState.PRESA` / `CONSEGNATO` constants.
* **`customers` module** (`it.logisticasanchez.web.customers`; ADR 0005): `Customer` entity with
  the chapter 08 §2.4 transitions (`verifyEmail`, `approve` only when verified, `decline`,
  `registerAgain`, `changeEmail`), `CustomerStatus`, `CustomerView` DTO, `RegistrationValidator`
  (trim, lengths, CAP, phone required — Q-36 seam —, VAT/tax code optional — Q-10 seams),
  `CustomerMails` (EML-02, EML-03 approved/declined, EML-06 new registration), `CustomerService`
  (`register` from a `REGISTER` link → `PENDING_APPROVAL` + login + sign-in; known e-mail → sign-in
  link and the same page; `registerAgain` for a DECLINED customer; `approve`, `decline`,
  `emailVerified`, `emailChanged`, `requestEmailChange`, `pendingApproval`, `find`), the auth
  hooks `CustomerAuthHooks`, `RegistrationController` (`/area-clienti/registrazione/`: form →
  summary → confirm; honeypot `website`; rate limit `registration` per IP; DECLINED
  re-registration from the session), `ProfileController` (`/area-clienti/profilo/` read-only
  + «Chiedi una modifica» mailto; `POST profilo/email`), `CustomersRouteContributor`;
  templates `customers/registration.html`, `registration-review.html`, `profile.html`.
* **`orders` module** (`it.logisticasanchez.web.orders`; ADR 0006, 0007, 0008): `ShipmentOrder`
  (+ `Party` snapshots), `OrderLine`, `OrderInternalNote`, `TrackingEvent` entities and
  repositories; `OrderStatus`, `LineKind` («Pallet Generico/Colli» / «Epal»); `OrderTotals`
  (decimal, per-line half-up); `OrderDraft` (session state with stable line ids and the
  idempotency key); `OrderValidator` (line: type required, integers ≥ 1 — Q-35 seams —;
  details: DDT required — Q-24 seam —, package count digits, seeded service, notes; recipient:
  OP-R7 plus optional phone/e-mail; Q-35 warnings); `OrderMails` (EML-04, EML-05 approved /
  declined, EML-06 new order, EML-09); `OrderService` (`submit` idempotent with `TEMP-xxxxxxxx`,
  sender snapshot, EML-04 + EML-06; `listForCustomer`, `findForCustomer` by code or temp code;
  `approve` with unique code ignoring case and amount > 0 with the Italian comma, PRESA event,
  EML-05; `decline` + EML-05 quoting the code; `setTrackingState` on approved orders, one event
  per change, EML-09 only when it becomes CONSEGNATO; `setInternalNote` / `internalNote`;
  `trackingHistory`; `pendingApproval`); `OrderView` DTO; `CustomerOrdersController`
  (`/area-clienti/ordini/` with the CUS-03 status callouts, `/area-clienti/ordini/{codice}/`,
  404 for another customer's code); `OrderWizardController` (`nuovo/`, `nuovo/destinatario/`,
  `nuovo/riepilogo/`, `nuovo/inviato/`); `OrdersRouteContributor`; templates `orders/list.html`,
  `detail.html`, `wizard-lines.html`, `wizard-recipient.html`, `wizard-review.html`,
  `wizard-done.html`.
* **Shared files touched** (integrator role): `layout/private.html` (+ `customerNav(active,
  canOrder)` fragment), `PlatformModelAttributes` (+ `fmt`), `messages_it.properties`
  (`customers.*`, `orders.*`, `auth.customer.changeEmail*`, `mail.changeEmail*`;
  `auth.customer.registration.*` and `auth.customer.home.*` removed), `route-matrix.tsv` (+17
  lines; `GET /area-clienti/registrazione/` is 302 for a signed-in non-declined customer),
  `RouteMatrixIT` (the CUSTOMER role is now a real `CustomerPrincipal` of an inserted ACTIVE
  customer, so the customer-area controllers run), `CustomerSignInIT` (the registration page's
  new heading), test support `CustomerTestData` (public; `AuthTestData` delegates) and
  `Sessions.customer(principal, at)`.
* **`dev-seed`** now creates customers in every state (`cliente.attivo`, `cliente.in-attesa`,
  `cliente.da-verificare`, `cliente.rifiutato` on `@cliente.example`) and, for the ACTIVE one,
  one order per state through the real services: pending (`TEMP-…`), approved `TEST-0002`
  (PRESA), approved and delivered `TEST-0003` (CONSEGNATO), declined; their e-mails land in the
  local mail directory. A second run leaves them alone («già presenti»).
* **Tests** (`./mvnw -B -ntp verify` green on the local PostgreSQL: 174 unit + 357 integration tests, none skipped):
  `ItalianFormatsTest` (CUS-02/04: CAP, phones, e-mails, VAT, decimals, integers, rendering),
  `RegistrationValidatorTest` (CUS-02), `OrderTotalsTest` (CUS-04: the §6.3 example 1 236 kg /
  4,53 m³, per-line rounding, the 0,015 tie, no float artefacts, ids never reused, temp codes),
  `MailLayoutTest` (EML-02/06 shape); `CustomerRegistrationIT` (CUS-02, EML-01/02/06: link →
  form → summary → confirm → signed in and pending; all errors at once; e-mail must match the
  link; known e-mail → sign-in link + same page; honeypot and 429; declined re-registration),
  `CustomerServiceIT` (CUS-02, ADM-02, EML-03: approve only verified, decline with reason,
  illegal moves, sign-in verifies once), `CustomerProfileIT` (CUS-06, EML-01: read-only profile,
  two-step e-mail change, GET never consumes, audit, sessions ended, address in use → nothing),
  `OrderWizardIT` (CUS-04, CUS-03, EML-04/06: the pages, totals, validation, stored order =
  input, final wording, idempotency, non-ACTIVE refused), `CustomerOrdersIT` (CUS-05: own orders
  only, detail by code/temp code, 404 for others, internal note hidden), `OrderBackofficeIT`
  (ADM-04/06/07, EML-05/09: approve rules, unique code ignoring case, amounts, decline, tracking
  events and EML-09 only on CONSEGNATO, internal note audited), `DevSeedCommandIT` extended.
  Packaged jar smoke-tested on `ls_web` after `dev-seed`: sign-in link from `.local/mail` → «I miei
  ordini» with the four seeded orders and their states, detail `TEST-0003` (Consegnato, 95,00,
  totals), 404 for a foreign code, profile; the wizard end to end (invalid line → errors, totals
  900,00 kg / 3,46 m³, missing DDT refused, bad CAP refused, summary, confirm → `TEMP-…`, final
  wording, a second confirm creates nothing; EML-04 + EML-06 rows); a new address → `REGISTER`
  mail → form (all errors at once, e-mail mismatch refused) → summary → confirm → signed in as
  `PENDING_APPROVAL` with the CUS-03 callout and the wizard redirecting to the list; EML-02 +
  EML-06 rows; a known address gets the same request page.

### How to run it

```sh
scripts/local-db.sh start && eval "$(scripts/local-db.sh env)"
./mvnw -B -ntp verify
./mvnw -B -ntp -DskipTests package
java -jar target/ls-web-0.1.0-SNAPSHOT.jar dev-seed --spring.profiles.active=local \
  --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls
SERVER_PORT=8080 java -jar target/ls-web-0.1.0-SNAPSHOT.jar --spring.profiles.active=local \
  --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls
# http://localhost:8080/area-clienti/ → type cliente.attivo@cliente.example; the sign-in link is in .local/mail/*.eml
#   → /area-clienti/ordini/ (list with the four seeded orders), /area-clienti/ordini/nuovo/ (wizard),
#     /area-clienti/ordini/TEST-0002/ (detail), /area-clienti/profilo/ (profile and e-mail change)
# a new e-mail (nuovo@cliente.example) → the registration link in .local/mail → /area-clienti/registrazione/?t=…
# cliente.in-attesa@ / cliente.rifiutato@ → the status page of CUS-03 (the declined one can register again)
# the back office has no screens yet: CustomerService.approve/decline and OrderService.approve/decline/
#   setTrackingState/setInternalNote are the services the Phase 2 backoffice module calls
```

### Routes added (all in `route-matrix.tsv`)

`GET|POST /area-clienti/registrazione/` (public: token or session checked by the controller),
`GET|POST /area-clienti/cambio-email` (public link page), `GET /area-clienti/profilo/`,
`POST /area-clienti/profilo/email`, `GET /area-clienti/ordini/`, `GET /area-clienti/ordini/{codice}/`,
`GET|POST /area-clienti/ordini/nuovo/`, `…/nuovo/destinatario/`, `…/nuovo/riepilogo/`,
`GET …/nuovo/inviato/` (customer session).

### Decisions taken

* ADR 0005 (registration, state machine, re-registration, e-mail change), ADR 0006 (wizard
  rules and decimal totals), ADR 0007 (submission, temporary codes, idempotency, approval,
  decline, internal note), ADR 0008 (tracking events, EML-09), ADR 0009 (e-mail content).
* **The e-mail is typed again on the registration form and checked against the link**: the
  `REGISTER` token stores only the HMAC of the address (ADR 0004), so pre-filling is impossible
  without putting the address in the URL; a mismatch is a field error on the form («L'indirizzo
  deve essere quello a cui hai ricevuto il link.»), found at the packaged-jar smoke test of this
  resumed run (the interrupted attempt refused it only at confirm with the link-invalid page).
* **Registration from the link lands in `PENDING_APPROVAL` and signs the customer in**: the
  consumed link proves the mailbox exactly like a sign-in link; `PENDING_VERIFICATION` and the
  `VERIFY_EMAIL` purpose stay for customers created another way. `customer_pending_registration`
  (V003) is unused in M1: a declined customer re-registers from its session, the data are applied
  at once.
* **`tracking_event` and the transitions live in `orders`** (one service, one transaction with
  the order's state); `tracking` keeps the lookup, the API and the catalogue (plan §4.1 says
  `tracking` owns the table: proposed change below).
* **`MailLayout` instead of Thymeleaf e-mail templates**: the same blocks render text and HTML,
  testable without a template engine; branded templates can replace it later behind the same
  module calls.
* **Audit actions** use dots only (`customer.email.changed`, `order.note`): V008's CHECK refuses
  underscores.
* **New Italian texts** for the owner's review: the registration hint and lead, the summary
  pages, the re-registration callout, the profile page, the wizard field labels that were not in
  the legacy («Tipo Pallet» select, «Email del destinatario (facoltativo)», warnings), the
  final-screen code sentence, EML-02 and EML-04 (new, Q-07), the EML-03 and EML-05 decline
  subjects, the customer footer «Per qualsiasi domanda rispondi pure…», the EML-01 e-mail-change
  mails.

### Open points

* **Back-office screens** (ADM-02…ADM-08) are the Phase 2 `backoffice` module: the services and
  their rules exist and are tested here; `/gestione/` still shows the stage 4 stub home.
* **E2E** (Playwright `[CUS-02]`…`[CUS-06]`, axe on the customer pages) comes with the E2E stage;
  the wizard's live totals without a reload are a JavaScript enhancement for that stage (the
  server totals are the authority).
* `RouteMatrixIT` exercises `GET /area-clienti/ordini/{codice}/` with the sample code `1` (404
  for the matrix customer); the positive path is in `CustomerOrdersIT`.
* The order detail does not yet show the tracking history timeline (ENH-05); `trackingHistory`
  is ready.
* Lead/customer/order retention and the NFR-03 export/erasure tools are later stages.
* `ls.customers.max-users-per-customer` (Q-11) and `ls.orders.customer-changes` (Q-12) are
  declared seams with their defaults; no code path reads them yet.

### Proposed changes to shared files (for later stages / the integrator)

* `docs/plan.md` §4.1: `tracking_event` and the ADM-06 transitions moved to the `orders` row;
  `TrackingCatalog` added to the `tracking` row; `customers` row: `customer_pending_registration`
  unused in M1; `platform` row: `ItalianFormats`, `Formats` (`fmt`), `RuleViolationException`;
  `notifications` row: `MailLayout`.
* `docs/parity-matrix.md`: CUS-02…CUS-06, ADM-02 (service), ADM-04, ADM-06, ADM-07 (services),
  EML-01…EML-06, EML-09 now have server-side tests; the E2E parts stay open.
* `CLAUDE.md` Commands: the `dev-seed` line now mentions the four customers and the four orders.
* Still open from earlier stages: confidential-figure guard, local gitleaks, `/e2e` Dependabot
  entry, Q-55 wording trim, ADR 0015 EML-S1 line, `docs/plan.md` §4.1 items of steps 5 and B1.

## Step B3 — Back office, reports and privacy tools (2026-10-24)

The working tree was clean at the start (nothing left by an interrupted attempt). The step adds
the `backoffice` module (every `/gestione/**` screen over the services of step B2), the
`reports` module (ADM-09, EML-07) and a small `privacy` module (NFR-03), plus the two retention
jobs that were still open.

### What was built

* **V012** `report_recipient` (the ADMIN allow-list of extra report recipients, normalised
  e-mail, unique) and `customer.anonymised_at` (the erasure mark). `expected-columns.txt`,
  `expected-constraints.txt`, `SchemaConformanceIT`, `MigrationsIT` and `ApplicationContextIT`
  count twelve migrations.
* **`backoffice` module** (`it.logisticasanchez.web.backoffice`; ADR 0017): `BackofficeRoutes`,
  `BackofficeRouteContributor` (staff routes; `impostazioni/**` ADMIN), `StaffSession` (name and
  role for pages, id for actions), `BackofficeModelAttributes` (`staff` in every template), four
  controllers and sixteen templates under `templates/backoffice/`:
  * `BackofficeCustomersController` — «In Approvazione» (`clienti/in-approvazione/`: the nine
    legacy columns, «Email confermata» SI/NO, «Nessun utente da approvare»), approve and decline
    behind a named confirmation page («Sei sicuro di voler procedere?», optional reason sent in
    EML-03), «Registrati» (`clienti/`: newest first, search on name/e-mail/city, 25 per page), the
    customer page (`clienti/{id}/`: data and its orders);
  * `BackofficeOrdersController` — «Ordini da approvare» (`ordini/in-approvazione/`: the 13
    legacy columns, temporary codes shown as «-»), «Approva ordine» (`ordini/{id}/approva`:
    «Codice», «Importo Totale (per cliente)» with the Italian comma; a duplicate code or a bad
    amount is shown on the form with the typed values kept), decline (`ordini/{id}/rifiuta`),
    «Ordini» (`ordini/`: server-side pagination, filters state/tracking/customer/inclusive dates
    in Europe/Rome, search by code or temporary code, default state `APPROVED`), the detail
    (`ordini/{id}/`: «Dettaglio Spedizione», «Dettaglio Colli», «Pallets» with one row per line
    and its quantity, sender linked to its customer page, the tracking form with the current
    state preselected and the history, «Aggiorna note» with the customer's note read-only
    beside), «Imposta tracking» by code (`tracking/`);
  * `BackofficeReportsController` — «Report» (`report/`: customer, inclusive dates defaulting to
    the current month, «Solo ordini consegnati», «Invia anche al cliente», extra addresses,
    «Invia Report»; flash «Report inviato correttamente: N ordini» with the download link; the
    last twenty runs);
  * `BackofficeSettingsController` (ADMIN) — `impostazioni/` (the 25 M2 flags with one form each,
    all off; the report recipients allow-list, add/remove), `impostazioni/utenti/` (the staff
    accounts and the creation form: the set-password link goes by e-mail, EML-S1; duplicate
    username or e-mail refused on the form), `impostazioni/privacy/` (look an address up, export
    as JSON, anonymise behind a confirmation).
  * The home (`auth/staff-home.html`, still served by `StaffController`) shows the sections as
    cards, «Impostazioni» for ADMIN only; every page shows the section navigation. Every staff
    action writes `audit_log` through the domain services (ids only; addresses and note texts in
    `audit_log_detail`). Message keys `backoffice.*` (180) in `messages_it.properties`.
* **Domain additions the screens needed**: `CustomerService.search` (JPQL, live customers only),
  `listAll`, `anonymise`, `pendingApproval` honouring `ls.backoffice.show-unverified-customers`
  (PD-06); `CustomerView.emailVerified()/anonymised()`; `OrderService.search(OrderSearch, page,
  size)` (JPA `Specification`, newest first), `findApprovedByCode`, `approvedForReport`,
  `eraseInternalNotes`, `allForCustomer`; `OrderSearch` record; `StaffAccountService.listAll`,
  `EmailTakenException`; `StaffPrincipal` public with `require()` and `isAdmin()`.
* **`reports` module** (`it.logisticasanchez.web.reports`; ADR 0016): `ReportRun` (V007 row,
  `text[]` recipients), `ReportRecipient` + `ReportRecipientService` (office always allowed,
  ADMIN rows, `ls.reports.recipients.allow-list` as a configured seed; add/remove audited with the
  address in the detail), `ReportPeriod` (inclusive, Europe/Rome, `from ≤ to`, not in the
  future), `ReportService.request` (one customer's approved orders by submission date, optional
  `CONSEGNATO` only; recipients = office + customer if chosen (Q-08 seam
  `ls.reports.customer-can-receive`) + allow-listed extras, otherwise the whole request is
  refused; `report_run` row; one EML-07 per recipient; audit `report.send`),
  `pdfForStaff`/`pdfForCustomer` (regenerated at each download; another customer → 403),
  `ReportPdf` (OpenPDF 3.0.5, A4 landscape, «I tuoi dati» block, the 13 legacy headers verbatim,
  «-» for missing values, Italian numbers, «Dt/Cons.» = delivery date or «-», no partner header),
  `ReportMails` (EML-07 «Report ordini PDF», «Date di riferimento», «Scarica PDF» → the staff or
  the customer download), `ReportDownloadController` (`GET /gestione/report/{id}/download`,
  `GET /area-clienti/report/{id}/download`), `ReportsRouteContributor`.
* **`privacy` module** (`it.logisticasanchez.web.privacy`; ADR 0024): `PrivacyService.lookup`,
  `export` (JSON: customer, login identity, orders with snapshots and lines, leads) and
  `anonymise` (customer placeholders + DECLINED + `anonymised_at`; login identities disabled and
  renamed, links deleted, sessions ended — `CustomerAccountService.anonymise`; outbox rows
  addressed to, replying to or mentioning the address lose bodies and token, queued ones become
  FAILED — `NotificationService.eraseRecipient`; staff notes deleted; leads anonymised —
  `LeadService.anonymiseByEmail`; `report_run.recipients` rewritten — `ReportService.eraseRecipient`;
  `audit_log_detail` rows about the customer, its orders, its login or mentioning the address
  deleted in a maintenance transaction — `AuditService.deletePersonalDetails[Mentioning]`);
  orders stay with their snapshots. Both tools ADMIN-only (`@PreAuthorize`) and audited.
* **Retention jobs**: `LeadRetentionJob` (`site`: leads past `retain_until` anonymised, SQL in
  `LeadRepository`), `OutboxBodiesRetentionJob` (`notifications`: bodies and token purged
  `ls.retention.outbox-bodies-days` after delivery, in batches); registered with the platform
  `RetentionRunner` by being beans.
* **Legal pages**: `/privacy/` (customer-area data, the hosting provider as processor, the
  retention periods of the customer area, the export/erasure sentence) and `/cookie/` (the
  single technical cookie `__Host-LS_SESSION` of the private areas) gain plain-text sections,
  applied by `tools/port-ls-home-pages.py` (`ADDITIONS`, wrapped in `<!-- ls-web:addition -->`
  markers that `SitePagesIT` leaves out of the comparison; `--check` is still clean).
* **Tests** (every one tagged): `ReportPeriodTest` (unit); `BackofficeCustomersIT` (ADM-02,
  ADM-03, EML-03), `BackofficeOrdersIT` (ADM-04…ADM-08, EML-09: > 1 page of orders, filters,
  search, tracking from the row and by code, EML-09 once, notes, detail sections),
  `BackofficeSettingsIT` (ADM-01, ADM-09, NFR-02: OPERATOR 403, flag toggle with audit,
  allow-list, staff creation with EML-S1), `ReportIT` (ADM-09, EML-07: A holds no order of B,
  only delivered, refused recipient, downloads per session, PDF headers and A4 landscape, the
  page), `PrivacyIT` (NFR-03: export, anonymisation scanned over every text column of every
  table — only `shipment_order.sender_email`/`sender_name` survive —, retention jobs);
  `EnumerationSeedIT` already covers ADM-10. `route-matrix.tsv` gained 32 lines; the id-bound
  staff routes accept `404|200` because the sample id `1` may exist in a shared database.
  `./mvnw -B -ntp verify` green on the local PostgreSQL: 177 unit + 376 integration tests, none
  skipped, Spotless clean; the packaged jar was smoke-tested (dev-seed, admin and operator login,
  every back-office page, note, tracking, report with its two EML-07 rows and the PDF download,
  flag on/off, privacy lookup, operator 403 on `/gestione/impostazioni/`).

### How to run it

```sh
scripts/local-db.sh start && eval "$(scripts/local-db.sh env)"
./mvnw -B -ntp verify
python3 tools/port-ls-home-pages.py --check          # templates (with the legal-page additions) equal a fresh port
./mvnw -B -ntp -DskipTests package
java -jar target/ls-web-0.1.0-SNAPSHOT.jar dev-seed --spring.profiles.active=local \
  --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls
SERVER_PORT=8080 java -jar target/ls-web-0.1.0-SNAPSHOT.jar --spring.profiles.active=local \
  --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls
# http://localhost:8080/gestione/ → admin.seed (password printed by dev-seed) → the home cards
#   /gestione/clienti/in-approvazione/ (cliente.in-attesa: Approva → confirmation → EML-03 in .local/mail)
#   /gestione/ordini/in-approvazione/ (TEMP order of cliente.attivo: Approva ordine → codice + importo)
#   /gestione/ordini/ (filters, search by code), /gestione/ordini/{id}/ (detail, tracking, note)
#   /gestione/tracking/?codice=TEST-0002, /gestione/report/ (Invia Report → EML-07 → /gestione/report/{id}/download)
#   /gestione/impostazioni/ (flags, destinatari), /gestione/impostazioni/utenti/, /gestione/impostazioni/privacy/
# operatore.seed sees everything but /gestione/impostazioni/** (403)
```

### Routes added (all in `route-matrix.tsv`)

Staff (ADMIN or OPERATOR): `GET /gestione/clienti/`, `…/clienti/in-approvazione/`,
`…/clienti/{id}/`, `GET|POST …/clienti/{id}/approva`, `…/clienti/{id}/rifiuta`,
`GET /gestione/ordini/`, `…/ordini/in-approvazione/`, `…/ordini/{id}/`,
`GET|POST …/ordini/{id}/approva`, `…/ordini/{id}/rifiuta`, `POST …/ordini/{id}/tracking`,
`…/ordini/{id}/note`, `GET|POST /gestione/tracking/`, `GET|POST /gestione/report/`,
`GET /gestione/report/{id}/download`. ADMIN: `GET /gestione/impostazioni/`,
`POST …/impostazioni/flag`, `…/impostazioni/destinatari`, `…/impostazioni/destinatari/{id}/rimuovi`,
`GET|POST …/impostazioni/utenti/`, `GET …/impostazioni/privacy/`, `POST …/privacy/cerca`,
`…/privacy/esporta`, `…/privacy/anonimizza`. Customer: `GET /area-clienti/report/{id}/download`.

### Decisions taken

* ADR 0016 (report selection, recipients, authenticated downloads, PDF differences), ADR 0017
  (pages instead of dialogs, lists, pagination, detail, roles), ADR 0024 (export, erasure by
  anonymisation, retention jobs, legal pages); ADR README index updated.
* **Confirmation pages instead of dialogs** for approve/decline of customers and the decline of
  orders (named subject, optional reason); approval of an order is its own form; no
  confirmation on backward tracking moves in M1 (the history keeps every change).
* **«Ordini» defaults to `APPROVED`** (the legacy «registered» list) and lets the filter choose
  the other states; «Dt/Cons.» is the delivery date (the legacy printed something else, 12c §3.4).
* **The report file name has no e-mail local part** (`report-ordini-{dal}_{al}.pdf`).
* **Anonymisation placeholders** on the reserved domain `anonimizzato.invalid`; an anonymised
  customer is `DECLINED` and never listed; the orders keep their snapshots (Q-18).
* **`StaffPrincipal` is public** and offers `require()`; the back-office pages tolerate a plain
  principal (name and role from the authentication) so that `RouteMatrixIT` can render them,
  while actions need the real id (401 otherwise).
* **New Italian texts for the owner's review**: the home cards, the nav entry «Ordini da
  approvare», the filter and pager labels, the hints («Il codice definitivo…», «In euro, con la
  virgola…», the notes hint, the report hints, the settings hints), the flash messages, the
  staff-user screen, the privacy tool texts, EML-07 wording (title «Report ordini», the filter
  sentence, «è necessario accedere con il tuo account»), the refusal texts of `ReportPeriod`,
  `ReportService` and `ReportRecipientService`, the `/privacy/` and `/cookie/` additions
  (including the hosting-provider sentence, deliberately generic until ADR 0022 names it).

### Open points

* **E2E** (`[ADM-02]`…`[ADM-09]` Playwright specs, axe on `/gestione/**`, phone-width tables)
  belong to the E2E stage; no spec exists yet. The 14-column order tables rely on `.table-wrap`
  horizontal scrolling on a phone (NFR-01 to verify there).
* `report_run` rows are kept forever for now (PD-16: 24 months) and audit rows / IP hashes have
  no retention job yet; `ls.retention.report-runs-months`, `audit-months`, `ip-hashes-days` are
  declared seams.
* The home has no counters (ENH-08); the «Imposta tracking» row action links to the detail's
  tracking form; HTMX is not used.
* `ReportService.eraseRecipient` scans every run (fine for M1 volumes); `report_run` has no
  index on recipients.
* `OutboxWorker` sends the EML-07 of an allow-listed address only if the staging allow-list of
  `ls.notifications.allow-list` permits it outside production (chapter 10 §3), as for every mail.
* The `/privacy/` hosting-provider sentence and the Netlify/SES bullets need the owner's review
  once the infrastructure of ADR 0022 is decided (Netlify no longer processes the forms after
  cut-over).
* Shared files touched (integrator to confirm): `messages_it.properties` (`backoffice.*`,
  `auth.staff.home.lead`), `auth/staff-home.html`, `route-matrix.tsv`, `expected-columns.txt`,
  `expected-constraints.txt`, `SchemaConformanceIT`, `MigrationsIT`, `ApplicationContextIT`,
  `SitePagesIT` (marker filter), `Sessions` (staff principal overload), `pom.xml` (OpenPDF).

### Proposed changes to shared files (for later stages / the integrator)

* `docs/plan.md` §4.1: a `privacy` row (export/erasure orchestration, ADR 0024) — the
  `customers` row said «export and erasure tools»; `reports` row: `report_recipient` (V012);
  `backoffice` row: `impostazioni/utenti/` and `impostazioni/privacy/`.
* `docs/parity-matrix.md`: ADM-02…ADM-09, EML-07, NFR-03 now have server-side tests (E2E parts
  open); ADM-10 unchanged.
* `CLAUDE.md` Commands: the back-office URLs after `dev-seed`.
* Still open from earlier stages: confidential-figure guard, local gitleaks, `/e2e` Dependabot
  entry, Q-55 wording trim, ADR 0015 EML-S1 line, `docs/plan.md` §4.1 items of steps 5, B1, B2.

## Step C — Finish: E2E suite, CI, image, deploy folder, docs (2026-10-25)

The working tree held an interrupted attempt at this step: `e2e/` with `package.json`
(`@playwright/test` 1.56.1, `@axe-core/playwright` 4.14.0), `playwright.config.ts`,
`support/browser.ts`, `support/mail.ts`, `tests/public.spec.ts`, `tests/journey.spec.ts` and an
installed `node_modules`, but no runner script and never executed. Reviewed against the templates
and kept; fixed: the office address was read from `config.js` with a double-quote regex while
`ConfigJsController` writes single quotes, the EML-04 wait used a subject that does not exist
(«Richiesta di spedizione ricevuta» is the real one), the axe tests sampled the pages during the
reveal animation (`emulateMedia({ reducedMotion: 'reduce' })` now makes axe see the settled page,
which surfaced one real inherited finding, below).

### What was built

* **`e2e/run.sh`** — the only entry point, also used by CI: finds or builds the jar, recreates
  `ls_web_e2e` on the local instance (`scripts/local-db.sh psql postgres -c 'DROP DATABASE …'` +
  `createdb`; in CI `E2E_DATABASE_URL` points at the `postgres:16` service instead), runs
  `dev-seed` and captures the `admin.seed` password from its output, starts the jar with the
  `local` profile on port 8089 (`--ls.notifications.directory.path=.local/mail/e2e`,
  `--ls.notifications.worker.interval=2s`, `--ls.business.office-email=ufficio@logisticasanchez.example`
  so the office notices stay synthetic, `--ls.business.site-url=http://localhost:8089` so the
  mailed links point at the app under test), waits for `/actuator/health`, runs
  `npx playwright test "$@"`, stops the app and prints the app log tail on failure.
* **`tests/journey.spec.ts`** (serial, two browser contexts: customer and staff, each with the
  console/CSP listener checked after every step): `[CUS-02]` new address → «Ti abbiamo inviato
  un'email» → REGISTER link from the `.eml` → form → «Controlla i dati inseriti» → «Conferma» →
  signed in `PENDING_APPROVAL`; `[EML-02]` registration mail to the customer and office notice;
  `[ADM-02]` staff login, «In Approvazione» row with «SI», approve behind the confirmation page,
  row gone, «Registrati» shows «Attivo»; `[EML-03]` «Conferma nuovo cliente» with «Benvenuto in»;
  `[CUS-01]` sign-in link → «Accedi» → «I miei ordini» without the status callout; `[CUS-04]`
  wizard (Epal 180×120×80, 450 kg × 2 → 900,00; DDT; recipient; «Conferma l'ordine» → `TEMP-`
  code on the final page); `[CUS-05]` list and detail, EML-04 file; `[ADM-04]` «Approva ordine»
  with code `E2E-…` and amount `120,50`, the order listed in «Ordini» with the amount and its
  «Dettaglio» link; `[EML-05]` «Conferma ordine N. …»; `[TRK-01]` `POST /mono/api/order/trace/state`
  → «Presa in carico», `/tracking/?code=` renders the code and the title, unknown code → «Non
  gestito»; `[ADM-06]` tracking select → CONSEGNATO; `[TRK-02]` the API follows; `[EML-09]`
  «Consegna effettuata per ordine N. …» file exists.
* **`tests/public.spec.ts`**: `[PUB-01]` the 25 pages of `SitePages` answer 200 with the public
  CSP, a title, a visible main, no horizontal scroll, no console error, no CSP violation
  (`securitypolicyviolation` listener installed by `addInitScript`); `[NFR-02]` `/area-clienti/`
  and `/gestione/login` with the nonce CSP (no `unsafe-`), `no-store`; `[PUB-04]` three 301
  cases and a 404; `[PUB-09]` sitemap = the 24 indexable pages, robots points at it; `[PUB-10]`
  axe (`wcag2a`, `wcag2aa`, `best-practice`; serious/critical fail) on the five budget pages;
  `[NFR-01]` axe on the two private entry pages. Two projects: `desktop` (Desktop Chrome) and
  `phone` (Pixel 7, public spec only). Every request to a host other than the app is answered
  204 by a route (`support/browser.ts offline()`), so the run needs no network.
* **Inherited finding allow-listed by name** (`KNOWN_LEGACY_VIOLATIONS` in `public.spec.ts`):
  `color-contrast` on `span[data-zone="3"]` of `/servizi/spedizione-pallet/` — the «72 ore» pill
  (`.eta-pill[data-zone="3"]`: white on the legacy zone-3 colour) of the verbatim ls-home CSS.
  Anything else fails the run. Changing the colour is a PUB-01 allow-list change for the owner.
* **CI** (`.github/workflows/ci.yml`): new `e2e` job (JDK 21 + `-DskipTests package`,
  `actions/setup-node@v6` Node 22 with the npm cache on `e2e/package-lock.json`, `npm ci`,
  `npx playwright install --with-deps chromium` — CI only —, `e2e/run.sh` against the
  `ls_web_e2e` service database, report + traces + app log uploaded on failure); `guards` now
  also runs `docker compose --env-file .env.example -f compose.yml config --quiet`; new `image`
  job (`needs` the three others; Buildx, GHCR login with `GITHUB_TOKEN` on pushes only,
  `docker/metadata-action` tags `sha-<short>`, `<branch>`, `latest` from `main`; built on pull
  requests, pushed on `main` and `phase-*`; `packages: write` at job level).
* **`Dockerfile`** multi-stage (`eclipse-temurin:21-jdk-noble` build with the wrapper and a
  dependency pre-fetch layer; `eclipse-temurin:21-jre-noble` runtime, `fonts-dejavu-core` +
  `curl`, user `app` uid 10001, `TZ=Europe/Rome`, `HEALTHCHECK` on `/actuator/health`,
  `ENTRYPOINT java -jar /app/app.jar` so `create-staff-user` and `dev-seed` work as arguments);
  `.dockerignore` keeps only `mvnw`, `.mvn`, `pom.xml`, `src`.
* **`deploy/`**: `compose.yml` (`proxy` caddy:2 with ports 80/443(+udp) and the certificate
  volumes, `app` from `ghcr.io/francescocorbosiero/ls-web:${LS_IMAGE_TAG}` with every setting
  from `.env` and `${VAR:?}` for the required ones, `db` postgres:16 with `pg_data`; healthchecks
  and `depends_on` conditions; no `container_name`); `caddy/Caddyfile` (the five hosts in one
  block, automatic HTTPS, `encode`, `header -Server`, `reverse_proxy app:8080` with health
  checks, JSON access log; host behaviour stays in `HostRoutingFilter`); `.env.example` (every
  variable the app reads, grouped, required ones empty, optional ones commented with their
  `ls.*` key); `backup.sh` (`pg_dump | gzip | age`, 7/4/12 rotation, `rclone` off-site copy,
  `--check` age alert at 26 h, `--restore FILE`). `docker compose config` passes locally (no
  daemon needed) and in CI.
* **Docs**: ADR 0022 (deployment, CI image, backups) and its index row; `tools/parity-matrix.py`
  (Status column + coverage block between `<!-- generated:start/end -->` from `@Tag`/`[ID]` and
  the surefire/failsafe XML + Playwright `results.json`; `--check` mode; MIG-* blocked, NFR-05/06/07
  capped at ready-for-owner while their deliverables exist) and the regenerated
  `docs/parity-matrix.md` (header rewritten, PUB-10/NFR-01 notes); `CLAUDE.md` Commands filled;
  README quickstart and E2E/deploy section; `docs/runbooks/local-setup.md` and
  `docs/runbooks/deploy.md` in Italian (install, update, rollback, backup/restore, key rotation,
  cut-over outline); `docs/plan.md` §Status.

### How to run it

```sh
cd /home/user/ls-web && scripts/local-db.sh start && eval "$(scripts/local-db.sh env)" && ./mvnw -B -ntp verify
(cd e2e && npm ci)                      # once; browsers are preinstalled under /opt/pw-browsers
e2e/run.sh                              # whole suite (≈ 2 min); e2e/run.sh --project=desktop tests/journey.spec.ts for the journey only
(cd e2e && npx playwright show-report)  # HTML report; traces of failures under e2e/test-results/
tools/parity-matrix.py                  # regenerate docs/parity-matrix.md after the two runs above
(cd deploy && LS_ACME_EMAIL=x@example.invalid POSTGRES_PASSWORD=x LS_SECURITY_IP_HASH_KEY=x LS_TOKEN_ENCRYPTION_KEY=x docker compose --env-file .env.example -f compose.yml config --quiet)
```

Results on 2026-10-25: `./mvnw -B -ntp verify` green — 177 unit and 376 integration tests, none
skipped, Spotless clean; `e2e/run.sh` green — 85 titles (49 desktop, 36 phone) in 1.8 min;
`tools/parity-matrix.py` — 47 of 50 deliverable M1 IDs green, 3 ready-for-owner (NFR-05/06/07),
MIG-01…03 blocked (Q-55). `docker build` could not be run here (no Docker daemon): the
`Dockerfile` is exercised by the CI `image` job on the first push.

### Decisions taken

* The E2E suite is a **smoke** of the M1 journey plus a page sweep, not a replica of every
  planned E2E row of the parity matrix (owner's fast mode): the planned-but-not-written E2E
  parts stay listed in the "Planned tests" column and the coverage block shows what runs.
* axe runs with `prefers-reduced-motion: reduce` (the site's own rule turns the reveal animation
  off), because the settled page is the one users read; the one legacy contrast finding is
  allow-listed by rule and selector, never by page.
* `tools/parity-matrix.py` marks an ID green when any tagged class or title passed in the **last
  local run**; it is the generator the matrix header promised, run by hand for now (CI would
  need the reports of both jobs as artifacts — noted as the next CI step).
* The deploy to staging/production is a documented manual `docker compose pull && up -d`
  (runbook §2); an SSH deploy job from CI waits for the owner's VPS and a secret (ADR 0022).
* The office address in the E2E run is overridden with `ufficio@logisticasanchez.example`; in
  the `local` profile the default stays the business mailbox because the directory sink never
  sends anything (step B2 open point, unchanged).

### Open points

* **Lighthouse CI** (PUB-10 performance/SEO/best-practices ≥ 95) is not run: `@lhci/cli` 0.15.1
  job to add to `e2e` (budget pages: `/`, `/servizi/spedizione-pallet/`, `/preventivo/`,
  `/tracking/`, `/contatti/`). PUB-10 is green on the axe and console/CSP parts only (matrix note).
* The inherited contrast finding («72 ore» pill) needs the owner's call: change `--zone-3` text
  colour (PUB-01 allow-list) or accept it.
* Not covered by E2E yet (server-side tests exist for all of them): CUS-03 status pages, CUS-06
  profile and e-mail change, ADM-03/05/07/08/09 screens, PUB-06/07 wizard with JS, PUB-08 CAP
  widget and map consent, TRK-01 six rendered states and 429 handling, API-03 old link, phone
  width inside the signed-in areas.
* CI first run on GitHub still to happen (Chromium install through `playwright install
  --with-deps`, GHCR push permissions on the public repository, Buildx cache); fix-ups may be
  needed in the PR.
* `tools/parity-matrix.py --check` is not in CI (needs the surefire/failsafe/Playwright reports
  of both jobs); `Dependabot` already watches `/e2e`.
* Remaining retention jobs (audit rows 24 months, IP hashes 30 days, report runs 24 months) from
  step B3 are still open; `ls.retention.*` seams declared.
* NFR-05/06/07 become green only with dated rehearsal records (`docs/rehearsals/`): restore
  drill, staging deploy, cut-over drill — all need the owner's VPS, DNS and SES credentials.
* New Italian texts of this step for the owner's review: the two runbooks.

### Proposed changes to shared files (for the integrator / the PR)

* All shared files of this step were edited directly (step C is the finishing step): `CLAUDE.md`
  Commands, `README.md`, `.github/workflows/ci.yml`, `docs/plan.md` §Status, `docs/parity-matrix.md`
  header and notes, `docs/adr/README.md`. Still open from earlier steps and not done here:
  `docs/plan.md` §4.1 module rows (config.js under platform, tracking_event under orders, privacy
  row, reports/backoffice rows), the Q-55 wording trim of §4/§5/§8, ADR 0015 EML-S1 line, the
  confidential-figure guard and a local gitleaks run, the §4.3 `docs/parity-plan.md` freeze
  (dropped by the lighter process; the matrix header says so).

## Fix after CI run 4 (2026-10-25)

CI failed with 45 `??key_it??` renderings and a `NoSuchMessageException` while the local
build passed: Spring Boot's `MessageSourceAutoConfiguration` only creates the message
source when the base file `messages.properties` exists, and locally a stale copy survived
in `target/classes`. The Italian bundle is now `messages.properties` (the app has one
locale, `spring.web.locale=it` fixed); the test and the layout comment follow.

## Step D — Owner requests of 2026-10-25: record editing, private-area interface, home motion

The owner's message of 2026-10-25, after trying the app: «the dashboards (admin and customers)
are definitely lacking UX/UI elements. But they are working solid … It's just matter of some
CSS and some direct UX changes on how orders are prepared», «CRUD for records details is
completely missing … both from admin side but also i'd be amazing feature for customers to be
able to CRUD their own orders before they get confirmed by admin», «you can use the repo
"alpacode-motion" as reference for (lightweight) structured GSAP animations in the homepage».

### What was built

* **Record editing (ADR 0028; chapter 09 CUS-07, ADM-11, ADM-12; Q-12 decided).** Migration
  `V013__record_editing.sql`: order status `CANCELLED` with `cancelled_at`, `cancelled_by_type`,
  `cancelled_by`, `cancel_reason`; `edited_at`, `edited_by_type`; consistency checks; a customer
  created by staff (source `BACKOFFICE`) may be ACTIVE before its first sign-in verifies the
  e-mail. `OrderService.updateByCustomer`, `cancelByCustomer`, `createByStaff`,
  `updateByStaff`, `cancelByStaff` (optimistic check on the version the page showed, audit,
  domain events `OrderChanged`/`OrderCancelled`, EML-06 notices only for the customer's own
  changes); `CustomerService.createByStaff`, `update` (`CustomerChanged`).
  `CustomerOrderChangesController` (`/area-clienti/ordini/{codice}/modifica`, `…/annulla`),
  `BackofficeOrderChangesController` (`/gestione/ordini/nuovo`, `…/{id}/modifica`,
  `…/{id}/annulla`), `BackofficeCustomerChangesController` (`/gestione/clienti/nuovo`,
  `…/{id}/modifica`). `dev-seed` adds a cancelled order and only creates the seed orders that
  are missing.
* **Private-area interface (ADR 0029).** `static/css/app.css` on top of `main.css`,
  `static/img/app-icons.svg` (Lucide), `static/js/app.js`; every customer-area, auth and
  back-office template moved onto it. The order form is one page (`OrderForm`, fragments in
  `orders/form.html`) shared by the new order, the customer's edit and the staff form, working
  without JavaScript. «I miei ordini» with tiles and status tabs, the order detail with tracking
  steps and history; the back-office «Panoramica» (`BackofficeHome` through the new
  `auth.StaffHomePage` seam), navigation counters, the order-list filter «Non ancora
  consegnati».
* **Home motion (ADR 0030; built in a parallel worktree, merged as `eea60c6`).**
  `static/js/motion/` after `alpacode-motion@7b1a203`, GSAP 3.15.0 vendored, a selector map so
  the ported home markup is untouched (PUB-01); `SitePagesIT` and `public.spec.ts` «home motion».
  Also fixed: the header button read «Preventivogratuito» (`layout.header.quote.suffix` now
  starts with an escaped space).
* **Tests.** New: `CustomerOrderChangesIT`, `CustomerOrderChangesOfficeOnlyIT`,
  `BackofficeOrderChangesIT`, `BackofficeCustomerChangesIT`, `BackofficeHomeIT`; rewritten:
  `OrderWizardIT` (the one-page form without JavaScript: rows added, removed and renumbered,
  Enter, the 50-row guard); updated: `BackofficeOrdersIT`, `BackofficeCustomersIT`,
  `MigrationsIT`, `ApplicationContextIT`, `DevSeedCommandIT`, `DomainEventsIT`,
  `SettingsDefaultsIT`, `route-matrix.tsv`, the schema files. E2E `journey.spec.ts`: the order
  through the one-page form with live totals, [CUS-07] edit and cancel, [ADM-11] an order
  entered by staff, [ADM-12] a customer created and corrected, [NFR-01] axe and phone width on
  17 private screens.

### How to run it

As in step C (`scripts/dev.sh`, then `/area-clienti/` with `cliente.attivo@cliente.example`
and `/gestione/` with `admin.seed`). `e2e/run.sh` needs `CHROMIUM_PATH=/opt/pw-browsers/chromium`
where the pre-installed Chromium is not Playwright 1.56.1's (this environment).

### Routes added (all in `route-matrix.tsv`)

Customer: `GET|POST /area-clienti/ordini/{codice}/modifica`, `GET|POST …/{codice}/annulla`.
Staff (ADMIN or OPERATOR; an approved order only for ADMIN, checked in the service):
`GET|POST /gestione/ordini/nuovo`, `GET|POST …/ordini/{id}/modifica`, `GET|POST
…/ordini/{id}/annulla`, `GET|POST /gestione/clienti/nuovo`, `GET|POST …/clienti/{id}/modifica`.
Removed: `GET|POST /area-clienti/ordini/nuovo/destinatario/` (the recipient is on the form).

### Decisions taken

* **Q-12 answered by the owner** (chapter 11, ADR 0028): `ls.orders.customer-changes` now
  defaults to `SELF_SERVICE`; `OFFICE_ONLY` keeps the M1 behaviour. Chapter 09 gains CUS-07,
  ADM-11, ADM-12; the «Not ported» note points at them; ADR 0006 and 0007 are marked amended.
* **Cancel, never delete**: `CANCELLED` keeps the row and its history; «Rifiuta» stays the
  office's refusal with its e-mail; a cancelled order leaves public tracking and the reports.
* **Corrections after approval are ADMIN only** (chapter 08 §2.4), code and amount with the
  approval rules; OPERATORs change and cancel pending orders.
* **No e-mail to the customer for staff changes** (creation, edit, cancel): the customer is
  told at approval (EML-05) or by the office; EML-06 notices go to the office for the
  customer's own edits and cancellations.
* **Staff-created customers are ACTIVE at once**, approved by the creator, the e-mail verified
  by the first sign-in; the welcome e-mail (EML-03) is a ticked-by-default choice.
* **One-page order form** with the summary page kept (ADR 0029); 50 rows at most; blank rows
  ignored.

### New Italian texts for the owner's review

* **Customer area**: the form sections «Pallet e colli», «Dati della spedizione»,
  «Destinatario», their hints («Una riga per ogni tipo di unità…», «Scegli «Epal» e la base
  120 × 80 cm si compila da sola.», «Dove consegniamo la merce.»), «Aggiungi riga», «Elimina la
  riga {n}», «Continua al riepilogo», «I totali si aggiornano mentre scrivi…», the stepper
  «Dati dell'ordine» / «Riepilogo e invio»; «I miei ordini» tiles and tabs («In attesa di
  conferma», «In viaggio», «Consegnati», «Ordini inviati», «Rifiutati o annullati»), the empty
  state; the detail («Dove si trova la spedizione», «Cronologia», «Hai annullato questo
  ordine il …», «Il centro logistico ha annullato questo ordine il …»); edit and cancel
  («Modifica l'ordine …», «Puoi cambiare pallet, dati della spedizione e destinatario finché il
  centro logistico non conferma l'ordine.», «Vuoi davvero annullare questo ordine?», «Sì,
  annulla l'ordine» / «No, tienilo»); the flash messages («Modifiche salvate: il centro
  logistico vede già l'ordine aggiornato.», «Ordine … annullato. Il centro logistico è stato
  avvisato.») and the refusals («L'ordine è stato modificato nel frattempo…», «Il centro
  logistico ha già gestito l'ordine: non si può più modificare né annullare.»).
* **Back office**: «Panoramica» (the lead «N richieste aspettano te: le trovi qui sotto.», the
  tiles, «Cerca e aggiorna»), «Nuovo ordine» / «Nuovo cliente» forms and their hints, «Codice e
  importo» for an ADMIN correction, «Annulla l'ordine …» with «Motivo (facoltativo, solo per
  l'ufficio)», the order flags («Inserito dall'ufficio», «Il cliente ha modificato l'ordine il
  …: controlla i dati prima di approvarlo.»), «Non ancora consegnati», the flash messages and
  refusals of `BackofficeOrderChangesController`, `BackofficeCustomerChangesController` and
  `OrderService`/`CustomerService` («Solo un amministratore può modificare o annullare un ordine
  già confermato.», «Il cliente non è attivo: approvalo prima di inserire un ordine.», «Esiste
  già un cliente con questa e-mail: cercalo tra i clienti.»…).
* **E-mails**: the two EML-06 office notices «Richiesta di spedizione {codice} modificata dal
  cliente» (with the current detail) and «… annullata dal cliente».

### Open points

* A cancelled approved order is excluded from reports (ADM-09 selects `APPROVED`); to keep
  cancelled shipments in the reports is a change to ADM-09 for the owner.
* The customer gets no e-mail when staff edit or cancel its order; if the owner wants one, it
  is a new EML entry.
* Reported by the motion worktree, not changed here: Lighthouse's phone profile measured below
  95 locally with and without the motion layer (PUB-10 budget; the Lighthouse CI job of step C
  is still missing) and a contrast finding on the tagline of the ported public header
  (pre-existing, PUB-01 allow-list). A local gitleaks run is still open from step C.

### Proposed changes to shared files

All shared files of this step were edited directly by the integrator: `CLAUDE.md` (dev-seed
line, motion convention, `CHROMIUM_PATH`), chapter 09 and 11, `messages.properties`,
`layout/private.html`, `docs/plan.md` §Status and §8.1, `docs/parity-matrix.md`,
`docs/adr/README.md`, `route-matrix.tsv`, the schema files.

## Step E — Audit of `main` and the 60-day sign-in (2026-10-25)

The owner's message of 2026-10-25: «Align with main and audit», then the M2 planning prompt,
and «Also set the lifetime of magic link of 60 days so customers don't have to always check
email inbox».

### Audit findings (fixed)

* **CI on `main` red since PR #20** (runs 45 and 46): the build job failed on two counts.
  * Spotless: `BackofficeOrderChangesIT` was not formatted (`./mvnw spotless:apply`).
  * `RouteMatrixIT` depended on the order the test classes run in: the matrix samples every
    `{id}` as `1`, and when order 1 is closed (approved, for an OPERATOR; declined or
    cancelled, for everyone) or customer 1 anonymised, the edit and cancel pages of ADR 0028
    send staff back to the record with a message (302). Reproduced on a fresh database with
    order 1 cancelled; the three rows now accept `404|200|302` for staff, with a comment.
* **A second order-dependent test**, found by running the suite alphabetically on a fresh
  database: `PhaseOneEntitiesIT.auditLogAndDetailRoundTrip` read the newest audit row of
  `shipment_order` 42, which the order tests may have written after the fixed test time; it
  now uses an entity id no test order reaches.
* `docs/plan.md` §Status still described step D as unmerged; updated.
* Open PR #14 «Dev script» (the owner's, an earlier version of the local scripts) is in conflict
  with `main` and looks superseded by #13 and #15–#19: left to the owner.

### The 60-day sign-in (ADR 0031; PD-10 decided)

The e-mailed link stays single use and valid 15 minutes; after it, with «Resta connesso su
questo dispositivo per 60 giorni» ticked (default), the device keeps a `__Secure-LS_REMEMBER`
cookie that opens a new session whenever the 60-minute one has ended, for 60 days from the
sign-in (not extended by use). Migration `V014__remembered_sign_in.sql`; `auth`
`RememberedSignInService`, `RememberedSignInFilter` (after `SessionLimitsFilter`, before the
CSRF filter), `ExpiredRememberedSignInRetentionJob`; «Esci», a decline, an e-mail change and
the anonymisation forget the devices, and so does a sign-in with the box unticked (a shared
computer must not reopen the previous customer's account); the registration summary offers the same choice; the
cookie page lists the cookie; setting `ls.security.sessions.customer.remember` (0 = off).
Tests: `RememberedSignInIT` (12), `RememberedSignInOffIT`, `RememberedSignInServiceTest`, the
E2E step «[CUS-01] «Resta connesso» opens the area again once the session has ended».

### New Italian texts for the owner's review

«Resta connesso su questo dispositivo per 60 giorni», «Togli la spunta se usi un computer
condiviso.», and on the cookie page the row `__Secure-LS_REMEMBER` «Ti mantiene connesso
all'area clienti su questo dispositivo, se all'accesso lasci la spunta «Resta connesso»» —
«60 giorni dall'accesso, o fino a quando premi «Esci»» (the intro now says «solo cookie
tecnici»).

### Open points

* No staff action signs a customer out of every device (the e-mail change does it); add one
  if the office needs it.
* M2.1: the proposal is in the chat of 2026-10-25; the owner picks the scope.

## Step F — Customer password sign-in (2026-10-25)

The owner's message of 2026-10-25: «se questo maledetto link non è comodo mettiamo una semplice
basic auth! non mi sembra di star chiedendo la luna!» — Q-56 added to chapter 11 and decided;
ADR 0032.

### What was built

* Migration `V015__customer_password.sql`: `customer_user.password_hash`,
  `password_changed_at` (both or neither).
* `auth.CustomerPasswordService`: `authenticate` (rate limits `signin.password.ip` 30 and
  `signin.password.email` 10 per 15 minutes, one answer for every refusal, bcrypt work in every
  case), `setPassword` (policy: 8 characters, `ls.security.customer-password.min-length`, and the
  common-password list; audit `customer.password.set` / `.change`), `isCurrentPassword`.
* Sign-in page `/area-clienti/`: e-mail and password with «Resta connesso» (`POST
  /area-clienti/password`), then «Prima volta o password dimenticata?» with the link form (button
  «Ricevi il link»). `CustomerSessionService` records how the session was opened (`LINK`,
  `PASSWORD`): after a link the profile sets a new password without the current one.
* Profile section «Password» (`POST /area-clienti/profilo/password`): set or change; a change ends
  the other sessions and remembered devices, this browser goes on. Registration summary: «Scegli
  la password» before «Conferma». Orders home: «Scegli una password per entrare senza aspettare
  l'e-mail.» until one is set. Anonymisation clears the password. `dev-seed` prints a password for
  the ACTIVE customer.
* Tests: `CustomerPasswordIT` (8), `DevSeedCommandIT` (customer password sign-in),
  `RateLimiterTest` (the two keys), registration helpers send the password, route matrix (two
  POST routes), schema files; E2E: the registration chooses the password, «[CUS-01] the customer
  signs out and back in with e-mail and password».

### New Italian texts for the owner's review

Sign-in page: «Accedi all'area clienti», «Password», «Accedi», «E-mail o password non corrette.»,
«Prima volta o password dimenticata?», «Ti mandiamo un link per entrare; se sei un nuovo cliente,
il link ti porta alla registrazione.», «Ricevi il link» (also on «Il link non è più valido»).
Profile: «Password», «Scegli una password per entrare con e-mail e password, senza aspettare il
link.», «Cambia la password con cui entri: le altre sessioni e i dispositivi ricordati vengono
chiusi.», «Password attuale», «Nuova password», «Ripeti la password», «Almeno {n} caratteri,
diversa dalle password più comuni.», «Salva la password», «Password salvata: da ora entri con
e-mail e password.», «La password attuale non è corretta.», «Le due password non coincidono.»,
«La password deve avere almeno {n} caratteri.», «Questa password è troppo comune: scegline
un'altra.». Registration: «Scegli la password». Home: «Scegli una password per entrare senza
aspettare l'e-mail.», «Imposta la password».

### Open points

* No e-mail notice when the password changes; add one (a new EML entry) if the owner wants it.
* M2.1 still waits for the owner's pick (proposal in the chat of 2026-10-25).

## Step G — M2.1 item 0: the flags screen (2026-10-26)

The owner's message of 2026-10-26 picked the scope of M2.1 and the order of the work: «0.
Flags screen (/gestione/impostazioni/): show for each flag a one-line Italian description of
what it does and its state: «Disponibile» when built, «Non ancora sviluppata» otherwise, with
the toggle disabled for flags not yet built. Built flags go first.» Then ENH-07, ENH-05 and the
office tools, one PR each.

### What was built

* `FlagKey.built()`: whether the feature behind the flag exists, set in the code by the change
  that builds it (all false in this step).
* «Funzionalità in prova» lists, per flag, its description (`backoffice.settings.flag.<key>`),
  the key, the ENH id, «Sviluppo» («Disponibile» / «Non ancora sviluppata»), «Stato»
  («Attiva» / «Spenta») and the toggle; built flags first, then the others, each group in ENH
  order. The toggle of a flag not built is disabled; the controller refuses a crafted request
  to turn one on («La funzionalità «…» non è ancora sviluppata: non si può attivare.») and lets
  a flag left on be turned off. ADR 0025 has the dated line.
* Tests: `FlagDescriptionsTest` (every flag has a one-line description, no leftover key; order
  and toggles of the rows), `BackofficeSettingsIT` (descriptions and order on the page,
  disabled toggles, refusal without an audit row, turning off with the audit entry).

### New Italian texts for the owner's review

«Con una funzionalità spenta l'applicazione si comporta come oggi. Attiva quelle disponibili
solo quando l'ufficio è pronto a usarle; le altre non sono ancora sviluppate.», «Funzionalità»,
«Sviluppo», «Disponibile», «Non ancora sviluppata», «Non ancora sviluppata: non si può
attivare.», «La funzionalità «{chiave}» non è ancora sviluppata: non si può attivare.», and the
25 one-line descriptions under `backoffice.settings.flag.*` in `messages.properties`.

## Step H — M2.1 item 1: ENH-07, saved recipients and «Ordina di nuovo» (2026-10-26)

The owner's item 1: «saved recipients per customer: pick one in the order form, "save this
recipient", manage the list in the profile; staff can use the customer's address book when
entering an order by phone (ADM-11); «Ordina di nuovo» on an order: opens the order form
prefilled with lines, service, recipient and notes; a new transport document code is still
required.» ADR 0033.

### What was built

* Migration `V016__customer_address_book.sql`: `customer_address` (the recipient fields of an
  order, one entry per name, street and CAP, at most 200 per customer), in the `customers` module:
  `CustomerAddress`, `AddressBookService` (list, find, create, update, delete, `remember` from an
  order, `erase`), audit `address.create` / `.update` / `.delete` on the customer.
* Profile: «Rubrica dei destinatari» (list, «Modifica», «Elimina», «Aggiungi destinatario»;
  `AddressBookController`, `/area-clienti/profilo/rubrica/nuovo`, `/{id}`, `/{id}/elimina`).
* Order forms (`orders.OrderAddressBook`): «Scegli dalla rubrica» + «Usa questo destinatario»
  (server round trip; `/js/app.js` fills the fields at once), «Salva il destinatario nella
  rubrica» (saved at the confirmation of a new order, or when a change is saved; the summary says
  so). Staff: «Mostra la rubrica del cliente» after choosing the customer, «Salva il destinatario
  nella rubrica del cliente».
* «Ordina di nuovo» on the customer's order detail (`POST /area-clienti/ordini/{codice}/ripeti`,
  any state, while the customer can order) and «Ripeti ordine» on the back-office order page
  (`/gestione/ordini/nuovo?ripeti={id}`): lines, accessory service, notes and recipient copied,
  transport document and «Numero colli» left empty.
* Privacy: the export lists `rubricaDestinatari` when there are entries; the anonymisation
  deletes them (and the audit details go with the customer's).
* `FlagKey.built()` true for `customers.addressBook` and `orders.repeat`; ADR 0025 dated line.
* Tests: `AddressBookIT` (6, flags on), `AddressBookOffIT` (2, flag off = M1),
  `AddressBookServiceTest`, `BackofficeSettingsIT` (a built flag on and off with audit), schema
  files, route matrix, `MigrationsIT`/`ApplicationContextIT` (16 migrations); E2E
  `e2e/tests/m2.spec.ts` (the ADMIN turns the flags on from «Impostazioni», the customer and the
  staff use the book and «Ordina di nuovo», axe at desktop and phone width, flags off again).
  `PhaseOneEntitiesIT.emailOutboxRoundTrip` no longer depends on how many e-mails other classes
  leave queued. `tools/parity-matrix.py` lists the ENH rows that have tests.

### New Italian texts for the owner's review

Profile: «Rubrica dei destinatari», «I destinatari a cui spedisci più spesso: nel modulo d'ordine
li scegli senza riscriverli.», «La rubrica è vuota. Aggiungi un destinatario qui, oppure spunta
«Salva il destinatario nella rubrica» quando invii un ordine.», «Contatti», «Aggiungi
destinatario», «Elimina», «Nuovo destinatario», «Modifica il destinatario», «Torna alla rubrica»,
«Salva il destinatario», «Questo destinatario è già nella rubrica.», «Destinatario «…» salvato
nella rubrica.», «… aggiornato.», «… eliminato dalla rubrica.», «La rubrica può contenere al
massimo 200 destinatari: eliminane qualcuno.». Order form: «Scegli dalla rubrica», «Destinatari
salvati», «Usa questo destinatario», «Scegli un destinatario della rubrica.», «La rubrica è vuota:
spunta «Salva il destinatario nella rubrica» qui sotto per ritrovarlo la prossima volta.», «Salva
il destinatario nella rubrica», «Quando invii l'ordine, il destinatario viene salvato nella
rubrica.», «Il destinatario non è stato salvato nella rubrica: ha già il numero massimo di voci
(200).». «Ordina di nuovo», «Il modulo riporta i dati dell'ordine {codice}: inserisci il nuovo
documento di trasporto e controlla il resto.». Back office: «Ripeti ordine», «Mostra la rubrica
del cliente», «Salva il destinatario nella rubrica del cliente», «La rubrica del cliente è vuota:
spunta «Salva il destinatario nella rubrica del cliente» per ritrovarlo la prossima volta.».

## Step I — M2.1 item 2: ENH-05, tracking e-mails, timeline and delivery day (2026-10-26)

The owner's item 2: «an e-mail to the customer at every tracking state change (through the
outbox, once per transition; PRESA is already covered by the approval e-mail and CONSEGNATO by
EML-09); a timeline with dates in the customer's order detail and on public tracking, within the
TRK-03 limits; an indicative estimated delivery date from the recipient's CAP, using the
DeliveryZones dataset and working days from pickup.» ADR 0034.

### What was built

* `tracking.notifyCustomer`: `orders.TrackingUpdateNotice` decides, `OrderService.setTrackingState`
  queues EML-10 «Aggiornamento spedizione N. {codice}: {stato}» in the event's transaction for
  TRANSITO, TERMINAL_DEST and ASSEGNATO (not PRESA, CONSEGNATO, NON_GESTITO; nothing when a state
  is set again). Migration `V017__tracking_update_email.sql` (`ck_email_outbox_kind` accepts
  `EML-10`).
* `tracking.history`: the public answer adds `eta` and `events` `{time, status, location}`,
  newest first (`TrackingLookupService.history`, `CompatApiController`); the customer's order
  detail shows under «Dove si trova» the delivery day and the states with their dates.
* `tracking.DeliveryEstimate`: zone 1/2/3 of the CAP widget's dataset as 1/2/3 working days after
  the Europe/Rome day of the approval (Monday–Friday, Italian national holidays with Easter
  Monday); none for minor islands, the Venice lagoon or an unknown CAP; the day of delivery once
  CONSEGNATO. `ItalianFormats.formatLongDate` («martedì 4 marzo 2025»).
* `FlagKey.built()` true for both flags; ADR 0025 dated line; notes in ADR 0010 and 0015.
* Tests: `DeliveryEstimateTest` (26), `TrackingHistoryIT` (4, flags on: allow-list and no
  personal data, delivery day, timeline, one e-mail per change), `TrackingHistoryOffIT` (flag off
  = M1), schema counts (17 migrations); E2E `m2.spec.ts`: the ADMIN turns the flags on, a change
  to «In transito» e-mails the seeded customer, the order detail and `/tracking/` show the
  history and the delivery day, axe on the order detail.

### New Italian texts for the owner's review

E-mail EML-10: subject «Aggiornamento spedizione N. {codice}: In transito» (or «Arrivata al
terminal di destinazione», «Affidata al mezzo per la consegna»); title «Aggiornamento sulla
spedizione N. {codice}: {stato}»; «La spedizione è in viaggio verso il terminal della zona di
destinazione.», «La spedizione è arrivata al terminal più vicino al destinatario ed è pronta per
la consegna.», «La spedizione è stata affidata al mezzo che effettua la consegna.»; table
«Spedizione» (Codice, Stato, Destinatario, Città, Codice documento trasporto, «Consegna prevista
(indicativa)»); «Ti scriviamo a ogni passaggio della spedizione; alla consegna ricevi la
conferma.»; button «Traccia la spedizione». Customer area: «Consegna prevista», «Consegnata»,
«Indicativa: dal CAP del destinatario, in giorni lavorativi dal ritiro.».

## Step J — M2.1 item 3: the office tools, ENH-15 and ENH-08 (2026-10-26)

The owner's item 3: «ENH-15 (flag orders.bulkTracking): set one tracking state on many codes at
once (paste a list); a «Oggi» list on the Panoramica: to approve, to pick up, in transit for more
than N days; free-text search on orders (recipient, city, transport document, customer); ENH-08
exports (flag admin.exports): CSV of the order and customer lists, cells written as text.»
ADR 0035.

### What was built

* `orders.bulkTracking`: «Aggiorna molte spedizioni» on «Tracking»; `POST
  /gestione/tracking/in-blocco` with `action=preview` (the confirmed orders found and the codes
  that name none) and `action=apply` (`OrderService.setTrackingStates`: one transaction, one time,
  one `tracking_event` with source `BULK` per changed order, audit `order.tracking` with
  `"source": "BULK"`, EML-09 or the ENH-05 e-mail as for a single change; at most 200 codes).
  `setTrackingState` and the bulk change share `applyTrackingState`.
* `admin.dashboard`: «Oggi» on the Panoramica (`BackofficeHome.today`: to approve, to pick up, on
  the way for more than `ls.backoffice.today-transit-days` days, default 3; 8 rows each, oldest
  first, with the totals) and the free-text search of «Ordini» in the existing `codice` field
  (`OrderSearch.text`: codes, recipient, city, DDT, customer). `OrderFilters` holds the list's
  filters for the list and its export.
* `admin.exports`: «Esporta CSV» on «Ordini» (`GET /gestione/ordini/esporta`, the list's filters)
  and «Registrati» (`GET /gestione/clienti/esporta`, the list's search); `CsvFile` (UTF-8 BOM, `;`,
  CRLF, quoted cells, apostrophe before `=`, `+`, `-`, `@`, tab, CR); at most 10,000 rows; audit
  `orders.export` / `customers.export` with the row count.
* `FlagKey.built()` true for the three flags; routes in `BackofficeRoutes` and the route matrix;
  ADR 0025 dated line.
* Tests: `OfficeToolsTest` (8: the text-cell rule, the exact CSV bytes, the pasted-list parsing),
  `OfficeToolsIT` (4, flags on: preview then apply with one event per changed order and the same
  user and time, a pending order's temporary code and an unknown code reported, EML-09 only for
  the orders that change; «Oggi» groups; the search by recipient, city, DDT and customer; both
  exports with a `=HYPERLINK(` value kept as text and the audit rows), `OfficeToolsOffIT` (flags
  off = M1: 404 for exports and bulk, no «Oggi», code-only search); E2E `m2.spec.ts`: the ADMIN
  turns the flags on, a pasted list sets «Assegnato» on TEST-0002 and reports an unknown code
  (the ENH-05 e-mail follows), «Oggi», the search by «Destinatario Due», «como» and «Ditta
  Attiva», both CSV downloads; axe and phone width on the Panoramica, «Tracking», «Ordini» and
  «Registrati».

### New Italian texts for the owner's review

«Tracking»: «Aggiorna molte spedizioni»; «Incolla i codici, uno per riga oppure separati da spazi
o virgole, e scegli lo stato: prima di applicarlo controlli l'elenco.»; «Codici delle
spedizioni»; «Al massimo 200 codici per volta. Solo le spedizioni confermate.»; «Scegli lo
stato»; «Controlla l'elenco»; «Spedizioni trovate: N»; «Codici non trovati o non ancora confermati:»; «Già in questo
stato, non cambiano: N.»; «Imposta «Stato» su N spedizioni» (N those that change; 1: «su 1
spedizione»); «Ogni cambio
di stato vale come quello fatto spedizione per spedizione, e-mail ai clienti comprese.»; after
the change «Tracking «Stato» impostato su N spedizioni. N erano già in questo stato. Codici non
trovati o non ancora confermati: …»; errors «Incolla almeno un codice di spedizione.», «Al
massimo 200 codici per volta.», «Scegli il nuovo stato di tracking.», «Nessun codice dell'elenco
è di una spedizione confermata.», «Un ordine dell'elenco non è più confermato: ricontrolla i
codici.».

Panoramica: «Oggi»; «Le cose da fare adesso, le più vecchie in alto.»; «Da approvare»; «Da
ritirare» with «Confermati, ancora in «Presa in carico».»; «In viaggio da più di N giorni» with
«In transito, al terminal o in consegna, confermati prima del gg/mm/aaaa.»; «Niente.»; «e altri
N»; «dal gg/mm/aaaa».

«Ordini»: the field «Cerca» with «Codice, destinatario, città, DDT o cliente». «Ordini» and
«Registrati»: «Esporta CSV» (tooltip «Il file contiene tutte le righe dei filtri scelti, non solo
la pagina.»). CSV headers — orders: ID, Codice, Codice provvisorio, Inviato il, Confermato il,
Cliente mittente, Codice Doc. Trasporto, Numero colli, Destinatario, Indirizzo, Città,
Provincia, Cap, Telefono, Email destinatario, Peso (kg), Volume (m³), Importo, Ser. Accessorio,
Stato, Tracking, Tracking aggiornato il; customers: ID, Ragione sociale, Email, Indirizzo,
Provincia, Città, Cap, Telefono, Partita IVA, Codice fiscale, Email confermata, Stato,
Registrato il.

## Step K — M2: ENH-06, the website requests in the back office (2026-10-26)

The owner asked to build the M2 items that need nothing from them («procedi con quelli che puoi
fare ora»): ENH-06, ENH-20, ENH-16, ENH-09, ENH-13, ENH-14, ENH-17, ENH-18, one PR each. First
ENH-06. ADR 0036.

### What was built

* `leads.inbox`: «Richieste» (`/gestione/richieste/`): the stored website requests, newest
  first, tabs by state, search; the page of a request with the form's data, `mailto:`/`tel:`
  links, the moves of chapter 08 §2.4 (`LeadStatus`), the customer (linked, or registered with the
  same e-mail: «Collega la richiesta al cliente»), «Crea cliente» and «Crea ordine» filled from
  the request (`LeadDrafts`, `OrderForm.prefilled`); the order made marks the request WON.
  `LeadService` (site.forms) holds the rules and the audit (`lead.status`, `lead.customer`,
  `lead.convert`); `WorkQueue.leads` counts the new requests for the navigation. No migration.
* Privacy: a request linked to a customer is exported and anonymised with the customer even
  when its e-mail differs (`PrivacyService` → `LeadService.exportFor`, `anonymiseByCustomer`).
* `dev-seed`: three synthetic requests (`richiesta.preventivo@`, `richiesta.contatti@`,
  `richiesta.contattata@cliente.example`) through `FormValidator` and `LeadService.store`.
* Tests: `LeadStatusTest`, `LeadDraftsTest`, `LeadsInboxIT` (5), `LeadsInboxOffIT`,
  `DevSeedCommandIT`, route matrix; E2E `m2.spec.ts`: the ADMIN turns the flag on, the seeded
  quote becomes «Contattata», then a customer and an order; axe on the request page and the list.

### New Italian texts for the owner's review

Navigation «Richieste»; page «Richieste dal sito» («Le richieste di preventivo e i messaggi
arrivati dai moduli del sito, le più recenti in alto.»), tabs «Nuove», «Contattate», «Vinte»,
«Perse», «Spam», «Tutte»; search «Nome, azienda, e-mail, telefono o città»; columns «Arrivata
il», «Modulo», «Da», «Richiesta», «Stato»; states «Nuova», «Contattata», «Vinta», «Persa»,
«Spam». The page of a request: «Modulo «Preventivo», arrivata il …»; sections «Spedizione»,
«Merce», «Messaggio», «Contatti», «Cliente», «Stato della richiesta»; «Da concordare»; «Collegata
al cliente»; «C'è già un cliente registrato con questa e-mail:» with «Collega la richiesta al
cliente»; «Nessun cliente registrato con questa e-mail: con «Crea cliente» lo registri con i
dati della richiesta.»; «Ordine creato dalla richiesta:»; ««Crea ordine» apre il modulo con i
dati della richiesta: quando lo salvi, la richiesta diventa «Vinta».»; buttons «Crea cliente»,
«Crea ordine», «Segna come contattata / vinta / persa / spam»; ««Vinta», «Persa» e «Spam» sono
definitivi.». Messages: «Richiesta segnata come «Contattata».», «Questo passaggio di stato non è
previsto per la richiesta.», «Scegli il nuovo stato della richiesta.», «Richiesta collegata al
cliente «…».», «Nessun cliente registrato con l'e-mail della richiesta.», «La richiesta è
collegata al nuovo cliente.», «La richiesta del sito è segnata come vinta.», «Il modulo riporta i
dati della richiesta del sito: completa il destinatario, il documento di trasporto e le righe che
mancano.»; on «Nuovo cliente»: «I dati vengono dalla richiesta del sito: controllali e completa
indirizzo e provincia.».

## Step L — M2: ENH-20, saved quotes (2026-10-26)

Second of the M2 items of the owner's request «procedi con quelli che puoi fare ora». ADR 0037.

### What was built

* Module `quotes` and migration `V018__saved_quotes.sql` (`quote`, `quote_line`; outbox kinds
  `EML-10`…`EML-19`): `QuoteService` (create, withdraw, accept, export and erasure),
  `QuoteRepository`, `QuoteTokens` (32 random bytes, only the SHA-256 stored), `QuotePdf`,
  `QuoteMails` (EML-11 «Il tuo preventivo PRV-…»), `QuotePageController` (`/preventivi/{token}`
  and `/pdf`, public, noindex, no-store), `QuoteAcceptController`
  (`/area-clienti/preventivi/{id}/ordina`), `QuoteNotices` (open quotes on «I miei ordini»).
* Back office: «Prepara preventivo» on a request linked to an active customer
  (`BackofficeQuotesController`, the order form's line rows filled from the request,
  `OrderForm.validateLines`), the quotes of a request with «Ritira» and the PDF; «Dal preventivo»
  on the staff order page and the quote's amount proposed by «Approva».
* `LeadService.converted` takes the actor (the customer, when a quote is ordered);
  `PrivacyService` exports and erases the customer's quotes.
* Tests: `QuoteTokensTest`, `SavedQuotesIT` (5), `SavedQuotesOffIT`, schema expectations and
  migration counts (18), route matrix; E2E `m2.spec.ts`: a quote request sent with the seeded
  customer's e-mail is linked, quoted, e-mailed, opened from the link and ordered.

### New Italian texts for the owner's review

E-mail EML-11: subject «Il tuo preventivo PRV-…»; title «Il tuo preventivo PRV-…»; «Grazie per la
tua richiesta: ecco il preventivo che abbiamo preparato per te.»; table «Preventivo» (Preventivo,
Importo, Valido fino al, Tratta, Merce «N unità, N righe»); «Dalla pagina del preventivo puoi
scaricarlo in PDF e, quando sei pronto, trasformarlo in un ordine: accedi all'area clienti,
completa destinatario e documento di trasporto e invia.»; button «Vedi il preventivo». The page of
the link: «Preventivo PRV-…», «Per {ditta}, del {data}», Importo, Valido fino al, Ritiro,
Consegna, Servizio accessorio, Note, «Pallet e colli» (Pallet / colli, Peso unitario, Quantità),
«Ordina il preventivo», «Scarica il PDF», «Per ordinare accedi all'area clienti: trovi il
preventivo anche in «I miei ordini».»; closed: «Hai già trasformato il preventivo … in un
ordine.», «Il preventivo … è stato ritirato: chiedici un nuovo preventivo.», «Il preventivo … è
scaduto il …: chiedici di aggiornarlo.». The PDF: «… - Preventivo PRV-…», «Del …, valido fino al
…», Cliente, the lines, «Importo: …», «Per ordinare apri il link ricevuto per e-mail, accedi
all'area clienti e completa destinatario e documento di trasporto.». Customer area: «Hai un
preventivo da ordinare» with «PRV-…: 180,50 €, valido fino al …»; «Ordina il preventivo PRV-…»,
«Importo …, valido fino al …. Completa i dati della spedizione e il destinatario.», «Cosa
spedisci», «Righe e servizio sono quelli del preventivo: per cambiarli chiedici un nuovo
preventivo.», «Invia l'ordine», «L'ordine arriva all'ufficio con un codice provvisorio: lo
confermiamo con codice e importo.», flash «Ordine TEMP-… inviato dal preventivo PRV-…: lo
confermiamo con codice e importo.». Back office: «Prepara preventivo», «Per {ditta}: il link al
preventivo arriva a {e-mail}.», «Prezzo e validità», «Importo (€)», «Valido fino al», «Città di
consegna», «CAP di consegna», «Nota per il cliente (facoltativa)», «Condizioni, tempi, IVA:
compare sulla pagina del preventivo e nel PDF.», «Invia il preventivo»; «Preventivi» (Preventivo,
Importo, Valido fino al, Stato: «Inviato», «Ordinato», «Ritirato», «Scaduto»), «Vedi l'ordine»,
«Ritira»; «Per preparare un preventivo collega prima la richiesta a un cliente attivo.»; «Dal
preventivo»; «Importo del preventivo PRV-…: ….»; flashes «Preventivo PRV-… inviato a …: il
cliente lo trova nel link dell'e-mail e in «I miei ordini».», «Preventivo PRV-… ritirato: il
link ora dice che non è più valido.»; errors «Collega prima la richiesta a un cliente attivo: il
preventivo è per lui.», «La richiesta è già chiusa: il preventivo non si prepara più.», «Aggiungi
almeno una riga di pallet o colli.», «Inserisci un importo maggiore di zero, per esempio
180,50.», «Scegli fino a quando vale il preventivo: da oggi a un anno.», «La nota per il cliente
può avere al massimo 1000 caratteri.», «Il preventivo PRV-… non è più ritirabile.».

## Step M — M2: ENH-16, the customer's own data changes (2026-10-26)

Third of the M2 items of the owner's request «procedi con quelli che puoi fare ora». ADR 0038.

### What was built

* Migration `V019__customer_change_requests.sql` (`customer_change_request`, one PENDING per
  customer); in `customers`: `CustomerChangeService` (save on the page's version, withdraw,
  approve, decline, export, erasure), `CustomerChangeRequest`, `CustomerIdentity`,
  `ChangeRequestView`, `ProfileEditController` (`/area-clienti/profilo/dati`, `…/dati/ritira`),
  `Customer.changeContacts` / `changeIdentity`, `CustomerRepository.lockById`, `CustomerMails`
  (EML-06 office notices, EML-12 to the customer).
* Profile: «Modifica i dati» for an ACTIVE customer in place of «Chiedi una modifica», the change
  waiting for approval with «Ritira la richiesta».
* Back office: the request on the customer page with «Approva la modifica» / «Rifiuta la
  modifica» (`BackofficeCustomersController`), the section «Modifiche dei dati da approvare» on
  «In Approvazione», `WorkQueue.customerChanges` in the navigation count and on the home.
* `PrivacyService` exports («richiesteModificaDati») and erases the requests.
* Tests: `ProfileEditIT` (6), `ProfileEditOffIT`, schema expectations and migration counts (19),
  route matrix; E2E `m2.spec.ts`: a customer created by the office changes its phone and asks for
  a new company name, the office approves, the customer gets EML-12; the form under axe and on a
  phone.

### New Italian texts for the owner's review

Profile: «Puoi aggiornare tu i tuoi dati: indirizzo e telefono cambiano subito, ragione sociale,
partita IVA e codice fiscale dopo l'approvazione del nostro ufficio.», «Modifica i dati», «Il
{data} hai chiesto di cambiare questi dati: la modifica aspetta l'approvazione del nostro
ufficio.», «Ritira la richiesta». Form «Modifica i tuoi dati»: «Torna al profilo», «Indirizzo e
telefono cambiano subito. Ragione sociale, partita IVA e codice fiscale cambiano quando il nostro
ufficio approva la modifica.», «Ragione sociale e dati fiscali», «Cambiano dopo l'approvazione del
nostro ufficio: ti avvisiamo via e-mail.», «In attesa di approvazione. Ora: …», «Indirizzo e
telefono», «È l'indirizzo del ritiro: vale per i prossimi ordini, quelli già inviati restano con i
dati di allora.», «Salva i dati»; flashes «Dati salvati: valgono per i prossimi ordini.», «La
modifica di ragione sociale, partita IVA o codice fiscale è stata inviata al nostro ufficio: vale
quando la approviamo e ti avvisiamo via e-mail.», «La richiesta di modifica in attesa è stata
ritirata.», «Non hai cambiato nessun dato.», «Hai ritirato la richiesta di modifica: i tuoi dati
restano quelli di prima.», «Non c'è nessuna richiesta di modifica in attesa.»; errors «Puoi
modificare i tuoi dati quando il tuo account è attivo.», «I tuoi dati sono stati modificati nel
frattempo: controlla i dati aggiornati e riprova.». Back office: «Modifica dei dati da approvare»,
«Il cliente l'ha chiesta dal suo profilo il …. Approvando, i nuovi dati valgono per i prossimi
ordini.» (Dato, Ora, Richiesto), «I dati richiesti sono già quelli attuali: approvando non cambia
nulla.», «Approva la modifica», «Motivo del rifiuto (facoltativo, lo legge il cliente)», «Rifiuta
la modifica»; «Modifiche dei dati da approvare», «Ragione sociale, partita IVA o codice fiscale che
i clienti hanno chiesto di cambiare dal loro profilo: apri il cliente per approvare o rifiutare.»
(Modifica richiesta, Chiesta il, «Apri il cliente»); home «Registrazioni e modifiche dei dati dei
clienti in attesa della tua approvazione.»; flashes «Modifica approvata: i dati di «…» sono
aggiornati e il cliente riceve un'e-mail.», «Modifica rifiutata: i dati restano quelli di prima e
il cliente riceve un'e-mail.», «La richiesta di modifica non è più in attesa.». E-mails: EML-06
«Dati del cliente aggiornati» / «Modifica dei dati da approvare» («Hai una nuova notifica: un
cliente ha aggiornato i suoi dati dall'area clienti. Valgono per i prossimi ordini.» / «… un
cliente chiede di cambiare ragione sociale, partita IVA o codice fiscale. La modifica aspetta la
tua approvazione nella pagina del cliente.», tables «Dati aggiornati» «… (prima: …)» and «Da
approvare» «… (ora: …)», button «Apri il cliente»); EML-12 «Dati aggiornati» («Abbiamo
aggiornato i tuoi dati», «Il nostro ufficio ha approvato la modifica che hai chiesto. Ecco i tuoi
dati:», «Valgono per i prossimi ordini: quelli già inviati restano con i dati di allora.») and
«Modifica dei dati non accettata» («La modifica dei tuoi dati non è stata accettata», «Il nostro
ufficio non ha accettato la modifica di ragione sociale, partita IVA o codice fiscale che hai
chiesto: i tuoi dati restano quelli di prima.», «Motivo: …»), button «Vai al tuo profilo».

## Step N — M2: ENH-09, two-factor login for staff (2026-10-26)

Fourth of the M2 items of the owner's request «procedi con quelli che puoi fare ora». ADR 0039.

### What was built

* Migration `V020__staff_two_factor.sql` (`staff_user.totp_enabled_at`, `totp_last_step`, check
  `ck_staff_user_totp_complete`); in `auth`: `Totp` (RFC 6238), `TotpQr` (ZXing core 3.5.3, a new
  dependency, the QR code as one inline SVG path), `TotpSecrets` (AES-256-GCM, key derived from
  `LS_TOKEN_ENCRYPTION_KEY`), `StaffTwoFactorService`, `PendingStaffLogin`, `StaffSignIn`,
  `StaffTwoFactorController` (`/gestione/verifica`, a sign-in entry of `RouteRegistry`),
  `ResetStaffTotpCommand` (`reset-staff-totp`, dispatched by `Application.main`); the success
  handler holds a staff login for the code while the flag is on.
* «Utenti della gestione»: the two-factor state of each account and «Azzera»
  (`BackofficeSettingsController`); `StaffAccountService.StaffUserView.twoFactor`.
* The unused setting `ls.security.staff.totp.enabled` (ADR 0003) is removed; ADR 0001 lists
  ZXing; the deploy runbook names the reset command.
* Tests: `TotpTest` (RFC 6238 vectors, Base32, window and replay, QR), `StaffTwoFactorIT` (5),
  `StaffTwoFactorOffIT`, schema expectations and migration counts (20), route matrix,
  `PhaseOneEntitiesIT` and `SettingsDefaultsIT` adjusted; E2E `m2.spec.ts`: the seeded ADMIN sets
  the app up in a second browser (axe), a wrong code, the right one, then a second login with the
  code only (axe), «Attiva» on «Utenti della gestione» (axe).

### New Italian texts for the owner's review

«Verifica in due passaggi», «Inserisci il codice di 6 cifre che vedi nell'app di autenticazione
del tuo telefono.»; «Configura la verifica in due passaggi», «Per entrare nella gestione ora serve
anche un codice dal tuo telefono. Configuralo una volta sola: ci vuole un minuto.», «1. Collega
l'app», «Apri l'app di autenticazione del telefono (per esempio Google Authenticator o Microsoft
Authenticator), aggiungi un account e inquadra il codice QR.», «Codice QR da inquadrare con l'app
di autenticazione», «Non riesci a inquadrarlo? Scegli «Inserisci una chiave» nell'app e scrivi
questa chiave:», «2. Conferma con il primo codice», «Codice», «Le 6 cifre dell'app: cambiano ogni
30 secondi.», «Verifica», «Il codice non è corretto o è scaduto: riprova con quello che vedi
ora.», «Torna al login»; login page «La verifica non è riuscita o è scaduta: accedi di nuovo.».
«Utenti della gestione»: «Verifica in due passaggi» («Attiva», «Da configurare al prossimo
accesso», «Azzera», «Azzera la verifica in due passaggi di …»), «Chi entra nella gestione inserisce
anche il codice dell'app del telefono. Se un utente cambia o perde il telefono, azzera la sua
verifica: al prossimo accesso la configura di nuovo.», flash «Verifica in due passaggi di «…»
azzerata: al prossimo accesso la configura di nuovo.». Command: «Verifica in due passaggi
azzerata per …: al prossimo accesso la configura di nuovo.», «Nessun utente con username …»,
«Uso: reset-staff-totp --username=<username>».

## Step O — M2: ENH-13, the audit log viewer (2026-10-26)

Fifth of the M2 items of the owner's request «procedi con quelli che puoi fare ora». ADR 0040.

### What was built

* `platform.audit`: `AuditService.search` (plain SQL over `audit_log`, never `audit_log_detail`,
  newest first, a page at a time), `actions()`, `entityTypes()`, `AuditQuery`, `AuditView`.
* Back office: «Registro delle attività» (`BackofficeAuditController`, `/gestione/impostazioni/registro/`,
  ADMIN only) with filters by who, action, object and id, Europe/Rome days; the link on
  «Impostazioni».
* Tests: `AuditViewerIT` (3: an object's log newest first without personal values; filters by
  staff user, customers, action, Rome days, unknown values ignored; ADMIN only, link on the
  settings), `AuditViewerOffIT`; route matrix; E2E `m2.spec.ts`: the ADMIN filters the log by
  `flag.toggle` and finds the features it turned on (axe; the page on a phone in the NFR-01 list).

### New Italian texts for the owner's review

«Registro delle attività», «Chi ha fatto cosa e quando, le azioni più recenti in alto. Di ogni
azione vedi gli identificativi e i campi cambiati, mai i dati personali.»; filters «Chi» («Tutti»,
«Tutto lo staff», each user, «Clienti», «Sistema»), «Azione» («Tutte»), «Oggetto», «ID
dell'oggetto», «dal», «al»; columns «Quando», «Chi», «Azione», «Oggetto», «Modifica»; «Cliente ·
accesso N», «Staff n. N», «Dati personali registrati a parte: non si mostrano.», «Nessuna attività
registrata con questi filtri.».

## Step P — M2: ENH-17, the notice banner of the public site (2026-10-26)

Sixth of the M2 items of the owner's request «procedi con quelli che puoi fare ora». ADR 0041.

### What was built

* Migration `V021__site_notices.sql` (`site_notice`); package `site.notices`: `SiteNoticeService`
  (the notice in force, cached 30 seconds and forgotten at every change; create with the rules of
  the form, end, delete; audited), `SiteNoticeRepository`, `SiteNoticeView`, `NoticeLevel`,
  `SiteNoticeModelAttributes` (`siteNotice` for the site module's pages).
* `layout/public :: siteHeader` draws the notice above the top bar (classes `site-notice`,
  `site-notice-warning` in `main.css`); «Avvisi sul sito» (`BackofficeNoticesController`,
  `/gestione/impostazioni/avvisi/`, ADMIN only) and its link on «Impostazioni».
* Tests: `SiteNoticesIT` (3: published and shown on every public page, not in the customer area,
  no inline style or script; only within its Rome period, closed and deleted; unsafe links and
  impossible periods refused, OPERATOR refused), `SiteNoticesOffIT`; schema expectations and
  migration counts (21), route matrix; E2E `m2.spec.ts`: the ADMIN publishes a notice, the public
  pages show it with its link (axe), «Chiudi ora» takes it away.

### New Italian texts for the owner's review

«Avvisi sul sito», «Un messaggio in cima a tutte le pagine del sito: chiusure, ritardi, novità. Si
vede un avviso alla volta, quello iniziato per ultimo.»; columns «Avviso», «Periodo», «Stato»
(«Sul sito ora», «Programmato», «Concluso»), «dal …», «al …», «finché non lo chiudi»; levels
«Informazione», «Attenzione»; «Chiudi ora», «Elimina», «Nessun avviso: il sito non mostra nulla in
cima alle pagine.»; form «Nuovo avviso», «Testo dell'avviso», «Link (facoltativo)», «Una pagina del
sito, per esempio /contatti/, o un indirizzo https://.», «Testo del link», «Tipo», «Dal
(facoltativo)», «Al (facoltativo)», «Pubblica l'avviso»; errors «Scrivi il testo dell'avviso.»,
«Il testo può avere al massimo 300 caratteri.», «Inserisci l'indirizzo del link, oppure togli il
testo del link.», «Il link è una pagina del sito (per esempio /contatti/) o un indirizzo
https://.», «Scrivi il testo del link, per esempio «Scopri di più».», «Il testo del link può avere
al massimo 60 caratteri.», «La fine deve venire dopo l'inizio.», «La fine è già passata: scegli
una data futura.», «Inserisci data e ora (giorno, mese, anno, ore e minuti).»; flashes «Avviso
salvato: le pagine del sito lo mostrano nel periodo scelto.», «Avviso chiuso: non compare più sul
sito.», «L'avviso era già concluso.», «Avviso eliminato.»; the banner's region label «Avviso».

## Step Q — M2: ENH-18, «Aperto ora / Chiuso ora» (2026-10-26)

Seventh of the M2 items of the owner's request «procedi con quelli che puoi fare ora». ADR 0042.

### What was built

* Migration `V022__office_closures.sql` (`office_closure`); package `site.hours`: `OpeningHours`
  (the weekly ranges of `ls.business.hours`, Sunday closed, closure days; instants of Europe/Rome),
  `OfficeClosureService` (the openings of the next 14 days as JSON for the pages, kept 60 seconds
  and forgotten at every change; closures added by day or range and removed; audited),
  `OpenNowModelAttributes` (`openNow` for the site module's pages).
* `layout/public`: the hidden badge after the hours in the top bar and the footer, and the module
  `/js/open-now.js` (loaded only with the flag on) that writes «Aperto ora» or «Chiuso ora» from
  the openings and the browser's clock, every minute; styles in `main.css`.
* «Chiusure dell'ufficio» (`BackofficeClosuresController`, `/gestione/impostazioni/chiusure/`,
  ADMIN only) and its link on «Impostazioni».
* Tests: `OpeningHoursTest` (weekdays, Saturday noon, Sunday, a closure day, the daylight-saving
  changes of 2026, the ranges of the configuration), `OpenNowIT` (3), `OpenNowOffIT`; schema
  expectations and migration counts (22), route matrix; E2E `m2.spec.ts`: the badge matches the
  openings and the clock, a closure added for today makes it «Chiuso ora» (axe).

### New Italian texts for the owner's review

Badge «Aperto ora» / «Chiuso ora». «Chiusure dell'ufficio», «I giorni in cui l'ufficio resta chiuso
anche se l'orario dice aperto: festività, ferie, imprevisti. Il sito accanto all'orario scrive
«Aperto ora» o «Chiuso ora».», «Adesso il sito scrive: …», columns «Giorno», «Motivo», «Rimuovi»
(«Rimuovi la chiusura del …»), «Nessuna chiusura in programma: valgono gli orari di sempre.»; form
«Aggiungi una chiusura», «Dal», «Al (facoltativo)», «Lascialo vuoto per un giorno solo.», «Motivo
(facoltativo, non compare sul sito)», «Aggiungi»; errors «Scegli il giorno di chiusura.», «Il
giorno è già passato.», «L'ultimo giorno viene dopo il primo.», «Al massimo 62 giorni alla volta.»,
«Il motivo può avere al massimo 100 caratteri.», «Inserisci una data valida.»; flashes «Chiusura
aggiunta: il sito ne tiene conto accanto all'orario.», «Chiusura rimossa: quel giorno vale
l'orario di sempre.».

## Step R — M2: ENH-14, accessory services from the back office (2026-10-26)

Eighth and last of the M2 items of the owner's request «procedi con quelli che puoi fare ora».
ADR 0043.

### What was built

* Migration `V023__accessory_service_placements.sql`: `in_orders`, `on_site`, `in_quote`,
  `description`, `icon`, `quote_hint` on `accessory_service`, with checks (at least one place; text
  and icon on the site; item 0 only on the order); «Sponda idraulica» gets the card and the extra
  of the ported pages, the six other services of those pages become rows not on the order.
* `tracking`: `AccessoryServiceAdmin` (the list, add with the next id and a code of its own,
  update, move, switch off and on, delete when unused; audited); `TrackingCatalog` offers the order
  forms only the services of the order (keeping one already chosen), and reads the two public
  lists; EP-20 serves the services of the order by id.
* `site`: `SiteAccessoryModelAttributes` (`siteAccessories` on `/servizi/`, `quoteExtras` on
  `/preventivo/`, only with the flag on); `FormValidator` accepts the extras of the list with the
  flag on; the port script adds the drawn block after the ported one of the two pages (the ported
  one renders with the flag off).
* `orders`, `quotes`: the validator and the saved quotes accept services of the order only.
* «Servizi accessori» (`BackofficeAccessoryServicesController`,
  `/gestione/impostazioni/servizi-accessori/`, ADMIN only) and its link on «Impostazioni».
* Tests: `AccessoryServicesIT` (5), `AccessoryServicesOffIT` (3); `EnumerationSeedIT` (ADM-10: the
  six legacy services of the order; new ids after the seeds), schema expectations and migration
  counts (23), route matrix; E2E `m2.spec.ts`: the seeded cards, a service added by the ADMIN on
  `/servizi/`, in the wizard and in the order form, switched off and deleted (axe, phone width).

### New Italian texts for the owner's review

«Servizi accessori», «Un solo elenco per l'ordine, la pagina Servizi del sito e il preventivo: per
ogni servizio scegli dove compare. L'ordine dell'elenco vale ovunque.», columns «Servizio», «Dove
compare» («Ordine», «Sito», «Preventivo»), «Stato» («Attivo», «Disattivato»), «Codice …», «Icona
sul sito: …», «Sempre la prima scelta dell'ordine: non si modifica.», buttons «Su», «Giù»,
«Modifica», «Disattiva», «Riattiva», «Elimina» (with «… «nome»» for screen readers); «Modifica
«…»», «Torna ai servizi accessori»; form «Aggiungi un servizio», «Nome» («Compare così ovunque lo
mostri: nell'ordine, sul sito, nel preventivo.»), «Dove compare»: «Nell'ordine (area clienti e
gestione)», «Nella pagina Servizi del sito», «Tra i servizi del preventivo» («Almeno uno. Nel
preventivo il nome è anche il testo che arriva con la richiesta.»; for a legacy service «È uno dei
servizi storici dell'ordine: resta sempre nell'ordine.»), «Descrizione sul sito» («Il testo sotto
il nome nella pagina Servizi: serve se il servizio compare sul sito.»), «Icona sul sito» («Scegli
un'icona» and 20 names: «Freccia verso terra», «Calendario con orologio», …), «Suggerimento nel
preventivo (facoltativo)» («La riga piccola sotto il nome, per esempio «Giorno e fascia oraria
concordati».»), «Aggiungi il servizio», «Salva le modifiche», «Annulla»; errors «Scrivi il nome del
servizio.», «Il nome può avere al massimo 120 caratteri.», «C'è già un servizio con questo
nome.», «Scegli almeno un posto in cui compare.», «Scrivi la descrizione che compare sul sito.»,
«La descrizione può avere al massimo 300 caratteri.», «Scegli l'icona che compare sul sito.»,
«Scegli un'icona dell'elenco.», «Il suggerimento può avere al massimo 120 caratteri.»; flashes
«Servizio «…» aggiunto in fondo all'elenco.», «Servizio «…» aggiornato.», ««…» spostato: l'ordine
vale ovunque compare.», ««…» è di nuovo attivo.», ««…» disattivato: non compare più, gli ordini
che lo hanno lo conservano.», ««…» eliminato.», ««…» è in ordini o preventivi già registrati: non
si può eliminare, puoi disattivarlo.». The public cards and extras keep the texts of the ported
pages.

## Step S — Times first in the screens (2026-10-26)

Owner request: the office works by the day, so the screens show the time and the day only when
it is not today. ADR 0044.

### What was built

* `fmt.when` (lists, cells, timelines: `14:32`, `ieri 14:32`, `12/10 14:32`, `12/10/2025 14:32`,
  the full date on hover) and `fmt.at` (sentences: `oggi alle 14:32`, `il 12/10 alle 14:32`), from
  the application clock in Europe/Rome; applied to the customer area and the back office.
  Documents, e-mails, exports, the quote page and schedules keep the full date.
* Tests: `FormatsTest` (Rome midnight against UTC, summer time, another year).

### New Italian texts for the owner's review

«Inviato {0}», «Registrato {0}», «Modulo «…», arrivata {1}», «Rifiutato {0}. Motivo: …», «Annullato
dall'ufficio {0}. Motivo: …», «Il cliente ha annullato l'ordine {0}.», «Il cliente ha modificato
l'ordine {0}: …», «Modificato dal cliente {0}», «Il cliente l'ha chiesta dal suo profilo {0}. …»,
«Hai annullato questo ordine {0}.», «Il centro logistico ha annullato questo ordine {0}. …», «Hai
chiesto di cambiare questi dati {0}: …», «Aggiornato {0}»; columns «Inviato», «Registrato»,
«Richiesta», «Arrivata»; the words «oggi alle», «ieri», «domani», «il … alle».
