# Phase 0 reader — chapter 01 · Context and goals

Source: `docs/brief/01-context-and-goals.md` (148 lines), read in full on 2026-10-16. Line
anchors below are `01:<line>`. Code citations use the brief's convention (`repo@ref:path:line`);
chapter 01 cites no code itself, so the citations come from chapter 12, which it defers to.

## 1. Scope

Chapter 01 fixes the goal (one monolith replacing site, portal, CMS and backend in one cut-over),
the three milestones (M1 parity + customer area, M2 flagged enhancements, Later), six principles
and four success criteria; it also summarises the as-is system (01:34-86) and the company data
(01:16-32) that the public pages must show. It is "always, first" for every module and phase
(README reading order); no section of it is module-specific. The planner, ADR 0001 and every
gate summary should quote its principles 1, 5 and 6 and its success criteria (01:124-131).

## 2. Requirement IDs touched

| IDs | Where chapter 01 specifies or constrains them |
|---|---|
| PUB-01, PUB-02 | principle 2 (01:112), company table and hours (01:16-27): contacts come from one configuration source |
| PUB-04, API-01, API-03 | success criterion "old links keep working" (01:129); as-is diagram: e-mail links point to the apex by default, tracking box calls `POST /mono/api/order/trace/state` (01:36-54) |
| PUB-05, PUB-10, NFR-01 | success criterion: Lighthouse ≥ 95 in all four categories, no serious a11y violations, strict CSP (01:130-131) |
| PUB-06, PUB-07, EML-08 | as-is public site: quote/contact forms sent by e-mail through SES (01:56-59); goal: all e-mail through SES (01:90-92) |
| PUB-08, ENH-10 | business paragraph: 24–72 h, up to 96 h for remote areas (01:28-29) |
| TRK-01, TRK-02, ADM-06, ADM-10 | the six tracking states, listed verbatim (01:74-75) |
| CUS-01, CUS-02, CUS-03 | portal flow NEW/PENDING/REGISTERED, registration with company data, e-mail confirmation, office approval; "there is no authentication" (01:60-65); M1 scope "customer area with login" (01:100) |
| CUS-04, ADM-04, ADM-08 | order content (pallets/parcels with sizes and weights, transport document number, accessory service, notes, recipient) (01:62-64); approval = shipment code + amount typed by staff (01:66-69, 01:78-82) |
| ADM-01, ADM-02, ADM-07, ADM-09 | CMS bullet: JWT login, approve/decline, notes, detail, report for a date range (01:66-71) |
| EML-01…09, EML-05, EML-09 | ten automatic e-mails through SES; customers see the amount in e-mails and in the report (01:74-76, 01:80-81); principle 1 keeps e-mail content and links already sent (01:106-108) |
| MIG-01, MIG-02, MIG-03 | the database is a MySQL container, the owner can provide a dump (01:12-14); "the data migrated" (01:100); success criterion: codes, states, amounts reconciled (01:127-128); one cut-over (01:92-93) |
| NFR-02, NFR-03, NFR-04 | goal "closes the security gaps" (01:93-95); open staff endpoints (Q-21) and body logging (01:75-77); hard-coded keys (01:109) |
| NFR-05, NFR-06, NFR-07 | backups only on the server (01:86-87, Q-48); principle 3: one developer runs, tests, deploys alone (01:114-115); principle 5: every claim checked in CI (01:118-119) |
| ENH-01…22 (all) | M2 rule: each behind a flag, off by default, no M1 contract change, built after the cut-over (01:101); ENH-01…04 from "personal price list per customer", "indicative prices within the bands" (01:30-32) |

## 3. Rules to keep (M1 parity)

