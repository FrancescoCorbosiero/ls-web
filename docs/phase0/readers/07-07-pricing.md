# Phase 0 reader — chapter 07 · Pricing (confidential)

Reader of `docs/brief/07-pricing.md` (871 lines, read in full on 2026-10-16). The chapter is confidential: this summary names cells (`MOSCA!A43`, `RKS!CALCOLI!B4`) and example IDs (E1…E9, R1…R13) instead of prices, costs or constants. Citations are as in the chapter (`ls-home@fe4b056` = `9b78785` in `legacy/ls-home`).

## 1. Scope

How the amount is set today (by hand at approval, §1), the one customer price list available (MOSCA 2024: 20 regions × 7 pallet classes, transit times, verbatim notes, 17 defects, §2), the seven classes and the "cheapest fitting class" rule with nine worked examples (§3), the parcel rules that cannot be priced (§4), the partner cost calculator RKS with its formulas, 13 re-computed scenarios and 18 bugs (§5), the proposed M2 pricing module (data model, Excel import, engine, ENH-02/03/04/10/11, tests, §6), confidentiality (§7) and the questions PR-01…PR-14 (§8).
For M1 only §1.1 matters (the manual amount). Everything else is M2 behind `pricing.*` and `delivery.unifiedTimes`, off by default.
Reading map: **orders** → §1.1; **pricing (M2)** → the whole chapter, `data/*.json`, 08 §2.6, 12c §4; **migration** (amount mapping, DQ-13) → §1.1 with 08 §3.2/§5 and 12c §4.2; **site** (CAP widget, quote wizard) → §2.4, §6.7, §6.9 with PUB-06/PUB-08; **back office** → §1.1, §6.5, §6.8; Phase 1 foundations → §6.1 (flags, audit), §6.2 money types, §7 (private bucket, repo privacy).

## 2. Requirement IDs touched

