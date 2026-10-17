# Phase 0 reader — chapter 04 · Legacy CMS (`ls-cms@dev`)

Read in full (1,068 lines) on 2026-10-16. Precedence applied: legacy code > chapter 12 > chapter 04; chapter 09 and the ADRs decide the target. `legacy/ls-cms` HEAD verified as `f0c881c` (branch `dev`).

## 1. Scope

Chapter 04 documents the office back office as the repository holds it: boot, login and session (§1), navigation and the five tabs (§2), the four data tables with columns and formats (§3), the nine dialogs with their exact request bodies (§4), the two enumerations (§5), the 16 REST calls and the objects as the CMS reads them (§6), the copy deck (§7), 29 quirks CMS-01…29 with a Replicate/Fix/Ask verdict (§8), the rules to keep and the gaps (§9) and the unknowns (§10). Its banner states that this build is **not** the production CMS (Q-47): screens, workflow and copy come from here, the rules from chapter 12.
Reading map: **back office** §2–§5, §7–§9 · **customers + sign-in** §4.2–4.3 · **orders** §4.4–4.5 · **notifications + reports** §4.9 · **migration** §6.2 · Phase 1: nothing directly, but §1.5 and CMS-01…05 shape the staff session and the deny-by-default configuration.

## 2. Requirement IDs touched

| ID | Where chapter 04 specifies or constrains it |
|---|---|
| ADM-01 | §1.5 (login body, token in page memory, no logout, no 401 handling), §4.1, CMS-01…03, §9.2 (no roles or user management); 09: "fix: CMS-01…03" |
| ADM-02 | §3.2 (nine columns, "SI"/"NO", "Nessun utente da approvare"), §4.2–4.3 (approve/decline by e-mail), CMS-19, CMS-20, §9.1 rule 1 |
| ADM-03 | §3.3 (same columns, reversed, no actions, "Nessun utente registrato"), §9.2 (no customer page) |
| ADM-04 | §3.4 (13 columns, TEMP → "-"), §4.4 (code + amount, `tracking: 1`), §4.5 (decline with e-mail), CMS-12…14, CMS-21, §9.1 rules 2–4 and 11 |
| ADM-05 | §3.5 (raw code, reversed, three row actions), CMS-26, §9.2 (no search, filter, pagination) |
| ADM-06 | §4.6 ("Imposta tracking": editable code, six hard-coded states, any transition), CMS-08, CMS-10, §9.1 rule 6 |
| ADM-07 | §4.7 ("Aggiorna note": one note, `<pre>` + single-line field, "Nessuna nota presente"), CMS-09, CMS-11, §9.1 rule 7 |
| ADM-08 | §4.8 ("Dettaglio": `spedizione` / `dettaglioColli` / `palletList`, raw keys), CMS-16, §9.1 rule 8 |
| ADM-09 | §4.9 (e-mail + `from`/`to`, UTC today, `min=2024-01-01`), CMS-06, CMS-22, §9.1 rule 9 |
| ADM-10 | §5.1 (six tracking states, ids 0–5, verbatim), §5.2 (services fetched, never used), CMS-10 |
| API-02 | §5, §6.1 rows 15–16 (unauthenticated GETs sending `Content-Type`; shape `{id, name, label}`) |
| API-03 | §6.1 (the 14 auth/CMS calls retired at cut-over; the enumerations stay) |
| CUS-02, CUS-03 | §4.2 (approval before verification, CMS-20 → Q-28), §6.2 (statuses NEW/PENDING/REGISTERED) |
| CUS-04 | §3.4 (temporary code, DDT, accessory service), §4.8 (lines, `type` 0/1), cross-references OP-07, OP-14, OP-15 |
| EML-03, EML-05, EML-07, EML-09 | §4.2–4.5 (which actions notify, Q-07), §4.9 (report e-mail), banner (e-mail on CONSEGNATO) |
| MIG-02 | CMS-11 (literal "Nessuna nota presente" in data), CMS-13 (`importo` vs `importoAssicurato`), §6.2 (objects, TEMP codes), §10 (timestamps) |
| NFR-01 | CMS-23, CMS-24, CMS-26 (accessibility, 14 columns on a phone) |
| NFR-02, NFR-03 | CMS-01…05, CMS-25, CMS-27, CMS-28 (XSS, open reads, HTTP host, Google Fonts); §9.2 (no audit trail) |
| PUB-04 | §1.4 (CMS host unknown; `dashboard.` → `/gestione/`) |
| TRK-01, TRK-02 | §5.1 last bullet (Q-03), §3.4 (TEMP codes) |
| ENH-02, 05, 08, 09, 12, 13, 14, 15 | §9.2 gaps: suggested amount, history, counters/export, 2FA, attachments, audit viewer, service management, bulk tracking |

