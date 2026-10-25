# 0019 · Logging without bodies or personal data; configuration from the environment; management endpoints

* **Status:** Accepted
* **Date:** 2026-10-23
* **Requirement IDs:** NFR-02, NFR-03, NFR-04 · **Questions:** Q-53 · **Findings:** SEC-06, SEC-07, SEC-17, SEC-20, F-02…F-07, F-13, F-14 (12a §5, 12d)

## Context

The legacy backend logged every request and response body — passwords, JWTs, customer data —
and committed the logs (SEC-07); its secrets were committed in profiles and `.env` files
(SEC-06, F-02…F-07); its production profile file was inert, so production ran the base
configuration with Hibernate SQL logging and an actuator exposure of `*` that only the
missing dependency kept harmless (SEC-17, SEC-20); the e-mail link host came from an
ambiguous `EMAIL_ORIGIN` (F-14). Chapter 10 §3 "Logs" and §5 ask for structured logs
without bodies or personal data, kept at most 30 days; §4 for only the health endpoint on
the public port; the conventions of `CLAUDE.md` for configuration from the environment and
no secrets in the repository.

## Decision

### Logging

* **Never logged:** request or response bodies, header values, query strings (a sign-in link
  carries the token in `?t=`), passwords, tokens, e-mail addresses, names, IP addresses.
  Log lines carry **ids**: request id, outbox row id, order id or code, staff user id, flag
  key. `LoggingWithoutBodiesIT` (`NFR-04`) captures the root logger on a login attempt, a
  JSON POST and a sign-in link request and asserts none of those values appears.
* **Request id:** `RequestIdFilter` takes a well-formed `X-Request-Id` from the proxy or
  generates a UUID, puts it in the MDC (`requestId`), in the response header and in every
  error body (ADR 0002); the audit log stores it too, so a support question can be traced
  from the error page to the log and the audit entry without any personal datum.
* **Access log:** one `INFO` line per request from the logger
  `it.logisticasanchez.web.access`: `access method=… path=… status=… ms=…` — method, path
  without query string, status and duration; nothing else.
* **Format:** plain console lines in `local` and `test`; **structured JSON** (Logback ECS
  through `logging.structured.format.console=ecs`, Boot BOM) in `staging` and `prod`, so the
  container's json-file driver and any shipper read them as records. Rotation and the 30-day
  cap are the container runtime's (deploy stage, chapter 10 §3).
* **Exceptions:** stack traces are logged only by the catch-all handler of ADR 0002, with the
  request id; a message that might contain an address is scrubbed before logging
  (`OutboxWorker.safeMessage`).
* Hibernate SQL logging stays off in every profile; `DEBUG` for `it.logisticasanchez` only in
  `local` and `test`.

### Configuration from the environment

* Every `ls.*` key is bound by a `@ConfigurationProperties` record under
  `it.logisticasanchez.web.platform.config`, `@Validated`, with a typed default and the
  `// Q-xx` / `// PD-xx` comment of the seam (plan §8). Nothing reads the `Environment` for
  an `ls.*` key directly. The records are the API Phase 2 modules consume.
* Values that have no safe default come from the environment and are **required in staging
  and production**: `DATABASE_URL`/`USER`/`PASSWORD`, `LS_SECURITY_IP_HASH_KEY`,
  `LS_TOKEN_ENCRYPTION_KEY`; `RequiredSettingsCheck` fails the start-up naming the missing
  variable, and refuses `ls.flags.override.*` in `prod` (ADR 0025) and any sink but SES
  there. `local` and `test` carry non-secret development values in their profile files.
* `application.yml` is the one schema; profile files hold only what differs
  (`local`, `test`, `staging`, `prod`). No `.env` is ever committed (`.gitignore`, CI guard,
  gitleaks); `deploy/.env.example` documents the keys with empty values.
* **One base URL:** `ls.business.site-url` (ADR 0027) is the origin of every link the app
  writes — e-mails, redirects, JSON-LD, `security.txt` — instead of the legacy
  `EMAIL_ORIGIN` host ambiguity (F-14).

### Management endpoints

Only `/actuator/health` is reachable, without details or components; every other endpoint
has `access: none`, discovery is off, `info.env` and `info.build` are off (SEC-17). The
health route is declared public in the `RouteRegistry` (ADR 0002); `/actuator/health/**`
below it is undeclared and answers 404. `HealthEndpointIT` (`NFR-04`, stage 1) asserts the
set on a real port.

## Consequences

* A support investigation uses the request id, never a customer's name or address; the
  erasure tool (NFR-03) has nothing to scrub in the logs.
* A new setting requires a record field with a default and, when it is a secret, a line in
  `RequiredSettingsCheck` and in `deploy/.env.example`; a forgotten secret stops staging at
  start-up instead of running with a silent default (SEC-20).
* The JSON format is ECS; if the owner picks a log shipper that prefers another layout, the
  single property changes.

## References

Chapter 10 §3 "Logs/alerts", §4, §5; 12a §2.12–2.14, §5; 12d §1; `CLAUDE.md`
"Conventions"; ADR 0002, ADR 0022 (deploy), ADR 0025, ADR 0027.
