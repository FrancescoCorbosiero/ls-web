# Phase 0 reader — chapter 02 · Legacy API contract

Read in full (1,088 lines) on 2026-10-16 against README precedence (code > 12 > 02–08; 09 + ADRs decide the target).

## 1. Scope

Chapter 02 rebuilds the backend contract from its four clients: 20 endpoints EP-01…EP-20 (environments, prefixes, auth, CORS, error semantics, per-endpoint request/response), wire-type variants across builds (§8), the two enumerations (§9), the cut-over decision per endpoint with the contract fixtures FX-01…FX-14 (§10), and the unknowns CU-01…CU-24 with the read-only probes P-01…P-07 (§11). Every EP carries a "From the source" banner from 12a §4.1, which wins over the older body text.
Reading map: **tracking + compatibility** §5–§6, §7.4, §7.8, §9, §10, §11.2 · **orders** §8 · **notifications + reports** §7.7 · **migration** §7 (EP-04, EP-05, EP-09, EP-10, EP-15, EP-17), §8, §9, §11 · **Phase 1** nothing directly, but §4.3 and §10.2 shape the security and CORS configuration.

## 2. Requirement IDs touched

| ID | Where chapter 02 specifies or constrains it |
|---|---|
| API-01 | §7.4 EP-13, §10.1, §10.2 rules 1–7, §10.4 FX-01…FX-10 |
| API-02 | §7.8 EP-19/EP-20, §9.1–9.2, §10.2, FX-11, FX-12 |
| API-03 | §10.1 (17 retired paths), §10.3 (410 bodies, old `GET /mono/**` links, logo), FX-13, FX-14 |
| TRK-01, TRK-02 | §7.4 (label, `Non gestito`), §10.2 rules 1–3, FX-03, FX-04, FX-06, CU-02 |
| TRK-03, TRK-04 | §10.2 rules 4 and 7, FX-09, FX-10; §11 CU-23 (legacy has no limits) |
| ADM-01 | §4.3 last bullet (staff session on every back-office route, test per route), EP-01 (staff accounts not migrated) |
| ADM-02, ADM-04 | EP-06/EP-07 banners (approval needs PENDING; decline resets to NEW); EP-11 banner (`codice` unique, `importo` > 0, tracking → PRESA), EP-12 |
| ADM-06, ADM-07, ADM-08, ADM-09 | EP-14 (events replace overwrite), EP-15/16 (note = customer's note, CU-10), EP-17 (four keys, units per row), EP-18 (open, `onlyDelivered`, `to` inclusive) |
| ADM-10 | §9.1, §9.2 "Target: seed exactly these" |
| CUS-01, CUS-02, CUS-04 | EP-02 (uniform answer), EP-03 banner (phone required, limits 50/50/50/50/9/20/50), EP-08 (sender from session, lines, totals), §8 |
| EML-01, 03, 05, 06, 07, 09 | banners of EP-03, EP-06/07, EP-11/12, EP-08, EP-18, EP-14 (subjects and recipients); CU-06 |
| MIG-01, MIG-02, MIG-03 | §7 list endpoints and EP-17/EP-15 as the API-export sources, §8 (importer accepts every variant, DQ-07), §10.1 risk (block legacy writes), CU-21 |
| PUB-04 | §10.1 (`ordini.` host → `/area-clienti/`) |
| NFR-02, NFR-03 | §4.3 (model not to reproduce), §10.2 (CORS, rate limit); §11.2 (probe answers with personal data stay out of git and the session) |

## 3. Rules to keep (M1 parity)

| Rule | Citation | ID |
|---|---|---|
| `POST /mono/api/order/trace/state {"codice"}` → `200 {"status": "<label>"}`; label, never name or id | ls@migration:src/main/java/it/logisticasanchez/mono/api/controller/OrderController.java:33-38 | API-01, TRK-01 |
| Unknown code, pending order's TEMP code, declined order → `200 {"status":"Non gestito"}`; `{}` or `""` → `400` | 12a §1.4 D1 (SpedizioneService.java:116-119; OrderDetailReq.java:16-18) | API-01, TRK-02 |
| Legacy clients produce codes as typed (v1, ls-home@v1:js/custom.js:49, 69) or whitespace-stripped, 1–64 chars (ls-home@fe4b056:public/js/tracking.js:32, 204-206); a 2xx without JSON or without `status` breaks them | §6, §7.4 | API-01 |
| Serve EP-13 on the apex (same-origin widget) and on `app.logisticasanchez.com` (external callers, Netlify site until Q-20) | §10.1, §10.2 rule 6; ls-home@fe4b056:public/js/config.js:31 | API-01 |
| `statoTracking`: ids 0–5, names `NON_GESTITO…CONSEGNATO`, labels "Non gestito"…"Consegnato", in this order, ids as JSON numbers | ls-cms@dev:js/components/data/user-data.js:22-53; 12a H2 | API-02, ADM-10 |
| `servizioAccessorio`: six items ids 0–5, item 0 = "Nessun servizio accessorio" is a real item | ls@migration:src/main/java/it/logisticasanchez/mono/business/model/enumeration/ServizioAccessorioEnum.java:16-21 | API-02, ADM-10 |
| Enumeration `GET`s need no auth; the CMS sent `Content-Type` on them, so answer the preflight too | ls-cms@dev:js/rest/rest-caller.js:119-133 | API-02 |
| Any tracking transition is allowed, backwards included; e-mail to the customer on CONSEGNATO only | 12a §1.4 D2 (CmsController.java:111-123) | ADM-06, EML-09 |
| Approval needs a unique code and `importo` > 0 and forces tracking PRESA; a duplicate code is refused | 12a §1.4 C6 (SpedizioneService.java:152-173, as PROMPT.md cites) | ADM-04 |
| Only PENDING (verified) customers can be approved; a decline keeps the row | 12a §1.4 B5/B6 | ADM-02, Q-28 |
| Order sender must be a REGISTERED customer; lines carry type 0/1 ("Pallet Generico/Colli"/"Epal"), cm, kg per unit, quantity; `destinatario.email` was the customer's own e-mail | ls-order@dev:js/rest/dto/order-request.js:3-43 (:15); ls-order@dev:js/constant/costant.js:86-90 | CUS-04 |
| Registration fields and limits: `telefono` required, sizes 50/50/50/50/9/20/50 | 12a §1.4 B2 | CUS-02 |
| E-mail subjects of the legacy to reuse: "Conferma indirizzo email", "Conferma nuovo cliente", "Aggiornamento indirizzo email", "Conferma ordine N. {codice}", "Aggiornamento ordine rifiutato", "Consegna effettuata per ordine N. {codice}" (brand name per Q-37) | §7 banners; 12c §1 | EML-01/03/05/09 |
| Report: one customer's approved orders by creation date, `to` inclusive, optional only-delivered; office always receives it | 12a §1.4 G1 | ADM-09, EML-07 |
| Importer accepts every wire variant: numeric strings, `servizioAccessorio` `null`/`1`/`0`/`"3"`, line `type` `null`/`1`/`"0"`/`"1"`, `telefono` `""`/`null`, timestamps ISO or epoch seconds | §8 table; ls-order@dev:js/function/data-handler.js:23-26, 58-61, 85 | MIG-01, MIG-02 |

## 4. Deliberate fixes (each needs an ADR)

| Fix (legacy → new) | Source | Proposed ADR title |
|---|---|---|
| 17 endpoints retired: `410 Gone` JSON, never the old action; old `GET /mono/**` e-mail links → small Italian 410 page on apex and `app.`; logo served as a file (not copied from `legacy/ls`: use the new site's logo) | §10.1, §10.3; 09 API-03 | "Retired legacy endpoints answer 410" |
| CORS on EP-13: only `https://logisticasanchez.com`, `POST, OPTIONS`, `Content-Type`, no credentials, max-age (legacy: `ALLOWED_ORIGINS`, all methods, credentials true) | §10.2 rule 5, FX-07/08; 12a §2.10, SEC-18 | "CORS policy of the compatibility endpoint" |
| Per-IP rate limit with 429 (legacy: none, CU-23); legacy clients render 429 as "not found" (06 §5.7) | §10.2 rule 7, FX-09 | "Rate limiting of public tracking" |
| Tracking answer: only non-personal keys, extra keys may be added, never removed | §10.2 rule 4, FX-10; 09 TRK-03 | "Public tracking response schema" |
| Code matching in the app: trimmed, inner spaces removed, case-insensitive (legacy: SQL equality under an unknown collation; leading spaces never match) | §10.2 rule 1, FX-04; 12b §3.7 | "Shipment code normalisation and matching" |
| Deny by default, staff session on every back-office route, 401/403 instead of 500 (legacy: permit-all, dead URL rule, EP-09/EP-18 open) | §4.3; 12a §2.7–2.9, SEC-01…05, SEC-09 | "Authorisation: deny by default and explicit error mapping" |
| Customer identity from the session, never from `clienteEmail`; uniform answer to `customer/state` (OP-S1, OP-S2) | EP-02, EP-08 "Compatibility" | "Customer identity from the session" |
| Tracking changes recorded as `tracking_event` with user and time (legacy: overwrite, two timestamps) | EP-14 | "Tracking history as events" |
| Internal staff notes separate from the customer's order note; the literal "Nessuna nota presente" never stored | EP-15/16, CU-10; Q-26 | "Internal notes separate from the customer note" |
| Report e-mails carry authenticated download links; recipients from an allow-list; audited (legacy: public never-expiring links, open EP-18) | EP-18, §10.3; Q-08 | "Report delivery through authenticated downloads" |
| Approval amount: decimal > 0, Italian comma accepted at the UI, stored as number (legacy CMS sent free text, backend required a number) | EP-11, §8 "amount" | fold into "Order approval contract" (ADM-04) |
| Staff accounts not migrated; new roles ADMIN/OPERATOR, no permanent refresh token (SEC-08) | EP-01; 08 §3.9 | "Staff accounts and sessions of the new back office" |

## 5. Unknowns by ID (default the brief gives)

* **Q-03** keep label + `200 Non gestito` + 400 on empty; match trimmed, case-insensitive · **Q-38** TEMP codes → `Non gestito` · **Q-39** only the apex origin until P-03 · **Q-20** retire the Netlify site once DNS moves · **Q-50** answer old `/mono/*` links on apex and `app.` · **Q-30** old links → page to `/area-clienti/`, never confirm or serve data · **Q-27** P-01/P-02/P-03 by agents after go-ahead, P-04…P-07 owner only · **Q-21** urgent nginx block on the legacy VPS (EP-09, `order/active`, `order/delivered`, EP-18, report links) · **Q-47** assume `ordini.`/`dashboard.` call only `app.` · **Q-46** inventory AWS-era hosts (UAT EB host is still the committed `HOST`) · **Q-16** DNS by the owner.
* **Q-05** `importo` = price charged, VAT excluded, > 0 · **Q-06** staff type the code, uniqueness trimmed/case-insensitive · **Q-07** reuse the legacy e-mail content, add EML-02/04 · **Q-08** PDF via authenticated download, allow-list · **Q-09** explicit `DECLINED`, re-registration allowed · **Q-17** two roles, 2FA in M2 · **Q-25** answered: seed the six services · **Q-26** separate internal notes · **Q-28** only verified customers approved · **Q-36** phone required.
* **CU-01…CU-24**: answered from the source (12a §4.2, 12b §5.8) except **CU-02/CU-03/CU-17** (production collation → default normalise in the app, verify on the dump), **CU-13** (staff count, Q-17), **CU-14** (live `ALLOWED_ORIGINS` → P-03), **CU-21** (live frontend builds/hosts → nginx block at the freeze, Q-47). **CU-15** inferred ascending id (no `ORDER BY`).
* **P-01…P-07**: P-01 (enumerations), P-02 (tracking answers with owner-given codes), P-03 (CORS) may go to `docs/legacy-captures/`; P-04 status codes only (read 500 as "protected"); P-05/P-06/P-07 answers hold personal data: outside git and the session; never probe EP-14, EP-18, `/mono/report`, `/mono/download-report`.
* **OP-S1/S2** (fixed by CUS-01/04), **OP-S3, CMS-27, LPS-S3** (plain-HTTP UAT host: never reproduce), **OP-S5** (unescaped label: escape on output), **OP-02/CMS-07/CMS-25** (repeated clicks → duplicates: double-submit protection, DQ in importer), **OP-04** (`type` 1 ambiguous), **OP-07** ("Numero Colli" never sent: store it, Q-24), **OP-11** (e-mail as typed: importer normalises), **OP-14** (recipient e-mail = customer's: importer drops it, CUS-04 adds a real optional one), **OP-15** (nested `palletList`: flat lines), **OP-U7** (DDT required, Q-24), **CMS-03/17** (all errors look alike: explicit error mapping), **CMS-10/11/12/13/20/21/22** (CMS quirks: not reproduced), **LPS-03** (`status` must never be null), **LPS-U2/U4/U9** (live site code, S3 tracking app, extra origins: P-03/P-06), **LPS-S4** (`ordini.` redirect), **DQ-07** (May-2024 "none" stored as "Prenotazione telefonica": importer flags, keeps the stored label), **SEC-01…05, 09, 12, 18** (fixes in §4 above), **F-01** (`@PreAuthorize` fix on the legacy, owner's action).

## 6. Contradictions

* 12a §4.4 point 5 and 12b §3.7 still say chapter 02's target for FX-03 is **404** ("a deliberate change"); 02 §10.2 rule 3, §10.4 FX-03/FX-06, 09 API-01 and 11 Q-03/Q-38 all say `200 {"status":"Non gestito"}`. The chapter-12 notes are stale (written before 02 was corrected); the target is 200. The ADR must say so explicitly, or a later reader will reintroduce 404.
* 02 §9.3 and CU-19 call a pallet-type enumeration "(unknown)"; 12a §1.4 H3 and 12b §2.3 show `GET /mono/api/enumeration/tipoPallet` (`PALLET`/`EPAL`, ids 0/1) and `statoConferma`. 12 wins. Neither is in 02 §10.1, so under API-03 they answer 410 unless the ADR keeps them (cheap; same rationale as EP-19/20).
* Internal: the body text under EP-03, EP-06/07, EP-08, EP-15 and EP-17 keeps pre-source wording ("creates a customer waiting for approval", CU-05/CU-10/CU-09 "(unknown)"); the "From the source" banners win (EP-03 creates NEW; EP-08 refuses non-REGISTERED; the note is the customer's; EP-17 has four keys).
* 02 §5.2 says `ordini.logisticasanchez.com` "must be allowed" for the portal; 12a §2.10 finds it in no committed origin list. Either the server's `ALLOWED_ORIGINS` differs from git or the portal is same-host today: P-03/Q-47 settle it; no impact on the target (API-03 retires the portal).
* 02 §10.2 rule 1 accepts padded codes (FX-04 → same as FX-01); 12b §3.7: leading spaces never match in the legacy. A deliberate leniency, not parity: record it in the matching ADR (TRK-02).
* 02 §7.1 EP-01 says roles `ADMIN` and `CUSTOMER`; 09 ADM-01 says `ADMIN` and `OPERATOR`: intended (new model), not a conflict.
* PROMPT.md "Legacy at a glance" agrees with 02 on every point checked (EP-13 answers, EP-08 body, EP-11 `importo` body, open endpoints).

Spot check of citations in `legacy/` (all five opened at the cited commits):

| Citation | Result | Evidence |
|---|---|---|
| ls@migration:…/api/controller/OrderController.java:33-38 | VERIFIED | `trackingState` builds `StatusRepr` with `.status(state.getLabel())` at :37 |
| ls@migration:…/enumeration/ServizioAccessorioEnum.java:16-21 | VERIFIED | six constants `NESSUN_SERVIZIO("Nessun servizio accessorio")` … `GDO("GDO")` at :16-21 |
| ls-cms@dev:js/function/component-handler.js:420-434 | VERIFIED | `req: { codice, importoAssicurato: importoAssicuratoTxt, tracking: 1 }` at :423-425; optional keys commented at :428-432 |
| ls-order@dev:js/rest/dto/order-request.js:3-43 (:15) | VERIFIED | `"email": userData.email` at :15 inside `destinatario`; `altezza…quantity` at :32-36 |
| ls-home@fe4b056:public/js/tracking.js:32, 168-184, 204-206 | VERIFIED | `CODE_RE` (1–64 chars, no whitespace, `<`, `>`, quotes or backticks) at :32; `fetchShipment` :168-184 with the wrong comment at :178; whitespace strip at :204. The file gained 7 lines in 9b78785 (ff58d4f), as chapter 06 says |

## 7. Planned tests

| ID | Layer · what it asserts · tag |
|---|---|
| API-01 | contract: FX-01…FX-06 on both hosts (`Host` apex and `app.`), JSON content type, `status` non-empty string, 400 for `{}`/`""`/missing body; FX-07/FX-08 preflight headers; FX-10 keys. `@Tag("API-01")` |
| API-02 | contract: FX-11/FX-12 byte-equal arrays (order, numeric ids, three keys), no auth, preflight answered; decision test for `tipoPallet`/`statoConferma` (410 or kept). `@Tag("API-02")` |
| API-03 | contract, parametrised over the 17 retired paths plus the 16 backend-only paths of 12a §4.3: 410 JSON, no DB row written (FX-13), no CORS headers; `GET /mono/email-confirm`, `/mono/report`, `/mono/download-report` → 410 HTML page with the `/area-clienti/` link and no state change; `/mono/img/logistica-sanchez-logo.jpg` → 200 image; on both hosts. `@Tag("API-03")` |
| TRK-01 | E2E `[TRK-01]`: `/tracking/?code=` for each of the six states, unknown code → neutral message, 429 rendering decision |
| TRK-02 | unit: normalisation (trim, inner spaces, case), TEMP code / declined order / null state → `Non gestito`; integration on PostgreSQL with codes differing only by case. `@Tag("TRK-02")` |
| TRK-03 | integration: response schema allow-list; a recipient name, phone, note or document link never appears. `@Tag("TRK-03")` |
| TRK-04 | integration: N+1 lookups → 429; limit keyed on the one trusted `X-Forwarded-For` hop, never the socket address or a client header. `@Tag("TRK-04")` |
| ADM-10 | integration: seed equals §9.1/§9.2 exactly; item 0 `is_none`; `legacy_json` round-trips to the EP-20 answer. `@Tag("ADM-10")` |
| ADM-01, NFR-02 | integration route matrix: every `/gestione/**` route and every legacy admin path without a session → 401/403/redirect, never 500 or 200; auth-negative cases (expired, wrong role). `@Tag("ADM-01")`, `@Tag("NFR-02")` |
| ADM-02, ADM-04, ADM-06 | unit + E2E: approving an unverified customer refused; approval needs a unique (normalised) code and amount > 0 with comma; tracking → PRESA + event; any transition allowed, each stored with user/time; EML-09 only on CONSEGNATO. tags per ID |
| ADM-07, ADM-08, ADM-09 | E2E: customer note shown read-only beside internal notes; detail shows sender, recipient, lines with quantity; report by creation date, `to` inclusive, only-delivered filter, authenticated download. tags per ID |
| CUS-01, CUS-02, CUS-04 | integration: identical status and body for known/unknown e-mail; registration accepts the legacy lengths, phone required, e-mail trimmed/lower-cased; an order body naming another customer is ignored (sender = session), lines validated as integers, service default none, double submit → one order with a `TEMP`-marked code. tags per ID |
| EML-01/03/05/06/07/09 | integration per e-mail: recipient (office vs customer), subject (legacy wording, brand per Q-37), Reply-To; EML-06 office-only on order creation. tags per ID |
| MIG-01, MIG-02 | unit: the §8 variant matrix on synthetic fixtures (numeric strings, `null`/`0`/`1`/`"3"` service, `type` `null`/`1`, `""`/`null` phone, ISO vs epoch timestamps), `"Nessuna nota presente"` → null, DQ-07 flagged; exporter allow-list refuses EP-14, EP-18 and any unlisted path; reconciliation counts per state. `@Tag("MIG-01")`, `@Tag("MIG-02")` |
| MIG-03 | rehearsal (ready-for-owner) + config test: the legacy-nginx block list contains every write path of 10 §7 step 2 and keeps EP-13/19/20; `nginx -t` in CI. `@Tag("MIG-03")` |
| PUB-04 | integration: `ordini.` → 301 `/area-clienti/`, `dashboard.` → 301 `/gestione/`. `@Tag("PUB-04")` |
| NFR-03 | CI check: `docs/legacy-captures/` holds only P-01/P-03 outputs and P-04 status lines (allow-listed names); no JSON with e-mail-shaped strings. `@Tag("NFR-03")` |

## 8. Notes for the planner

* **Ownership (10 §2 modules).** `tracking`: API-01/02/03, TRK-01…04, ADM-10 seed data, ADM-06 rules, the `/mono/**` 410 catch-all and the old-link page. `orders`: CUS-04, ADM-04/07/08 rules, the §8 wire-variant knowledge shared with `migration`. `customers`: CUS-01/02, ADM-02 rules. `backoffice`: ADM screens and E2E. `notifications`/`reports`: EML-*, ADM-09. `migration`: MIG-01/02, the exporter and the probe script (`tools/probes/`, read-only allow-list P-01…P-03). `platform` (Phase 1): deny-by-default security, JSON error schema, CORS per endpoint, rate limiting with one trusted proxy hop, host handling (apex and `app.` on one app), `docs/legacy-captures/` convention. `site`: PUB-04 redirects and `TRACKING.endpoint` → relative path.
* **Phase 1 foundations this chapter needs.** Tables `tracking_state` (ids 0–5 seeded), `accessory_service` (`legacy_id`, `legacy_json`, `is_none`), `tracking_event`, `shipment_order.code`/`temp_code` with a unique index on the normalised code; security configuration registering the `/mono/**` compatibility routes as public and everything else deny-by-default; contract-test harness with the FX fixtures as JSON files under test resources; TLS/virtual hosts for apex and `app.` in the infrastructure default (ADR 0001).
* **Risks.** (1) Production collation unknown: imported codes or e-mails differing only by case/spaces would collide on the normalised unique index — the importer must report them before the first rehearsal. (2) Unknown third-party callers of EP-13 or the enumerations (P-06) and legacy frontends calling the old backend by another host after the DNS switch (CU-21) — mitigated only by the nginx block at the freeze (MIG-03). (3) Live `ALLOWED_ORIGINS` unknown: if the live site also answers on `www` or Netlify origins keep calling `app.`, the apex-only CORS default breaks them until Q-20 — run P-03 before the cut-over. (4) 429/5xx shown as "not found" by every legacy client: decide whether the ported `tracking.js` treats them as a network error (PUB-01 allow-list). (5) Stale chapter-12 notes about a 404 target. (6) The legacy logo path must be served from the new site's asset, since nothing may be copied from `legacy/ls`.
* **For ADR 0001 / the Gate 0 summary.** Confirm Q-03 + Q-38 (label, `200 Non gestito`, trimmed case-insensitive matching) and Q-39 (apex-only CORS); Q-27 go-ahead for P-01…P-03 and owner-run P-04/P-06/P-07; the 410 JSON wording and the Italian 410 page text (owner review); keep or retire `tipoPallet`/`statoConferma` (proposal: keep under API-02, zero cost); the rate-limit trade-off of 06 §5.7; Q-05 (`importo` meaning) and Q-06 (code format) as they shape ADM-04 and tracking; Q-47/Q-50 assumptions (`ordini.`/`dashboard.` call `app.`; old links answered on both hosts); both hosts on one VPS/certificate in the infrastructure decision.