## 3. Rules to keep (M1 parity)

| Rule | Citation | ID |
|---|---|---|
| Customers registered through the portal wait in a pending list; staff approve or decline them | ls-cms@dev:js/function/component-handler.js:339-341, 374-376 | ADM-02 |
| Customer columns: ID, Ragione sociale, Email, Indirizzo, Provincia, Città, Cap, Telefono, Email confermata ("SI"/"NO"); registered list newest first | ls-cms@dev:js/components/types/data-tables-component.js:57-67, 71-81 | ADM-02, ADM-03 |
| Pending orders carry a temporary code shown as "-"; the detail still uses the real code | ls-cms@dev:js/components/types/data-tables-component.js:242, 298 | ADM-04 |
| Order columns: ID, Codice, Cliente mittente, Codice Doc. Trasporto, Destinatario, Indirizzo, Città (Prov), Cap, Telefono, Importo, Ser. Accessorio, Stato, Tracking; "-" for a missing DDT or amount; registered list newest first | ls-cms@dev:js/components/types/data-tables-component.js:149-165, 240-254 | ADM-04, ADM-05 |
| Approval = staff type the definitive shipment code and the amount "Importo Totale (per cliente)", both mandatory; the order goes to tracking 1 = PRESA | ls-cms@dev:js/function/component-handler.js:397-426; ls-cms@dev:js/components/types/dialog-components.js:113-116; ls-cms@dev:js/components/data/user-data.js:28-32 | ADM-04 |
| Declining an order notifies the customer; declining a customer is a plain confirmation | ls-cms@dev:js/function/component-handler.js:465-468, 374-376 | ADM-02, ADM-04, EML-03/05 |
| The shipment code is the key of tracking updates, notes and detail (and of public tracking) | ls-cms@dev:js/function/component-handler.js:266, 305; ls-cms@dev:js/rest/rest-caller.js:99-102; ls-cms@dev:js/rest/dto/order-request.js:3-8 | ADM-06/07/08, TRK-01 |
| Six tracking states, ids 0–5, names `NON_GESTITO`…`CONSEGNATO`, labels "Non gestito"…"Consegnato", in this order | ls-cms@dev:js/components/data/user-data.js:22-53 | ADM-10, API-02 |
| Staff may set any tracking state after any other, backwards and `NON_GESTITO` included, on registered orders | ls-cms@dev:js/components/types/dialog-components.js:301-363; ls-cms@dev:js/function/component-handler.js:507-534 | ADM-06 |
| One free-text note per registered order, read and replaced by staff | ls-cms@dev:js/function/component-handler.js:265-308 | ADM-07 |
| Order detail in three parts: "Dettaglio Spedizione", "Dettaglio Colli", "Pallets" | ls-cms@dev:js/function/component-handler.js:80-129; ls-cms@dev:js/components/types/dialog-components.js:397-409 | ADM-08 |
| Report asked for an e-mail and a `from`/`to` range (`YYYY-MM-DD`), delivered by e-mail; success "Report inviato correttamente" | ls-cms@dev:js/components/types/form-components.js:53-92; ls-cms@dev:js/function/component-handler.js:213-238 | ADM-09, EML-07 |
| Enumerations read with unauthenticated GETs that carry `Content-Type: application/json` (preflight) | ls-cms@dev:js/rest/rest-caller.js:119-133 | API-02 |
| Copy to reuse: tabs "In Approvazione" / "Registrati" / "Report"; titles "Approva ordine", "Imposta tracking", "Aggiorna note", "Dettaglio", "Sei sicuro di voler procedere?"; buttons "Conferma" / "Annulla" / "Chiudi" / "Imposta" / "Imposta stato" / "Invia Report"; empty states "Nessun utente da approvare", "Nessun utente registrato", "Nessun ordine da approvare", "Nessun ordine registrato" | ls-cms@dev:js/constant/language-messages.js:22-89; §7.2 | ADM-02…09 (PROMPT rule 3) |
| Staff log in with username and password | ls-cms@dev:js/rest/rest-caller.js:7-16; ls-cms@dev:js/rest/dto/login-request.js:3-8 | ADM-01 |

