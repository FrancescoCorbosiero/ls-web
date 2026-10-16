# 10 · Architecture

How to build the app: the first decision (Gate 0), the reference architecture, the
default stack, infrastructure, security, privacy, testing, cut-over and how to split the
work between parallel agents. Requirements are in chapter 09; this chapter says *how*.

## 1. Gate 0 — path and stack (decide with the owner before writing code)

| Path | When | What it means |
|---|---|---|
| **A · Evolve the legacy backend** | The source (`ls@migration`, chapter 12) meets all the criteria below and the owner prefers it. | Start from it: keep its business rules and e-mail templates; evolve its schema with forward-only migrations into the target model of chapter 08 §2 (tables, states, constraints); add the public site, the customer area and the back office; expose only API-01/API-02 — every other legacy endpoint is retired as API-03 requires. All chapter 09 IDs apply unchanged. Lowest regression risk. |
| **B · New monolith** (recommended) | The source does not meet the criteria, or evolving it costs more than it saves. | Build from this brief, with the legacy source (`legacy/ls`, chapter 12) as the primary reference for how things work today: port business rules and e-mail copy by reading the code, never by copying files. Parity is guaranteed by the parity matrix, by contract tests on the endpoints kept for compatibility, and by migrating the data into the new schema. |

**Path A details.** "Keep its rules" means keep its internals; the public contract after
the cut-over is still API-01…API-03 (every legacy endpoint except EP-13, EP-19 and EP-20
answers 410 and never performs its action — EP-08 above all). Choose A only if the source
meets all of these: it builds and starts locally; its framework and runtime are still
supported; its dependencies can be upgraded with no known critical vulnerability left;
its schema can be evolved with forward-only migrations. Record the assessment in ADR 0001.
Under A:
* scan the source for secrets before committing any of it, tell the owner to rotate what
  you find, and import the code without its git history;
* evolve the database in place: a baseline migration of the legacy schema, then forward
  migrations to the target of chapter 08 §2; MIG-01/02 become those migrations plus data
  fixes, with the same dry-run and reconciliation report (chapter 08 §4.5–4.6), rehearsed
  on the anonymised copy;
* map the module layout of §2 onto the legacy packages in `docs/plan.md`.
**Assessment from the source** (chapter 12; verify it in Phase 0 and record it in ADR 0001):

| Criterion | Finding |
|---|---|
| Builds and starts locally | Plausible: Maven, Dockerfile, Docker Compose; the integration test is `@Disabled` and the test client swaps arguments (12a SEC-21). Verify. |
| Framework and runtime supported | **No.** Spring Boot 3.2 (open-source support ended November 2024), AWS SDK for Java v1 (end of support December 2025), Spring Cloud AWS 2.2.6 (built for Boot 2), iText 2.1.7 (2009), MySQL 8.0 (end of life April 2026) (12d §2). Upgrading touches every layer. |
| Schema evolvable with forward-only migrations | No migrations today: `ddl-auto: update`, so the live schema may have drifted (12b §1.8). A baseline migration is possible. |
| Fit with the target | The domain is small (seven tables, about twenty endpoints, ten e-mail templates). Most of M1 is new: public site, customer area with sign-in links, server-rendered back office, outbox, audit, roles, tests. The security model (dead URL rule and permit-all default, JWT for a separate CMS, body logging, open endpoints, 12a §5) must be replaced either way. |

**Recommended default: Path B on the same stack** (Java + Spring Boot, current versions),
reading `legacy/ls` for every rule, text and edge case. Path A stays possible if the owner
prefers it and the criteria hold after the upgrade.

**Stack rule.** The legacy backend is Java 17 + Spring Boot 3.2.2 (12d §2): use Java and
Spring Boot, current versions. This keeps a single stack for the owner and lets rules be
ported. The **reference stack** below is that stack. Record the decision in
`docs/adr/0001-path-and-stack.md`.

**Reference stack (default):**

