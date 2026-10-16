# 09 · Features

Everything the new app must do, with stable IDs. Every M1 item is a **parity
requirement**: it maps to a behaviour that exists today (chapters 02–06) or to the
minimum needed to replace it safely, and it needs at least one automated test (or, for
the few operational IDs, a dated rehearsal record — see `PROMPT.md`, rule 4). Tests are
tagged with their IDs and `docs/parity-matrix.md` is generated from the tags.

"Source" names the chapter where the legacy behaviour is documented; the reading map in
`README.md` gives the exact sections per module. Chapter 12 (the legacy backend source)
wins over chapters 02–08 on how the legacy works; this chapter decides what the new app
does, and where it differs from the legacy the difference is deliberate. "Fix" means the legacy behaviour is
deliberately not reproduced (security or data quality); record each fix in an ADR.

## M1 · Parity + customer area

### Public site (PUB)

| ID | Requirement | Source | Acceptance |
|---|---|---|---|
| PUB-01 | Every page of the new static site (ls-home at `9b78785`: `fe4b056` plus the two changes listed in chapter 06) is served at the same URL with the same content, metadata (title, description, canonical, Open Graph, JSON-LD) and assets. | 06 | A test walks the page inventory of chapter 06 §3 and the sitemap: every URL answers 200 with the expected `<title>`, canonical and one `<h1>`. The HTML differs from ls-home@9b78785 only in: form endpoints; the endpoint and business data in `config.js`; the order-portal links ("Portale ordini", "Effettua un ordine") renamed "Area clienti" → `/area-clienti/`; the privacy and cookie pages (NFR-03); copy the owner changes under Q-31. The test keeps an explicit allow-list of these differences, each listed in an ADR. |
| PUB-02 | Shared header, footer, head and CTA band are server-side fragments; contacts, hours, VAT number and social links come from one configuration source. | 06 | Changing the phone number in one place changes it on every page (test). |
| PUB-03 | `sitemap.xml` is generated from the public routes, plus `robots.txt`, `security.txt`, web manifest and icons. Routes under `/area-clienti/`, `/gestione/`, `/api/` and `/mono/` send `X-Robots-Tag: noindex, nofollow` and never appear in the sitemap. | 06 | Sitemap lists every indexable page, excludes `/grazie/`, 404 and the private areas; validates against the sitemap schema; header test on the private areas. |
| PUB-04 | Every redirect of `public/_redirects`, `/index.html` → `/` (the browser keeps the fragment, so `/index.html#chisiamo` still reaches its page), the legacy anchors (`/#chisiamo`, `/#contattaci`, `/#tracking`), `www` → apex (the delivery e-mails link to `https://www.logisticasanchez.com/#tracking`), the old order-portal host `ordini.logisticasanchez.com` → customer-area sign-in, the CMS host the backend links to, `dashboard.logisticasanchez.com` → `/gestione/`, and a 301 for every URL found by crawling the live site before the cut-over (Q-29). | 05, 06 | Each old URL returns a 301 to the right page (anchors: client-side redirect as today); every directory URL requested without the trailing slash (e.g. `/zone`) answers 301 to the slash form (chapter 06 §6.8). |
| PUB-05 | Security headers and CSP at least as strict as `public/_headers`; same caching rules. | 06 | Header test on public pages, including the caching rules (`/fonts/*` immutable for one year; `/img/*` and `/*.png` one week); no CSP violations in E2E runs. |
| PUB-06 | Quote request (4-step wizard): same fields, values, validation, URL prefill, summary and WhatsApp hand-off; server-side validation as `netlify/functions/form.mjs`, except that over-long values and malformed CAPs are rejected (422) instead of silently truncated (Fix, chapter 06 §5.5); e-mail to the office via SES with Reply-To = customer; honeypot; rate limit; `Origin`/`Referer` check (the endpoint is CSRF-exempt, chapter 10 §4); JSON answer for `fetch`, 303 → `/grazie/` without JavaScript. | 06 | E2E with JS on and off; invalid inputs rejected server-side with the same field rules; e-mail content asserted. |
| PUB-07 | Contact form: same as PUB-06 for its fields. | 06 | As PUB-06. |
| PUB-08 | CAP → region → delivery time widget, coverage map and Google Maps consent behave as today (same data in M1). | 06 | Unit test of the CAP mapping on the cases listed in chapter 06 §5.3; E2E on `/zone/`. |
| PUB-09 | 404 page and `/grazie/` (noindex) as today. | 06 | Unknown URL → 404 page with status 404. |
| PUB-10 | Quality budgets of the new site are kept. | 06 | Lighthouse CI ≥ 95 (performance, accessibility, best practices, SEO) on home, a service page, `/preventivo/`, `/tracking/`, `/contatti/`; axe: no serious/critical violations. |
| PUB-11 | Every valid quote or contact submission (honeypot excluded) is stored as a `lead` (status `NEW`, chapter 08 §2.3) in the same transaction that queues EML-08; retention per Q-18; no staff UI in M1 (ENH-06 adds it). | 06, 08 | Integration test: one valid submission → one lead row and one outbox row; honeypot → neither. |

