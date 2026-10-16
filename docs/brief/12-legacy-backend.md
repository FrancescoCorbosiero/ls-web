# 12 · The legacy backend, from its source

The legacy backend ("mono") is the private repository `ls` of GitHub user
`FrancescoCorbosiero`. Production runs the branch **`migration`**, commit
`ca1b0d022857977b212b774e0247d2218477e1f8` (2025-11-16): Java 17, Spring Boot 3.2.2,
MySQL 8.0.35, on a VPS with Docker Compose (inferred with high confidence, 12d §4.3; the
deployed commit is Q-45). Check it out read-only at `legacy/ls` (`PROMPT.md`, "Inputs").

This chapter and its four parts were written by reading that source. Chapters 02–08 were
written earlier, from the frontends only.

## 1. How to use it

**Precedence.** The legacy code wins over chapter 12, and chapter 12 wins over chapters
02–08 on how the legacy system works today. The owner's decisions (chapter 11, ADRs) win
over all of them for the new app. Chapters 02–08 carry banners and "From the source"
notes where the difference matters; when you find a disagreement nobody noted, follow
the code and record it in `docs/plan.md`. These rules describe the legacy. What the new app
does is decided by chapter 09 and the ADRs, and where the legacy is unclear or broken the
new app chooses (chapter 01, principle 1).

| Part | Content | Read it for |
|---|---|---|
| [12a](12a-backend-api-and-security.md) | Every mapping with its request, response, validation and errors (§1); the security model (§2); the error model (§3); verdicts on chapter 02's EP-01…EP-20 and CU-01…CU-24 (§4); security findings SEC-01…SEC-21 (§5) | compatibility API, tracking, customers, orders, back office, security |
| [12b](12b-backend-domain-and-data.md) | The MySQL schema (§1); enumerations with their exact JSON (§2); the state machines as implemented (§3); DTOs and entities (§4); reconciliation with chapter 08, DQ-25…DQ-37, the column-by-column mapping and the migration from the dump (§5); data risks (§6) | data model, migration, state machines |
| [12c](12c-backend-emails-reports-pricing.md) | The ten automatic e-mails L-E1…L-E10, verbatim (§1); the pages behind their links (§2); the PDF report (§3); pricing code (§4: none is used); SES (§5); admin tools (§6) | notifications, reports, pricing |
| [12d](12d-backend-infra-and-repo.md) | The VPS, containers, nginx, TLS, configuration, backups, deploy (§1); stack and end-of-life dates (§2); repository hygiene F-01…F-19 (§3); branches (§4); `ls-web` (§5); corrections to chapters 01, 08 and 10 and questions Q-45…Q-53 (§6) | infrastructure, cut-over, Gate 0 |

Each part defines its path abbreviations at the top; they differ between parts.

## 2. Never open these files of `legacy/ls`

They hold personal data or secrets (12a §2.14, 12d §3):

* `logs/` — 11 MB of developer logs with customer data, plaintext passwords and JWTs;
* `api/` — the Postman collection, with login passwords and session ids;
* `.env`, `.env.*` and `local-setup.sh`;
* the **values** in `src/main/resources/application*.yml` and `src/test/resources/*.yml`:
  read key names only.

Search with the exclusions, for example
`git -C legacy/ls grep -n 'importo' -- . ':!logs' ':!api' ':!.env*'`. Never copy a value
from that repository into this one, the plan, an ADR, a test fixture, a log or the chat.
Port rules and texts by reading the code; copy no file.

## 3. Key facts

**Security (chapter 11 §0).**
* The URL rule for `/mono/cms/**` never matches (context path `/mono`), and every other
  request is permitted: only `@PreAuthorize` protects anything (12a §2.7).
* Open without a token: `order/pending`, `order/active`, `order/delivered`,
  `cms/order/report`, `GET /mono/report` and `GET /mono/download-report` (Q-21; 12a §2.8).
* Every request and response body is logged; secrets and logs are committed (Q-53).
* Protected endpoints answer 500 without a token, 401 with an invalid one (12a §2.9).

**Behaviour the new app keeps (M1).**
* Tracking: `status` is the label; unknown and temporary codes answer
  `200 {"status":"Non gestito"}`; an empty code answers 400 (12a §1.4 D1).
* Enumerations: six tracking states and six accessory services, ids 0–5, item 0 of the
  services = none; the database stores labels (12b §2).