| Concern | Choice |
|---|---|
| Language/runtime | Java LTS — the newest version available both in the build environment and in CI (check it in Phase 0 and pin it in ADR 0001, or use a build toolchain) |
| Framework | Spring Boot (current major), Spring MVC |
| Server-rendered UI | Thymeleaf templates and fragments; HTMX for partial updates in the customer area and back office (configured for a strict CSP, §4); the public pages keep the new site's vanilla JS widgets. No SPA framework. |
| Security | Spring Security: form login for staff; one-time-token ("magic link") login for customers (§2); CSRF on for the customer area and back office; method-level authorisation. |
| Persistence | PostgreSQL (MySQL 8.4 LTS only if ADR 0001 keeps the legacy engine, Q-52), Spring Data JPA/Hibernate, Flyway migrations, Bean Validation |
| E-mail and files | AWS SDK v2: SESv2 for e-mail, SQS for bounce/complaint notices, S3 for documents |
| Office formats | A maintained PDF library (for example OpenPDF) for the ADM-09 report; Apache POI for the M2 Excel import and exports, with the safety limits of §4 |
| Background work | A database-backed outbox for e-mails and reports, processed by a scheduled worker (no extra broker). |
| Tests | JUnit 5, Testcontainers (PostgreSQL) — or a local PostgreSQL through `TEST_DATABASE_URL` where no Docker daemon runs —, Playwright for E2E, axe-core for accessibility, Lighthouse CI for public pages |
| Build/packaging | Maven or Gradle (pick one), a Docker image |

Use the newest stable versions at build time and pin them exactly. If the owner prefers
Python, Django + PostgreSQL + HTMX is an equivalent alternative (same architecture, Django
admin as a starting point for the back office); do not mix stacks.

## 2. Reference architecture

One deployable, one database, modules with clear boundaries:

```
app
├── platform        config, feature flags, security headers, audit log, rate limits, observability
├── auth            customer one-time-token login, staff login, sessions, roles
├── site            public pages (ported templates), forms (quote, contact), leads storage, sitemap, redirects
├── tracking        lookup, tracking events, compatibility endpoints, enumerations
├── customers       registration, verification, approval, profile, e-mail change
├── orders          order wizard, lines, temporary codes, approval/decline, codes, notes, detail
├── backoffice      staff UI over customers/orders/tracking/reports (controllers + templates)
├── notifications   e-mail templates, SES sender, outbox, bounce/complaint handling
├── reports         report generation (PDF, as the legacy), e-mail delivery, authenticated download
├── leads           inbox UI (M2); the storage is part of `site` from M1
├── pricing         (M2) price lists, Excel import, price engine, suggestions, margin view
└── migration       importer from the legacy dump or API snapshot, reconciliation report
```

Rules:
* A module exposes services; controllers and templates call services, never another
  module's repositories. Domain rules (state transitions, validation, pricing) live in
  services with unit tests.
* State machines are explicit (enums + transition methods that refuse illegal moves); the
  full tables of chapter 08 §2.4 win over this summary. Customer `PENDING_VERIFICATION →
  PENDING_APPROVAL → ACTIVE | DECLINED`, plus `PENDING_VERIFICATION → DECLINED` and
  `DECLINED → PENDING_VERIFICATION` (registers again — Q-09). Only a verified customer can
  be approved, as in the legacy backend (12b §3.1, Q-28). Order `PENDING → APPROVED |
  DECLINED`. Tracking on approved orders: any of the six states (`NON_GESTITO`, `PRESA`,
  `TRANSITO`, `TERMINAL_DEST`, `ASSEGNATO`, `CONSEGNATO`) may follow any other, as in the
  legacy CMS; every change is an event. Map legacy values exactly (chapter 08).
* Money: `numeric(10,2)` in EUR; whether amounts include VAT is an open question (Q-05) —
  store a flag, do not guess. Time: `timestamptz` in the database, shown in Europe/Rome.
* Identifiers: internal ids are yours; keep legacy ids and order codes in their own
  columns so old links, documents and e-mails stay valid.

### URL map