### Tracking (TRK)

| ID | Requirement | Source | Acceptance |
|---|---|---|---|
| TRK-01 | `/tracking/` looks up a shipment by code, supports `?code=` deep links, shows the state with the progress bar and legend of the new site; `Non gestito`, which the legacy also answers for unknown codes, shows the neutral "no updates yet" message (chapter 06 §5.7). | 06, 02 | E2E for each of the six states and for an unknown code. |
| TRK-02 | The lookup reads the database of the new app. Code matching: trimmed, spaces removed; case rule as the legacy backend (Q-03; default case-insensitive). Temporary codes of pending orders answer like unknown codes, `Non gestito`, as the legacy does (Q-38). | 02 | Unit tests for matching rules. |
| TRK-03 | Public tracking exposes no personal data and no documents: no names, addresses, phones, free-text notes, proof-of-delivery or other file links; the destination at most as city and province. | — (fix) | Schema test: the answer contains only `status`, `updatedAt`, `eta`, origin/destination city and `events` `{time, status, location}`. |
| TRK-04 | Tracking lookups are rate-limited per client IP. | — (fix) | Test: the limit answers 429. |

### Legacy API kept for compatibility (API)

| ID | Requirement | Source | Acceptance |
|---|---|---|---|
| API-01 | `POST /mono/api/order/trace/state` with `{"codice": "…"}` answers like the legacy backend: 200 `{"status": "<label>"}` for a known code, 200 `{"status": "Non gestito"}` for an unknown or temporary code, 400 for an empty one; CORS allows `https://logisticasanchez.com`; served on the apex and on `app.logisticasanchez.com`. Extra fields may be added, none removed. Full rules: chapter 02 §10.2. | 02, 05 | Contract test with the fixtures of chapter 02 §10.4; CORS preflight test. |
| API-02 | `GET /mono/api/enumeration/statoTracking` and `GET /mono/api/enumeration/servizioAccessorio` keep their JSON shape and values. | 02 | Contract tests with the values of chapter 02 §9.1–9.2 (known from the source; probe P-01 only confirms the live build). |
| API-03 | The host `app.logisticasanchez.com` is served by the new app for these paths. Other legacy endpoints (order portal, CMS, auth) are retired together with their frontends at cut-over and never perform their old action. | 02 §10.3 | ADR records the list; retired paths answer `410 Gone` (JSON); the `GET` links of e-mails already sent (`/mono/email-confirm`, `/mono/report`, `/mono/download-report`, any other `GET /mono/**`) get a small Italian page pointing to `/area-clienti/` on the apex and on `app.`, never confirm an address and never serve report data; `/mono/img/logistica-sanchez-logo.jpg` is served as a file (chapter 02 §10.3). |

### Customer area — replaces the order portal (CUS)

