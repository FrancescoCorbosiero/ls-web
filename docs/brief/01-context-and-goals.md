# 01 · Context and goals

This brief describes the system Logistica Sanchez runs today and the single web
application that must replace it. Read this chapter first: it fixes the goals, the
milestones and the rules that every other chapter assumes.

Sources: the legacy repositories on GitHub (user `FrancescoCorbosiero`): the backend `ls`
(private; production branch `migration`, commit `ca1b0d0`, 2025-11-16; Java 17, Spring
Boot 3.2.2, MySQL 8.0.35), the order portal `ls-order`, the CMS `ls-cms` and the site
`ls-home`; the owner's statements; and two Excel files from the owner (a customer price
list and a partner-network cost calculator). **The backend source is the primary reference
for how the system works today** (chapter 12); chapters 02–08, written from the frontends
before it was available, defer to it. Its database is a MySQL container on the production
VPS, so the owner can provide a dump (chapter 08 §4.2).

## The company

| | |
|---|---|
| Legal name | Logistica Sanchez S.r.l. – "Il pallet espresso" |
| VAT (P.IVA) | 13113080967 |
| Address / hub | Via dei Meiani 90, 20831 Seregno (MB), Italy |
| Phone and WhatsApp | +39 329 239 6962 |
| E-mail | logisticasanchezmb@gmail.com |
| Office hours | Mon–Fri 8:00–18:00, Sat 8:00–12:00 |
| Instagram | sanchez_logistica_mb |

Business: pallet shipping across Italy from the Seregno hub (24–72 h, up to 96 h for
remote areas), groupage/parcels, full loads, warehousing. Customers are mostly
companies; private recipients exist. Each customer has a **personal price list** (an
Excel sheet per customer); prices quoted online may be indicative as long as they stay
within the bands of those lists.

## The system today (as-is)

```
                     ┌──────────────────────────────────────────────┐
  visitors ────────▶ │ Public site  logisticasanchez.com            │  separate FE (not in the repos;
                     │  tracking box ── POST /mono/api/order/trace/state ──┐   rewrite of ls-home@v1); the
                     └──────────────────────────────────────────────┘     │   links in e-mails point to
                     ┌──────────────────────────────────────────────┐     │   the apex by default
  customers ───────▶ │ Order portal  ordini.logisticasanchez.com     │     │   repo ls-order (vanilla JS,
                     │  e-mail check, registration, order wizard ────┼──┐  │   MDC + Bootstrap), NO LOGIN
                     └──────────────────────────────────────────────┘  │  │
                     ┌──────────────────────────────────────────────┐  │  │
  office (admin) ──▶ │ CMS  dashboard.logisticasanchez.com          │  │  │   ls-cms (vanilla JS), JWT
                     │  approvals, tracking states, notes, reports ──┼──┤  │   login; host inferred; the live
                     └──────────────────────────────────────────────┘  ▼  ▼   build is newer than the repo (Q-47)
                     ┌──────────────────────────────────────────────────────┐
                     │ Backend  app.logisticasanchez.com/mono/...           │  repo ls@migration (chapter 12);
                     │  /mono/api · /mono/cms · /mono/auth · e-mail pages   │  Contabo VPS, Docker Compose:
                     │  MySQL 8.0 `lsdb` on the same server · AWS SES       │  nginx + Spring Boot + MySQL
                     └──────────────────────────────────────────────────────┘
```

* **Public site.** The live site is a separate frontend. A **new static site** with the
  new visual design already exists (`ls-home`, chapter 06): it calls the production
  tracking endpoint and sends the quote/contact forms by e-mail through AWS SES. It is
  the visual and content base of the new app.
* **Order portal** (`ls-order`, chapter 03). A customer types an e-mail; the backend says
  whether it is NEW, PENDING or REGISTERED. New customers register (company data) and
  confirm by e-mail; the office approves them. Registered customers place an order
  (pallets/parcels with sizes and weights, transport document number, accessory service,
  notes, recipient). **There is no authentication**: knowing a registered e-mail is
  enough to order in that customer's name.
* **CMS** (`ls-cms`, chapter 04). Office staff log in (JWT), approve or decline customers
  and orders, assign the shipment code and type the amount for the customer when
  approving an order, set the tracking state by code, keep notes per order, see the
  order detail and have a report e-mailed for a date range. The build in the repository
  is older than the backend's contract; the live one (probably at
  `dashboard.logisticasanchez.com`) is not in any repository (Q-47).
* **Backend** (chapters 02 and 12). Spring Boot, REST under `/mono/api`, `/mono/cms`,
  `/mono/auth`, plus pages for the links in its e-mails; tracking states `NON_GESTITO,
  PRESA, TRANSITO, TERMINAL_DEST, ASSEGNATO, CONSEGNATO`; ten automatic e-mails through
  SES; a PDF report reached through links. Only methods annotated for the ADMIN role are
  protected: several staff endpoints are open (Q-21), and every request and response body
  is logged (12a §5).