## 4. Deliberate fixes (each needs an ADR)

| Fix (legacy → new) | Source | Proposed ADR title |
|---|---|---|
| Visible password, credentials kept in page memory, bearer token without expiry handling or logout → server session cookie, logout, idle/absolute timeout, lock-out; roles ADMIN/OPERATOR (legacy: ADMIN/CUSTOMER, `policy` unused) | CMS-01…03; 12a §2.2–2.5; 09 ADM-01 | "Staff authentication and sessions of the back office" |
| Stored XSS in tables, detail and notes → auto-escaping templates, strict CSP, hostile-value tests | CMS-04; Q-22; 10 §4 | "Output escaping and CSP in the customer area and back office" |
| Staff reads partly open (`order/pending`, `cms/order/report`) → staff session on every `/gestione/**` route, deny by default, 401/403 instead of 500 | CMS-05; 12a §2.7–2.8 | "Authorisation: deny by default" (shared with the 02 reader) |
| Actions keyed by e-mail and positional row ids, duplicated requests, CSS-only disabled buttons → actions by internal id, one request per click, idempotent server actions, real `disabled` | CMS-06, CMS-07, CMS-25; 12b §3.1 (duplicate e-mails break lookups) | "Back-office actions keyed by id and idempotent" |
| Amount typed as free text under `importoAssicurato` → one decimal EUR field `amount` > 0, Italian comma accepted, server-validated; code trimmed, unique ignoring case; "pending" a state, not a code pattern | CMS-13, CMS-14, CMS-21; 12a §1.4 C6; 12b §3.3, §3.5; Q-05, Q-06 | "Order approval contract: code and amount" |
| UI lets staff approve unverified customers → refused (the backend already refuses) | CMS-20; 12b §3.1; Q-28 | "Customer approval requires a verified e-mail" |
| Staff note overwrites the customer's note; literal "Nessuna nota presente" saved; line breaks lost → separate `order_internal_note` (textarea, empty when none, breaks kept); importer maps the literal to empty | CMS-11; 12b §3.6; Q-26; 08 §2.3 | "Internal notes separate from the customer note" |
| Tracking overwritten without history, dialog with editable code and stale menu → page `/gestione/tracking/` plus row action, current state preselected, every change a `tracking_event` with user and time, confirmation on backward moves | CMS-08, CMS-10, CMS-12; 12b §3.4; 08 §2.4; 10 §2 URL map | "Tracking history as events" |
| Detail prints raw keys and one entry per physical unit → labelled sections, units grouped back into lines | CMS-16; 12a §1.4 F1; 12b §4.3 | "Order detail presentation" |
| Report to any address through public never-expiring links, UTC day boundaries, no range check → customer filter; office always, customer if chosen, allow-list; authenticated download; `from ≤ to`; Europe/Rome days; audited | CMS-06, CMS-22; 12c §3; Q-08; 09 ADM-09 | "Report delivery through authenticated downloads" + "Report period in Europe/Rome days" |
| Decline e-mail sent to the address in the request, quoting the numeric id → to the order's customer from the database, quoting the order code | 12a §1.4 C7; 09 EML-05 | "Decline notifications" |
| Full lists only; registered orders limited by the backend to the last 6 months → server-side pagination, filters, search, no time window | CMS-26; 12a §1.4 C2; 09 ADM-03/05 | "Back-office lists: pagination and filters" |
| Generic messages, return to the first tab, context-free confirmations, icon-only buttons, stray commas and "null" → specific messages, tab kept, named confirmations, labelled buttons | CMS-17, CMS-18, CMS-19, CMS-23, CMS-24 | "Back-office UX conventions" (one ADR for the visible changes) |
| Hard-coded plain-HTTP UAT host, Google Fonts, unused jQuery/Bootstrap JS, dead portal code → same origin, self-hosted assets, nothing ported | CMS-27…29 | fold into the NFR-02 baseline ADR |
| Backend admin tools (hard deletes, free e-mail, `cms/order/update`) not ported; corrections after approval, if wanted, ADMIN-only with audit | 09 ADM note; 12c §6; 08 §2.4 | "Legacy admin tools not ported" |

