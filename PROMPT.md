Ultracode.

# Build the Logistica Sanchez web app

You are building, in this repository, the single web application that replaces the
whole production system of **Logistica Sanchez S.r.l.**, an Italian pallet-logistics
company: public website, customer order portal, back office (CMS) and backend API. It
must do everything the current system does, add a customer area with real sign-in, and
prepare safe enhancements behind feature flags — without breaking anything customers or
the office rely on today.

The brief in `docs/brief/` is your source of truth; this message tells you how to work.
Talk to the owner in **Italian**; write code, identifiers, comments, commits, ADRs and the
plan in English (rule 3).

## Read first

1. `docs/brief/README.md` (reading order and **reading map per module**), then chapters
   **01** (goals, milestones, principles), **09** (every requirement, with IDs), **10**
   (architecture, Gate 0, security, testing, cut-over, parallel work), **11** (open
   questions with defaults) and **12** (the legacy backend from its source: precedence, key
   facts, files never to open) in full.
2. From chapters **02–08** and **12a–12d** read the sections the reading map gives for your
   task, not whole chapters: legacy API contract (02), order portal (03), CMS (04), old
   public site (05), new static site to port (06), pricing (07, confidential), domain model
   and migration (08); the backend's API and security (12a), data (12b), e-mails, report
   and pricing (12c), infrastructure and repository (12d). They cite the legacy code as
   `repo@ref:path:line`.

How things work today: the legacy code wins over chapter 12, and chapter 12 wins over
chapters 02–08 (written from the frontends before the backend source was available). Note
any discrepancy in the plan. What the new app does is decided by chapter 09 and the ADRs;
if the owner decides otherwise, the owner wins (recorded in chapter 11 and `docs/adr/`).

## Inputs

* **Legacy repositories** (read-only). Check out the exact commits the brief cites, one
  directory each under `./legacy/` (in `.gitignore`):
  * `legacy/ls` — `https://github.com/FrancescoCorbosiero/ls` at
    `ca1b0d022857977b212b774e0247d2218477e1f8` (the **backend**, branch `migration`,
    believed to be production; **private**). The primary reference for how the system works
    today (chapter 12). If this session cannot clone it, tell the owner once and continue
    from chapter 12, which documents it. **Never open** `logs/`, `api/`,
    `.env`, `.env.*` or `local-setup.sh`, and read only the key names in its YAML files:
    they hold personal data and secrets (chapter 12 §2, Q-53). Search with
    `git -C legacy/ls grep … -- . ':!logs' ':!api' ':!.env*'`. Copy no file and no value
    from it; port rules and texts by reading the code.
  * `legacy/ls-home` — `https://github.com/FrancescoCorbosiero/ls-home` at
    `9b78785396ed4c7f685437ae72fc21cadbae4864` (the **new static site**, branch
    `claude/determined-lovelace-ggeitw`; the porting baseline). The brief cites it at its
    ancestor `fe4b056`; only the files listed in chapter 06 changed since (clone the branch
    with its history). Its `main` (`bb5528c` when the brief was written) still has Netlify
    Forms and demo tracking: never port it.
  * `legacy/ls-home-v1` — same repository at `82017161a0ed51365813b7857663145d126acaf2`
    (old site).
  * `legacy/ls-order` — `https://github.com/FrancescoCorbosiero/ls-order` at
    `dbe7292216bb44b514a3d652ea99f6813ee01d0b` (order portal, branch `dev`).
  * `legacy/ls-cms` — `https://github.com/FrancescoCorbosiero/ls-cms` at
    `f0c881cc564d5ee966372641eae85e6484d9597a` (back office, branch `dev`).
  If a branch now has later commits, list them in `docs/plan.md` and ask the owner whether
  they are in scope.
* **Database:** MySQL 8.0.35, database `lsdb`, in a container on the production VPS. A
  schema-only dump with row counts (Q-02) and, later, an anonymised copy are useful but
  optional: without them, build the importer against the schema of 12b §1.7. The real dump
  is restored and imported only by the owner, on infrastructure the owner controls (chapter
  08 §4.2).
