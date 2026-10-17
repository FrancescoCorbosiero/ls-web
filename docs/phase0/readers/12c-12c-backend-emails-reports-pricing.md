# Phase 0 reader — chapter 12c · Legacy backend: e-mails, backend pages, report, pricing, SES, admin tools

Read in full (934 lines) on 2026-10-16. Precedence: legacy code > 12c > chapters 02–08; chapter 09 and the ADRs decide the target. Path abbreviations as in 12c: `J/` = `src/main/java/it/logisticasanchez/mono/`, `R/` = `src/main/resources/`, `T/` = `src/main/resources/templates/`; every citation is `ls@migration` (`ca1b0d0`) unless stated.

## 1. Scope

12c documents, from the backend source, everything the backend sends or renders for people: the ten automatic e-mails L-E1…L-E10 and the free-text tool L-E11 (§1: transport, triggers, recipients, verbatim subjects and bodies, link hosts, mapping to EML-01…09, defects), the pages behind e-mail links (§2), the PDF report (§3: request, selection, delivery, iText layout, open access, failure modes, the Q-08 answer), the unused pricing scaffolding and the manual amount (§4), the SES client and the committed key pair (§5), the Postman-only admin tools (§6) and the reconciliation with chapters 07, 09 and 11 (§7).
Reading map (README): **notifications + reports** §1–§3, §5 (with 06 §5.6, 10 §2 "E-mail" and §3, 04 §4.9, 02 §7.7) · **orders** §4.2 · **back office** §6 · **pricing (M2)** §4 · **migration / cut-over** §1.4, §2 (old links and hosts), §4.2 (amount columns), §5.5 (key rotation) through API-03 and MIG-03 · **Phase 1 foundations** §5.4–5.5 as the justification of the outbox and secrets rules of 10 §2 and §4.

## 2. Requirement IDs touched

| ID | Sections of 12c |
|---|---|
| EML-01 | §1.3 L-E1 (UUID code, no expiry, re-sent while NEW, GET side effect), §1.4 link host, §1.5, §1.6 |
| EML-02, EML-04 | §1.5 (no legacy e-mail; the `email-success` page text in §2 is the reusable copy) |
| EML-03 | §1.3 L-E3/L-E4 (welcome + portal link; decline through the generic `error` template, misleading subject) |
| EML-05 | §1.3 L-E6 (full detail with amounts, no link) / L-E7 (no order id), §4.2 (what the customer sees) |
| EML-06 | §1.3 L-E2 (sent on every verification click, before the check) / L-E5 (customer e-mail only, no code) |
| EML-07 | §1.3 L-E9/L-E10, §1.4 (public links), §3.3 (never an attachment), §3.5 |
| EML-08 | §1.5 (not a backend e-mail; `form.mjs`) |
| EML-09 | §1.3 L-E8 (CONSEGNATO only, no guard, `#tracking` link, `importo` under "Importo Assicurato"), §1.5, §7 |
| ADM-02 | §1.2 L-E3/L-E4 triggers, §7 (Q-28: approval needs PENDING, reached only by the link) |
| ADM-04 | §4.2 (`importo` required > 0, `@Positive`, code unique 409, PENDING only, PRESA, `importoContrassegno` bug) |
| ADM-06 | §1.3 L-E8 trigger (`CmsController.trace`), §5.4 (not transactional) |
| ADM-09 | §3 whole (request, selection, delivery, 13 columns, file name, access, failure modes), §3.7, §7 |
| ADM-01, NFR-02 | §3.5 (open report links), §5.5 (committed keys), §6 (ADMIN-only tools, no UI, not to port) |
| CUS-01 | §1.3 L-E1 token semantics, §1.6, §2 (`/mono/email-confirm` changes state on GET) |
| CUS-02, CUS-03 | §1.3 L-E1 re-registration (NEW: re-sent; PENDING: 500; REGISTERED: 409), L-E4 (decline → NEW), §2 landing texts |
| CUS-04 | §4.1 totals (weight Σ, volume Σ rounded to whole m³, PALLET/EPAL counts), §1.3 L-E5 (TEMP code), §3.4 cols 10–12 |
| CUS-05 | §4.2 (customers see the amount in L-E6, L-E8 and the PDF) |
| API-03 | §1.4, §2 (the five legacy GET paths, doubled `/mono/mono/*` views, 500 on unknown paths), §7 |
| PUB-04 | §1.4 and §2 hosts (`ordini.`, `dashboard.`, `www…/#tracking`, S3 and Elastic Beanstalk hosts) |
| MIG-01, MIG-02 | §4.2 (amount columns and fallback → DQ-13), §1.3 L-E7 (declined orders NEW/NON_GESTITO with TEMP code), §3.4 col 4 (`shippingTs` semantics) |
| MIG-03 | §1.4/§2 hosts for DNS and old links, §5.5 (rotate the committed SES key pair), §7 Q-30 |
| NFR-03 | §3.5 (PDF bodies in logs, personal data in the PDF), §6 (hard deletes vs anonymisation) |
| NFR-04 | §5.2 (no bounce/complaint pipeline), §5.4 (no outbox, no retry policy of its own) |
| NFR-06 | §5.5 (secret scanning need) |
| ENH-05 | §1.3 L-E8 (only the delivery e-mail exists; other states send nothing) |
| ENH-08 | §7 ADM-09 (XLSX only as an option beside the PDF) |
| ENH-01…04, ENH-10 | §4.3–4.7 (constants = MOSCA notes, traits = MOSCA layout, `EvasioneEnum` tiers, `PalletEnum` conflict, no RKS) |