| ID | Requirement | Source | Acceptance |
|---|---|---|---|
| CUS-01 | Entry by e-mail, as today, but authenticated. The customer types an e-mail and the page always shows the same message. A known customer receives a one-time sign-in link (EML-01). An unknown address receives a single-use registration link (24 h) that opens `/area-clienti/registrazione/` with the address pre-filled and already verified — so a new e-mail still leads to registration (OP-R1) without revealing whether it exists. The e-mailed link only opens a confirmation page; the token is used by that page's `POST` (chapter 10 §2), so mail scanners and link previews cannot burn it. | 03 (fix: OP-S1, OP-S2) | Tests: a `GET` never consumes the token (two GETs then a POST still sign in; a second POST is refused); expiry; same page and status for known and unknown e-mails; rate limits per e-mail and IP. |
| CUS-02 | Registration with the legacy fields and rules (company or personal name, street address, province, city, CAP, phone, e-mail — rules OP-R1/OP-R3 of chapter 03), a summary to confirm, e-mail verification, then **pending** until the office approves; legacy copy reused where it fits. The page always answers «Ti abbiamo inviato un'email: segui il link per continuare» with the same status: for the e-mail of an existing non-declined customer it creates nothing and sends a sign-in link instead; for a declined e-mail the new data are stored apart and applied only when the verification link is used (Q-09). Honeypot and the per-e-mail/per-IP limits of the sign-in link. The page links the privacy notice. Fixes: Italian formats (5-digit CAP, Italian/E.164 phone), trimmed and lower-cased e-mail; phone required, as the legacy backend requires it, although the portal's label said optional (OP-08, Q-36). | 03 (fix: OP-08…11) | E2E: register → verify → pending page → admin approves → customer can order; registering a known e-mail returns the same page and status as a new one. |
| CUS-03 | Pending and declined customers see their status after signing in and cannot order. | 03 | E2E per status. |
| CUS-04 | New order wizard with the legacy content (rules OP-R4…OP-R8 of chapter 03): lines of pallets/parcels (type "Pallet Generico/Colli" or "Epal", height, length, width in cm, weight per unit in kg, quantity; at least one line; integers ≥ 1, limits per Q-35) with live totals computed as today (weight = Σ peso × quantità; volume = Σ round₂(h × l × w / 1 000 000 × quantità)); transport document code required (latest legacy code; Q-24); number of packages (optional; collected but never sent by the legacy portal — store it); one accessory service from the enumeration (default "none"); notes; recipient (name, address, province, city, CAP required; phone optional; optional recipient e-mail, new); summary to confirm; submission creates a **pending order with a temporary code**, protected against double submission; final screen with the legacy wording. Sender = the signed-in customer. | 03 (fix: OP-01…07, OP-12…16) | E2E happy path; unit tests for totals (no float artefacts) and validation; the stored order matches the input. |
| CUS-05 | "I miei ordini": list of the customer's orders (code, date, recipient, state, tracking state, amount when approved) and an order detail page. | new (needed to make login useful) | E2E: the customer sees only their own orders (authorisation test). |
| CUS-06 | Profile page showing the company data; changes are requested to the office (message), not applied directly. The login e-mail changes only after the new address confirms a link sent to it (token `CHANGE_EMAIL`), with a notice to the old address; staff change it only on a request sent from the signed-in customer area, never on a phone call or an e-mail; each change is audited and ends the user's other sessions. | new | E2E; e-mail change tests. |

### Back office — replaces the CMS (ADM)