* **Pricing** (chapter 07). Manual: the admin types "Importo Totale (per cliente)" when
  approving (stored as `importo`, a number > 0; customers see it in their e-mails and in
  the report). The backend computes no price. Per-customer Excel price lists (region ×
  pallet class); the partner network (RKS) costs are computed in a separate Excel
  calculator.
* **Infrastructure** (12d). Since October 2025 one Contabo VPS runs nginx (Let's Encrypt),
  the backend and MySQL with Docker Compose; before, AWS Elastic Beanstalk and RDS in
  eu-north-1. AWS is still used for SES. Part of the production configuration (`.env`,
  `config/application-prod.yml`) and the backups live only on that server (Q-45, Q-48).

## The goal (to-be)

**One monolithic web application** that serves the public site, the customer area, the
back office and the API, owns the database, and sends all e-mails through AWS SES.
It replaces the public site, the order portal, the CMS and the backend in one cut-over,
keeps every existing behaviour and URL that customers or the office rely on, and closes
the security gaps of the legacy system.

### Milestones

| Milestone | Scope | Rule |
|---|---|---|
| **M1 · Parity + customer area** | Everything production does today (public site with the new design, order flow, back office, tracking, e-mails, reports), the data migrated, a customer area with login that replaces the open order portal. | Nothing that works today may stop working. Every legacy behaviour is listed in chapter 09 and covered by a test. |
| **M2 · Safe enhancements** | Additions that cannot break M1 and do not slow the site: per-customer price lists with Excel import and a suggested amount at approval, indicative prices, tracking history and notifications, leads inbox and saved quotes, address book and re-order, documents and monthly reports, exports, 2FA, unified delivery times, notice banner, "open now", pick-up cut-off, live counters… (chapter 09, ENH-01…22). | Each behind a feature flag, off until the owner turns it on; no change to M1 contracts; built after the cut-over. |
| **Later · Experimental** | Chat assistant, WhatsApp notifications, driver app with proof of delivery, delivery-slot booking, partner-network integrations, invoicing… | Out of scope now: leave extension points only. |

### Principles

1. **The legacy is a reference, not a contract.** Keep what customers and the office rely
   on — the order and approval flow, codes, data, tracking, e-mail content, links already
   sent — and record every deliberate change in an ADR. Where the legacy is ambiguous,
   inconsistent, undocumented or broken (bugs and security holes such as the open order
   portal, the US phone regex, hard-coded keys), the new app chooses the better behaviour
   and records it; it never waits for legacy information the owner may not have.
2. **Keep the new design.** The public pages, the design system and the widgets of the
   new static site are reused as they are (HTML/CSS/JS), rendered by the server.
3. **Boring, maintainable technology.** One repository, one deployable, one database.
   The owner (a single developer) must be able to run, test and deploy it alone.
4. **Italian product, English code.** Every user-facing text is Italian; code, comments,
   docs and commits are English.
5. **Measured, not assumed.** Every requirement of chapter 09 gets an automated test;
   every claim about performance or accessibility gets a check in CI.
6. **The owner decides** business rules, prices, anything that touches production,
   DNS, credentials or real customers. An unanswered question gets the default of
   chapter 11, listed at the next gate, and the work goes on.

### Success criteria

* The office runs a normal week on the new app without touching the old CMS.
* Every customer and order from the old system is in the new database, with codes,
  states and amounts reconciled.
* Old links (public pages, order portal address, tracking calls) keep working.
* Public pages keep the new site's quality: Lighthouse ≥ 95 in all four categories,
  no serious accessibility violations, strict CSP.

## How the brief is organised

| Chapter | Content |
|---|---|
| 01 | This chapter: context, goals, milestones, principles |
| 02 | Legacy backend contract, as observed from its clients |
| 03 | Legacy order portal (flows, fields, validations, copy, quirks) |
| 04 | Legacy CMS (views, actions, enumerations, copy, quirks) |
| 05 | Legacy public site and old URLs |
| 06 | New static site: the porting guide for the public part |
| 07 | Pricing: price lists, pallet classes, cost model, proposed engine (**confidential**) |
| 08 | Domain model and data migration |
| 09 | Features: M1 parity checklist, M2 enhancements, backlog |
| 10 | Architecture, stack, infrastructure, security, testing, cut-over |
| 11 | Open questions for the owner |
| 12 | The legacy backend from its source: overview, then 12a API and security, 12b data and migration, 12c e-mails, report and pricing, 12d infrastructure and repository |