## 5. Unknowns by ID (default the brief gives)

* **Q-05** amount = price charged, VAT excluded, decimal > 0 with a VAT flag · **Q-06** staff type the code; uniqueness trimmed and case-insensitive; nothing proposed · **Q-07** reuse the legacy e-mail content, add EML-02/EML-04, owner reviews the copy · **Q-08** PDF through an authenticated download; office always, the customer if chosen, allow-list, audited · **Q-09** explicit `DECLINED`, re-registration allowed · **Q-16** CMS hosting unknown; DNS by the owner · **Q-17** roles ADMIN/OPERATOR, 2FA in M2 (ENH-09); staff accounts created fresh (08 §3.9) · **Q-26** separate internal notes · **Q-28** only verified customers approved · **Q-47** production CMS source unknown: design from 04 (screens, copy) and 12 (rules); assume `dashboard.` calls `app.` · **Q-03** `status` is the label (answered, 12a §1.4 D1) · **Q-21** `order/pending` and `cms/order/report` open today (urgent, owner) · **Q-22** XSS from portal data into the office browser (urgent, owner).
* **CMS-01…CMS-29**: all **Fix**, except CMS-10 (replicate the six states; fix: show labels) and the **Ask** items CMS-05 (→ Q-21, answered), CMS-13 (→ Q-05), CMS-14 (→ Q-06), CMS-20 (→ Q-28). ADR mapping in §4.
* **§10 unknowns settled by chapter 12**: raw columns (`stato` = state name, `tracking` and `servizioAccessorio` = labels; 12a §1.3, F1); detail keys (four, including `mittente`; 12a F1); notes = the customer's note (12b §3.6); report content, format, `email` meaning, `to` inclusive (12c §3.7); token = HS256 JWT, 6 h, a refresh endpoint exists, roles ADMIN/CUSTOMER (12a §2.2–2.5); trace answer = updated `Spedizione`, 404 unknown code, 500 when not REGISTERED (12a D2); lists without `ORDER BY`, ascending id inferred (12a B3, C2); shipping/delivery flags forced false at approval, timestamps per 12b §3.4; accessory services = six items (12a H1). Still open: number and names of staff accounts (Q-17); CMS hosting and `HOST` (Q-16, Q-47).
* **OP-07** "Numero Colli" never sent (store it, Q-24) · **OP-14** recipient e-mail = the customer's (importer drops it; CUS-04 adds a real optional one) · **OP-15** nested `palletList` (flat lines in the new model).

## 6. Contradictions