| ID | Requirement | Source | Acceptance |
|---|---|---|---|
| ADM-01 | Staff login (username + password, hashed), roles `ADMIN` and `OPERATOR`, logout, lock-out after repeated failures. No staff account is created by migrations or seed data outside the local and test profiles; the first ADMIN is created by a one-off command that prints a one-time set-password link (chapter 08 §3.9); passwords have at least 12 characters and are checked against a list of common passwords; ADMIN creates the other accounts, each user sets the password through a one-time link. | 04 (fix: CMS-01…03) | Tests for login, roles, lock-out; a test fails if a migration inserts into `staff_user` or a seed user exists outside local/test. |
| ADM-02 | Customers waiting for approval: list with the legacy columns; approve / decline. Only a customer whose e-mail is verified can be approved, as in the legacy (Q-28). | 04, 12b §3.1 | E2E; e-mails EML-03 sent; approving an unverified customer is refused. |
| ADM-03 | Registered customers: list with the legacy columns, newest first, search, customer page with their orders. | 04 | E2E. |
| ADM-04 | Orders waiting for approval: list with the legacy columns (temporary codes shown as "-"); **approve** = assign the shipment code, type the amount for the customer (required, a euro amount > 0 as the legacy backend requires for `importo`, Italian comma accepted; meaning per Q-05; 12c §4.2), tracking becomes `PRESA`; **decline** with e-mail to the customer. | 04 | E2E; code uniqueness enforced; e-mails EML-05. |
| ADM-05 | Registered orders: list with the legacy columns, newest first, server-side pagination, filters (state, tracking state, customer, dates), search by code. | 04 | E2E with > 1 page of data. |
| ADM-06 | Set the tracking state of an order by code (the six states, any order as in the legacy CMS); each change is stored as an event with time and user; when the state becomes `CONSEGNATO` the customer gets EML-09, as in the legacy. | 04, 12c §1.3 | Unit + E2E; history kept; EML-09 sent on `CONSEGNATO` only. |
| ADM-07 | Internal notes per order: read and edit. The legacy note is the customer's own order note, overwritten by staff (12b §3.6); the new app keeps the customer's note and adds internal notes (a behaviour change: ADR, Q-26). | 04 | E2E. |
| ADM-08 | Order detail: shipment data, packages, pallet lines (legacy sections). | 04 | E2E. |
| ADM-09 | Report of one customer's approved orders created in a date range (inclusive), optionally only the delivered ones, as the legacy: a PDF, A4 landscape, customer block and 13 columns (12c §3). The office always receives it; the customer it is about only if staff choose so; any other address must be on a recipients allow-list kept by ADMIN (default: the office address), otherwise the send is refused. The e-mail (EML-07) carries a link to an **authenticated** download (staff session, or the customer's own session), never the legacy public, never-expiring links (12c §3.5). No internal notes, no margins. Every send writes the audit log (user, recipient, range, filter, row count). Spreadsheet output, if added (ENH-08), writes cells as text (chapter 10 §4). | 04, 12c §3 | Tests: a report for customer A contains no order of customer B; an address that is neither the customer's nor on the allow-list is refused; the download needs the right session; the PDF has the legacy columns. |
| ADM-10 | Enumerations (tracking states, accessory services) seeded with the legacy values: the six tracking states and the six accessory services of chapter 02 §9 (item 0 = none). | 02, 04, 12b §2 | Seed test: exactly the legacy values. |

Not ported: the backend's admin tools (hard deletes, free-text e-mail through SES, order
update from Postman; 12c §6, 12a SEC-15, SEC-16). Corrections after approval, if the owner
wants them, are an ADMIN action with an audit entry (chapter 08 §2.4).

### E-mails (EML)

All e-mails: Italian, text + HTML in the brand style, sent with AWS SES from a verified
domain address, Reply-To set where a person should answer. The legacy backend sends ten
automatic e-mails, HTML only, without Reply-To (12c §1: triggers, recipients, subjects and
verbatim bodies; mapping in 12c §1.5). Reuse their content without their defects (12c
§1.6); the owner reviews the copy at the next gate. The office address is a setting (the
legacy hard-codes a Gmail address, Q-43).