| Rule (01 wording) | Evidence in the legacy code | Serves |
|---|---|---|
| Public tracking: `POST /mono/api/order/trace/state` answers `{"status": "<label>"}`; unknown/temporary codes → "Non gestito", empty → 400 | ls@migration:src/main/java/it/logisticasanchez/mono/api/controller/OrderController.java:33-38 (verified); 12a §1.4 D1 | API-01, TRK-01, TRK-02 |
| The six tracking states and their Italian labels, in this order (ids 0–5) | ls@migration:src/main/java/it/logisticasanchez/mono/business/model/enumeration/state/TrackingState.java:15-20 (verified) | ADM-06, ADM-10, API-02 |
| Approval = shipment code (unique) + amount `importo` > 0, tracking becomes PRESA; the amount is shown to customers | ls@migration:src/main/java/it/logisticasanchez/mono/business/service/crud/SpedizioneService.java:152-173 and api/req/crud/PatchSpedizioneReq.java:27-28 `@Positive` (verified); 12c §4.2 | ADM-04, EML-05, ADM-09 |
| Customer flow NEW → (e-mail link) PENDING → (staff) REGISTERED; registration with company data; new customers register, the office approves | 12a §1.4 B (SoggettoService.java:53-70, 117-121); 12b §3.1 | CUS-02, CUS-03, ADM-02 |
| Order content: lines with type, sizes, weight, quantity; transport document number; one accessory service; notes; recipient | 12a §1.4 C1; ls-order@dev:js/rest/dto/order-request.js:3-43 (PROMPT.md) | CUS-04 |
| Ten automatic e-mails (content and links already sent are kept); delivery e-mail on CONSEGNATO; report for a date range as PDF | 12c §1.2 (L-E1…L-E10), §1.3, §3 | EML-01…09, ADM-09 |
| Links in e-mails default to the apex host; old portal and CMS hosts (`ordini.`, `dashboard.`) exist and must keep resolving | 12c §1.4 (docker-compose.yml:83 `EMAIL_ORIGIN`, LinkUtil.java:24-30) — value NOT CHECKED (redacted) | PUB-04, API-03 |
| Company data shown on every page: legal name and slogan, VAT, hub address, one phone/WhatsApp number, office e-mail, hours Mon–Fri 8–18 and Sat 8–12, Instagram (values: 01:18-27, 06 §7.3) | ls-home@fe4b056:public/js/config.js:11; partials/footer.html:1-73 (06 §7.3) | PUB-02 |

## 4. Deliberate fixes (each needs an ADR; proposed titles)

| Fix (01 names it) | ADR title |
|---|---|
| Open order portal (no authentication, 01:64-65; SEC-12) → customer area with one-time sign-in links (CUS-01) | "0002 Customer sign-in replaces the open order portal" |
| Only `@PreAuthorize` methods protected, several staff endpoints open (01:75-76; SEC-01…05) → deny-by-default, retired endpoints answer 410 (API-03, NFR-02) | "0003 Deny-by-default authorisation and retired legacy endpoints" |
| Every request/response body logged (01:76-77; SEC-07) → no body logging, no personal data in logs (NFR-03) | "0004 Logging without bodies or personal data" |
| Hard-coded keys (01:109; SEC-06, Q-53) → configuration from the environment, secret scanning | "0005 Secrets from the environment only" |
| US phone regex and other portal validations (01:109; 03 OP-08…11) → Italian formats, phone required as the backend requires (CUS-02) | "0006 Italian validation rules for registration and orders" |
| JWT CMS login with permanent refresh tokens (01:66; SEC-08; CMS-01…03) → staff form login, roles ADMIN/OPERATOR, lock-out (ADM-01) | "0007 Staff authentication and roles" |
| Public never-expiring report links and GET verification (12c §1.4, §3.5) → authenticated download, POST-confirmed tokens (ADM-09, CUS-01) | "0008 Authenticated report download and single-use links" |
| Staff overwrite the customer's note (12b §3.6) → separate internal notes (ADM-07, Q-26) | "0009 Internal notes separate from the customer's note" |
| Public site HTML differs from ls-home only by the allow-list of PUB-01 (form endpoints, config, "Area clienti" links, privacy pages) | "0010 Allowed differences from the static site" |

## 5. Unknowns by ID (default from chapter 11 unless noted)

* Q-21 (01:75) — open staff endpoints: nginx rule on the legacy VPS now, `@PreAuthorize` fix (F-01); new app deny-by-default.
* Q-45 (01:86) — what runs on the VPS: not needed for the build; owner runs 12d §1.15 read-only before cut-over.
* Q-47 (01:52, 01:69-70) — live CMS build and host: not needed; back office built from 04 + 12; assume `ordini.`/`dashboard.` call `app.`.
* Q-48 (01:86) — backups only on the server: encrypted off-site backups and a restore test before any production work (F-09).
* Q-05 (01:78-81) — meaning of `importo`: price charged, VAT excluded, decimal with a VAT flag, > 0 (CU-08).
* Q-01, Q-52 (principle 3, 01:114) — Path B on Java + Spring Boot; new VPS at the same provider; PostgreSQL.
* Q-16, OP-U6, LPS-U7 (01:36-54) — who hosts the apex, `ordini.`, `dashboard.` and manages DNS: owner changes A records at the current provider.
* Q-50 (01:40-41 "links point to the apex by default") — `EMAIL_ORIGIN` host: answer `/mono/*` on apex and `app.` (F-14).
* Q-53, SEC-06 (01:109 "hard-coded keys") — committed keys: rotate now, purge history with the owner; agents never open them.
* Q-10 (01:30 "mostly companies; private recipients exist") — who registers: both; VAT/tax code optional.
* Q-15, LPS-04 (01:24 one number) — second WhatsApp number: not used.
* Q-34, Q-14, Q-33, PR-01…PR-14 (01:30-32) — price lists and indicative bands: MOSCA layout only; VAT excluded; public estimate off until bands approved.
* Q-31 (01:28-29 "24–72 h, up to 96 h") — published claims: keep only what the owner confirms.
* Q-43 (01:24 office mailbox is a consumer Gmail) — business mailbox before go-live; the address is a setting.
* Q-02 (01:12-14) — schema dump: optional; importer built against 12b §1.7; real data only on owner infrastructure.
* Q-07 (01:74-76 "ten automatic e-mails") — wording: reuse legacy content without defects; owner reviews at the gate.
* Q-19, Q-40, Q-29, Q-30 (01:92-93 "one cut-over", 01:129 "old links keep working") — Saturday window, rollback until next working day, crawl before cut-over, `/mono/**` landing page.
* CMS-01…03, CU-21 (01:66-71) — JWT CMS and unknown live build: fixed by ADM-01; no parity with `ls-cms@dev` needed.
* SEC-01…05, SEC-07, SEC-12 (01:64-65, 75-77) — fixes as in §4; F-19 (stack end-of-life) feeds ADR 0001.

