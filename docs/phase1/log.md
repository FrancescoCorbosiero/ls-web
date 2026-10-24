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