| ID | Trigger | To | Legacy (12c §1.2) |
|---|---|---|---|
| EML-01 | Sign-in link, registration link, e-mail verification, e-mail change | customer | L-E1, verification only (its link never expires and confirms on `GET`: fixed by CUS-01) |
| EML-02 | Registration received, waiting for approval | customer | none (new) |
| EML-03 | Account approved / declined | customer | L-E3 / L-E4 |
| EML-04 | Order received (pending) | customer | none (new) |
| EML-05 | Order approved (code, order detail, amount, tracking link) / declined (with the order code) | customer | L-E6 / L-E7 |
| EML-06 | New registration (when the customer verifies the e-mail, once), new order | office | L-E2 (sent on every click of the link) / L-E5 |
| EML-07 | Report for a date range (link to an authenticated download), per ADM-09 rules | office; the customer if chosen | L-E10 / L-E9 |
| EML-08 | Quote and contact requests from the public site (as `form.mjs`) | office | not in the backend (`form.mjs`) |
| EML-09 | Delivery done, «Consegna effettuata per ordine N. {codice}», when tracking becomes `CONSEGNATO` (ADM-06) | customer | L-E8 |

Acceptance: an integration test per e-mail asserts recipient, subject, Reply-To and the
key content; a local mail catcher shows them during development; outside production no
e-mail reaches real recipients (chapter 10 §3).

### Data migration (MIG)

