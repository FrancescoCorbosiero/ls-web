# Build brief — Logistica Sanchez web app

The brief for building the single web app that replaces the current production system
of Logistica Sanchez S.r.l. (public site, order portal, CMS, backend).

> **Confidential.** Chapter 07 and the files in `data/` contain a customer's price list
> and the partner network's costs. Keep the repository private.

## Reading order

| # | Chapter | Read |
|---|---|---|
| 01 | [Context and goals](01-context-and-goals.md) — company, as-is system, milestones, principles | always, first |
| 09 | [Features](09-features.md) — M1 parity checklist, M2 enhancements, backlog (IDs) | always |
| 10 | [Architecture](10-architecture.md) — Gate 0, stack, modules, infrastructure, security, tests, cut-over, parallel work | always |
| 11 | [Open questions](11-open-questions.md) — what only the owner can answer, with defaults and decisions | always |
| 12 | [Legacy backend](12-legacy-backend.md) — what the backend source says, precedence over 02–08, key facts | always |
| 02 | [Legacy API contract](02-legacy-api-contract.md) — every endpoint the frontends call, enumerations, probes | API, migration, tracking |
| 03 | [Legacy order portal](03-legacy-order-portal.md) — flows, fields, validations, copy, quirks | customer area |
| 04 | [Legacy CMS](04-legacy-cms.md) — views, actions, enumerations, copy, quirks | back office |
| 05 | [Legacy public site](05-legacy-public-site.md) — old pages, URLs, tracking box | redirects, tracking |
| 06 | [New static site](06-new-site.md) — pages, design system, widgets, SEO; the porting guide | public site |
| 07 | [Pricing](07-pricing.md) — price lists, pallet classes, cost model, proposed engine (**confidential**) | M2 pricing |
| 08 | [Domain model and migration](08-domain-model-and-migration.md) — legacy entities, target model, mapping, migration | data, migration |
| 12a | [Backend: API and security](12a-backend-api-and-security.md) — every endpoint from the source, security model, error model, findings SEC-01…21 | API, tracking, back office, security |
| 12b | [Backend: domain and data](12b-backend-domain-and-data.md) — MySQL schema, enumerations, state machines, column mapping, migration from the dump | data, migration, customers, orders |
| 12c | [Backend: e-mails, report, pricing](12c-backend-emails-reports-pricing.md) — the ten e-mails verbatim, the pages behind their links, the PDF report, pricing scaffolding, SES, admin tools | notifications, reports, pricing |
| 12d | [Backend: infrastructure and repository](12d-backend-infra-and-repo.md) — VPS, Docker Compose, nginx, backups, deploy, repository hygiene, branches, corrections to 01/08/10 | infrastructure, cut-over |

Chapters 02–08 and 12a–12d are long (600–1,500 lines). Read the sections a task needs, not
whole chapters: `grep -n '^#' <file>` lists the sections.

**Precedence.** Chapters 02–08 were written from the frontends before the backend source
was available. Chapter 12 reads the source; where they disagree on how the legacy works,
**12 wins**, and the legacy code itself wins over 12. What the new app does is decided by
chapter 09 and the ADRs: the legacy is a reference, not a contract (chapter 01,
principle 1). The chapters carry banners and "From the source" notes
where the difference matters.

## Reading map per module

| Module / phase | Sections |
|---|---|
| Phase 1 foundations | 08 §2.1–2.5; 10 §2, §4–§6, §8; 06 §4.10, §6.4, §8.1, §8.5; 09 NFR-*; 12a §2 and §5 (the security model not to reproduce); 12d §6.2 (infrastructure) |
| site | 06 §8 first, then §3–§7; 05 §2, §4.1; 10 §2 "Public site inside the app"; 08 §2.3 `lead`; 09 PUB-* |
| tracking + compatibility | 12a §1.4 D and H, §4.1; 02 §5–§6, §7.4, §7.8, §9, §10, §11.2; 05 §3.7; 06 §5.7; 08 §2.3 `tracking_state`, `tracking_event`, §2.4 (tracking), §2.5; 09 TRK-*, API-*, ADM-06, ADM-10 |
| customers + sign-in | 12a §1.4 B; 12b §3.1; 03 §5, §8, §10 (OP-S1…S5, OP-08…11), §11 (OP-R1…R3); 04 §4.2–4.3; 08 §2.3 `customer`, `customer_user`, `login_token`, §2.4 (customer, login token); 10 §2 "Customer authentication"; 09 CUS-01…03, CUS-06, EML-01…03 |
| orders | 12a §1.4 C; 12b §3.2–3.6, §4; 12c §4.2; 03 §6, §7.4, §10 (OP-01…07, OP-12…16, OP-25), §11 (OP-R4…R9); 02 §8; 04 §4.4–4.5; 07 §1.1; 08 §2.3 `shipment_order`, `order_line`, `accessory_service`, §2.4 (order), §2.5; 09 CUS-04…05, ADM-04 |
| back office | 04 §2–§5, §7–§9; 12a §1.4 E–G, §2; 12c §6; 08 §2.3 `staff_user`, `order_internal_note`, `audit_log`, `audit_log_detail`; 10 §4–§5; 09 ADM-* |
| notifications + reports | 12c §1–§3, §5; 06 §5.6; 10 §2 "E-mail", §3 (staging e-mail); 04 §4.9; 02 §7.7; 09 EML-*, ADM-09, NFR-04 |
| migration | 12b §1, §2, §5, §6; 12d §1.7, §1.11, §6.4–6.5; 08 §1, §3–§5; 02 §7 (EP-04, EP-05, EP-09, EP-10, EP-15, EP-17), §8, §9, §11; 03 §9; 04 §6.2 |
| pricing (M2) | 07; `data/*.json`; 08 §2.6; 12c §4 |
| infrastructure + cut-over | 12d §0, §1, §6; 10 §3, §7; 08 §4.7–4.9 |

Phase 0 readers return, per chapter and in at most two pages: the requirement IDs
touched, the rules to keep, the deliberate fixes, the unknowns (by ID), contradictions
with other chapters, and the planned tests.

## Data files

| File | Content |
|---|---|
| `data/tariffario-mosca-2024.json` | One customer's price list (regions × pallet classes, delivery times, rules) — **confidential**; personal contacts redacted |
| `data/pallet-classes.json` | The seven pallet classes (max weight, max height, footprint) |
| `data/rks-cost-model.json` | Partner-network cost calculator as data and formulas — **confidential**; never loaded by a migration or seed (07 §6.8) |

## Conventions used in the brief

* Sources are cited as `repo@ref:path:line` (repositories `ls`, `ls-home`, `ls-order`,
  `ls-cms` of GitHub user `FrancescoCorbosiero`; `owner` = stated by the owner). The backend
  is cited at branch `migration` (`ls@migration` = `ca1b0d0`), the new site at commit
  `fe4b056` (port from `9b78785`, chapter 06), the portal at `dev` (`dbe7292`), the CMS at
  `dev` (`f0c881c`), the old site at `v1` (`8201716`). Each part of chapter 12
  defines its path abbreviations at the top, and they differ (`T/` is `src/test/` in 12a but
  the templates folder in 12c).
* "(inferred)" = deduced from the code, not certain; "(unknown)" = cannot be known from
  the code: it needs real traffic, the production server or the owner.
* Italian strings are quoted verbatim; everything else is in English.
