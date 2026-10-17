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