| ID | Requirement | Source | Acceptance |
|---|---|---|---|
| MIG-01 | Importer from the legacy MySQL dump (`lsdb`; the plan: chapter 08 §4.2, 12b §5.6); a JSON snapshot taken through the legacy API by the owner is only an emergency fallback with known gaps (chapter 08 §4.3). Idempotent, with dry-run. | 08, 12b §5 | Running it twice gives the same result (on synthetic fixtures). |
| MIG-02 | Customers (with status), orders (codes, states, tracking state, amounts, dates), lines, recipients, notes and enumerations are imported. | 08 | Reconciliation report: counts per state and sum of amounts equal the source. |
| MIG-03 | Cut-over and rollback runbook (freeze, final delta import, DNS for apex, `www`, `app.`, `ordini.`, `dashboard.`; legacy write endpoints blocked in the legacy VPS's nginx; dangling DNS records removed; old credentials rotated). | 08 §4.7–4.9, 10 §7 | Rehearsed on staging with the anonymised copy of production; the real snapshot only in the owner-approved, access-restricted rehearsal environment. |

### Non-functional (NFR)

| ID | Requirement |
|---|---|
| NFR-01 | Performance and accessibility budgets of PUB-10; customer area and back office usable on a phone. |
| NFR-02 | Security baseline of chapter 10 §4 (sessions, CSRF, rate limits, headers, uploads, secrets, least-privilege IAM, audit log of staff actions). |
| NFR-03 | GDPR (chapter 10 §5): privacy and cookie pages updated for the customer area and processors; export and erasure on request (erasure = anonymisation, never a cascade delete; orders kept for the legal period); no personal data in logs or in the audit log beyond ids. |
| NFR-04 | Observability: structured logs, health endpoint (the only management endpoint reachable from the internet, without details), error alerts, SES bounce/complaint handling without a public endpoint (chapter 10 §2). |
| NFR-05 | Backups: encrypted, off-site, monitored for age (chapter 10 §3), and a tested restore procedure. |
| NFR-06 | CI runs lint, unit, integration, contract, E2E, accessibility and Lighthouse checks on every pull request; deploys to staging automatically and to production on approval. |
| NFR-07 | Runbooks in Italian: local setup, deploy, cut-over, rollback, restore, key rotation. |

## M2 · Safe enhancements

Rules: each item sits behind its feature flag (default **off**), changes no M1 contract,
adds no JavaScript framework to public pages, and has its own tests. M2 work starts after
the cut-over and the rollback window (`PROMPT.md`, Phase 5). The owner enables flags one
by one.

| ID | Flag | Enhancement | Notes |
|---|---|---|---|
| ENH-01 | `pricing.lists` | Price lists: per-customer and public lists (region × pallet class, transit times, rules, validity, versions), Excel import in the layout of the customer files with preview and validation, assignment to customers. | Chapter 07 §6; layouts of the other customers' files per Q-34. |
| ENH-02 | `pricing.suggestedAmount` | At order approval the back office proposes the amount from the customer's list; staff can change it; both values are stored. | Cheapest fitting class rule, chapter 07 §3. |
| ENH-03 | `pricing.customerEstimate` | While ordering, a customer with a list sees an indicative price ("indicativo, confermato all'approvazione"). | |
| ENH-04 | `pricing.publicEstimate` | Indicative price range in the public quote wizard from the public list. | Owner must approve the published bands; never customer-specific prices. |
| ENH-05 | `tracking.history`, `tracking.notifyCustomer` | Timeline with dates on public tracking (TRK-03 limits apply) and in the customer area; optional e-mail to the customer on the other state changes (the delivery e-mail is M1, EML-09). | Events are recorded from M1 (ADM-06). |
| ENH-06 | `leads.inbox` | Quote and contact requests listed in the back office with a status (new, contacted, won, lost) and "convert to customer / order draft". | Submissions are stored from M1 (PUB-11). |
| ENH-07 | `customers.addressBook`, `orders.repeat` | Saved recipients; "repeat this order". | |
| ENH-08 | `admin.dashboard`, `admin.exports` | Counters on the back-office home; CSV/XLSX export of every list (cells as text, chapter 10 §4). | |
| ENH-09 | `security.adminTotp` | Two-factor authentication (TOTP) for staff. | |
| ENH-10 | `delivery.unifiedTimes` | One source for delivery times (zones / price-list transit times) feeding the CAP widget and an API. | Resolves the mismatch noted in chapter 07 §2.4. |
| ENH-11 | `pricing.marginView` | Staff-only margin per order using the partner cost model. | Chapter 07 §6.8, confidential. |
| ENH-12 | `documents` | Staff upload documents per order (transport document, proof of delivery, invoices) to S3; the order's customer downloads them in the customer area only. | Upload and download rules: chapter 10 §4. Never linked from public tracking (TRK-03). |
| ENH-13 | `admin.audit` | Audit log viewer (the log itself is written from M1). | |
| ENH-14 | `admin.enumerations` | Manage accessory services from the back office; the public `/servizi/` list and the quote-wizard extras read the enumeration (field values unchanged, PUB-06). | |
| ENH-15 | `orders.bulkTracking` | Update the tracking state of many codes at once. | |
| ENH-16 | `customers.profileEdit` | Customers edit their data; changes to company name or VAT go through approval; e-mail change as CUS-06. | |
| ENH-17 | `site.noticeBanner` | Staff-managed notice banner (text, link, level, start/end) rendered server-side in the header fragment; no inline style or script. | |
| ENH-18 | `site.openNow` | «Aperto ora / Chiuso» next to the office hours, computed in Europe/Rome from the business-hours configuration (PUB-02) plus staff-managed closures, by a small vanilla module so cached HTML is never stale. | |
| ENH-19 | `site.pickupCutoff` | Same-day pick-up cut-off countdown («Prenota entro le {hh:mm} per il ritiro di oggi») from a cut-off per weekday and the closures list. | Cut-off times: Q-44. |
| ENH-20 | `quotes.saved` | From a lead (ENH-06) staff prepare a saved quote (lines, amount, validity); the customer gets an unguessable link and a PDF and converts it into a pending order after sign-in. | |
| ENH-21 | `documents.monthlyReport` | Monthly shipment report per customer (XLSX/PDF) in the customer area; invoices uploaded as documents (ENH-12) until e-invoicing exists. | |
| ENH-22 | `site.liveCounters` | Public counters (spedizioni, clienti) computed nightly from the database, shown only after the owner confirms the figures (Q-31). | |

## Later · Experimental backlog (do not build now)

Leave clean extension points (events, services) but no code: chat assistant for FAQs
and quotes · WhatsApp Business notifications · driver app with photo/signature proof of
delivery · delivery-slot booking for recipients · claims with photos · partner-network
(RKS) tracking integration · e-invoicing and payments · bulk CSV upload and an API for
large customers · PWA/offline · review requests after delivery · English version of the
site · editable blog/content · route optimisation · QR codes on labels.

Excluded: an embedded Google reviews widget (third-party script or iframe against the
CSP, consent and the Lighthouse budget); use a static link to the Google Business Profile
and, later, review requests after delivery.