## 3. Rules to keep (M1 parity)

| Rule | Citation | ID |
|---|---|---|
| Verification e-mail at registration, "Sanchez Logistica - Conferma indirizzo email", re-sent with the same code while the customer is NEW; body texts "Conferma indirizzo mail", "Clicca per confermare…", button "Conferma" | J/business/service/CustomerService.java:47-55; J/business/util/EmailReqFactory.java:25-41; T/email-confirmation.html:58-73 | EML-01, CUS-02 |
| Office notice on new customer and new order: "Nuovo cliente in attesa di conferma" / "Nuova richiesta di spedizione in attesa di conferma", body "Azione: …", "Cliente: {e-mail}" | EmailReqFactory.java:149-163; J/business/model/enumeration/AdminNotificationEnum.java:15-16; J/business/service/OrderService.java:110 | EML-06 |
| Approval: "Benvenuto in Sanchez Logistica!", "Da questo momento in poi fai parte dei clienti…", button "Portale ordini"; decline: "Indirizzo email e/o dati cliente rifiutati dall'amministratore" | T/customer-success.html:60-71; EmailReqFactory.java:44-72; T/error.html:46-50 | EML-03, ADM-02 |
| Order approval e-mail "Conferma ordine N. {codice}": "Ti confermiamo la presa in carico…", consignee block, amount, DDT, service, note, tracking, totals, one block per physical pallet, customer block; decline: "Ordine invalido rifiutato dall'amministratore" | EmailReqFactory.java:74-105; T/order-detail.html:46-208 | EML-05, ADM-04 |
| Delivery e-mail "Consegna effettuata per ordine N. {codice}" to the sender only when tracking becomes CONSEGNATO; "Ricordiamo che è sempre possibile verificare lo stato…" + public tracking link; no e-mail on other states | J/api/controller/CmsController.java:111-123; T/tracking-state.html:46-49 | EML-09, ADM-06 |
| Report e-mail "Report ordini", "Date di riferimento: {from} / {to}", download link; office always, the customer only when staff choose | CmsController.java:104-106; EmailReqFactory.java:107-131, 165-189; T/email-report.html:60-72 | EML-07, ADM-09 |
| Report selection: one customer (the sender) by e-mail, REGISTERED orders only, by creation time, `to` inclusive, optionally only CONSEGNATO | J/business/service/crud/SpedizioneService.java:206-234; J/persistence/repository/SpedizioneRepository.java:68 | ADM-09 |
| PDF: A4 landscape, title "Sanchez Logistica - Report", customer block (6 rows) + 13 columns with the verbatim headers, service label, Dt/Sped. = approval date, Colli = packages else PALLET count, file `{local-part}-{dd_MM_yyyy}_{dd_MM_yyyy}.pdf` | J/business/util/HorizontalPdfGenerator.java:26-222; J/business/factory/ReportFactory.java:37-65; J/business/util/ReportUtil.java:113-127 | ADM-09 |
| Amount: `importo` required and > 0 at approval, `importoAssicurato` optional; shown to the customer; fallback to `importoAssicurato` when `importo` is null and tracking ≠ PRESA | SpedizioneService.java:152-154, 194; J/api/req/crud/PatchSpedizioneReq.java:21-28; T/order-detail.html:85-95 | ADM-04, CUS-05, MIG-02 |
| Totals at registration: weight Σ per unit, PALLET/EPAL counts, volume Σ h·l·w / 10⁶ (rounding: see §6 item 8) | OrderService.java:80-94; J/business/util/MathUtil.java:5-7 | CUS-04 |
| Declined order → NEW + NON_GESTITO, kept with its TEMP code; declined customer → NEW | OrderService.java:140-146; CustomerService.java:73-75 | ADM-04, MIG-02, Q-09 |
| Sender address from configuration; sending switchable by one setting; SES in eu-north-1 | J/configuration/property/EmailProperties.java:8-13; J/business/service/AwsEmailService.java:37, 53, 71; J/configuration/AwsMailConfig.java:20-26 | EML-*, NFR-04 |
| Legacy link paths to answer on both hosts: `/mono/email-confirm`, `/mono/report`, `/mono/download-report`, `/mono/request-success.html`, `/mono/img/logistica-sanchez-logo.jpg`; hosts `ordini.`, `dashboard.`, `www…/#tracking`, `{EMAIL_ORIGIN}` (apex by default) | J/web/WebController.java:34-68; J/business/util/LinkUtil.java:14-40; docker-compose.yml:83 | API-03, PUB-04, MIG-03 |
| Customer footer "Questa email viene generata automaticamente…"; post-verification page "Abbiamo verificato il tuo indirizzo mail… Rimani in attesa della email di conferma…" (copy for EML-02) | T/email-confirmation.html:73; T/email-success.html:39-42 | EML-02, Q-07 |

