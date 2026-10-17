# Phase 0 reader — chapter 03 · Legacy order portal (`ls-order`)

Source: `docs/brief/03-legacy-order-portal.md` (733 lines), read in full on 2026-10-16. Section anchors are `03 §n`;
code citations keep the brief's convention (`repo@ref:path:line`; `ls-order@dev` = `dbe7292`, `ls@migration` = `ca1b0d0`).

## 1. Scope

Chapter 03 documents the login-less order portal from its source: boot and state machine (§2–§4), e-mail-first identification and
registration (§5), the order wizard with lines, totals, accessory service and recipient (§6), the four REST calls it makes (§7), the
verbatim copy deck (§8), the `dev`/`main` differences (§9), 25 quirks + 5 security issues with a Keep/Fix/Compat/Drop decision (§10),
the nine business rules to preserve OP-R1…R9 (§11) and nine unknowns OP-U1…U9 (§12). The banner at the top carries the backend facts
of chapter 12 that override the chapter's inferences. Reading map (README): **customers + sign-in** → §5, §8, §10 (OP-S1…S5,
OP-08…11), §11 (OP-R1…R3); **orders** → §6, §7.4, §10 (OP-01…07, OP-12…16, OP-25), §11 (OP-R4…R9); **migration** → §9 (and §7.4
wire shapes, OP-13…15 Compat). Nobody is assigned §3 (HOST, CORS), §1 (hosts, `ordini.`) or OP-17…24: see §8 below.

## 2. Requirement IDs touched

| IDs | Where chapter 03 specifies or constrains them |
|---|---|
| CUS-01 | §5.1 e-mail-first entry and hint; §5.2 three statuses as the only gate; §5.6 "Basta solo la tua email" intent; OP-S1, OP-S2, OP-R1, OP-R2 |
| CUS-02 | §5.3 registration fields, regexes and validation order; §5.4 confirm step; §5.5 "e-mail sent" screen; §7.2 wire fields; OP-08…11, OP-20, OP-22, OP-R3 |
| CUS-03 | §4 `PENDING_STEP` (forward off, back to start), §5.2 PENDING copy; OP-R2 |
| CUS-04 | §6.1–6.7 order screen, dialog, totals, cards, service, recipient, confirm/final; §7.3–7.4 wire shape; §8 copy; OP-01…07, OP-12…16, OP-25, OP-R4…R9 |
| CUS-05 | §1 "Not in the portal": no order history, no code shown after submission (§6.7) — the gap CUS-05 closes |
| CUS-06 | §1: no profile, no logout — nothing to keep; the registration fields of §5.3 are the profile fields |
| ADM-02 | §5.2, §5.6: approve/decline of pending customers; "Email confermata" column inference (corrected by the banner, Q-28) |
| ADM-04, ADM-08 | §6.7 "what happens next": temporary code shown as "-", office confirms/declines; §7.4 tail: how the CMS sees the data (flat list, detail with `dettaglioColli`/`palletList`) |
| ADM-10, API-02 | §6.5, §7.3: `servizioAccessorio` shape `{id, label}` read by the portal, default `0` = none (OP-U4, Q-25) |
| EML-01, EML-02, EML-04 | §5.5–5.6 "istruzioni contenute nella mail" (verification link); §6.7 "attendere la conferma via email" (no customer e-mail at submission today: EML-04 is new); OP-U3 |
| EML-03, EML-05, EML-06 | banner: backend e-mails only the office at registration/order; customer e-mailed at approval/decline (§6.7) |
| MIG-01, MIG-02 | §7.4 exact request; §9 build variants (`servizioAccessorio` null/1/0, DDT null, `type` 1); OP-04, OP-07, OP-11, OP-13…15 (Compat) |
| MIG-03, PUB-04, API-03 | §1 hosts (`ordini.logisticasanchez.com`, old S3 endpoint linked by the v1 site); §3 `HOST`, paths to retire, CORS; OP-S3, OP-U6 |
| NFR-01 | OP-23 (placeholders as labels, `aria-label="Label"`, no `<form>`, no `lang`), OP-17 (CSS-only disabled) |
| NFR-02, NFR-03 | OP-S2 enumeration oracle, OP-S3 plain HTTP, OP-S4 no abuse protection, OP-S5 unescaped `innerHTML` |
| ENH-03, ENH-07 | §1/OP-R8: no prices in the portal (ENH-03 adds an estimate); no saved recipients, OP-25 "duplica riga" (ENH-07) |