1. **§9.1 rule 1 and CMS-20** ("approval does not depend on" e-mail confirmation; staff can approve unverified customers) vs the chapter's own banner, 12a §1.4 B5 and 12b §3.1: EP-06 answers 500 unless the customer is PENDING (verified), and EP-04 lists only PENDING rows, so "Email confermata" is always "SI". 12 wins; 09 ADM-02 and Q-28 already follow it.
2. **§4.6 and §9.1 rule 6** ("the endpoint takes any typed code", "any state at any time") vs 12a §1.4 D2 (SpedizioneService.java:236-239): 404 for an unknown code, **500 when the order is not REGISTERED**. Tracking is limited to approved orders; 08 §2.4 agrees; the 09 ADM-06 wording "any order" must be read as "any sequence of states, on approved orders only".
3. **§4.8** (three top-level keys; pallet `type` `"0"`/`"1"`) vs 12a §1.4 F1: four keys (`spedizione`, `mittente`, `dettaglioColli`, `palletList`), `type` as `"PALLET"`/`"EPAL"`, one entry per physical unit, not per line (12b §4.3). 12 wins.
4. **§3.4 "Cliente mittente" = `email`, sender or recipient unknown** vs 12a §1.4 C1: the order's `email` column stores `destinatario.email`, which the portal fills with the customer's own address (OP-14). The new lists show the customer through the foreign key; the importer drops the duplicate.
5. **CMS-26 / §9.2 "the registered-orders list grows without limit"** vs 12a §1.4 C2 (SpedizioneRepository.java:39-45): EP-09/EP-10 return only orders created in the last 6 months. Older orders are invisible today; ADM-05 shows them all with pagination (deliberate, ADR in §4).
6. **§4.9 "`POST /mono/cms/order/report`, Bearer" and §1.5 "whether reads need the token is unknown"** vs 12a §2.7–2.8: the report endpoint and `order/pending` are open; customer lists, registered orders, detail and notes answer 500 without an ADMIN token. Answered (Q-21, urgent).
7. **§4.7 "Nessuna nota presente" written by the CMS** vs 12a §1.4 E1 (OrderService.java:172-174): the **server** already answers that literal for a null note and for an unknown code. Both layers substitute it; the importer rule of CMS-11 stands.
8. **§4.4** `importoAssicurato` as text, "180,50" accepted vs 12a §1.4 C6 and 12c §4.2: the backend requires `importo` as a JSON number > 0 (400 for 0, 500 for "180,50"); the banner says so. §4.6 "`tracking` as a string" is harmless (coerced, 12a D2).
9. **§1.5 "no refresh endpoint exists in the code"** (true of the CMS) vs 12a §0 point 3: `POST /mono/auth/refresh-token` exists in the backend, never called by the CMS. API-03 must retire it too.
10. **08 §2.5 "the app proposes the next code when the imported codes follow a recognisable sequence"** vs 11 Q-06 default "proposes nothing until you say how codes are made" and 12b §3.3 (mostly 12-digit, probably external numbers). Chapter 11 is the master list: propose nothing.
11. **Internal**: §9.1 rule 9 states "from 2024-01-01" as a rule, but it is only the date picker's `min` (form-components.js:68, 74); the backend has no floor (12c §3.1). Do not port it. **PROMPT.md**: no contradiction; its approval bullet cites ls-cms@dev:js/function/component-handler.js:420-434, consistent with §4.4.