## 6. Contradictions

* 01:116-117 principle 4 ("docs … are English") vs NFR-07 ("Runbooks in Italian"), PROMPT.md rule 3 and CLAUDE.md (gate summaries, UAT checklist, runbooks in Italian). PROMPT.md and chapter 09 win; read principle 4 as "code and design docs in English".
* 01:60-61 "the backend says whether it is NEW, PENDING or REGISTERED" vs 12a §1.4 B1 / 12b §2.2: `customer/state` can also answer `DECLINED` for rows declined before 2024-07-22 (ConfirmationState.java:15-18). Code wins; the importer and CUS-03 must handle `DECLINED` rows (Q-09).
* 01:112-113 principle 2 "reused as they are" vs PUB-01's explicit allow-list of differences (form endpoints, `config.js`, "Area clienti" links, privacy pages, Q-31 copy). Chapter 09 is the precise rule; principle 2 is the intent.
* 01:72-74 lists `/mono/api`, `/mono/cms`, `/mono/auth` and e-mail pages; 12a §2.7 also has `/mono/sendEmail` and `/mono/utils/**` (admin tools, ADMIN). Omission, not a conflict; API-03 retires them.
* 01:74 "ten automatic e-mails" vs 12 §6 "L-E1…L-E11": L-E11 is the free-text admin tool (`POST /mono/sendEmail`, 12c §1.2), not automatic. Consistent once read; do not port L-E11 (SEC-15).
* 12d §6.1 proposes corrections to "01:7-12, 01:47-50, 01:67-68" of an older revision; the current chapter 01 already carries them (12 §4). Still missing in 01: SES region eu-north-1 with a static IAM key (12d §0.1) and the delivery e-mail's `www…/#tracking` link (covered by PUB-04). Line references in 12d §6.1 are stale.
* 01:100 M1 rule "every legacy behaviour … covered by a test" vs PROMPT.md rule 4: NFR-05, NFR-06 (deploy), NFR-07, MIG-02 on real data and MIG-03 reach "ready-for-owner" until a dated rehearsal record. Nuance, PROMPT.md wins.
* 06 §7.3 (06:1227): two ls-home pages show hours without Saturday, while 01:25 and `config.js` include Sat 8–12. The owner's data (01) wins; PUB-02 fixes it from configuration (allow-listed difference, ADR 0010).

Spot check of legacy citations (opened in `legacy/ls`, head `ca1b0d0` confirmed with `git log -1`):

| Citation | Result | Evidence |
|---|---|---|
| ls@migration:…/api/controller/OrderController.java:33-38 | VERIFIED | `@PostMapping(PathConstants.ORDER_TRACKING_STATE_URI)` returns `StatusRepr` with `status(state.getLabel())` |
| ls@migration:…/business/model/enumeration/state/TrackingState.java:15-20 | VERIFIED | six constants NON_GESTITO…CONSEGNATO with the labels of 12b §2.2, `@JsonValue getLabel()` |
| ls@migration:…/business/service/crud/SpedizioneService.java:152-173 | VERIFIED | `requireNonNull` on codice/importo/tracking (152-154), `CODE_409` on duplicate (163), PENDING check (164), contrassegno overwritten (166-167), REGISTERED + PRESA (169-170); `@Positive importo` in PatchSpedizioneReq.java:27-28 |
| ls@migration:…/configuration/security/WebSecurityConfig.java:37-49 | VERIFIED | `requestMatchers(MONO_CMS_ALL).hasRole(ADMIN)` then `.anyRequest().permitAll()`, CSRF disabled, stateless; `MONO_CMS_ALL = "/mono/cms/**"` (PatternConstants.java:6). The "rule never matches" is 12a's inference from the context path (YAML value redacted here) |
| ls@migration:…/configuration/RequestResponseLoggingFilter.java:15-45 | VERIFIED | `@Component` filter wraps every request and logs the full request and response bodies at INFO |