## 4. Deliberate fixes (each needs an ADR)

| Fix (legacy → new) | Source | Proposed ADR title |
|---|---|---|
| Synchronous SES send inside the business transaction (a failure rolls back the order or registration; a late commit failure sends an e-mail for nothing) → outbox row in the same transaction, worker with retries, staging guard | §1.1, §5.4; 10 §2 | "E-mail outbox and delivery worker" |
| Office notice on every verification GET, before the code is checked → EML-06 once, after the POST that verifies | §1.3 L-E2, §1.6 | fold into "One-time links consumed by POST" (12a reader) |
| Public never-expiring report links carrying the e-mail; `email` both filter and recipient; PDF bodies logged → authenticated download (staff or the customer's own session), `report_run`, allow-list, audit, no body logs | §3.3, §3.5, §7 | "Report delivery through authenticated downloads" (12a reader) |
| HTML-only, no Reply-To, raw enum names and Java doubles, no € or comma, FA icons and commented logo, mismatched template variables, unencoded query values, decline e-mail without order id, `importo` labelled "Importo Assicurato" → text + HTML templates, Italian labels and amounts, Reply-To, encoded links, order code in every order e-mail | §1.1, §1.6 | "E-mail templates: legacy content, new rendering" |
| Hard-coded office Gmail recipient → a validated setting (Q-43) | §1.1 | fold into "Configuration, secrets and management endpoints" (12a reader) |
| Three Double amount columns, `importoContrassegno` overwritten, comma text → 500 → one `numeric(10,2)` amount with VAT flag, > 0, Italian comma accepted; legacy columns only as migration inputs (DQ-13) | §4.2 | "Amount semantics and migration rule" |
| Report: `onlyDelivered` NPE, `from > to` unchecked, UTC day boundaries, no ordering → explicit defaults, `from ≤ to` (422), Europe/Rome days, rows by approval date | §3.1, §3.2, §3.6 | "Report semantics: period, time zone, validation" |
| PDF: one bad row fails the whole download (400), header not repeated, no date range, partner billing annex header printed → generated from validated domain data, header repeated, range printed, annex header dropped | §3.4, §3.6 | "Report PDF layout" |
| SDK v1 with static IAM keys and hard-coded region, no bounce handling → SESv2 with a dedicated IAM user or role, SNS → SQS bounces (10 §2–§3) | §5.2, §5.5 | "SES integration and bounce handling" |
| `/mono/utils/**`, `/mono/sendEmail`, `/mono/cms/order/update` → not ported; corrections as audited ADMIN actions; anonymisation instead of cascade delete | §6 | "Legacy admin utilities not ported" (12a reader) |
| `TariffarioConst`, tariff traits, `EvasioneEnum`, `PalletEnum` → not ported; the chapter 07 engine behind the M2 flag; `PalletEnum` values rejected in favour of the image (PR-02) | §4.3–4.7 | "Pricing: no legacy code ported" |
| Delivery e-mail in M2 only (old ENH-05) → EML-09 in M1, applied in chapter 09 | §1.5, §7 | record in ADR 0001 as a chapter 09 correction |

## 5. Unknowns by ID (default the brief gives)

* **Q-07** answered (ten e-mails verbatim): reuse the content without the §1.6 defects, add EML-02/EML-04, owner reviews the copy at the next gate · **Q-08** answered: PDF via authenticated download; office always, the customer if chosen, others from an ADMIN allow-list (default the office); every send audited · **Q-05** partly: `importo` required, VAT (unknown); default price charged, VAT excluded, decimal + flag, > 0 · **Q-09** legacy facts (decline → NEW, same code re-sent); default explicit `DECLINED`, re-registration allowed · **Q-14** still open (no VAT or delivery-time logic; `EvasioneEnum` adds "48" and tier names); default VAT excluded, public estimate off, times from the price list · **Q-18** the legacy cleanup TODO belongs to the retention jobs; default 10 §2 values · **Q-28/CU-22** answered: only verified customers can be approved; default yes.
* **Q-21** (urgent) report links and EP-18 open: nginx block or office-IP allow-list the same day, counts-only log export (P-06), art. 33 assessment (12d F-01) · **Q-53** (urgent) committed AWS key pair: deactivate and rotate, check CloudTrail and SES statistics, purge history (12d F-03) · **Q-50** production `EMAIL_ORIGIN` (unknown); default answer `/mono/*` on both hosts · **Q-47/CU-21** live CMS build (sends `importo` and the report flags?); default assume `app.`, the back office is new anyway · **Q-30** links never expire; default a small page on `/mono/**`, never confirm or serve · **Q-29** keep the S3 bucket names; crawl before cut-over · **Q-43** Gmail hard-coded; default business mailbox before go-live, a setting in the app · **Q-37** every subject says "Sanchez Logistica"; default «Logistica Sanchez» · **Q-32/Q-34** constants mirror MOSCA 2024, no rule; defaults of 07 §8, MOSCA layout only.
* **CU-06, CU-12, CU-24** answered (§1, §3.7, §1.4/§2) · **PR-02** `PalletEnum` conflict; default the image limits · **PR-07, PR-09, PR-14** still open; defaults: no parcel price, 5 % replaces 3 %, storage information only · **DQ-13** default `COALESCE(importo, importo_assicurato)`, flag differences (12b §5) · **CMS-22** `from > to` unchecked (02 §7.7); fix with 422 · **SEC-03/SEC-05/SEC-07** (12a) open report links, open EP-18, body logging: fixed by design.

## 6. Contradictions

1. 12a §1.5 (line 895) says the order-decline e-mail "shows the order id"; 12c §1.3 L-E7 says it never names the order. Code: T/error.html:47 renders only `${request}`; EmailReqFactory.java:94-96 puts `codice` in the context unused → 12c is right; EML-05 must add the code (new behaviour, ADR).
2. `importo` required since **2024-07-16** (`d7732eb`: 12c §4.2, 07 §1.1, 11 Q-05, 12b §1) vs **2024-07-18** (`d7b2c56`: 12a §0.6, C6, §2.16). The 12a reader settled it: column on 07-16, `requireNonNull` on 07-18; DQ-13 uses 07-18.
3. 12c §7 says Q-28's default "staff may approve an unverified customer" is wrong; 11 Q-28 already reads "Yes, as today" and ADM-02 agrees. Stale sentence, no action.
4. 12c §1.5/§7 propose "add EML-09 (M1)" and "XLSX if unknown no longer applies": applied in 09 (EML-09, ADM-06, ADM-09 PDF, ENH-05 reworded). History, not a conflict.
5. 04 §4.9 (lines 653-657) still calls the report's content, format, attachment and the meaning of `email` unknown (Q-08) and carries no "From the source" banner (02 §7.7 has one). 12c §3.7 wins.
6. 12c §1.3 L-E1 says the verification e-mail is re-sent "while the customer is still NEW"; 12a §1.5 B2 and 12b §3.1 C5 add `DECLINED` rows of pre-2024-07-22 builds, whose link then fails. Same code, different scope; the migration maps those rows (12b).
7. Internal: §0 finding 5 ("the CMS never sends `sendToCustomer`") implies office-only reports work; §3.6 shows `ls-cms@dev` fails with 500 before any e-mail (`onlyDelivered` NPE). Both true: either production runs a newer CMS or the report is broken today (Q-47).
8. Volume: 12c §4.1 whole-m³ at registration vs 09 CUS-04 per-line round₂ (03 OP-R5); 12c §3.4 col 12 prints the legacy integer. Target per 09; the PDF must use one rule for migrated and new orders (recompute from lines), never compare the two (12a reader).
9. Time zone: 12c §3.2 selects by UTC days, §3.4 col 3 prints Europe/Rome dates, 10 §2 displays Europe/Rome. Target: Europe/Rome for selection and display (ADR); a few legacy reports differ around midnight.
10. PROMPT.md agrees with 12c (open report endpoints, line 107; the nginx block list of 10 §7 includes `/mono/sendEmail` and `GET /mono/email-confirm`; mail catcher and fake SES). CLAUDE.md "outbox only" matches §4. 10 §2 "SESv2" vs 12c §5.2 SDK v1 is a deliberate change.

Spot check of citations in `legacy/ls` (HEAD = `ca1b0d0`):

| Citation | Result | Evidence |
|---|---|---|
| J/api/controller/CmsController.java:79-123 | VERIFIED | `report(@RequestBody @Valid ReportReq)` :79-80 without `@PreAuthorize`; `onlyDelivered` read with no default :84; `report(null)` :101; `if(sendToCustomer) … REPORT` :104-105, `ADMIN_REPORT` always :106; `trace` sends `TRACKING_STATE` only when `CONSEGNATO` :120-121 |
| J/business/service/crud/SpedizioneService.java:152-154, 166-167, 194, 206-234 | VERIFIED | `requireNonNull` on `codice`/`importo`/`tracking` :152-154; `setImportoContrassegno(getImportoAssicurato())` :167; fallback when tracking ≠ PRESA and `importo` null :194; `findByEmail` :210, `ZoneOffset.UTC` and `plusDays(1)` :212-215, `REGISTERED` + `CONSEGNATO`-only list :216-223, `shippingTs` adjustments :224-229 |
| J/business/service/CustomerService.java:38-41 | VERIFIED | `sendAdminNotification(ADMIN_CUSTOMER_REQUEST, email)` at :39 precedes `soggettoService.emailConfirm(email, code)` at :40 |
| J/business/model/enumeration/PalletEnum.java:15-21 | VERIFIED | HP `150.0, 100.0, 120.0, 150.0` (weight 150); ULP/LP/FP height `150.0`; MQP/QP/ELP as the image |
| J/web/WebController.java:39-68; J/configuration/security/WebSecurityConfig.java:41-42 | VERIFIED | `downloadReport` and `report` have no security annotation; `from`/`to` required `String` on `/report` :57-59, `onlyDelivered` default `"false"` :42, 60; `"https://www.google.com/url?q=" + linkUtil` object :65; `anyRequest().permitAll()` :42 |

## 7. Planned tests

| ID | Layer · what it asserts · tag |
|---|---|
| EML-01…09 | integration, one per e-mail: outbox row written in the business transaction (rollback test: SES fake failing never loses the order); recipient from the record (never from a request field); subject with the legacy wording and the Q-37 brand; Reply-To; text and HTML parts; amounts rendered `1.234,50 €`; enum labels not names; links URL-encoded (`+`, `&` in the address); EML-05 approval contains code, consignee, lines, amount, tracking link, and decline contains the code; EML-06 once per verification; EML-09 once per CONSEGNATO transition; snapshot test of every template rendered with synthetic data. `@Tag("EML-0x")` |
| EML-07, ADM-09 | integration: customer A's report has no order of B; selection by approval/creation rule, `to` inclusive, Europe/Rome boundary (order at 00:30 Rome on `to`+1 excluded), only-delivered filter; `from > to` → 422; recipient neither the customer nor on the allow-list → refused and audited; office always; download without the right session → 401/403, customer session of B → 404; `report_run` + audit row (user, recipient, range, filter, row count); PDF parsed: A4 landscape, 13 headers verbatim, customer block, file name pattern, header repeated on page 2, no partner annex text; an order without amount renders `-` instead of failing. `@Tag("ADM-09")`, `@Tag("EML-07")` |
| ADM-04 | unit + E2E: `importo` required, `0`/negative refused, `180,50` accepted and stored `180.50`; duplicate code → 409 in Italian; approval → REGISTERED/PRESA + EML-05; decline → DECLINED, code kept, EML-05 decline with code. `@Tag("ADM-04")` |
| ADM-02, CUS-01, CUS-02, CUS-03 | integration/E2E: unverified customer cannot be approved; GET on a link never changes state or sends EML-06; re-registration of NEW/PENDING/ACTIVE/DECLINED e-mails answers the same page; decline → EML-03 decline text, customer sees status. tags per ID |
| ADM-06, EML-09 | unit: transition to CONSEGNATO queues EML-09, other states do not; event stored with user/time. `@Tag("ADM-06")` |
| CUS-04, CUS-05 | unit: totals per 09 (no legacy whole-m³ rounding); E2E: order detail shows the amount only after approval. tags per ID |
| API-03, PUB-04 | contract: `GET /mono/email-confirm?email=…&verificationCode=…`, `/mono/report?…`, `/mono/download-report?…`, `/mono/request-success.html` → Italian page, no state change, no PDF, on apex and `app.`; logo → 200 image; `ordini.` → sign-in, `dashboard.` → `/gestione/`, `www…/#tracking` → tracking. tags per ID |
| MIG-02 | unit on synthetic dump rows: amount = `importo` else `importo_assicurato`, DQ-13 flag when both differ; declined orders (NEW/NON_GESTITO/TEMP) → DECLINED; reconciliation sums the chosen amount. `@Tag("MIG-02")` |
| MIG-03 | config/rehearsal: legacy nginx block list covers EP-18, `/mono/sendEmail`, `GET /mono/email-confirm` and the two report links; DNS list includes `ordini.` and `dashboard.`; rotation checklist names the SES key. `@Tag("MIG-03")` |
| NFR-02, NFR-03, NFR-04 | integration: no PDF or e-mail body in logs (log capture); `/mono/utils/**`, `/mono/sendEmail`, `/mono/cms/order/update` → 410 and nothing written; staging profile refuses to start without mail catcher or allow-list; a bounced address is marked and shown to staff; outbox retry with backoff and dead state. tags per ID |
| ENH-01…04 (M2) | unit: `PalletEnum` values are not the class limits (image values win, PR-02); flag off → no price anywhere. tags per ID |

## 8. Notes for the planner

* **Ownership (10 §2).** `notifications`: EML-01…09 templates (text + HTML), outbox, worker, SES sender, staging guard, bounce handling, office address setting read from `platform` config. `reports`: ADM-09 service, selection rules, PDF generator, `report_run`, allow-list, authenticated download, EML-07 payload. `orders`: ADM-04 approval contract (amount, code), EML-05 data; `customers`: EML-01/02/03/06 triggers; `tracking`: EML-09 trigger from ADM-06; `backoffice`: report form (customer picker instead of a typed e-mail, date range, only-delivered, send-to-customer, extra recipient from the allow-list) and the allow-list screen under `/gestione/impostazioni/`; `site`/`platform`: `/mono/**` pages and PUB-04 host redirects; `migration`: DQ-13 and the old-link inventory; `pricing` (M2): nothing ported from §4.
* **Phase 1 foundations needed.** `email_outbox` table + scheduled worker + mail catcher + staging guard; a template harness that renders every template with synthetic data in CI; money type `numeric(10,2)` with VAT flag and an Italian formatter; validated settings for the sender address and the office mailbox (fail fast); `report_run` and audit action `report.send`; file storage for generated reports (S3 lifecycle 30 days per 10 §2, or on-demand generation with no storage — decide); a maintained PDF library (iText 2.1.7 is unmaintained and not under the current AGPL iText: use OpenPDF or PDFBox; record in the stack ADR); the `/mono/**` catch-all on both hosts.
* **Risks.** (1) Report semantics on migrated data: Dt/Cons. is `shippingTs` (first exit from PRESA), volumes are whole m³, amounts may be in `importo_assicurato`: define the PDF parity test on synthetic data and show the owner a sample at Gate 1. (2) Twelve e-mails to review (ten legacy, EML-02/04 new) plus the brand decision Q-37 that changes every subject. (3) `EMAIL_ORIGIN` unknown: old links may reach the apex, which must never confirm or serve data. (4) The PDF's fixed header holds a partner account code and the company address: treat as confidential, never in fixtures. (5) Q-21/Q-53 are outside the build but must be in the first owner message. (6) If the live CMS still sends `importoAssicurato` (Q-47), recent legacy rows have `importo` null: the DQ-13 fallback matters more than expected.
* **ADR 0001 / Gate 0 decisions.** Confirm EML-09 in M1 and EML-02/EML-04 added (Q-07); PDF kept, XLSX later (Q-08, ENH-08); recipient allow-list default = office address; report period in Europe/Rome, `from ≤ to`, rows by approval date, Dt/Cons. = CONSEGNATO event time else last event (a change from legacy, say so); one "Importo" row in e-mails instead of the three legacy amounts; `importo` meaning, VAT flag and "0 refused" (Q-05); DQ-13 rule with the 07-18 date; brand name (Q-37); office mailbox and its processor status (Q-43); the partner annex header dropped; the e-mail copy review scheduled (Gate 0 text, Gate 1 rendered); report file retention (Q-18).
