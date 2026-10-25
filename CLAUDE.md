# CLAUDE.md

Logistica Sanchez web app: one monolith serving the public site, the customer area, the
back office and the API of Logistica Sanchez S.r.l. (pallet logistics, Seregno, Italy).
It replaces the legacy public site, order portal (`ls-order`), CMS (`ls-cms`) and backend
(`ls`).

* Brief (source of truth): `docs/brief/` — start from `README.md` (reading map per module).
* Requirements with IDs: `docs/brief/09-features.md`. Progress: `docs/parity-matrix.md`
  (generated from test tags).
* Decisions: `docs/adr/` and the "Decision" column of `docs/brief/11-open-questions.md`.
* Legacy code for reference (read-only, gitignored): `legacy/` at the commits listed in
  `PROMPT.md`. How things work today: the legacy code wins over brief chapter 12, and 12
  wins over chapters 02–08. `legacy/ls` (the backend) is the primary reference.
* **Never open** `legacy/ls/logs/`, `legacy/ls/api/`, `legacy/ls/.env*` or
  `legacy/ls/local-setup.sh`, and read only key names in its YAML files: they hold personal
  data and secrets. Search with `':!logs' ':!api' ':!.env*'`. Never copy a file or a value
  from `legacy/ls`; port rules and texts by reading the code.

## Status

Current phase, last completed workflow, next step and open gate: `docs/plan.md` §Status.
Update it at the end of every workflow and before stopping at a gate. In a new session
read this file, then `docs/plan.md` §Status, then continue; never restart a completed
phase.

## Language

* User-facing text: Italian, in the informal "tu" register of the new site and the legacy
  portal ("Inserisci…", "ti ricontattiamo"), staff screens included.
* Code, identifiers, comments, commits, ADRs, plan and parity matrix: English.
* Talk to the owner in Italian. Gate summaries, the UAT checklist and the cut-over,
  rollback and restore runbooks are written in Italian, with commands and identifiers
  unchanged.

## Commands

```sh
docker compose up --build && docker compose run --rm app dev-seed   # Docker only: database + app on :8080, synthetic data
scripts/dev.sh                        # try the app: local database, build if needed, dev-seed, app on :8080 (PORT=…, --rebuild)
scripts/local-db.sh start && eval "$(scripts/local-db.sh env)"   # PostgreSQL 16 in .local/pg (port 54329), TEST_DATABASE_* exports
./mvnw -B -ntp verify                 # Spotless check, unit tests (*Test), integration tests (*IT), jar
./mvnw spotless:apply                 # format Java sources and pom.xml
./mvnw -B -ntp -DskipTests package    # jar only: target/ls-web-0.1.0-SNAPSHOT.jar
JAR=target/ls-web-0.1.0-SNAPSHOT.jar; DB="--spring.profiles.active=local --DATABASE_URL=jdbc:postgresql://localhost:54329/ls_web --DATABASE_USER=ls --DATABASE_PASSWORD=ls"
java -jar $JAR dev-seed $DB           # local/test only: admin.seed + operatore.seed (passwords printed once), 4 customers, 5 orders
java -jar $JAR create-staff-user --username=admin --role=ADMIN --name="Nome Cognome" $DB   # one-time set-password link
SERVER_PORT=8080 java -jar $JAR $DB   # http://localhost:8080/  /area-clienti/  /gestione/  (mail sink: .local/mail/*.eml)
e2e/run.sh [playwright args]          # E2E: fresh ls_web_e2e, dev-seed, app on :8089, Playwright (cd e2e && npm ci once)
CHROMIUM_PATH=/opt/pw-browsers/chromium e2e/run.sh   # where the pre-installed Chromium is not Playwright 1.56.1's (this environment)
tools/parity-matrix.py [--check]      # regenerate docs/parity-matrix.md Status from the tags and the last reports
python3 tools/port-ls-home-pages.py --check   # public templates still equal a fresh port of legacy/ls-home (needs the checkout)
tools/ci/guard-tracked-files.sh       # files that must never be tracked
docker build -t ls-web:dev . && (cd deploy && docker compose --env-file .env.example config --quiet)   # image and Compose (needs a daemon)
```

Never run `npx playwright install` in this environment (browsers are in `/opt/pw-browsers`);
CI installs Chromium itself. Runbooks (Italian): `docs/runbooks/local-setup.md`, `docs/runbooks/deploy.md`.

## Conventions

* Modules talk to each other through services, never through another module's
  repositories (`docs/brief/10-architecture.md` §2).
* Every change ships with tests tagged with the requirement IDs it touches (JUnit
  `@Tag("CUS-04")`, Playwright title prefix `[CUS-04]`); the parity matrix is generated.
* Only the integrator creates database migrations; versions are strictly sequential and
  forward-only; never edit a migration that is already merged.
* Only the integrator edits shared files: this file, chapter 11, application and security
  configuration, shared layout, message bundles. Parallel agents propose changes in their
  result.
* M2 code lives behind its feature flag; with the flag off the app behaves exactly as M1.
* E-mails are sent only through the notifications outbox (never directly from a
  controller); outside production no e-mail reaches real recipients.
* Public pages reuse the design system and the vanilla JS widgets; no client-side
  framework; keep the Lighthouse and axe budgets and the CSP green.
* Home motion is `static/js/motion/`, after alpacode-motion@7b1a203 (ADR 0030): start from the
  section's story in that repo's catalog, copy the entry, map its hook in `config.js`; never
  add hooks to the home's `<main>`.
* The private areas use `static/css/app.css` on top of `main.css`, the `app-icons.svg` sprite
  and `static/js/app.js` as progressive enhancement only (ADR 0029): every screen works without
  JavaScript; the order form is `OrderForm` with the fragments of `orders/form.html`.
* No secrets in the repository: configuration comes from the environment. Never ask for
  passwords, tokens or keys in the chat.
* Real personal data (dumps, snapshots, probe answers, reports) never go into git, test
  fixtures, screenshots, logs or the agents' session.
* Test and demo data are synthetic: e-mails on `.example`, invented company names,
  fictitious phone numbers, codes like `TEST-0001`. A `dev-seed` command creates staff
  users with random passwords, customers in every state and orders in every order and
  tracking state. No staff account in migrations or seeds outside local and test.
* `docs/brief/07-pricing.md` and `docs/brief/data/` are confidential (a customer's prices,
  partner costs). This repository must stay private; tests use synthetic price lists,
  except the documented worked examples; never load the cost model or price lists from
  `docs/brief/data/` in a migration, seed or startup loader.

## Decisions and the owner

* The legacy is a reference, not a contract: keep what customers and the office rely on
  (chapter 09 M1); where it is ambiguous or broken, decide, record it in an ADR and carry
  on. Never wait for legacy information.
* Never decide silently. For an unanswered item of `docs/brief/11-open-questions.md`,
  implement its recommended default behind one clear seam (a setting or a single method,
  with a `Q-xx` comment), list it in the next gate summary and carry on.
* New customer-facing texts, e-mails included, follow the copy decks and the chapter 09
  defaults; the owner reviews them at the next gate.
* Stop and wait for the owner only before: touching production, DNS or real customer
  data; sending e-mail to real recipients or enabling it outside production; using or
  creating credentials; spending money or adding a paid service; adding an external
  processor of personal data; changing a business rule, price or text the owner has already
  decided (chapter 11 "Decision" column, ADRs).