## 7. Planned tests (per ID chapter 01 constrains; owners in §8)

* PUB-01/02: E2E `[PUB-01]` page inventory vs ls-home diff allow-list; unit `@Tag("PUB-02")` business-config fragment renders phone, hours (with Saturday), VAT on every page.
* PUB-04 / API-01 / API-03: contract `@Tag("API-01")` fixtures of 02 §10.4 (label, "Non gestito", 400, CORS); `@Tag("API-03")` retired paths → 410, `/mono/email-confirm` GET never confirms; E2E `[PUB-04]` old hosts and anchors → 301.
* PUB-10 / NFR-01: quality — Lighthouse CI ≥ 95 ×4 on the five pages; axe no serious/critical; CSP report-only violations = 0 in E2E.
* TRK-01/02, ADM-06/10: unit `@Tag("TRK-02")` matching rules; seed test `@Tag("ADM-10")` exactly six states/labels in legacy order; E2E `[TRK-01]` per state + unknown; integration `@Tag("ADM-06")` event stored, EML-09 only on CONSEGNATO.
* CUS-01…04: E2E `[CUS-01]` same page for known/unknown e-mail, GET never consumes the token; `[CUS-02]` register→verify→pending→approve→order; unit `@Tag("CUS-04")` totals and Italian validation (CAP, phone E.164, phone required).
* ADM-01/02/04/07/09: integration `@Tag("ADM-04")` code uniqueness (trimmed, case-insensitive), amount > 0 with Italian comma, tracking PRESA, EML-05; `@Tag("ADM-01")` no staff user from migrations/seeds outside local/test; `@Tag("ADM-09")` report contains only customer A, download needs a session, audit row written.
* EML-01…09: integration per e-mail (recipient, subject with «Logistica Sanchez», Reply-To, key content, links on the configured host); `@Tag("EML-08")` SES Reply-To = customer; staging guard test: no real recipient outside production.
* MIG-01/02/03: integration `@Tag("MIG-01")` idempotent import on synthetic dump twice; `@Tag("MIG-02")` reconciliation counts per state and sum of amounts (COALESCE(importo, importo_assicurato)), `DECLINED` rows mapped; MIG-03 rehearsal record (ready-for-owner).
* NFR-02/03/04: route matrix test (every `/gestione/**` and `/area-clienti/**` denied without session); log test: no bodies, no e-mail/phone in logs; health endpoint only management route.
* NFR-05/06/07: CI pipeline test runs all layers; backup script unit test + restore rehearsal record; runbooks exist in Italian (lint check).
* ENH-*: one `@Tag("ENH-xx")` per flag asserting M1 behaviour unchanged with the flag off.

## 8. Notes for the planner

* Ownership (10 §2): PUB-* → site; TRK-*, API-* → tracking; CUS-* → customers (CUS-04/05 → orders); ADM-* screens → backoffice, rules → customers/orders/tracking; EML-* → notifications; ADM-09/EML-07 → reports; MIG-* → migration; NFR-02/03/04 → platform (Phase 1), NFR-05/06/07 → integrator; ENH-* per 09 flag groups (Phase 5).
* Phase 1 foundations this chapter needs: business configuration source (PUB-02: contacts, hours, office address Q-43); the six tracking states and six services as seeds (ADM-10); `importo` as `numeric(10,2)` + VAT flag (Q-05); legacy ids/codes in their own columns (10 §2); deny-by-default security; outbox with the staging e-mail guard; audit log; no-body structured logging.
* ADR 0001: Path B on Java + Spring Boot current versions, PostgreSQL, new VPS at the same provider, pinned JDK; record the Path A assessment (Spring Boot 3.2 EOL, SDK v1, iText 2.1.7, `ddl-auto: update`, 12d §2) and principle 3.
* Gate 0 summary: Q-21/Q-53/Q-48/Q-49/Q-22/Q-23 urgent actions first (PROMPT.md "Start now"); then Q-05 (meaning of the amount, VAT), Q-01/Q-52, Q-16, Q-10, Q-15, Q-43, Q-50; the principle-4 language fix; the request for the schema-only dump (Q-02) and price lists (Q-34).
* Risks: the data model and pricing rest on defaults (Q-05, Q-14) until the owner answers; schema drift in the real `lsdb` (`ddl-auto: update`) only shows on the real dump; live CMS build unknown (Q-47) so office habits may differ from `ls-cms@dev`; `EMAIL_ORIGIN` host unknown (links in old e-mails); DNS/hosting of apex, `ordini.`, `dashboard.` unknown (Q-16) and needed for a one-step cut-over; stale line references in 12d §6.1 may mislead readers.