* Customers: register → NEW (unverified) → the e-mail link → PENDING → staff approve →
  REGISTERED. Only verified customers can be approved. A decline resets to NEW and keeps
  the row (DECLINED before 2024-07-22) (12b §3.1).
* Orders: the e-mail must belong to a REGISTERED customer; lines are stored one row per
  unit with an aggregate row; approval requires a code (unique) and `importo` > 0 and sets
  tracking PRESA; a decline resets to NEW (12b §3.2, 12c §4.2).
* Tracking changes: any state to any state, on approved orders only; the customer gets an
  e-mail on CONSEGNATO only; no history, only `shipping_ts` and `delivery_ts` (12b §3.4).
* Notes: the CMS note is the customer's order note, overwritten by staff (12b §3.6).
* E-mails: ten automatic ones, HTML only, no Reply-To, office recipient hard-coded
  (12c §1). The links use `https://{EMAIL_ORIGIN}/mono/…`, by default the apex (Q-50).
* Report: one customer's approved orders by creation date, optionally only delivered,
  as an A4 landscape PDF reached through public, never-expiring links (12c §3).
* Pricing: none in the code; the amount is typed by staff (12c §4).

**Data (chapter 08 §4).** Seven tables in `lsdb` (`soggetti`, `spedizioni`,
`dettaglio_colli`, `dettaglio_colli_seq`, `pallet`, `users`, `user_session`); `create_tms`
and `update_tms` on every business table; amounts as `DOUBLE`; `ddl-auto: update`, so the
live schema may have drifted. The dump is the migration source; the list endpoints see
only the last 6 months (12b §0, §5.6).

**Infrastructure (chapter 10 §3, §7).** One Contabo VPS: nginx with Let's Encrypt, the app
and MySQL in Docker Compose; AWS only for SES in eu-north-1; part of the configuration and
the backups only on the server; no health check; the deploy script is broken; the stack
is end-of-life (12d §0).

**The CMS in the repository is not the production CMS.** `ls-cms@dev` sends
`importoAssicurato` and no `onlyDelivered`, which today's backend refuses; the backend
links to `https://dashboard.logisticasanchez.com/` (Q-47; 12a §0 point 6).

## 4. What changed in the rest of the brief

* 01: sources, the as-is diagram (VPS, `dashboard.`), backend and infrastructure bullets.
* 02: what the server protects, the tracking answers, the accessory services, old e-mail
  links, probe P-04; a "From the source" note under every endpoint.
* 03, 04: banners with the backend rules; the CMS build in the repository is not the live
  one.
* 06: the two later ls-home commits (`ff58d4f` for `Non gestito`, `9b78785` for `/mono/*`).
* 07: the amount (`importo` > 0, seen by customers); unused pricing code; `PalletEnum`
  versus the class image (PR-02).
* 08: the customer state machine (no approval before verification), the dump as the plan,
  the cut-over on the legacy VPS, `dashboard.`.
* 09: API-01/02, ADM-02/04/06/07/09/10, EML-01…09 (EML-09 delivery e-mail in M1), MIG-01/03,
  NFR-05, CUS-02 (phone required).
* 10: Path B recommended on the same stack; infrastructure default = one VPS with Docker
  Compose; IAM user; cut-over steps.
* 11: answered rows marked **Legacy:**; urgent Q-21 (confirmed), Q-53, Q-48, Q-49; new
  Q-45…Q-52.

## 5. Still unknown

Only the owner or the production server can tell: the deployed commit, the key names in
the server's `.env` and `config/application-prod.yml` (Q-45); where the production CMS
source is (Q-47); the `EMAIL_ORIGIN` host (Q-50); the production collation (matching of
codes and e-mails); the staff accounts (Q-17); AWS-era leftovers (Q-46); backups and TLS
renewal (Q-48, Q-49); whether the data hold `DECLINED` rows, duplicate customer e-mails or
approvals with a null `importo` (the dump, 12b §5.9).

## 6. ID families introduced here

| IDs | Where |
|---|---|
| SEC-01…SEC-21 | 12a §5 (security findings, with the fix for the new app) |
| DQ-25…DQ-37 | 12b §5.3 (data quality, in addition to chapter 08 §5) |
| L-E1…L-E11 | 12c §1.2 (legacy e-mails) |
| F-01…F-19 | 12d §3 (repository and operations findings) |
| Q-45…Q-53 | chapter 11 (proposed in 12d §6.7; the proposals of 12c §7 are merged into Q-21, Q-47, Q-50 and Q-53) |