| ID | Sections that specify or constrain it |
|---|---|
| ADM-04 | §1.1 (required amount > 0 at approval, comma and dot accepted, `numeric(10,2)`, Q-05 meaning), §6.5 (dialog unchanged in M1) |
| CUS-04 | §1.2 (what a line gives the engine; type `"0"` mixes pallets and parcels, no stackability/goods description), §3.5 (a line must say it is a parcel line before parcel pricing exists) |
| CUS-05 | §1.1 (customers see the approved amount; M2 ENH-03 shows an indicative price only after login, §6.6) |
| EML-05, EML-09, ADM-09 | §1.1 (the amount reaches the customer in the approval e-mail, the delivery e-mail and the PDF "Importo" column; the suggested amount never does, §6.5; no margins anywhere, §6.8) |
| ADM-10, ENH-14 | §1.2, §6.2 `INCLUDED_SERVICE`/`SERVICE_PRICE`, §6.4 (service prices keyed by the accessory-service enumeration; codes must stay stable) |
| PUB-06 | §1.3, §6.7 (wizard fields, validation and e-mail unchanged; ENH-04 only adds a range) |
| PUB-08 | §2.4, §6.9 (M1 keeps eta.js zones and its "Su richiesta" list unchanged; Bolzano/San Marino wording is an owner copy change) |
| MIG-02 | §1.1 (`importo`, fallback `importoAssicurato`; parse `180,50`/`180.50`; DQ-13) |
| NFR-02 | §7 (prices never in public HTML, JS, public APIs, logs, analytics, error messages; uploads staff-only, private bucket with SHA-256), §6.7 (public engine reads `PUBLIC` lists only), §6.8 (margin = ADMIN only) |
| NFR-03 | §2.5 #3, §7 (the MOSCA file holds personal contacts: treat as personal data; never reproduce) |
| ENH-01 | §6.1–6.3 (lists, versions, rates, transit times, exclusions, rules, assignments, import with preview/validation), §2.5 (defects to warn on), Q-34 (only the MOSCA layout) |
| ENH-02 | §3.3–3.4 (rule + E1…E9), §6.4 (engine), §6.5 (prefill, breakdown, both values stored, audit when they differ) |
| ENH-03 | §6.6 (same engine, only after a real login) |
| ENH-04 | §6.7 (`PUBLIC` list only, range never a single price, band = owner setting, copy change on the blog page before enabling) |
| ENH-10 | §2.4 (16/20 regions differ between list and site), §6.9 (one source: the applicable list's transit times) |
| ENH-11 | §5 (cost model and its bugs), §6.8 (fixed model, ADMIN only, `applicable_regions` empty until PR-13) |

## 3. Rules to keep (M1 parity)

| Rule | Citation | ID |
|---|---|---|
| Approval dialog has two fields, "Codice" and "Importo Totale (per cliente)"; the amount is required | ls-cms@dev:js/components/types/dialog-components.js:113-116; ls-cms@dev:js/function/form-validator.js:130-138, 176-186 | ADM-04 |
| The backend requires `req.importo` as a JSON number > 0 (0, negatives and `"180,50"` refused) and stores it in `importo`; `importoAssicurato` optional, shown as fallback when `importo` is null | 12c §4.2 (ls@migration:…/SpedizioneService.java:152-154, 194); ls-cms@dev:js/function/component-handler.js:407-435 sends the older field | ADM-04, MIG-02 |
| Customers see the amount in the approval e-mail, the delivery e-mail and the PDF report; the order portal shows no price and sends none | 12c §4.2; ls-order@dev:js/rest/dto/order-request.js:3-24 | EML-05, EML-09, ADM-09, CUS-05 |
| Order lines carry height, weight, width, length (per unit), quantity and type `"0"` = "Pallet Generico/Colli", `"1"` = "Epal"; `peso` is per pallet, multiplied by quantity for the total | ls-order@dev:js/rest/dto/order-request.js:32-37; ls-order@dev:js/function/data-handler.js:91 | CUS-04 |
| One accessory service per order from the six-value enumeration (id 0 = none) | ls-order@dev:js/rest/dto/order-request.js:21; 02 §9.2; 12b §2 | CUS-04, ADM-10 |
| CAP widget: three zones, 2-digit prefix map, fixed minor-island and lagoon CAP lists answer "Su richiesta"; Bolzano and San Marino are not special | ls-home@fe4b056:public/js/eta.js:8-19, 22-43, 46-51, 67 | PUB-08 |
| Quote wizard asks total weight and optional max height; server limits 1–40000 kg, 10–300 cm; `tipo_pallet` values `eur`, `industriale`, `mezzo`, `quarto`, `altro` | ls-home@fe4b056:netlify/functions/form.mjs:26, 92-94 | PUB-06 |
| The public site publishes no price list ("Per questo non pubblichiamo un listino valido per tutti") | ls-home@fe4b056:public/blog/quanto-costa-spedire-un-pallet/index.html:165 | PUB-01, ENH-04 |
| The backend computes no price; `TariffarioConst`, tariff traits, `EvasioneEnum`, `PalletEnum` are unreferenced scaffolding: never seed from them | 12c §4.1, §4.3–4.6 | ENH-01 |

## 4. Deliberate fixes (each needs an ADR)

| Fix | Where stated | Proposed ADR title |
|---|---|---|
| Amount stored as `numeric(10,2)` (not `Double`), accepting both `180,50` and `180.50`; `importoContrassegno`-overwrite bug not reproduced | §1.1; 12c §4.2; 10 §2 | "Money as decimal; the approval amount accepts Italian and dotted input" |
| Amount is required and > 0 at approval (legacy backend rule kept, legacy CMS "not empty" not enough); 0 only if the owner says so | §1.1; 12c §7; ADM-04 | "Approval amount: required, positive, meaning and VAT flag per Q-05" |
| No price in code: lists are versioned data imported from Excel, stored in a private bucket; `docs/brief/data/` never loaded by migration, seed or startup loader | §6.1, §6.8, §7; CLAUDE.md | "Price lists and cost models are imported data, never fixtures" |
| Price-list rates use `numeric(12,4)` (keeps three decimals as stored), an exception to the `numeric(10,2)` money type; rounding half-up on the unit price first (PR-06) | §6.2, §6.4 | "Rate precision and rounding policy for suggested amounts" |
| Class selection = cheapest fitting class, deterministic tie-break, not "first column" or "smallest class" | §3.2–3.3, PR-01 | "Pallet class selection rule" |
| Parcels (`colli`) get no automatic price until PR-07 is answered; parcel rules stored as `BLOCKING` data | §4, §6.2 | "Parcel lines are priced manually" |
| Public estimate reads `PUBLIC` lists only and shows a range; customer estimate only after login (the legacy portal had no authentication) | §6.6–6.7 | "Scope of price visibility: public, customer, staff" |
| Cost model used with the RKS bugs fixed (all rows checked, 3 kg minimum, limits block); margin ADMIN-only, never in exports or e-mails | §6.8, §5.7 | "Margin view: corrected cost model, admin only" |
| `order_price.final_amount` duplicates `shipment_order.amount`: keep one authority (copy in the same transaction or drop the column) | 08 §2.6 vs §6.2 | "One authority for the approved amount" (part of the pricing ADR) |

## 5. Unknowns by ID (recommended default)

| ID | Question (short) | Default given by the brief |
|---|---|---|
| Q-05 | Is `importo` the price charged or an insured value; VAT included; may it be 0? | Price charged, VAT excluded, decimal + VAT flag, > 0 (11 Gate 0) |
| Q-14 | VAT excluded? public range (ENH-04) and bands? which delivery times are right? | VAT excluded; public estimate off until bands approved; times from the price list (11 Gate 2) |
| Q-32 | PR-01…PR-14 as a block | The §8 defaults below |
| Q-33 | May class names and limits be public? | Yes, without prices |
| Q-34 | Other customers' lists: same layout? | ENH-01 accepts only the MOSCA layout, rejects others clearly |
| Q-47 | Which CMS build runs in production (the repo one cannot approve)? | Not needed; the new back office follows 04 + 12 |
| DQ-13 | `importo` ≠ `importoAssicurato` in the dump | 08: `importoAssicurato`; 07/12c: `importo` first (see §6) |
| PR-01 | Several fitting classes: charge the cheapest? | Yes |
| PR-02 | Limits inclusive; height incl. base; 100 × 120 with rotation; image vs `PalletEnum`? | Inclusive; total height; image limits (§3.1) |
| PR-03 | MOSCA validity dates | Valid from 2024-01-01, open-ended, confirm before activating |
| PR-04 | Odd cells (Veneto HP < ELP, LP < ULP in Veneto/Liguria, Basilicata ULP) intended? | Keep as written; warn at import |
| PR-05 | Real weight only, or volumetric for pallets? | Real weight only |
| PR-06 | Rounding of 3-decimal prices | Unit price rounded half-up first |
| PR-07 | Parcels: freight, "Presa in carico" meaning, 22-parcel rule, Lombardia/Veneto scope, P/V scope | No automatic parcel price |
| PR-08 | Exactly which areas are "da preventivare" | eta.js islands + lagoon + CAP `39…` + `47890`–`47899` |
| PR-09 | Monthly discounts: replace or add; base; who applies | 5% replaces 3%; transport only; on the invoice, outside the app |
| PR-10 | Services not in the list (appointment, insurance, COD) | Manual when selected and not included |
| PR-11 | Pallets over class limits or over the network's per-pallet limit | Manual quote; warn staff above the network limit |
| PR-12 | Contacts/VAT typos in the list; whose PEC and second mobile | Use the company data of chapter 01; treat as personal data |
| PR-13 | RKS: NORD only? SUD tariff? hub handling? which deliveries; still current? | Margin view off until answered (`applicable_regions` empty) |
| PR-14 | Storage per pallet per day: free days, counting, billed in app? | Information only |

## 6. Contradictions and spot checks

* **DQ-13 default differs.** 07 §1.1 and 12c §7 (Q-05 row): prefer `importo`, fall back to `importoAssicurato`. 08 §3.2 (line 658) and §5 DQ-13: when both are present and differ, use `importoAssicurato`. The source (12c §4.2: `importo` required since 2024-07-16, `importoAssicurato` optional and only a display fallback) supports `importo` first; 08 should be corrected and the rule fixed in ADR 0001 / Gate 0.
* **Zero amount.** 07 §1.1, 12c §4.2 and ADM-04 say > 0; 08 §2.3 `shipment_order.amount` says "≥ 0". Compatible only if the column CHECK is for legacy rows and the form rule is > 0; say so explicitly.
* **VAT is asked twice.** 07 §1.1 points to Q-05, 07 §6.2 `vat_included` to Q-14; chapter 11 has both. Answer once (Q-05) and let Q-14 reference it.
* **Included service names.** `MOSCA!A26` includes "PREAVVISO TELEFONICO"; the enumeration has "Prenotazione telefonica" (02 §9.2, 12b §2). 07 §6.2 maps it to `PREAVVISO_TELEFONICO` without saying which enumeration value that is: the import's rule-confirmation step must ask.
* **Class limits.** 07 §3.1 (image) vs ls@migration `PalletEnum` (HP weight, ULP/LP/FP height): the chapter itself resolves it in favour of the image (PR-02); not a contradiction to act on, but the covers relation of §3.2 changes if the enum were adopted.
* **Two citation refs for ls-home.** The chapter cites `fe4b056`; PROMPT.md and 09 PUB-01 use `9b78785`. Line numbers matched at `9b78785` (below).
* **PROMPT.md** agrees with 12c (approval body with `importo` as a number, `component-handler.js:420-434`); no conflict with 07.
* Internal: none found. Arithmetic of E6, E7, §5.8 and §6.8 (R8 = 1.5 × R2) re-checked.

Spot checks (opened in `legacy/`):

| Citation | Result | Evidence |
|---|---|---|
| ls-cms@dev:js/components/types/dialog-components.js:113-116 | VERIFIED | `createTextField(…, "Codice")` at 113, `createNumericTextField(…, "Importo Totale (per cliente)")` at 116 |
| ls-cms@dev:js/function/component-handler.js:407-435 | VERIFIED | body `{codice, importoAssicurato: importoAssicuratoTxt, tracking: 1}` at 420-434; no `importo` key |
| ls@migration:…/enumeration/PalletEnum.java:15-21 | VERIFIED | seven constants (label, altezza, larghezza, profondità, peso); HP weight equals MQP's; ULP, LP, FP height 150 |
| ls-order@dev:js/rest/dto/order-request.js:32-37 | VERIFIED | `altezza`, `peso`, `larghezza`, `lunghezza` (32-35), `quantity` (36), `type` (37) |
| ls-home@fe4b056:public/js/eta.js:8-19, 46-51, 67 | VERIFIED at 9b78785 | `ZONES` 8-12, `REGION_ZONE` 14-19 (20 regions), `MINOR_ISLANDS` 46-50, `isLagoon` 51, "Su richiesta" 67 |

## 7. Planned tests

| ID | Tests (layer: assertion) — tag |
|---|---|
| ADM-04 | unit: `180,50`, `180.50`, `1.234,56` parse to the same decimal; `0`, negative, text refused with the field message — `@Tag("ADM-04")`. E2E: approve with a comma amount; detail shows `180,50 €` — `[ADM-04]` |
| MIG-02 | unit: `importo` wins, `importoAssicurato` fallback, both-differ → DQ-13 flagged; `180,50` and `180.50` → 180.50; ambiguous → null + DQ-12 — `@Tag("MIG-02")`. rehearsal: sum of amounts equals the source in the reconciliation report |
| EML-05, EML-09, ADM-09 | integration: approval and delivery e-mails and the PDF carry the approved amount, never a suggested amount, margin or list name — `@Tag("EML-05")`, `@Tag("EML-09")`, `@Tag("ADM-09")` |
| CUS-04 | unit: `GENERIC`/`EPAL` kinds and per-unit weight preserved so the engine can read them — `@Tag("CUS-04")` |
| PUB-08 | unit: CAP cases of 06 §5.3 incl. a Bolzano and a San Marino CAP still answer the zone (M1 parity) — `@Tag("PUB-08")` |
| PUB-06 | E2E: wizard fields and messages unchanged with `pricing.publicEstimate` off — `[PUB-06]` |
| NFR-02 | integration: with every pricing flag off no pricing route, template or JSON field exists; grep-style test: no literal rate in `src/main` — `@Tag("NFR-02")` |
| ENH-01 | integration: synthetic `*-synthetic.xlsx` in the MOSCA layout covers every error and warning of §6.3 (unknown class, unknown/duplicate region, negative price, blank cell → "da preventivare", 3 decimals, cheaper covering class, outlier, unparsable transit, contact mismatch); activation closes the previous `valid_to`, no overlapping `ACTIVE`; rules are proposed, never committed silently; audit rows written — `@Tag("ENH-01")` |
| ENH-02 | unit: E1…E9 of §3.4 (synthetic rates may replace the MOSCA numbers except the documented examples); property test: chosen class always fits and no fitting class is cheaper; tie-break deterministic; `PARTIAL` never prefills; rounding per PR-06 (E7). integration: approval stores `suggested_amount`, `final_amount`, breakdown, version; differing values write an audit event — `@Tag("ENH-02")`. E2E: prefill and breakdown panel; `MANUAL_REQUIRED` leaves the field empty — `[ENH-02]` |
| ENH-03 | E2E: an unauthenticated request for an estimate is refused; a customer without a list sees no price — `[ENH-03]` |
| ENH-04 | unit: without height the range spans the four height limits; `altro` → none; excluded CAP → "Su richiesta"; output rounded to whole euros. security: a `CUSTOMER`-scoped list is never read by the public endpoint (service-level test) — `@Tag("ENH-04")` |
| ENH-10 | unit: the widget reads transit times from the active public list when the flag is on and eta.js zones when off — `@Tag("ENH-10")` |
| ENH-11 | unit: R1…R13 reproduce the legacy sheet values and the fixed model asserts R1, R2, R8, R9 (not computable) with synthetic constants; margin absent from customer views, exports and e-mails; OPERATOR gets 403 — `@Tag("ENH-11")` |
| NFR-03 | unit: the importer drops the contact lines (A81–A85) from the stored report except a mismatch flag — `@Tag("NFR-03")` |

## 8. Notes for the planner

* **Ownership (10 §2).** `orders` owns the M1 amount (ADM-04 validation, `shipment_order.amount`, VAT flag); `migration` owns DQ-13; `notifications`/`reports` render the amount; `site` keeps eta.js and the wizard; `pricing` (M2) owns everything in §6 and exposes services only (`suggest(order)`, `publicEstimate(...)`, `transitTimes(...)`), reading `orders` and `customers` through their services. `platform` owns flags, audit log and the private bucket client that ENH-01/ENH-11 uploads need.
* **Phase 1 foundations.** Money as `numeric(10,2)` + `amount_vat_included`; the amount parser (shared by ADM-04 and the importer); `feature_flag` table with the six pricing-related keys registered but off; audit log events; `order_line.kind` and per-unit weight as in 08; reference data `region` (20 eta.js names) and `cap_special` are M2 tables but the eta.js mapping must already be a server-side unit (PUB-08) that ENH-10 can swap.
* **Risks.** The amount's meaning/VAT is a default until Q-05 is answered (consistent code on a wrong assumption); the parcel gap in order lines (type `"0"`) means ENH-02/03 may propose a pallet price for parcels; only one customer list exists (Q-34), so the import layout may not generalise; the RKS model is 2024 and NORD-only by inference (PR-13), so margins could be wrong silently; the confidential chapter and `data/` require the repository to stay private (PROMPT.md check at Phase 0 start); `PalletEnum` and `TariffarioConst` tempt a copy-paste seed.
* **ADR 0001 / Gate 0.** Record: money type and parser; amount required > 0 (0 = owner); DQ-13 = `importo` first (correct 08); no price data in code or fixtures beyond the documented examples; M1 keeps eta.js zones and shows no estimate; `shipment_order.amount` is the single authority. Ask the owner at Gate 0: Q-05 (price vs insured value, VAT, zero), Q-14 (which delivery times), Q-34 (send the other lists), and whether a per-line "colli" indicator may be added to the order wizard as an optional field (otherwise parcel lines stay invisible to the engine). PR-01…PR-14 can wait for Gate 2 with the §8 defaults, except PR-02 (class limits) and PR-12 (contact data) which are cheap to confirm now.