| Area | URLs |
|---|---|
| Public site | the URLs of chapter 06 (`/`, `/servizi/…`, `/zone/…`, `/tracking/`, `/preventivo/`, `/contatti/`, `/chi-siamo/`, `/faq/`, `/blog/…`, `/privacy/`, `/cookie/`, `/grazie/`) |
| Customer area | `/area-clienti/` (sign in by e-mail), `/area-clienti/accesso` (link confirmation page), `/area-clienti/registrazione/`, `/area-clienti/ordini/`, `/area-clienti/ordini/nuovo/`, `/area-clienti/ordini/{codice}/`, `/area-clienti/profilo/` |
| Back office | `/gestione/` (staff sign-in) and below: `clienti/`, `ordini/`, `tracking/`, `report/`, `impostazioni/` |
| Public JSON | `POST /api/form` (quote/contact), tracking via the compatibility endpoint |
| Compatibility | `POST /mono/api/order/trace/state`, `GET /mono/api/enumeration/statoTracking`, `GET /mono/api/enumeration/servizioAccessorio` |

The header link "Portale ordini" of the new site becomes "Area clienti" → `/area-clienti/`.
Everything under `/area-clienti/`, `/gestione/`, `/api/` and `/mono/` is `noindex` and out
of the sitemap (PUB-03).

### Public site inside the app

* Copy `public/css`, `public/js`, `public/img`, `public/fonts`, icons, manifest and
  `.well-known` from ls-home@9b78785 unchanged into the static resources.
* Turn each page into a template; the four partials (`head`, `header`, `cta`, `footer`)
  become fragments; the active-menu logic of `tools/sync.mjs` becomes a template helper.
* Same-origin endpoints for the widgets: set `TRACKING.endpoint` to
  `/mono/api/order/trace/state` (same contract, now served locally) and point the forms
  to `/api/form`; with everything same-origin, `connect-src 'self'` is enough again.
* Public pages stay cookie-free and cacheable: keep the caching rules of `public/_headers`
  (immutable fonts, a week for images) and add `ETag`/short caching for public HTML only.

### Customer authentication (magic link)

* The e-mailed link opens `GET /area-clienti/accesso?t=…`, which only renders a page with
  one button «Accedi» (it works without JavaScript). The token is checked, marked used and
  exchanged for a session by that page's CSRF-protected `POST`, never by a `GET` or `HEAD`:
  mail scanners and link previews (common in company mailboxes) cannot burn it. The page
  sends `Cache-Control: no-store` and `Referrer-Policy: no-referrer`; the `POST` redirects to
  a URL without the token. A used or expired token shows «Il link non è più valido» with
  the form to ask for a new one. The same flow applies to `VERIFY_EMAIL`, `REGISTER` and
  `CHANGE_EMAIL` links (chapter 08 §2.3).
* Tokens: random, at least 128 bits, stored **hashed**, valid 15 minutes (registration
  links 24 hours), single use.
* Unknown e-mails get a registration link instead of a sign-in link; the page answers the
  same way for every address (CUS-01); rate limits per e-mail and per IP.
* Sessions (the owner may change these): customers 60 minutes idle and 12 hours absolute;
  staff 30 minutes idle and 10 hours absolute; cookie `HttpOnly`, `Secure`,
  `SameSite=Lax`, with the `__Host-` prefix; session id rotated on login. Logout, a decline
  and an e-mail change end all sessions of that user.