* **Secrets and personal data.** Never ask the owner to paste passwords, tokens or keys
  into the chat, and never write them into repository files: the owner adds them as
  secrets of the Claude Code environment (or a local `.env`), and you read them from
  environment variables. A dump, snapshot, probe answer, report or log with real personal
  data never goes into git, test fixtures, screenshots, logs or this session; the owner runs
  real imports on infrastructure the owner controls (chapter 08 §4). The `.gitignore` of
  the first commit covers `legacy/`, `private/`, `.env*`, SQL files, dumps, backups,
  spreadsheets and infrastructure state; keep it, and make CI fail if such files are
  tracked. Exceptions: Flyway migrations (`**/db/migration/*.sql`), synthetic SQL and
  `*-synthetic.xlsx` fixtures under the test resources.
* **Confidential data:** `docs/brief/07-pricing.md` and `docs/brief/data/` contain one
  customer's prices and the partner network's costs. At the start of Phase 0 check that this
  repository is private (`gh repo view --json visibility` or the GitHub API). If it is
  public, or the check fails, stop, tell the owner in Italian to make it private, and push
  nothing until that is confirmed. Never copy those numbers into logs, public places or test
  fixtures, except the documented worked examples in unit tests.

## Legacy at a glance (verify in the cited code)

* **Kept** (API-01): `POST /mono/api/order/trace/state` `{"codice":"LS-2024-0118"}` →
  `200 {"status":"Presa in carico"}` (the label); an unknown code or a pending order's
  temporary code → `200 {"status":"Non gestito"}`, not an error; `{"codice":""}` → `400`
  (`ls@migration:src/main/java/it/logisticasanchez/mono/api/controller/OrderController.java:33-38`;
  chapter 02 §10.4 FX-01, FX-03, FX-05, FX-06; 12a §1.4 D1).
* **Retired, but its content lives on in CUS-04** with the sender taken from the session —
  the open order portal sends:
  `{"clienteEmail":"ufficio.spedizioni@example.com","destinatario":{"ragioneSociale":"Ferramenta Rossi S.n.c.","indirizzo":"Via Roma 12","provincia":"BS","citta":"Brescia","cap":"25121","telefono":null,"email":"ufficio.spedizioni@example.com"},"dettaglioColli":{"palletList":[{"altezza":"180","peso":"450","larghezza":"80","lunghezza":"120","quantity":"2","type":"1"}]},"codiceDocumentoTrasporto":"DDT 457/2024","servizioAccessorio":0,"note":""}`
  — numbers as strings, `destinatario.email` is the customer's own e-mail, lines nested in
  an object
  (`ls-order@dev:js/rest/dto/order-request.js:3-43`; chapter 03 §7.4). The backend refuses
  it with 500 unless the e-mail belongs to a REGISTERED customer, stores one `pallet` row per
  physical unit and computes the totals (12a §1.4 C1; 12b §4.3).
* **CMS approval** (ADM-04): the backend requires
  `{"id":118,"req":{"codice":"LS-2024-0118","importo":180.5,"tracking":1}}` — the code
  (unique), the amount as a number > 0, tracking 1 = `PRESA`
  (`ls@migration:src/main/java/it/logisticasanchez/mono/business/service/crud/SpedizioneService.java:152-173`;
  12c §4.2). The CMS in the repository still sends `"importoAssicurato":"180,50"`
  (`ls-cms@dev:js/function/component-handler.js:420-434`), which fails today: the live CMS
  is a later build whose source is unknown (Q-47).
* **Do not reproduce** the security model: a URL rule that matches nothing and a permit-all
  default, so `order/pending`, `order/active`, `order/delivered`, `cms/order/report` and the
  report links are open (12a §2.7–2.8, §5).

## What to build

* **M1 — parity + customer area:** every ID in chapter 09 §M1 (public site, tracking,
  compatibility API, customer area, back office, e-mails, migration, non-functional).
* **M2 — safe enhancements:** chapter 09 §M2 (ENH-01…22), each behind its feature flag,
  default off, built after the cut-over (Phase 5).
* **Later:** nothing from the experimental backlog; leave extension points only.

## Rules

1. **The legacy is a reference, not a contract.** Keep what customers and the office rely
   on (chapter 09 M1) and never drop it silently. Where the legacy is ambiguous,
   inconsistent or broken (the open order portal, wrong regexes, hard-coded keys…), choose
   the better behaviour and record it in an ADR in `docs/adr/`. Never block on legacy
   information that is missing.
