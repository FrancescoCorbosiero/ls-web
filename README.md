# ls-web

The web app of Logistica Sanchez S.r.l. (pallet logistics, Seregno, Italy): one Spring Boot
monolith that serves the public site, the customer area, the back office and the API.
It replaces the legacy public site, order portal, CMS and backend.

## Quickstart

One command (JDK 21 and the PostgreSQL 16 binaries are the only prerequisites):

```sh
scripts/dev.sh          # local database, build, synthetic data, app on http://localhost:8080
```

It prints the staff passwords (`admin.seed`, `operatore.seed`) at every start; the customer
area accepts `cliente.attivo@cliente.example` and writes the sign-in link to `.local/mail/`.

Step by step:

```sh
scripts/local-db.sh start && eval "$(scripts/local-db.sh env)"
./mvnw -B -ntp verify                                    # build + unit + integration tests
JAR=target/ls-web-0.1.0-SNAPSHOT.jar
DB="--spring.profiles.active=local --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls"
java -jar $JAR dev-seed $DB                              # synthetic staff users (passwords printed once), customers, orders
SERVER_PORT=8080 java -jar $JAR $DB                      # http://localhost:8080/ · /area-clienti/ · /gestione/
```

Sign-in links and every other e-mail are written to `.local/mail/*.eml` (nothing leaves the
machine outside production). End-to-end suite: `cd e2e && npm ci && cd .. && e2e/run.sh`.
Step-by-step guide in Italian: `docs/runbooks/local-setup.md`; deploy: `docs/runbooks/deploy.md`.

## Prerequisites

* JDK 21 (the only toolchain supported; `./mvnw` enforces it)
* PostgreSQL 16 server binaries (`initdb`, `pg_ctl`, `psql`) for the local database, or a
  Docker daemon for Testcontainers
* Node 22 (only for the Playwright end-to-end suite under `e2e/`)

Maven is not needed: the wrapper downloads Maven 3.9.11.

## Local database

```sh
scripts/local-db.sh start      # PostgreSQL 16 under .local/pg on port 54329, role ls/ls,
                               # databases ls_web (app) and ls_web_test (tests)
scripts/local-db.sh status
scripts/local-db.sh psql       # psql on ls_web (or: scripts/local-db.sh psql ls_web_test)
scripts/local-db.sh createdb ls_orders   # another database on the same instance
scripts/local-db.sh stop | reset
```

The credentials are local only. `scripts/local-db.sh env` prints the exports the tests read.

## Build and test

```sh
eval "$(scripts/local-db.sh env)"   # TEST_DATABASE_URL, TEST_DATABASE_USER, TEST_DATABASE_PASSWORD
./mvnw -B -ntp verify               # compile, format check (Spotless), unit tests (*Test),
                                    # integration tests (*IT) on PostgreSQL, packaged jar
./mvnw spotless:apply               # format the sources
```

When `TEST_DATABASE_URL` is not set the integration tests start a Testcontainers
`postgres:16` container instead (needs a Docker daemon). Unit tests are `*Test` classes run
by Surefire; integration tests are `*IT` classes run by Failsafe.

## Run

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# or
./mvnw -DskipTests package && java -jar target/ls-web-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

The `local` profile listens on port 8080 and uses the local database `ls_web`. Every other
profile (`test`, `staging`, `prod`) is driven by the environment: `DATABASE_URL`,
`DATABASE_USER`, `DATABASE_PASSWORD`, `SERVER_PORT` and the `LS_*` settings; there are no
secret defaults. `/actuator/health` is the only management endpoint.

## End-to-end tests, image and deploy

* `e2e/` — Playwright 1.56.1 (Chromium): `e2e/run.sh` recreates `ls_web_e2e`, seeds it, starts
  the jar on port 8089 and runs the journey (registration → approval → order → tracking →
  delivery e-mail) and the public smoke with axe. CI runs the same script.
* `Dockerfile` (Temurin 21 JRE, non-root) and `deploy/` (Compose: Caddy 2 + app + PostgreSQL 16,
  `.env.example`, `backup.sh`); CI pushes `ghcr.io/francescocorbosiero/ls-web` from `main` and
  `phase-*`.

## Documentation

* `docs/brief/` — the brief (requirements with IDs in `09-features.md`; reading map in `README.md`)
* `docs/plan.md` — build plan, module owners and the current status (`§Status`)
* `docs/adr/` — architecture decision records (`0001-path-and-stack.md` holds the stack pins)
* `docs/parity-matrix.md` — requirement coverage, generated from the test tags
* `docs/phase1/log.md` — what each Phase 1 stage built and how to run it
* `docs/runbooks/` — runbooks in Italian (local setup, deploy, rollback, backup/restore, key rotation, cut-over)

Conventions for contributors and agents are in `CLAUDE.md` and `PROMPT.md`.