## 3. Rules to keep (M1 parity)

| Rule | Citation (from the chapter) | Serves |
|---|---|---|
| OP-R1: identified by e-mail; unknown e-mail → registration; hint "Assicurati di aver inserito la mail corretta. Se sei già registrato NON è necessario ripetere la registrazione." | ls-order@dev:js/components/types/form-components.js:28, :90; js/function/form-validator.js:7-22 | CUS-01, CUS-02 |
| OP-R2: three outcomes — new → register; pending → waiting page, cannot order; registered → may order; only approved customers order (backend: 500 otherwise) | ls-order@dev:js/function/state-machine.js:146-168; ls@migration:…/business/service/crud/SpedizioneService.java:78-79 | CUS-01, CUS-03, CUS-04 |
| OP-R3: registration = company/personal name, street, province, city, CAP, phone, e-mail; a confirm step; e-mail with instructions; then office approval | 03 §5.3–5.6; ls-order@dev:js/rest/dto/customer-request.js:9-19 | CUS-02, ADM-02, EML-01 |
| OP-R4: ≥ 1 line; line = type ("Pallet Generico/Colli" `0` / "Epal" `1`), height, length, width (cm), weight per unit (kg), quantity | ls-order@dev:js/constant/costant.js:86-90; js/components/types/dialog-components.js:42-56; js/function/form-validator.js:113-117 | CUS-04 |
| OP-R5: live totals — kg = Σ peso × quantità; m³ = Σ round₂(h × l × w / 1 000 000 × quantità) (per-line rounding); worked example §6.3 (1236 kg, 4.53 m³) | ls-order@dev:js/function/data-handler.js:88-115 | CUS-04 |
| OP-R6: DDT required (as `dev`); "Numero Colli" optional, digits only; notes optional; one accessory service from the enumeration, default none (`0`) | ls-order@dev:js/function/form-validator.js:88-126; js/function/data-handler.js:53-66 | CUS-04, ADM-10 |
| OP-R7: recipient name, address, province, city, CAP required; phone optional | ls-order@dev:js/function/form-validator.js:47-86 | CUS-04 |
| OP-R8: submission → order pending office approval; no price shown; "attendi la conferma via email"; sender = the identified customer | ls-order@dev:js/function/state-machine.js:106-111; js/rest/dto/order-request.js:7 | CUS-04, ADM-04, EML-04/05 |
| OP-R9: the Italian copy of §8 is the baseline (titles, placeholders, dialog buttons, final texts), corrected per §10 (OP-20, OP-22) | ls-order@dev:js/constant/language-messages.js:1-74 | CUS-02, CUS-04 |
| Enumeration contract `[{id, label, …}]`, id 0 = "Nessun servizio accessorio" (settled by 12b §2.3) | ls-order@dev:js/components/types/dropdown-component.js:36-41; ls@migration:…/enumeration/ServizioAccessorioEnum.java:16-21 | API-02, ADM-10 |
| `ordini.logisticasanchez.com` is the address customers know (linked by the new site header/footer) → must redirect to `/area-clienti/` | ls-home@fe4b056:partials/header.html:11, 88; partials/footer.html:21 | PUB-04, MIG-03 |

## 4. Deliberate fixes (each needs an ADR; proposed titles)