2. **One design.** Reuse the new site's HTML, CSS, JS and design system for every screen —
   public pages, customer area, back office. No SPA framework; server-rendered pages with
   small progressive enhancements.
3. **Language.** User-facing text in Italian, in the informal "tu" register of the new site
   and the legacy portal ("Inserisci…", "ti ricontattiamo"), staff screens included; reuse
   the copy decks of chapters 03, 04 and 06. Gate summaries, the UAT checklist and the
   cut-over, rollback and restore runbooks are in Italian (commands and identifiers
   unchanged). Everything else in English.
4. **Tested.** Every requirement ID has at least one automated test tagged with the ID
   (JUnit `@Tag("CUS-04")`, Playwright title prefix `[CUS-04]`); CI generates
   `docs/parity-matrix.md` from the tags. Statuses: planned · in progress · green ·
   ready-for-owner · blocked (Q-xx). NFR-05, NFR-06 (deploy), NFR-07, MIG-02 on real data
   and MIG-03 can at most reach "ready-for-owner": they ship the script or runbook plus an
   automated test of every part that runs locally, and become green with a dated rehearsal
   record (who, when, result). Never mark an ID green on a placeholder test.
5. **Secure by default:** the baseline of chapter 10 §4–§5 from the first commit.
6. **Owner gates.** Stop and wait for the owner before: touching production, DNS or real
   customer data; sending e-mail to real recipients or enabling it outside production;
   using or creating credentials; spending money or adding a paid service; adding an
   external processor of personal data; changing a decision the owner has already taken.
   Locally use fakes: a mail catcher, fake SES and S3.
7. **Unknowns:** for an unanswered question of chapter 11, implement its recommended
   default behind one clear seam, list it for the next gate and carry on. Record every
   owner answer in chapter 11 ("Decision" column) and in the affected ADR in the same commit.
8. Keep `CLAUDE.md` and `docs/plan.md` §Status current.

## How to run it

One workflow per phase; read each phase's result before starting the next; stop at every
gate and wait for the owner.

### Environment check (first step of Phase 0)

Record in `docs/plan.md` what works here: JDK version, Maven/Gradle, Node, Playwright
browsers, PostgreSQL server binaries, a running Docker daemon (`docker info`), and whether
you can push to this repository. If no Docker daemon runs, run integration and E2E tests
against a local PostgreSQL started with `initdb`/`pg_ctl` (the test setup reads
`TEST_DATABASE_URL` and skips Testcontainers when it is set), use in-process fakes for SES
and S3, and keep Testcontainers and Docker Compose for CI and the owner's machine. Never
drop a test layer because a tool is missing. Pin a JDK that exists both here and in CI.

### Git and CI

* You may commit and push to this repository's branches without asking: `phase-<n>` per
  phase, `module/<name>` per Phase 2 worktree, `m2` for M2. Never force-push or rewrite
  `main`; never push anywhere else.
* The integrator merges modules into `phase-<n>` in dependency order after the full suite
  passes. At the end of each phase open a PR `phase-<n>` → `main` and merge it when CI is
  green; at a gate, leave it open for the owner.
* CI (GitHub Actions) runs on pull requests and on `main`, with caching. If Actions minutes
  run out, stop and tell the owner; never enable paid minutes.

### Phase 0 — Understand and plan → **Gate 0**

After the environment check, parallel readers over: the brief chapters (each returns the
two-page summary described in the README); each legacy repository, the backend `legacy/ls`
first, with chapter 12 as its map (never opening the files of chapter 12 §2); the schema
dump if the owner provided it. A planner merges their findings into:

* `docs/adr/0001-path-and-stack.md` — Path A or B with the assessment of chapter 10 §1
  (recommended: B on Java + Spring Boot), stack, JDK, hosting (chapter 10 §3) and database
  engine (Q-52);
* `docs/plan.md` — modules, owners of every requirement ID, integration order, risks, and
  a §Status section (current phase, last workflow, next step, open gate);
* `docs/parity-matrix.md` — every M1 and M2 ID with the planned tests;
* the Gate 0 questions of chapter 11, each with your recommended default.

Then stop: summarise the decisions for the owner in Italian and wait. No application code
before Gate 0.

