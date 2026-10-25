# 0022 · Deployment: one VPS with Compose and Caddy, CI image on GHCR, encrypted backups

* **Status:** Accepted (procedure not yet rehearsed on a real server)
* **Date:** 2026-10-25
* **Requirement IDs:** NFR-05, NFR-06, NFR-07 (chapter 09) · **Questions:** Q-48 (backups), Q-53 (rotation), Q-55 (empty database at cut-over)

## Context

ADR 0001 chose a new Contabo VPS with Docker Compose and Caddy 2, a versioned image on GHCR and
Flyway at start-up. The owner asked for a simple, fast build (2026-10-24) and decided that no
legacy data is migrated (Q-55). Phase 1 step C had to turn that choice into files that run
without a server at hand: a Dockerfile, a Compose project, a proxy configuration, the backup
procedure and the CI that builds and publishes the image.

## Decision

* **Image.** `Dockerfile` is multi-stage: `eclipse-temurin:21-jdk-noble` packages the jar with
  `./mvnw -DskipTests package` (the unit, integration and E2E jobs of the same commit must pass
  first: the `image` job `needs` them), `eclipse-temurin:21-jre-noble` runs it as the non-root
  user `app` (uid 10001) with DejaVu fonts for the PDF report, `TZ=Europe/Rome`, a `HEALTHCHECK`
  on `/actuator/health` and `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`. Published as
  `ghcr.io/francescocorbosiero/ls-web` with the tags `sha-<short sha>`, `<branch>` and, from
  `main` only, `latest`; on pull requests the image is built but not pushed.
* **Compose** (`deploy/compose.yml`): services `proxy` (`caddy:2`, ports 80/443, volumes for
  certificates and config), `app` (image by `LS_IMAGE_TAG`, every setting from `deploy/.env`,
  `expose 8080` only, healthcheck), `db` (`postgres:16`, named volume `pg_data`, `pg_isready`
  healthcheck). No `container_name`, so `up -d app` rolls the app alone. `deploy/.env.example`
  lists every variable the app reads; `(required)` values have no default and `compose config`
  fails loudly without them (`${VAR:?…}`).
* **Proxy** (`deploy/caddy/Caddyfile`): the five hosts (apex, `www`, `app`, `ordini`,
  `dashboard`) in one site block with automatic HTTPS; Caddy adds only transport (TLS, HTTP/2,
  compression, `X-Forwarded-*` for the one trusted hop). Host-based behaviour stays in the app
  (`HostRoutingFilter`), so staging works with another `LS_DOMAIN` without touching the proxy.
* **Backups** (`deploy/backup.sh`, NFR-05): `pg_dump | gzip | age -r <public key>` into
  `/var/backups/ls-web/{daily,weekly,monthly}` with the 7/4/12 rotation, optional off-site copy
  with `rclone`, `--check` for the age alert (fails above 26 h), `--restore FILE` with the age
  private key, which never lives on the server. Cron lines and the restore drill are in
  `docs/runbooks/deploy.md`.
* **CI** (`.github/workflows/ci.yml`, NFR-06): `build-and-test` (Spotless, unit, integration on
  `postgres:16`), `e2e` (jar + Playwright 1.56.1 on Chromium against a fresh `ls_web_e2e`
  service database through `e2e/run.sh`), `guards` (tracked-file guard, `docker compose config`,
  gitleaks) and `image` (needs the three). The deploy to staging and production stays a manual
  `docker compose pull && up -d` on the server (runbook §2) until the owner provides the VPS;
  an automated deploy job (SSH from CI) is the natural next step and needs a server secret.
* **Runbooks** (`docs/runbooks/`, NFR-07, Italian): `local-setup.md` and `deploy.md` (install,
  update, rollback, backup and restore, key rotation, cut-over outline). Cut-over details follow
  at Gate 1 with the owner's DNS and SES access.

## Consequences

* The parity matrix caps NFR-05/06/07 at **ready-for-owner**: the procedures exist and pass
  `docker compose config`, but no server, DNS or SES credentials were touched (`PROMPT.md` rule
  6). A dated rehearsal record under `docs/rehearsals/` turns them green.
* Rollback of the app is a tag change as long as migrations stay additive (they are in M1);
  a schema rollback means a restore from backup.
* Secrets live only in `deploy/.env` on the server (mode 600) and in GitHub Actions secrets; the
  repository holds `.env.example` with empty required values (guarded by `tools/ci/guard-tracked-files.sh`).
* Costs and processors for the owner (unchanged from ADR 0001): the VPS, the GHCR package (free
  for a public repository), the off-site backup storage, Let's Encrypt (free).