* Customer-area and back-office responses send `Cache-Control: no-store`.
* One login per customer at first (the customer's e-mail); the model allows more users per
  company later (Q-11).

### E-mail

* SESv2 `SendEmail` from an address of the verified domain `logisticasanchez.com` (DKIM,
  SPF, DMARC set up); Reply-To where a person should answer.
* Every e-mail goes through an outbox table (written in the same transaction as the
  business change) and a worker with retries, so an SES hiccup never loses an order.
* For sign-in and registration e-mails the outbox row references the `login_token` and
  holds the raw token only encrypted (key from the secret store); the worker decrypts it to
  send and wipes it after delivery. No log or staff screen shows the body of such an e-mail.
* Retention (the owner may change it, Q-18): outbox bodies deleted 30 days after delivery
  (metadata kept); `login_token` rows 24 hours after expiry; report files removed by a
  30-day S3 lifecycle rule. IP hashes (`ip_hash`, `requested_ip_hash`) are HMAC-SHA-256 with
  a secret key and kept at most 30 days.
* Templates: Thymeleaf HTML + plain text, Italian (informal "tu"), the brand style of
  `netlify/functions/form.mjs`.
* SES bounces and complaints reach the app through SNS → an SQS queue polled by the
  worker: no public endpoint. A marked address is shown to staff, who can clear it.
* Outside production, e-mail never reaches real recipients (§3).

### Feature flags

A `feature_flag` table (key, enabled, updated_by, updated_at) read through a cached
service, with environment overrides for tests. M2 flags default to **off**; ADMIN users
can toggle them in `/gestione/impostazioni/`. Code paths for a disabled flag behave
exactly like M1. Business rules that are not features (such as Q-28) are not flags.

## 3. Infrastructure (default; confirm at Gate 0)

In October 2025 the owner moved the legacy backend from AWS Elastic Beanstalk and RDS to a
single VPS with Docker Compose (12d §1). The default follows that choice: **one EU VPS with
Docker Compose, AWS only for SES (and S3 if used)**. Managed AWS (Elastic Beanstalk or ECS,
RDS, Secrets Manager) stays an alternative if the owner asks for it (Q-52).

| Piece | Default |
|---|---|
| Hosting | One EU VPS from the provider the owner already uses (Contabo), on a supported Ubuntu LTS. **A new VPS**, so the legacy server stays intact for rollback; share the legacy VPS only if cost forces it (one reverse proxy routing by host name). Contabo's data-processing agreement signed. |
| Containers | `proxy`: Caddy 2 with automatic HTTPS, or nginx with a certbot renewal that works (the legacy one probably does not, Q-49). `app`: the monolith image, non-root, with a healthcheck on the health endpoint. `db`: PostgreSQL (current major). Only the proxy publishes ports 80/443; `app` and `db` sit on an internal network (Docker bypasses UFW). |
| Database | A container with a named volume; Flyway; nightly logical dump plus a provider snapshot; a tested restore. |
| Files | A private S3 bucket (Block Public Access, default encryption, a policy that denies non-TLS access; pre-signed URLs), or a local volume included in the backups if the owner prefers no S3. |
| E-mail | SESv2 in eu-north-1, where the legacy already sends, reusing the verified domain identity (inferred), with a **dedicated IAM user** for the new app (§4). |
| Secrets | A server-side `.env` owned by root, mode 0600, or Docker secrets; a redacted `.env.example` documents the keys; never in git (`.gitignore` guards and secret scanning in CI). |
| TLS/DNS | A/AAAA records for the apex, `www`, `app.`, `ordini.` and `dashboard.` pointing to the VPS at the current DNS provider (no Route 53 or ALIAS needed); Let's Encrypt (DNS-01 to issue in advance, or HTTP-01 right after the switch); HSTS. |
| Deploy | CI builds a versioned image and pushes it to a private registry (GHCR); on the server `docker compose pull && docker compose up -d`, with manual approval for production; Flyway runs at start-up; rollback = previous image tag plus a database restore point; the proxy has a maintenance page. |
| Logs/alerts | Docker json-file logs with rotation; application logs without bodies or personal data, kept at most 30 days; an external uptime and TLS-expiry check on the health URL; disk-space and backup-age alerts by e-mail through SES. |
| Backups | Nightly database dump plus files, encrypted (restic, borg or age), sent off-site to EU object storage; 7 daily, 4 weekly, 12 monthly (Q-18); a monthly test restore; an alert when the newest backup is older than 26 hours. |
| Hardening | UFW for 22, 80 and 443; SSH keys only; no root login; unattended-upgrades; fail2ban for sshd. |
| Environments | local (Docker Compose — or local PostgreSQL where no Docker daemon runs —, a mail catcher, SES/S3 fakes), staging (a second compose project on the new VPS), production |
| Staging e-mail | Staging never e-mails real recipients: the outbox delivers to a mail catcher, or through SES only to an allow-list of the owner's addresses (any other recipient is dropped and logged). The app refuses to start with the staging profile unless one of the two is configured (tested). |
| Staging data and access | Only the anonymised copy of production (names, e-mails, addresses, phones and notes replaced; pseudonyms derived deterministically from the normalised e-mail so case variants still collide; codes, states, dates and amounts unchanged). Staging is not public (IP allow-list or HTTP basic auth) and sends `X-Robots-Tag: noindex`. A real copy only with the owner's written go-ahead, in an access-restricted environment with the outbox disabled. |
| Infrastructure as code | A `deploy/` folder in the repository with the compose files, the proxy configuration, a server bootstrap script and the runbook; Terraform optional (state never in git; `*.tfstate*` and `*.tfvars` in `.gitignore`). |

## 4. Security baseline

* OWASP ASVS level 1 as the checklist; output escaping by the template engine everywhere
  (the legacy CMS had stored XSS, CMS-04); parameterised queries only.
* CSRF protection on every form of the customer area and back office. `POST /api/form` is
  exempt (unauthenticated, no session) and protected by the honeypot, a rate limit and an
  `Origin`/`Referer` check against the site's origin, so public HTML stays cacheable and
  cookie-free.
* Headers: HSTS, CSP as strict as `public/_headers`, `X-Content-Type-Options`,
  `Referrer-Policy`, `Permissions-Policy`. The customer area and back office get a CSP at
  least as strict, without `'unsafe-inline'` or `'unsafe-eval'`; HTMX is configured for it
  (chapter 06 §8.5: no inline indicator styles, no eval, no `hx-on`).
* Rate limits: sign-in links, registration, staff login, tracking lookups, public forms.
  Limits key on the client IP from `X-Forwarded-For` as written by the reverse proxy on the
  server (trust exactly one proxy hop through the framework's forwarded-header support),
  never on the socket address and never on a header the client controls. Tests cover both.
* Staff passwords hashed with a modern algorithm (bcrypt/argon2), at least 12 characters,
  checked against common passwords; lock-out; no default or seeded accounts outside local
  and test (ADM-01); TOTP in M2.
* Authorisation tests: a customer can only read their own orders, documents and profile.
* Audit log of every staff action (who, what, when), without personal values (chapter 08
  §2.3 `audit_log` / `audit_log_detail`).
* Uploads (ENH-12 documents, ENH-01 price lists, ENH-11 cost model): staff only unless the
  owner decides otherwise. Limits: PDF ≤ 10 MB, XLSX ≤ 5 MB. Check the type from the content
  (the `%PDF-` header; for XLSX a ZIP containing `[Content_Types].xml`), not from the name or
  the browser's MIME type. Random object keys (original name as metadata). Downloads only
  through an authorised controller (the order's customer, or staff) answering with a
  pre-signed GET valid for at most 5 minutes, `Content-Disposition: attachment` and the
  stored type. Excel is parsed with the streaming reader, with POI's zip-bomb limits on and
  external entities off; formulas are never evaluated. Tests: a wrong type, an oversize
  file and another customer's document are refused.
* Spreadsheet output (ADM-09, ENH-08): every user-supplied value is a text cell (never a
  formula); in CSV, prefix with an apostrophe any value starting with `=`, `+`, `-`, `@`, a
  tab or a carriage return. Test with a company name that starts with `=HYPERLINK(`.
* Only the health endpoint, without details, is reachable from the internet; other
  management endpoints are disabled or on a non-public port (the legacy configuration
  exposed `*` and `env`/`configprops`, harmless only because actuator was missing; 12a
  SEC-17).
* Least-privilege IAM: a dedicated IAM user (a VPS has no instance role) that may send
  e-mail only from the site's address (`ses:FromAddress` condition), read and delete on its
  bounce queue and use only its bucket. Its key lives only in the server's secret file and
  is rotated at least yearly.
* Dependency updates (Dependabot or Renovate) and secret scanning in CI.
* Legacy leftovers to drop: hard-coded API keys, `http://` endpoints, US-style phone and
  postal-code regexes, unauthenticated ordering, and from the backend (12a §5): a permit-all
  default with a URL rule that matches nothing (deny by default instead, with a test per
  route), request and response bodies in the logs, a permanent refresh token without
  logout, authorisation failures answered as 500, data keyed on a client-supplied e-mail,
  public never-expiring report links, the free-text e-mail endpoint.

## 5. Privacy

* Update `/privacy/` and `/cookie/`: customer-area data and lawful bases; processors: the
  VPS provider (hosting, database, backups; Contabo today), AWS (SES e-mail; S3 if used) and
  the provider of the office mailbox that receives
  EML-06/07/08 — today a consumer Gmail account without a data-processing agreement (Q-43);
  remove Netlify once it is retired; retention; rights. The registration and sign-in pages
  link the privacy notice.
* Only technical cookies (session, CSRF) and only in the customer area and back office;
  Google Maps stays behind consent as today.
* Export and erasure on request, also for recipients' and leads' data found by e-mail or
  phone. Erasure means anonymisation, never a cascade delete: customer, customer user,
  login tokens, leads, internal notes, legacy payload columns, outbox bodies and
  `audit_log_detail` are anonymised at once; orders and their sender and recipient snapshots
  needed for transport documents and accounting are kept until the legal period ends (GDPR
  art. 17(3)(b)), then anonymised by the retention job. The request, its date and its
  outcome are logged without the erased values. Test: after an erasure no table holds the
  customer's e-mail, phone or name except the retained order snapshots.
* Logs and the audit log carry ids, not personal data.
* Test and demo data are synthetic; real data (dumps, snapshots, probe answers, reports)
  never enter the repository, fixtures, screenshots, logs or the agents' session.

## 6. Testing and CI

| Layer | What |
|---|---|
| Unit | state machines, validation, totals, code generation, pricing worked examples of chapter 07 |
| Integration | repositories and services on PostgreSQL; e-mails rendered and "sent" to a fake SES; outbox retries; staging e-mail guard |
| Contract | compatibility endpoints against the JSON fixtures of chapter 02 §10.4 |
| E2E (Playwright) | public smoke over every URL; forms with JS on/off; tracking; register → verify → approve → order → approve → tracking updates → customer sees them; declines; report rules; authorisation. Any CSP violation fails the run, on public pages, the customer area and the back office. |
| Quality | axe on key pages; Lighthouse CI budgets on public pages |
| Migration | importer on hand-written synthetic fixtures (never extracted from a dump, snapshot or probe answer); dry-run and reconciliation on the anonymised copy of production |

CI on every pull request and on `main`: build, lint, all of the above (E2E against the app
started with Docker Compose). `main` deploys to staging once the owner has provided it;
production deploys on a manual approval. Where the build environment has no Docker daemon,
integration and E2E tests run against a local PostgreSQL (`TEST_DATABASE_URL`) with
in-process fakes for SES and S3; never drop a test layer because a tool is missing.

## 7. Cut-over

1. Weeks before: crawl the live site and add a 301 for every old URL (Q-29); lower the DNS
   TTLs of the apex, `www`, `app.`, `ordini.` and `dashboard.` (and of any record that
   points to the legacy VPS or to AWS); provision the new VPS; certificates (DNS-01 in
   advance, or HTTP-01 at the switch); a new SES key for the new app (the verified identity
   is reused); take a legacy backup and **test-restore it** (Q-48); staging loaded with the
   anonymised copy of production; owner UAT on staging (Gate 1).
2. Freeze: announce a short window; staff stop using the old CMS; in the legacy VPS's nginx
   answer 503 or 410 for the write endpoints (EP-03, EP-06, EP-07, EP-08, EP-11, EP-12,
   EP-14, EP-16, EP-18), `/mono/cms/order/update`, `/mono/utils/**`, `/mono/sendEmail` and
   `GET /mono/email-confirm`, keeping EP-13, EP-19 and EP-20 open; reload nginx. The app is
   reachable only through that nginx, so this stops every caller whatever the host name
   (inferred). Also stop any AWS-era backend still running (Q-46). Details: chapter 08 §4.7,
   12d §6.4.
3. Final import from a dump taken inside the legacy container (`mysqldump
   --single-transaction`), transferred encrypted, on infrastructure the owner controls;
   reconciliation report signed off by the owner.
4. DNS (A/AAAA records): `logisticasanchez.com` → app; `www` → apex; `app.logisticasanchez.com`
   → app (compatibility paths and the old e-mail links of chapter 02 §10.3);
   `ordini.logisticasanchez.com` → 301 to `/area-clienti/`; `dashboard.logisticasanchez.com`
   → 301 to `/gestione/`.
5. Smoke tests on production (add one old e-mail link and the logo URL); watch logs and
   e-mail bounces.
6. Rollback (window and triggers: Q-40): DNS back to the legacy VPS, nginx block removed;
   never run `docker compose down -v` on the legacy server (it deletes the database
   volume); any order created in the new app during the window is re-entered from the
   export the runbook produces (details: chapter 08 §4.8).
7. After: before cancelling the legacy VPS or deleting any old resource, remove or repoint
   every DNS record that points at it (the VPS IP, Elastic Beanstalk, S3 website,
   CloudFront, Netlify hosts — chapter 08 §4.9); archive a final encrypted dump, then
   securely delete the legacy backups and logs (personal data and secrets); keep the old S3
   buckets as redirects (deleting them would let someone else take their names); delete the
   legacy SES key and any key committed in the `ls` repository (Q-53), the Elastic Beanstalk
   environments and the RDS instances and snapshots (after checking them, Q-46), and the
   Netlify form function's IAM key; retire the Netlify site (Q-20); rotate the staff
   passwords; archive the legacy repositories after purging logs and secrets.

## 8. Building it with parallel agents

* **Foundations first, alone (Phase 1):** project skeleton, CI, local environment, the
  **complete M1 schema** of chapter 08 §2.3 (all tables, constraints, indexes and the seeds
  already known) as the first migrations, staff login and the customer magic-link sign-in
  (finished, not stubbed), the layout fragments and design-system assets, the security
  configuration with one extension point per module, the message-bundle structure, feature
  flags, audit log, e-mail outbox, and the **skeleton of every module**: public service
  interfaces with DTOs and domain errors, plus stubs. Everything else depends on it.
* **Then modules in parallel (Phase 2)**, each in its own worktree, with its own database
  (`ls_<module>`) and HTTP port, owning its packages and templates: site (with leads
  storage); tracking + compatibility; customers (registration, verification, approval
  rules, profile, on top of the Phase 1 sign-in); orders; back office (ADM screens and their
  E2E; the rules and their unit tests belong to the domain modules); notifications +
  reports; migration tool. `docs/plan.md` gives every requirement ID exactly one owning
  agent and names the owners of rate limits, NFR-03 tools and NFR-04.
* **Schema changes:** Phase 2 agents do not add migration files. A module that needs a
  change describes it in its result; after the merge the integrator writes the next
  sequential migration on the integration branch. Versions never go backwards on a
  persistent database; per-module version ranges are not used.
* **Shared files** (CLAUDE.md, chapter 11, application and security configuration, shared
  layout, message bundles, the parity matrix) are edited only by the integrator; agents put
  proposed changes in their result. Every test carries its requirement IDs (e.g. JUnit
  `@Tag("CUS-04")`, Playwright title prefix `[CUS-04]`) and CI generates
  `docs/parity-matrix.md` from the tags.
* **Integrate in dependency order** — notifications → customers → orders → tracking +
  compatibility → reports → back office; site and migration whenever green — and run the
  full suite after every merge.
* **Review until dry (Phase 3):** distinct lenses (parity against chapters 02–06, security,
  privacy, data integrity, accessibility/performance, code quality); each finding checked by
  independent skeptics. A round is dry when it confirms no new blocker or major finding;
  minor findings go to `docs/backlog.md`. Stop after two consecutive dry rounds or after
  round 4, whichever comes first, and list what remains in the Gate 1 summary.