**Spot check of legacy citations** (`legacy/ls-cms` at `f0c881c`):
* ls-cms@dev:js/components/data/user-data.js:22-53 — **VERIFIED**: `trackingStateEnum` with ids 0–5, names `NON_GESTITO`…`CONSEGNATO`, labels "Non gestito"…"Consegnato".
* ls-cms@dev:js/function/component-handler.js:420-435 — **VERIFIED**: `req = {id: order['ID'], req: {codice, importoAssicurato, tracking: 1}}` with the five commented optional keys.
* ls-cms@dev:js/components/types/data-tables-component.js:242 — **VERIFIED**: `order.codice.includes("TEMP") ? "-" : order.codice`; line 244 maps a null DDT to "-".
* ls-cms@dev:js/rest/rest-caller.js:170-187 — **VERIFIED**: `doPostAuthRestCall` sends `Authorization: Bearer <token>` and throws `Error("Rest call failed")` when `!response.ok`.
* ls-cms@dev:js/components/types/form-components.js:68, 74 — **VERIFIED**: both date inputs default to `new Date().toISOString().split('T')[0]` (UTC) with `min="2024-01-01"` and `max` = the same UTC day.

## 7. Planned tests

| ID | Tests (layer · assertion · tag) |
|---|---|
| ADM-01 | unit: password policy (≥ 12 chars, common-password list) · integration: login/logout, lock-out after N failures, cookie flags (`__Host-`, HttpOnly, Secure, SameSite), idle/absolute timeout, `/gestione/**` without session → sign-in, OPERATOR on ADMIN routes → 403 · architecture: no migration inserts into `staff_user`, no seed user outside local/test · `@Tag("ADM-01")`, `[ADM-01]` |
| ADM-02 | integration: approve PENDING_APPROVAL → ACTIVE + EML-03 outbox row + audit; approve PENDING_VERIFICATION → refused, no e-mail; decline → DECLINED + EML-03 · E2E `[ADM-02]`: nine legacy columns, "SI"/"NO", "Nessun utente da approvare", confirmation names the customer, hostile company name rendered inert |
| ADM-03 | E2E `[ADM-03]`: newest first, search by name/e-mail, customer page lists its orders · integration: OPERATOR allowed, customer session refused |
| ADM-04 | unit: amount parser ("180,50" and "180.50" → 180.50; "0", "abc", "-1" refused), code normalisation (trim, case-insensitive uniqueness) · integration: approve → APPROVED, `tracking_event` PRESA, EML-05 queued, audit; duplicate code in any case → conflict; decline → DECLINED + EML-05 to the order's customer quoting the code; second approval refused; double submit → one approval · E2E `[ADM-04]`: 13 legacy columns, TEMP shown "-", "Approva ordine" form empty for each order |
| ADM-05 | E2E `[ADM-05]` with > 1 page of synthetic orders: pagination, filters (state, tracking, customer, dates), search by code, newest first, orders older than 6 months visible |
| ADM-06 | unit: any state → any state allowed, same state → no event · integration: each change inserts `tracking_event` (user, time), updates the order, audit; CONSEGNATO → EML-09 once, other states → none; pending order → refused · E2E `[ADM-06]`: current state preselected, backward move asks confirmation, labels shown |
| ADM-07 | integration: internal note stored apart, customer note unchanged, line breaks kept, before/after in `audit_log_detail`; empty note never stores "Nessuna nota presente" · E2E `[ADM-07]` |
| ADM-08 | E2E `[ADM-08]`: labelled sections shipment/packages/lines, units grouped into lines with quantity, labels for service/state/tracking, "-" for missing values · integration: detail requires a staff session |
| ADM-09 | unit: range validation (`from ≤ to`, not in the future, Europe/Rome day boundaries, `to` inclusive) · integration: report for customer A holds no order of B; `onlyDelivered` filter; address outside the allow-list refused; EML-07 to the office always, to the customer only if chosen; download needs staff or that customer's session (other customer → 403); audit row (user, recipient, range, filter, row count); PDF has the 13 legacy columns · E2E `[ADM-09]`: the typed address is used after other actions (CMS-06 regression), one request per click |
| ADM-10 | seed test `@Tag("ADM-10")`: exactly six tracking states and six services with the legacy ids, names, labels and order |
| API-02 | contract: both enumerations without auth, preflight with `Content-Type` answered, JSON equal to the chapter 02 §9 fixtures |
| API-03 | contract: the 14 auth/CMS calls of §6.1 plus `/mono/auth/refresh-token` answer 410 JSON and perform nothing |
| CUS-02, CUS-03 | customers module; back-office E2E adds: a pending customer cannot order after sign-in, approval unlocks ordering |
| CUS-04 | integration: the stored order (temp code, DDT, service id, note, lines) is what ADM-04/ADM-08 display |
| EML-03/05/07/09 | integration per e-mail: recipient from the database (never from a request field), subject, Reply-To, key content (code, amount), nothing sent outside production |
| MIG-02 | importer unit: note "Nessuna nota presente" → empty; `amount = COALESCE(importo, importo_assicurato)` rounded to 2 decimals; TEMP codes kept verbatim; `stato`/`tracking`/service labels mapped to ids; order `email` equal to the customer's dropped |
| NFR-01 | quality: axe on `/gestione/` pages (no serious/critical), accessible names on every button, phone-width layout |
| NFR-02, NFR-03 | security: XSS payloads in every customer/order field rendered inert, CSP on `/gestione/**`, CSRF token on every staff POST, audit rows carry ids and no personal values |