### Phase 1 — Foundations

Sequential, one agent (chapter 10 §8): skeleton, build, CI, local environment, the complete
M1 schema of chapter 08 §2.3 as the first migrations, staff login and the customer
magic-link sign-in (finished), layout fragments and design-system assets, security
configuration with one extension point per module, message bundles, feature flags, audit
log, e-mail outbox, staging e-mail guard, and the skeleton of every module (public service
interfaces, DTOs, domain errors, stubs). CI must be green before Phase 2.

### Phase 2 — M1 modules in parallel

One agent per module, each in its own git worktree with its own database and port:
site (with leads storage); tracking + compatibility endpoints; customers (registration,
verification, approval, profile); orders; back office; notifications + reports; migration
tool. Each implements its IDs with tagged tests. Agents do not create migrations or edit
shared files (CLAUDE.md, chapter 11, configuration, security configuration, shared layout,
message bundles): they put the change in their result and the integrator applies it.
Integrate in this order, running the whole suite after every merge: notifications →
customers → orders → tracking + compatibility → reports → back office; site and migration
whenever green.

### Phase 3 — Review until dry

Review workflow over the integrated code with distinct lenses: parity against chapters
02–06 (ID by ID), security (10 §4), privacy (10 §5), data integrity and migration,
accessibility and performance budgets, code quality. Each finding is checked by three
independent skeptics trying to refute it; keep it if at least two confirm. A round is dry
when it confirms no new blocker or major finding; minor findings go to `docs/backlog.md`.
Stop after two consecutive dry rounds or after round 4, whichever comes first.

### Phase 4 — Release readiness → **Gate 1**

Full E2E suite; Lighthouse and axe budgets; migration dry-run with the reconciliation
report on synthetic data (and on the anonymised copy if provided); runbooks; staging
deploy if the owner provides it. Then stop with the Gate 1 package, in Italian: the parity
matrix; the E2E, Lighthouse and axe reports; the reconciliation report; the runbooks; the
defaults still in force (Q-IDs); a UAT checklist; open risks. The owner tests on staging and
sets the cut-over date. The cut-over itself is run with the owner (chapter 10 §7).

### Phase 5 — M2 → **Gate 2**

Only after the cut-over and the rollback window (chapter 08 §4.8); until then M2 work stays
on branch `m2` and only Gate 1 fixes reach `main`. Same pattern per group of flags:
pricing (ENH-01…04, 11); tracking history and notifications (ENH-05); leads and saved
quotes (ENH-06, 20); customer conveniences (ENH-07, 12, 16, 21); staff tools (ENH-08, 09,
13, 14, 15); site extras (ENH-17, 18, 19, 22); delivery times (ENH-10). The owner turns
flags on one by one.

## Sessions and status

This build spans many sessions. At the end of every workflow and before stopping at a gate,
update `docs/plan.md` §Status. In a new session read `CLAUDE.md`, then `docs/plan.md`
§Status, then continue; never restart a completed phase.

## Definition of done for M1

* Every M1 ID is green in `docs/parity-matrix.md` (test or dated rehearsal record).
* All old URLs and the compatibility endpoints behave as documented (contract tests).
* Public pages: Lighthouse ≥ 95 in all four categories, no serious or critical axe
  violations, no CSP violations, no console errors.
* Migration dry-run reconciles 100% (counts per state, sum of amounts) on the
  owner-provided anonymised copy of production, rehearsed on staging with e-mail caught.
* Runbooks written and rehearsed; ADRs for every deliberate change from the legacy
  behaviour.

## Start now

1. Before any workflow, send the owner one short message in Italian: (a) the urgent risks
   of the system in production today (chapter 11 §0: Q-21 open endpoints, Q-53 committed
   secrets and logs, Q-48 backups, Q-49 TLS renewal, Q-22, Q-23) and what to do about each
   now, pointing to `COME-USARLO.md`; (b) the optional inputs that would help (the
   schema-only dump, Q-02; other customers' price lists, Q-34); (c) whether the agents may
   run the read-only probes P-01…P-03 (Q-27; default: no). Do not ask for credentials, and
   do not wait for the answer.
2. Run Phase 0 and stop at Gate 0 with the plan, ADR 0001 and the defaults you applied;
   ask the owner only what you cannot default.
