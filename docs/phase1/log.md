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