## 8. Notes for the planner

* **Ownership (10 §2).** `backoffice` owns the ADM screens, their E2E and the §7 copy; rules live in the domain modules: `customers` (ADM-02/03 approval and decline rules, Q-28, Q-09), `orders` (ADM-04 approval contract, codes, amounts; ADM-07 internal notes; ADM-08 detail DTO), `tracking` (ADM-06 events, ADM-10 seeds, API-02), `reports` (ADM-09 PDF, allow-list, authenticated download, EML-07), `notifications` (EML-03/05/09 templates), `auth` + `platform` (ADM-01 staff login, sessions, roles, audit log, rate limits), `migration` (CMS-11/13 rules).
* **Phase 1 foundations this chapter needs.** `staff_user` with roles and lock-out columns; staff login and logout finished; `/gestione/` layout fragments in the new design (the tabs and dialogs of §2 become pages and forms); deny-by-default security with one extension point for `/gestione/**`; audit log and outbox; seeds of both enumerations (ADM-10) in the first migrations; message-bundle keys for the §7 copy; CSRF on every staff form; tables `order_internal_note`, `tracking_event`, `audit_log_detail` (08 §2.3).
* **Risks.** (1) The production CMS is a later, unknown build (Q-47): the office may rely on views the repository lacks (`order/active`, `order/delivered`, corrections through `cms/order/update`, `sendToCustomer`/`onlyDelivered` on the report); ask the owner what the live CMS can do and run the Gate 1 UAT with the people who use it daily. (2) The 6-month window hides old orders today; showing them all changes what the office sees and sizes ADM-05. (3) Legacy data may hold HTML payloads (Q-22, DQ-19), the literal "Nessuna nota presente", leading-space codes and duplicate customer e-mails (DQ-01): the importer stores as text, normalises and reports them. (4) The legacy PDF prints a partner-network header (12c §3.4): confidential, not reproduced. (5) Q-05 (meaning and VAT of the amount) shapes the approval form, the e-mails and M2 pricing: coherent code on a wrong assumption is the main domain risk. (6) The OPERATOR permission set is a proposal (08 §2.3): confirm with Q-17.
* **For ADR 0001 / the Gate 0 summary.** Confirm Q-05, Q-06, Q-07, Q-09, Q-17, Q-26, Q-28, Q-47; fix the ADM-06 wording (any transition, approved orders only, confirmation on backward moves); decide whether corrections after approval exist in M1 (ADMIN-only, audited), given the `cms/order/update` calls in the legacy logs (12b §3.2 O6); record that the back office is redesigned (pages instead of dialogs, tracking as a page) while keeping the §7 copy; record "no polling or counters in M1; counters in ENH-08"; report period in Europe/Rome with `from ≤ to`; Q-21 and Q-22 as urgent owner actions on the live system.