| Fix (chapter 03 / 09) | ADR title |
|---|---|
| OP-S1, OP-S2: open portal, status oracle → sign-in/registration links, uniform answer, sender from the session (CUS-01) | "Customer sign-in replaces the open order portal" (same as reader 01's 0002) |
| OP-S3, OP-S4, OP-S5: plain HTTP, no abuse protection, unescaped labels → HTTPS/HSTS, rate limits, honeypot, server-side max lengths, escaped output (NFR-02) | "Abuse protection and output escaping for customer-facing forms" |
| OP-08…11 + Q-36: US regexes, no trim, case-sensitive e-mail, phone "optional" label → 5-digit CAP, Italian/E.164 phone, trimmed lower-cased e-mail, phone required | "Italian validation rules for registration and orders" (reader 01's 0006) |
| OP-02 + CUS-04: double submission via "Indietro" → terminal final screens, idempotency key per wizard session | "Idempotent order submission" |
| OP-04, OP-12, Q-35: type per line required; integers ≥ 1 (backend already rejects 0); limits/warnings | fold into "Italian validation rules…" or "Order line limits (Q-35)" |
| OP-05, OP-06: float artefacts, colliding card ids → totals recomputed from lines in decimal arithmetic, Italian format ("0,07 m³"); legacy formulas kept | "Order totals: legacy formulas, decimal rounding, Italian formatting" |
| OP-07, Q-24: "Numero Colli" collected but never sent → stored as optional `package_count` | "Store the number of packages (Q-24)" |
| OP-13, OP-14, OP-15: string numbers, nested `dettaglioColli`, `destinatario.email` = sender → typed model, order → lines, optional real recipient e-mail; legacy shapes only in the importer | "Typed order model; legacy wire shapes accepted only by the importer" |
| OP-01, OP-16, OP-17, OP-18, OP-20, OP-23, OP-25: shared DOM ids, no summary, CSS-only disabled, generic errors, wrong wording, a11y, sticky dialog values → server-rendered wizard with summary pages, specific messages with contacts, real forms and labels | "Customer-area wizard redesigned on the new site's design system" |
| OP-22, Q-37: "Sanchez Logistica" → "Logistica Sanchez" in all customer copy | "Brand name in customer-facing copy (Q-37)" |
| Banner / 12c §1.5: no customer e-mail at registration or submission → EML-02, EML-04 added (Q-07); verification confirmed by POST, expiring tokens | "Customer confirmation e-mails and single-use POST-confirmed links" (reader 01's 0008) |
| OP-03, OP-19, OP-21, OP-24: Drop (incidental to the SPA); no ADR, note in the plan | — |

## 5. Unknowns by ID (default = the brief's recommendation)

* OP-U1 → answered by 12a §1.4 B1: `status` ∈ NEW/PENDING/REGISTERED/DECLINED (legacy rows only), 400 on malformed e-mail; new app: uniform answer (CUS-01); importer maps DECLINED (Q-09).
* OP-U2 → answered by 12a B2/C1: register 200/200-resend/500 (PENDING)/409 (REGISTERED); order 500 for non-REGISTERED (CU-05). Contract tests are for the new app only (endpoints retired, API-03).
* OP-U3 → partly answered (12a B2, 12c §1.4): `GET /mono/email-confirm?email=…&verificationCode=<UUID>`, never expires, only the office e-mailed at submission. Default: old links land on a page pointing to `/area-clienti/`, never confirm (Q-30); EML-04 added (Q-07).
* OP-U4 → answered (Q-25, 12b §2.3): six services, ids 0–5, 0 = none; seed exactly those (ADM-10); DQ-07 for May-2024 orders storing "Prenotazione telefonica" as "none".
* OP-U5 → answered (12b §4.1, §4.3): numeric strings coerced; `type` 1 stored as EPAL (OP-04 undetectable, DQ-15); one `pallet` row per unit; totals computed, volume rounded to an integer (DQ-28).
* OP-U6 → still unknown (Q-16, Q-47, CU-21): deployed commit and `HOST` of `ordini.`; assume it calls `https://app.logisticasanchez.com`; DNS changed by the owner; S3 names kept (Q-46).
* OP-U7 → Q-24 default: DDT mandatory (as `dev`); "Numero Colli" stored as an optional count; DQ-11 for legacy null DDTs.
* OP-U8 → answered (12a B2, C1, CU-23): lengths 50/50/50/50, CAP 1–9, phone ≤ 20 + pattern, note ≤ 255; no rate limits anywhere. New app: NFR-02 limits.
* OP-U9 → answered (CU-20): `destinatario.email` stored as `spedizioni.email`, never used to resolve the customer; importer: `recipient_email` = null (DQ-23).
* Q-09 declined customers keep the row, may register again (data stored apart until verified); Q-10 companies and private persons, VAT/tax code optional; Q-11 one login per customer; Q-13 pick-up = registered address (sender snapshot).
* Q-12 changes/cancellation only while pending, by asking the office (CUS-05 detail page shows this); Q-28 approval only after verification; Q-35 integers ≥ 1, no maximum, warning above 240 cm / 1 200 kg; Q-36 phone required; Q-37 "Logistica Sanchez"; Q-39 CORS only the apex for the kept endpoints.
* DQ-01…07, DQ-11, DQ-15, DQ-16, DQ-20, DQ-23 (08 §5) — portal-born data defects; importer flags per 08 §3.8 normalisation; DQ-20 double submissions listed, never merged.
* SEC-11, SEC-12, SEC-13, SEC-19 (12a §5) — fixed by CUS-01/02, NFR-02 (escape on output, expiring opaque tokens, POST confirmation).
* CU-14 (live CORS origins, P-03 by the owner), CU-21 (which build runs at `ordini.`) — not needed for the build; cut-over assumption as Q-47.

## 6. Contradictions

* 03 §10 OP-08 decision "Fix: optional as labelled" and 08 §2.3 `customer.phone` "optional unless the owner decides otherwise" vs 09 CUS-02 and Q-36 "phone required, as the backend requires it" (ls@migration:…/api/req/crud/CreateSoggettoReq.java:42-43 `@NotBlank @Pattern`). Chapter 09 wins; keep the column nullable for legacy rows only.
* 03 banner: "phone exactly 10 digits until 2025-01-07, then a looser pattern" is not in 12a §1.4 B2 nor in 12a §2.16; 12a:29 says `dev_API_v2` (2025-01-07) differs only in `LinkUtil.java`. At HEAD the pattern is the loose one (VERIFIED, `SoggettoConst.java:24`). Treat the 10-digit phase as unverified; the importer accepts any phone (DQ-06).
* 03 §5.6 "An approved customer may therefore have an unconfirmed e-mail" (inferred from the CMS column) vs the chapter's own banner and 12b §3.1 C7: approval requires PENDING, i.e. a verified e-mail. 12 wins (Q-28, DQ-24 cannot occur).
* 03 §5.2 "NEW = e-mail unknown to the backend" and "other values (declined) unknown" vs 12a B1: NEW also = registered-but-unverified or declined since 2024-07-22; `DECLINED` for older rows makes the portal show "Errore". 12 wins; CUS-01/02 must treat an unverified customer's e-mail as "known" (sign-in link verifies it, 08 §2.4).
* 03 §6.2 "integers ≥ 0 only; `0` passes" and 08 §2.3 `order_line` "DB CHECK (≥ 0) so legacy zeros import" vs 12a C1/12b §4.1: `@Positive`, `"0"` → 400 at least in the `migration` build. Zeros from this build cannot exist; keep the lenient CHECK only for older builds (DQ-16).
* 03 §6.3/OP-R5 per-line 2-decimal totals (kept by CUS-04) vs 12b §4.3: the backend rounds the order volume to an integer (`dettaglio_colli.volume`). Not a conflict for the new wizard, but ADM-08 and the reconciliation must compare against Σ lines (08 §4.6), not the legacy aggregate (DQ-28).
* 03 OP-09 "CAP regex accepts 20831-1234" vs 12a B2 `cap` 1–9 characters: a ZIP+4 (10 chars) gets 400 from the backend, so the portal showed "Errore"; DQ-03 can only come from 5–9-character typos.
* 03 §12 OP-U3 asks whether the order confirmation e-mail goes to the customer; 12c L-E6 confirms it (with full detail, no tracking link); EML-05 adds the tracking link. Settled.
* README reading map vs chapter content: OP-17…24, §3 (`HOST`, CORS, retired paths) and §1 hosts are assigned to no module. Proposal in §8.

Spot check of legacy citations (heads confirmed: `ls-order` `dbe7292` 2024-06-30, `ls` `ca1b0d0` 2025-11-16):

| Citation | Result | Evidence |
|---|---|---|
| ls-order@dev:js/rest/dto/order-request.js:3-43 | VERIFIED | `getRegisterOrderRequest()` builds `clienteEmail`, `destinatario{… "email": userData.email}` (line 15), `dettaglioColli: { palletList }`, `codiceDocumentoTrasporto`, `servizioAccessorio`, `note`; `mapPalletData()` keys `altezza, peso, larghezza, lunghezza, quantity, type` |
| ls-order@dev:js/function/state-machine.js:146-168 | VERIFIED | `switch(registrationState)`: REGISTERED → `await getOrderDropdownData(); updateState(ORDER_STEP)`; PENDING → `PENDING_STEP`; NEW → `REGISTRATION_STEP`; default → `console.error` + `showError()` |
| ls-order@dev:js/constant/costant.js:26-29 | VERIFIED | the four regexes exactly as quoted (`EMAIL_REGEX`, `MOBILE_PHONE_REGEX`, `POSTALCODE_REGEX` with ZIP+4 branch, `ONLY_NUMBERS_REGEX`); status strings at :17-19 |
| ls-order@dev:js/function/data-handler.js:88-115 | VERIFIED | `weight = palletWeight * palletQuantity` → `toFixed(2)`; `volume = (h*l*w)/1_000_000 * quantity` → `toFixed(2)`; running sums `userData.orderWeight/orderVolume` written raw to the DOM (OP-06) |
| ls@migration:…/business/service/crud/SpedizioneService.java:78-79 (banner, 12a C1) | VERIFIED | `create()`: `ConfirmationState.REGISTERED.equals(cliente.getStato())` else `INVALID_ENUM_500`; then `stato NEW`, `tracking NON_GESTITO`, `codice "TEMP" + uuid` (:80-83) |

## 7. Planned tests

* CUS-01: E2E `[CUS-01]` same page/status for a known, unknown, unverified and declined e-mail; integration `@Tag("CUS-01")` GET of the link never consumes the token, second POST refused, expiry, rate limit per e-mail and IP (429); unit: e-mail trimmed + lower-cased before lookup (OP-10/11).
* CUS-02: unit `@Tag("CUS-02")` validators — CAP exactly 5 digits (rejects "20831-1234", " 20851"), phone Italian/E.164 normalised (accepts "0362 123456", "02 12345678", "+39 …"; rejects letters, > 20 chars), phone required (Q-36), trim on every field, max lengths 50 (server-side); integration: honeypot → nothing stored; E2E `[CUS-02]` register → summary → verify → pending → approve → order; copy assertions ("Inserisci i dati dell'azienda", hint text, no stray "B", "Logistica Sanchez").
* CUS-03: E2E `[CUS-03]` pending and declined customers see the status page with the legacy pending sentence and no "Nuovo ordine" entry; integration: order submission refused for non-ACTIVE customers.
* CUS-04: unit `@Tag("CUS-04")` totals on the §6.3 worked example (1236 kg, 4.53 m³), per-line rounding (10×10×10 → 0,00; 50×40×30 + 20×20×25 → 0,07 then 0,00 after deletes, no float artefacts), Italian formatting; validation: ≥ 1 line, integers ≥ 1, type required per line, DDT required after trim, "Numero Colli" digits only, service id in the seeded set with default none, recipient rules OP-R7, optional recipient e-mail; integration: idempotency key — second submit creates no second order; stored order = input (lines, snapshots, `package_count`); E2E `[CUS-04]` happy path with summary page and the legacy final wording; a11y: labels, `<form>`, Enter submits.
* CUS-05: E2E `[CUS-05]` list shows only the signed-in customer's orders, temp code while pending; authorisation test on `/area-clienti/ordini/{codice}/` of another customer → 404.
* CUS-06: E2E `[CUS-06]` profile shows the registration fields read-only with "chiedi una modifica".
* ADM-02 / ADM-04 / ADM-08: integration `@Tag("ADM-02")` approve refused for an unverified customer; `@Tag("ADM-04")` pending list shows "-" for temp codes; `@Tag("ADM-08")` detail shows lines grouped (not per unit) with the recomputed totals.
* ADM-10 / API-02: seed test `@Tag("ADM-10")` six services ids 0–5, item 0 = "Nessun servizio accessorio"; contract `@Tag("API-02")` `GET /mono/api/enumeration/servizioAccessorio` byte-equal to 12b §2.3.
* API-03 / PUB-04 / MIG-03: contract `@Tag("API-03")` `POST /mono/api/customer/state|register`, `/order/register` → 410 JSON, never act; E2E `[PUB-04]` `ordini.logisticasanchez.com/*` → 301 `/area-clienti/`; MIG-03 rehearsal record (ready-for-owner).
* EML-01/02/04/06: integration per e-mail — EML-01 verification link single-use; EML-02 and EML-04 sent to the customer (new, Q-07) with "Logistica Sanchez" subject; EML-06 to the office once per verification and per order; staging guard: no real recipient.
* MIG-01/02: integration `@Tag("MIG-01")` synthetic dump with the §9 variants (service null/1/0, DDT null, `type` 1, string numbers, `destinatario.email` = sender, per-unit `pallet` rows) imports idempotently; `@Tag("MIG-02")` lines regrouped, Σ kg/m³ per OP-R5 equal to the fixture, `recipient_email` null, DQ-07/11/15/23 flags present.
* NFR-01/02/03: axe on `/area-clienti/**` pages (no serious/critical); route matrix: customer pages need a session; log test: no e-mail/phone in logs; CSP green in E2E.

## 8. Notes for the planner

* Ownership (10 §2): CUS-01, CUS-02, CUS-03, CUS-06, OP-R1…R3, OP-08…11, OP-20, OP-22 → **customers** (+ **auth** for the links); CUS-04, CUS-05, OP-R4…R9, OP-01…07, OP-12…16, OP-25 → **orders**; OP-16, OP-18, OP-23, OP-17 → shared wizard components (Phase 1 layout/design system, then both modules); ADM-10/API-02 → **tracking** (enumerations); §1/§3 hosts, `HOST`, CORS, retired paths → **tracking + compatibility** (API-03) and **infrastructure + cut-over** (PUB-04, MIG-03); §9 variants, OP-13…15 → **migration**. Add these to the README reading map in the plan.
* Phase 1 foundations this chapter needs: magic-link auth with POST-confirmed tokens (CUS-01) finished in Phase 1; `accessory_service` seed (six items, id 0 none); `shipment_order` with `temp_code`, `idempotency_key`, `package_count`, sender snapshot, `recipient_email`; `order_line` with `kind`, decimal `line_volume_m3`; shared validators (CAP, phone E.164, trim) reused by the public quote wizard (06 §5.5) and the customer area; Italian number formatting helper; rate-limit and honeypot primitives; message bundle seeded with the §8 copy deck (keys per screen).
* Decimal rounding: 08 §2.3 prescribes `round_half_up` in decimal arithmetic, while the legacy used JS `toFixed(2)` on binary floats; they differ only on exact ties the float cannot represent (e.g. 0.015). Record the decimal rule in the totals ADR and test the §6.3 example plus one tie case.
* Gate 0 items: Q-36 phone required (03 said optional — confirm); Q-24 DDT mandatory and the meaning of "Numero Colli"; Q-35 line limits; Q-10 private persons and VAT/tax code; Q-13 pick-up = registered address; Q-37 brand name; Q-07 new e-mails EML-02/EML-04 and the copy of the §8 screens; Q-09 declined customers may register again; Q-12 (Gate 1) order changes only via the office. Put the banner's backend facts (NEW = unverified, 500 for non-REGISTERED, per-unit rows) in ADR 0001's "legacy as reference" section.
* Risks: the live portal build and its `HOST` are unknown (OP-U6) — a later `ls-order` commit than `dbe7292` may be deployed (PROMPT.md asks to list later commits); legacy data carry undetectable defects (OP-04 type 1, DQ-07 service 1, DQ-20 double submissions, DQ-02 swapped registration/recipient data) — reconciliation can only count them; "phone required" and "DDT required" rest on defaults the office may contradict at UAT; ZIP+4 and US phone leftovers are impossible from this build but possible from older ones; the new wizard's totals must match the portal's figures customers are used to (per-line rounding), not the backend's integer volume.
