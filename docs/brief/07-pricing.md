# 07 · Pricing (confidential)

> **Confidential.** This chapter, `data/tariffario-mosca-2024.json` and
> `data/rks-cost-model.json` contain one customer's prices and the partner network's
> costs. They are commercial secrets of Logistica Sanchez: keep them in a private
> repository only (section 7).

How prices are set today, the one customer price list we have (MOSCA 2024) with every
rule and defect it contains, the seven pallet classes and the rule that picks the class to
charge, parcels, the partner network's cost calculator (RKS), and the proposed pricing
module of the new app (M2 flags `pricing.lists`, `pricing.suggestedAmount`,
`pricing.customerEstimate`, `pricing.publicEstimate`, `pricing.marginView`,
`delivery.unifiedTimes` = ENH-01…04, 11, 10 in chapter 09). For M1 only section 1.1
matters: the manual amount must keep working exactly as today.

**Sources outside the repositories** (provided by the owner; not in any repo):

| Alias used below | File | Machine-readable copy |
|---|---|---|
| `MOSCA!<cell>` | `tariffario_MOSCA_2024.xlsx`, sheet `Foglio1` | `data/tariffario-mosca-2024.json` |
| `RKS!<cell>` | `CALCOLATORE__RKS_2024.xlsm`, visible sheet `CALCOLATORE KG` | `data/rks-cost-model.json` |
| `RKS!CALCOLI!<cell>` | same file, hidden sheet `CALCOLI` | `data/rks-cost-model.json` |
| image | `xl/media/image1.png` embedded in the MOSCA file (over `A29:J39`) | `data/pallet-classes.json` |

Numbers are written as stored in the cells, with a dot as decimal separator (`103.502`);
the Italian UI shows them as `103,50 €`. The JSON files were generated from the cells by
script; the RKS formulas were also re-computed with LibreOffice on 13 input scenarios.

## 1. How pricing works today

### 1.1 The amount is typed by hand when an order is approved

* Pricing is manual: when approving an order in the CMS the admin types
  "Importo Totale (per cliente)" (owner).
* The dialog "Approva ordine" (ls-cms@dev:js/constant/language-messages.js:42) has two
  fields: "Codice" and "Importo Totale (per cliente)"
  (ls-cms@dev:js/components/types/dialog-components.js:113-116).
* The amount field is `<input inputmode="numeric" type="text">`
  (ls-cms@dev:js/components/types/text-field-component.js:35): any text is accepted, and
  on phones the numeric keypad may offer no decimal separator (inferred).
* The only validation is "not empty" (ls-cms@dev:js/function/form-validator.js:130-138,
  176-186).
* `ls-cms@dev` sends the raw text as `importoAssicurato` to `POST {HOST}/mono/cms/order/confirm`
  (ls-cms@dev:js/function/component-handler.js:407-435,
  ls-cms@dev:js/constant/rest-constant.js:14):

```json
{
  "id": 42,
  "req": {
    "codice": "12345",
    "importoAssicurato": "51.75",
    "tracking": 1
  }
}
```

  (Sample values. The code format is unknown, see Q-06 in chapter 11; the amount is the
  string as typed, so `"51,75"` is just as possible.)
* The order tables show `order.importo` in the column "Importo", `-` when null
  (ls-cms@dev:js/components/types/data-tables-component.js:159, 250).
* **What the backend does** (12c §4.2, from the source): since 2024-07-16 approval
  **requires `req.importo`**, a JSON number > 0 (0, negatives and `"180,50"` are refused),
  stored in the `DOUBLE` column `importo`. `importoAssicurato` is optional (the field of
  older approvals, and the fallback shown when `importo` is null); `importoContrassegno`
  is overwritten with `importoAssicurato` (a bug). So the `ls-cms@dev` body above fails
  with 500 against today's backend: production must run a later CMS build that sends
  `importo` (unknown source, Q-47). Whether the amount includes VAT is still Q-05.
* The field exists only on `dev`: it was added by commit `e5a72fc` (2024-05-25) and is
  absent from `origin/main` of ls-cms.
* The order portal shows no price and sends no amount
  (ls-order@dev:js/rest/dto/order-request.js:3-24). **Customers do see the amount**,
  though: in the order-approval e-mail (with "Importo Contrassegno", "Importo Assicurato"
  and "Importo"), in the delivery e-mail (`importo` labelled "Importo Assicurato") and in
  the PDF report ("Importo" column) (12c §4.2).

**M1 parity:** keep a required amount at approval, greater than zero, stored as the
customer's amount (legacy `importo`, falling back to `importoAssicurato` for older
orders; chapter 08 DQ-13). Store it as `numeric(10,2)` and accept both `51,75` and
`51.75` in the form (safe improvement). Whether 0 should be allowed is an owner decision
(12c §7).

### 1.2 What an order already tells a price engine

| Data | Legacy field | Source |
|---|---|---|
| Height (cm), weight (kg), width (cm), length (cm) per row | `altezza`, `peso`, `larghezza`, `lunghezza` | ls-order@dev:js/rest/dto/order-request.js:32-35; labels ls-order@dev:js/constant/language-messages.js:28-31 |
| Number of identical pallets in the row | `quantity` | ls-order@dev:js/rest/dto/order-request.js:36 |
| Pallet type: `"0"` = `PALLET` ("Pallet Generico/Colli"), `"1"` = `EPAL` ("Epal") | `type` | ls-order@dev:js/rest/dto/order-request.js:37; ls-order@dev:js/constant/costant.js:87-90; ls-order@dev:js/constant/language-messages.js:62-63 |
| Destination CAP, province, city | `destinatario.cap`, `provincia`, `citta` | ls-order@dev:js/rest/dto/order-request.js:9-13 |
| One accessory service (backend enumeration, six values: none, Prenotazione telefonica, Sponda idraulica, Segnalazione merce lunga, Facchinaggio, GDO; chapter 02 §9.2) | `servizioAccessorio` | ls-order@dev:js/rest/dto/order-request.js:21; ls-order@dev:js/constant/rest-constant.js:11 |

* `peso` is per pallet: the portal multiplies it by `quantity` for the order total
  (ls-order@dev:js/function/data-handler.js:91) (inferred from that multiplication).
* Gaps for pricing: type `"0"` mixes pallets and parcels ("Pallet Generico/Colli"), so the
  engine cannot tell when the parcel rules apply; there is no stackability flag and no
  goods description. The fallback type is `1` with the comment "Generic pallet", but `1`
  is EPAL (ls-order@dev:js/function/data-handler.js:24-25).

### 1.3 Price lists and costs live in Excel

* Each customer has its own price list in Excel; example: tariffario MOSCA 2024 (owner).
* Indicative quotes within the price bands of those lists are acceptable (owner).
* What Logistica Sanchez pays the partner network is computed with the RKS calculator
  (owner; section 5).
* The backend computes **no price** (12c §4.1). It contains unused scaffolding:
  `TariffarioConst` (its numbers equal the MOSCA notes of §2.3: storage 7, 22 parcels per
  pallet, minimum and premium weights and prices, discount thresholds and percentages),
  two tariff traits that sketch region × 7 classes + transit time per row (the MOSCA
  layout), `EvasioneEnum` (the "TEMPO DI RESA" values plus "48", with tier names, 12c
  §4.5) and `PalletEnum`, which contradicts the class image (§3.1). Nothing references
  them; do not seed from them.
* The new public site promises no public price list:
  "Per questo non pubblichiamo un listino valido per tutti"
  (ls-home@fe4b056:public/blog/quanto-costa-spedire-un-pallet/index.html:165).
  Its quote wizard asks "Peso totale (kg)" (for all pallets together) and an optional
  "Altezza massima (cm)" (ls-home@fe4b056:public/preventivo/index.html:244-250;
  server limits 1–40000 kg and 10–300 cm in
  ls-home@fe4b056:netlify/functions/form.mjs:92-94).

## 2. The MOSCA price list (2024)

### 2.1 What the file is

* One sheet, title "Tariffario Pallet" (`MOSCA!A1`): a table of 20 regions × 7 pallet
  classes plus "TEMPO DI RESA" (`MOSCA!A3:I23`), notes below (`MOSCA!A25:A85`), and an
  image of the seven classes over `A29:J39`.
* The customer name and the year appear only in the file name. There are no validity
  dates, no unit, no VAT statement and no origin (unknown). Created 2024-01-04, modified
  2024-01-16 (`docProps/core.xml`).
* The euro comes from the notes (`MOSCA!A28`, `A43`, `A55`, `A56`). Prices are read as
  EUR per pallet of that class to that region (inferred: the sheet has no unit label).
* Display formats: columns B, C, D, F, G use `0.00`; E (HP) and H (FP) use `General`.
  `MOSCA!G19` stores `103.502` and Excel shows `103,50`.

### 2.2 Prices and delivery times (all 20 rows, exactly as stored)

| Row | Region (as in file) | MQP | QP | ELP | HP | ULP | LP | FP | TEMPO DI RESA |
|---|---|--:|--:|--:|--:|--:|--:|--:|---|
| 4 | VALLE D'AOSTA | 35 | 43.4 | 51.8 | 63.59 | 57.4 | 73.78 | 92.87 | 24/48 |
| 5 | LOMBARDIA | 15 | 22.7 | 31.8 | 33.91 | 27.9 | 41.04 | 55.2 | 24 |
| 6 | PIEMONTE | 30 | 39.2 | 47.6 | 54.44 | 51.8 | 61.15 | 79 | 24/48 |
| 7 | VENETO | 30 | 46.6 | 58.8 | 51.75 | 67.2 | 66.03 | 73.26 | 24 |
| 8 | TRENTINO-ALTO ADIGE | 34 | 42 | 51.8 | 62.3 | 56 | 78.24 | 90.1 | 24/48 |
| 9 | FRIULI-VENEZIA GIULIA | 31.36 | 37.8 | 46.3 | 56.87 | 49.7 | 69.62 | 80.24 | 24/48 |
| 10 | LIGURIA | 32.2 | 38.7 | 48 | 57.3 | 69.5 | 69.16 | 81.38 | 24/48 |
| 11 | EMILIA-ROMAGNA | 29.2 | 34 | 41.8 | 50.43 | 43.8 | 58.99 | 68.69 | 24/48 |
| 12 | TOSCANA | 31.9 | 39.2 | 49 | 58.01 | 57.5 | 71.36 | 82.24 | 24/48 |
| 13 | MARCHE | 31.6 | 34 | 41.6 | 54.3 | 49.7 | 65.77 | 79.93 | 48/72 |
| 14 | UMBRIA | 32.2 | 39.2 | 50.4 | 59.73 | 58 | 74.55 | 85.63 | 48/72 |
| 15 | LAZIO | 35 | 43.4 | 57.4 | 68.17 | 64 | 88.1 | 100.57 | 48/72 |
| 16 | ABRUZZO | 36.4 | 44.4 | 57.4 | 69.17 | 68.2 | 89.02 | 98.06 | 48/72 |
| 17 | MOLISE | 38.7 | 48.1 | 63.5 | 75.6 | 82.7 | 99.96 | 114.28 | 48/72 |
| 18 | `CAMPANIA ` | 37.2 | 46.2 | 60.9 | 72.89 | 79.2 | 95.64 | 109.35 | 48/72 |
| 19 | PUGLIA | 39.2 | 49 | 65.2 | 77.89 | 85.4 | 103.502 | 118.97 | 72 |
| 20 | BASILICATA | 41.9 | 53.4 | 72.8 | 86.18 | 65.9 | 116.9 | 132.91 | 72 |
| 21 | CALABRIA | 45 | 56.4 | 78.2 | 92.05 | 115 | 126.75 | 143.54 | 72 |
| 22 | SICILIA | 55 | 65 | 100 | 117.62 | 125 | 182.19 | 193.59 | 72/96 |
| 23 | SARDEGNA | 46.38 | 65 | 85 | 102.63 | 120 | 149.54 | 162.48 | 72/96 |

* Class codes are the headers `MOSCA!B3:H3`; names and limits are in section 3.
* "TEMPO DI RESA" mixes numbers (`24`, `72`) and text (`24/48`, `48/72`, `72/96`).
  Read them as hours (inferred: no unit in `MOSCA!I3`).
* "TRENTINO-ALTO ADIGE" covers the province of Trento only, because Bolzano is
  "da preventivare" (`MOSCA!A25`) (inferred).

### 2.3 Rules and notes (verbatim)

| Cell | Text | Structured meaning | Engine treatment (proposal) |
|---|---|---|---|
| A25 | "ISOLE E LAGUNE BOLZANO E SAN MARINO DA PREVENTIVARE." | No list price for: islands (the minor ones, since Sicilia and Sardegna have rows; inferred), lagoons (e.g. Venice; inferred), Bolzano, San Marino | Manual quote; no suggested amount |
| A26 | "SERVIZI ACCESSORI INCLUSI: SPONDA IDRAULICA, PREAVVISO TELEFONICO" | Tail-lift and phone pre-notice cost 0 | Service price 0 |
| A27 | "GESTIONE EPAL, GDO" | Also included (inferred from its place under A26): EPAL handling, deliveries to large retail (GDO); exact scope (unknown) | Info |
| A28 | "Deposito merce: €7 a bancale al giorno." | Storage 7 EUR per pallet per day; free days and day counting not stated | Info (no storage billing in scope) |
| A41 | "Massimo movimentabile per singolo collo 25kg secondo la legge iso11228. Ogni 22 colli per spedizone a singolo destinatario fare  bancale." | Max 25 kg per parcel; from 22 parcels to one recipient, palletise | Section 4 |
| A43 | " RAPPORTO P/V 300. MINIMO TASSABILE 3KG/ Presa in carico minimo 10kg = 6€ ogni 50KG=9€" (leading space in the cell) | Volumetric ratio 300 kg/m³ (inferred unit); minimum taxable 3 kg; a 6 EUR / 9 EUR charge whose meaning is ambiguous | Section 4 |
| A45 | "SPEDIZIONE COLLI : SOLO SU TUTTA LOMBARDIA, VENETO" | Parcels only for Lombardia and Veneto (destination, inferred) | Section 4 |
| A50 | "Tracking incluso nel prezzo tramite nostro network." | Tracking included | Info |
| A51 | "Fuel surchage non applicato tariffe fisse senza margine di variazione." | No fuel surcharge; fixed prices | No surcharge |
| A52 | "No supplemento zone disaggiate." | No remote-area surcharge | No surcharge |
| A53 | "NIENTE SUPPLEMENTO PRIVATI." | No surcharge for private recipients | No surcharge |
| A55 | "Se nel mese il totale dei trasporti eseguiti a vostro favore è superiore ai €.8.000 vi sarà applicato uno sconto in fattura del 3% sull'imponibile." | Monthly transport total > 8000 EUR: 3% off the taxable amount, on the invoice | Invoice level, never on one order |
| A56 | "Se nel mese il totale dei trasporti eseguiti a vostro favore è superiore ai €.16.000 vi sarà applicato uno sconto in fattura del 5% sull'imponibile." | > 16000 EUR: 5% (inferred: replaces the 3%, not added) | Invoice level |
| A58 | "Pagamento a 45 giorni, a cui sarà applicato 1% di sconto o a 60 giorni da emissione fattura con Ri.Ba o bonifico bancario. " | 45 days with 1% discount, or 60 days from invoice date; Ri.Ba or bank transfer | Info (customer terms) |
| A60 | "Per la corretta esecuzione della gestione del materiale occorre comunicare via e-mail o telefono le reali caratteristiche della merce" | The customer must declare the real characteristics of the goods | Info |
| C64–C66 | "Responsabilità civile Vettoriale:" / "la merce viaggia con copertura assicurativa ai sensi dell’art. 1696 Cod. Civ." / "come integrato dall’art. 10 del Decreto Lgs n.286/05." | Carrier liability under art. 1696 c.c. and art. 10 D.Lgs. 286/2005 | Info |
| A72 | "La merce sarà stoccata presso nostra sede operativa: Via dei Meiani n. 90, Seregno 20831 (MB)" | Storage location (matches the owner's address) | Info |
| A81–A85 | "Per esecuzione del servizio contattare:" / "E-mail: logisticasanchezmb@gmail.com" / "Pec: [PEC personale, vedi MOSCA!A83]" / "Telefono: 32923969627 / [secondo cellulare, vedi MOSCA!A84]" / "P.Iva: 131130809067" | Contacts; two typos, see 2.5 | Ignore at import; warn on mismatch |

Not stated anywhere in the sheet: prices above 1200 kg or 240 cm, footprints other than
100 × 120 cm, non-stackable goods, appointment delivery, cash on delivery, extra
insurance, VAT and validity dates.

### 2.4 Delivery times and excluded areas compared with the new site

The new site's CAP widget has three zones: "24 ore" (Nord Italia), "48 ore" (Centro e
parte del Sud), "72 ore" (Sud e isole maggiori)
(ls-home@fe4b056:public/js/eta.js:8-19).

| Region | MOSCA "TEMPO DI RESA" | New site (eta.js) | Same? |
|---|---|---|---|
| Valle d'Aosta | 24/48 | 24 ore (zone 1) | no |
| Lombardia | 24 | 24 ore (zone 1) | yes |
| Piemonte | 24/48 | 24 ore (zone 1) | no |
| Veneto | 24 | 24 ore (zone 1) | yes |
| Trentino-Alto Adige | 24/48 | 24 ore (zone 1) | no |
| Friuli-Venezia Giulia | 24/48 | 24 ore (zone 1) | no |
| Liguria | 24/48 | 24 ore (zone 1) | no |
| Emilia-Romagna | 24/48 | 24 ore (zone 1) | no |
| Toscana | 24/48 | 48 ore (zone 2) | no |
| Marche | 48/72 | 48 ore (zone 2) | no |
| Umbria | 48/72 | 48 ore (zone 2) | no |
| Lazio | 48/72 | 48 ore (zone 2) | no |
| Abruzzo | 48/72 | 48 ore (zone 2) | no |
| Molise | 48/72 | 48 ore (zone 2) | no |
| Campania | 48/72 | 48 ore (zone 2) | no |
| Puglia | 72 | 48 ore (zone 2) | no |
| Basilicata | 72 | 72 ore (zone 3) | yes |
| Calabria | 72 | 72 ore (zone 3) | yes |
| Sicilia | 72/96 | 72 ore (zone 3) | no |
| Sardegna | 72/96 | 72 ore (zone 3) | no |

* 16 of 20 regions differ. The site mostly shows the lower bound of the MOSCA range; for
  Puglia it promises 48 h where the list says 72; for Toscana the list allows 24/48 where
  the site says 48; for the major islands the list allows up to 96 h.
* Excluded areas: the widget answers
  "Su richiesta … tempi e costi li concordiamo insieme"
  for a fixed list of minor-island CAPs and for Venice lagoon CAPs
  (ls-home@fe4b056:public/js/eta.js:46-51, 67), consistent with
  `MOSCA!A25`. It does **not** treat Bolzano (CAP `39…` → Trentino-Alto Adige, eta.js:31)
  or San Marino (CAP `47890`–`47899` → prefix `47` → Emilia-Romagna, eta.js:33) as special:
  both get "24 ore" while MOSCA says "da preventivare".
* The island list covers only the places named in its comments (Capri, Ischia, Procida,
  Elba, Capraia, Giglio, Pantelleria, Favignana, Lipari, La Maddalena, Carloforte, Ponza;
  eta.js:47-49); other inhabited minor islands such as Ustica, Lampedusa or Ventotene are
  not in it (inferred).
* Which times are right is an owner decision (Q-14). ENH-10 must take them from one place
  (section 6.9).

### 2.5 Typos, defects and inconsistencies in the list

| # | Where | Issue |
|---|---|---|
| 1 | `MOSCA!A85` | "P.Iva: 131130809067" has 12 digits; the company VAT number is 13113080967 (owner): an extra `0` before `67`. |
| 2 | `MOSCA!A84` | "32923969627" has 11 digits; the company phone is 329 239 6962 (owner): a trailing `7` was added. |
| 3 | `MOSCA!A83`, `A84` | The PEC in A83 and the second mobile number in A84 are not among the owner-confirmed contacts (unknown); they are personal data and are not reproduced here. |
| 4 | `MOSCA!D7:E7` | VENETO: HP 51.75 < ELP 58.8, although HP allows more weight (600 vs 450 kg) at the same height (150 cm). ELP is never the cheapest fitting class in Veneto. |
| 5 | `MOSCA!F7:G7` | VENETO: LP 66.03 < ULP 67.2, although LP allows more weight (750 vs 350 kg) at the same height (240 cm). ULP is never the cheapest fitting class in Veneto. |
| 6 | `MOSCA!F10:G10` | LIGURIA: LP 69.16 < ULP 69.5 (same reason). |
| 7 | `MOSCA!F20` | BASILICATA ULP 65.9 is below ELP 72.8 and HP 86.18 of the same row and far below the neighbours' ULP (PUGLIA 85.4, CALABRIA 115): possible typo (inferred). |
| 8 | `MOSCA!G19` | PUGLIA LP 103.502 has three decimals (shown as 103,50). |
| 9 | `MOSCA!A18`, `I4:I23` | "CAMPANIA " has a trailing space; "TEMPO DI RESA" mixes numbers and text. |
| 10 | `MOSCA!I4:I23` | Delivery times differ from the new site in 16 of 20 regions (2.4). |
| 11 | `MOSCA!A25` | Bolzano and San Marino are "da preventivare" but get "24 ore" on the new site (2.4). |
| 12 | `MOSCA!A43` | P/V ratio 300 here; 200 in the RKS calculator (`RKS!K6`); 250 in the new site's example "1 m³ = 250 kg" (ls-home@fe4b056:public/servizi/groupage/index.html:209). Different contexts: make it a per-list parameter. |
| 13 | image vs `RKS!B20` | FP allows 1200 kg per pallet; the partner network stops at 1100 kg per pallet. |
| 14 | `MOSCA!A26`, `A53` | Included tail-lift and phone pre-notice, and "NIENTE SUPPLEMENTO PRIVATI", are customer-specific: the public site says such requests "hanno un costo specifico" (ls-home@fe4b056:public/blog/quanto-costa-spedire-un-pallet/index.html:174) and the quote form asks "Un'azienda" / "Un privato" (ls-home@fe4b056:public/preventivo/index.html:212-213). The engine must read them from the list's rules, never hard-code them. |
| 15 | `MOSCA!A41`, `A51`, `A52` | Typos: "spedizone", "surchage", "disaggiate"; "legge iso11228" cites the ISO 11228 standard as a law. |
| 16 | `MOSCA!A41:A45` | The parcel section has no freight price at all (section 4). |
| 17 | image vs new site | "Half Pallet"/"Quarter Pallet" are weight/height classes on a 100 × 120 cm base; the site's "Mezzo pallet 80 × 60 cm" and "Quarto di pallet 60 × 40 cm" are footprints (ls-home@fe4b056:public/preventivo/index.html:225-228). Do not map one onto the other by name. |

## 3. Pallet classes and the class-selection rule

### 3.1 The seven classes (from the embedded image)

| Code (`MOSCA!B3:H3`) | Name (image) | Max weight | Max height | Footprint | Price column |
|---|---|--:|--:|---|---|
| MQP | Mini Quarter Pallet | 150 kg | 60 cm | 100 × 120 cm | B |
| QP | Quarter Pallet | 300 kg | 100 cm | 100 × 120 cm | C |
| ELP | Extra Light Pallet | 450 kg | 150 cm | 100 × 120 cm | D |
| HP | Half Pallet | 600 kg | 150 cm | 100 × 120 cm | E |
| ULP | Ultra Light Pallet | 350 kg | 240 cm | 100 × 120 cm | F |
| LP | Light Pallet | 750 kg | 240 cm | 100 × 120 cm | G |
| FP | Full Pallet | 1200 kg | 240 cm | 100 × 120 cm | H |

* Each box of the image reads "Peso kg …", "Altezza … cm", "Larghezza: 100 cm",
  "Profondità: 120 cm". Codes are matched to names by initials and by order (inferred).
* Limits are maxima; treat them as inclusive (inferred). Whether the height includes the
  pallet base is (unknown). An EPAL 80 × 120 fits the footprint; rotation (120 × 100) is
  allowed (inferred); anything larger is outside every class (unknown how it is priced).
* Weight is the real gross weight of the single pallet; the sheet applies no volumetric
  weight to pallet classes (inferred: P/V sits in the parcel notes).
* The unused backend enum `PalletEnum` gives other limits: HP 150 kg, and ULP, LP and FP
  150 cm high (ls@migration:src/main/java/it/logisticasanchez/mono/business/model/enumeration/PalletEnum.java:15-21;
  12c §4.6). Keep the image (the price list the customer received) and ask the owner
  (PR-02).

### 3.2 The classes are not ordered by size

Class A *covers* class B when A allows at least B's weight **and** at least B's height:
every pallet that fits B also fits A. ELP and HP do not cover ULP, and ULP covers neither
of them (ULP is taller but lighter). The column order B…H is therefore not a size order,
and "the first column that fits" or "the smallest class" are wrong selection rules.
`data/pallet-classes.json` lists all 19 covers pairs and the 2 incomparable pairs.

### 3.3 The rule

1. A pallet **fits** a class when `weight_kg ≤ max_weight_kg` **and**
   `height_cm ≤ max_height_cm` (and the footprint is within 100 × 120 cm).
2. Charge the **cheapest fitting class** in the destination region's row. On a tie, take
   the class with the lower max weight, then the lower max height (deterministic).
3. No fitting class (over 1200 kg, over 240 cm, footprint too large) → no suggested price
   ("fuori classe", manual quote).
4. Destination in an excluded area (`MOSCA!A25`) → no suggested price ("da preventivare").

The sheet itself states no selection rule. Rule 2 is this brief's recommendation: charging
more than the cheapest class the pallet qualifies for contradicts the list's own offer
(inferred). Confirm with the owner (PR-01, section 8).

Where it changes the price in MOSCA:

* **A larger class is cheaper** (strict cover): VENETO HP 51.75 < ELP 58.8; VENETO
  LP 66.03 < ULP 67.2; LIGURIA LP 69.16 < ULP 69.5.
* **An incomparable class is cheaper:** ULP is cheaper than ELP in LOMBARDIA
  (27.9 < 31.8) and BASILICATA (65.9 < 72.8). There a pallet up to 350 kg that does not fit
  QP (taller than 100 cm or heavier than 300 kg) and is at most 150 cm tall is charged as
  ULP, a 240 cm class.

### 3.4 Worked examples (use them as unit tests)

All pallets 80 × 120 cm; prices from `MOSCA!` rows; region from the CAP prefix as in
eta.js.

| # | Destination | Pallets | Fitting classes and prices | Charged | Why it matters |
|---|---|---|---|---|---|
| E1 | 37121 Verona → Veneto | 1 × 400 kg, 140 cm | ELP 58.8 (D7), HP 51.75 (E7), LP 66.03 (G7), FP 73.26 (H7) | **HP 51,75 €** | Cheapest is not the smallest: "smallest class" (ELP) would charge 58,80 € (+7,05 €). MQP, QP, ULP do not fit (weight). |
| E2 | 20831 Seregno → Lombardia | 1 × 340 kg, 140 cm | ELP 31.8, HP 33.91, ULP 27.9, LP 41.04, FP 55.2 (D5:H5) | **ULP 27,90 €** | Incomparable classes: a 240 cm class is the cheapest for a 140 cm pallet; "first fitting column" gives ELP 31,80 €. |
| E3 | 16121 Genova → Liguria | 1 × 300 kg, 200 cm | ULP 69.5 (F10), LP 69.16 (G10), FP 81.38 (H10) | **LP 69,16 €** | The heavier class is cheaper. |
| E4 | 20831 Seregno → Lombardia | 1 × 1250 kg, 180 cm; or 1 × 800 kg, 250 cm | none (FP stops at 1200 kg; every class stops at 240 cm) | **manual** ("fuori classe") | Over the class limits; the partner network also stops at 1100 kg and 240 cm. |
| E5 | 39100 Bolzano → Trentino-Alto Adige | 1 × 500 kg, 150 cm | the row would give HP 62.3 (E8), LP 78.24, FP 90.1 | **manual** ("da preventivare") | `MOSCA!A25` excludes Bolzano. Same for 30124 Venezia (lagoon) and 47890 San Marino: with this pallet the Veneto row would give HP 51,75 and the Emilia-Romagna row HP 50,43, but neither may be used. |
| E6 | 40121 Bologna → Emilia-Romagna | 2 × 600 kg, 150 cm + 1 × 200 kg, 220 cm | line 1: HP 50.43, LP 58.99, FP 68.69; line 2: ULP 43.8, LP 58.99, FP 68.69 (E11:H11) | **2 × 50,43 + 43,80 = 144,66 €** | Each line is priced on its own; transit "24/48". |
| E7 | 70121 Bari → Puglia | 2 × 700 kg, 200 cm | LP 103.502 (G19), FP 118.97 | **2 × 103,50 = 207,00 €** | Three-decimal price. With 5 pallets: 5 × 103,50 = 517,50 € (unit rounded first) vs 517,51 € (exact 517.51): rounding policy PR-06. |
| E8 | 80121 Napoli → Campania | 1 × 1150 kg, 230 cm | FP 109.35 (H18) only | **FP 109,35 €** + warning | Fits the list but exceeds the partner network's 1100 kg per pallet (`RKS!B20`): flag it for staff. |
| E9 | 90121 Palermo → Sicilia | 1 × 500 kg, 150 cm | HP 117.62 (E22), LP 182.19, FP 193.59 | **HP 117,62 €** | ELP's 450 kg limit is exceeded. |

Example of the breakdown the engine stores for E6 (proposal, section 6.4):

```json
{
  "listino": "Tariffario MOSCA 2024",
  "versione": 1,
  "destinazione": { "cap": "40121", "citta": "Bologna", "regione": "Emilia-Romagna" },
  "tempoDiResa": "24/48",
  "righe": [
    { "quantita": 2, "pesoKg": 600, "altezzaCm": 150, "classe": "HP",
      "alternative": { "HP": 50.43, "LP": 58.99, "FP": 68.69 },
      "prezzoUnitario": 50.43, "totaleRiga": 100.86 },
    { "quantita": 1, "pesoKg": 200, "altezzaCm": 220, "classe": "ULP",
      "alternative": { "ULP": 43.8, "LP": 58.99, "FP": 68.69 },
      "prezzoUnitario": 43.80, "totaleRiga": 43.80 }
  ],
  "serviziAccessori": [],
  "esito": "OK",
  "importoSuggerito": 144.66
}
```

### 3.5 Inputs and edge cases

* The legacy order has everything for pallets: per-pallet weight and height, quantity,
  footprint, destination CAP (1.2).
* Region from CAP: start from eta.js's 2-digit prefix map
  (ls-home@fe4b056:public/js/eta.js:22-43); exclusions need CAP
  rules on top (minor islands, lagoon, `39…` Bolzano, `47890`–`47899` San Marino;
  inferred, verify PR-08). A full CAP → province table is more exact (inferred).
* Missing height (public wizard, optional): give a range (6.7), never a single price.
* Parcels: the line must say it is a parcel line (1.2 gap); until parcel pricing is
  defined, such lines get "manual" (section 4).

## 4. Parcels (colli)

What the list says (`MOSCA!A41`, `A43`, `A45`, verbatim in 2.3):

| Rule | Value | Cell |
|---|---|---|
| Max weight of one parcel | 25 kg | A41 |
| Palletise from N parcels to one recipient | 22 | A41 |
| Volumetric ratio | 300 (kg per m³, inferred) | A43 |
| Minimum taxable weight | 3 kg | A43 |
| "Presa in carico" | minimum 10 kg = 6 €; every 50 kg = 9 € | A43 |
| Where parcels are accepted | Lombardia and Veneto only | A45 |

What is ambiguous, exactly:

1. **There is no parcel freight price.** The only parcel amounts are 6 € and 9 €; no
   per-kg or per-parcel rate exists in the sheet.
2. **"Presa in carico minimo 10kg = 6€ ogni 50KG=9€"** can be read as (a) a pick-up fee
   added to the freight, (b) the parcel freight itself, (c) 9 € per *started* 50 kg,
   per *full* 50 kg, or pro rata (0.18 €/kg), (d) 6 € + 9 € per 50 kg, or 9 € replacing
   the 6 € above 10 kg; per shipment or per parcel; parcels only or pallets too.
3. **"MINIMO TASSABILE 3KG" vs "minimo 10kg"** in the same cell: two minimums with no
   stated scope.
4. **"RAPPORTO P/V 300"**: unit, formula (`max(real kg, m³ × 300)`, inferred) and scope
   (parcels only or pallets too) are not stated.
5. **"Ogni 22 colli … fare bancale"**: does the 22nd parcel trigger one pallet; are
   23–43 parcels one pallet plus loose parcels or two pallets; which class is then
   charged?
6. **"Massimo … 25kg"**: is a heavier parcel refused or palletised?
7. **"SOLO SU TUTTA LOMBARDIA, VENETO"**: destination, origin, or both?
8. The RKS note "*Fino 29,90 kg peso effettivo/volumetrico viene tassato come collo"
   (`RKS!J20`) suggests a 29.9 kg parcel threshold on the cost side, unrelated to the
   25 kg handling limit of the customer list (inferred).

Recommendation: store these rules as data (section 6.2) but compute **no automatic parcel
price** until the owner answers PR-07; parcel lines get "manual".

## 5. The RKS cost model (what Logistica Sanchez pays)

### 5.1 What the file is

* "TARIFFA A PESO" (`RKS!B1`): a per-kg tariff of the partner network RKS (owner: what
  Logistica Sanchez pays, not what customers pay).
* Two sheets: `CALCOLATORE KG` (visible, protected; only inputs are meant to be edited)
  and `CALCOLI` (hidden, protected, every value formatted `;;;` so it stays invisible).
  The `.xlsm` contains no macros. Created 2023-01-16, modified 2024-01-04.
* Defined name `HUB` = `CALCOLI!$I$4:$I$5` = "NORD", "SUD": referenced by nothing.
* Data validations (stored in an x14 extension of the sheet XML): `RKS!D3` from
  `CALCOLI!F12:F14` = "----", "Colli", "Bancale"; `RKS!K7` from `CALCOLI!E12:E13` =
  "SI", "NO".

### 5.2 Inputs

| Input | Cell | Values |
|---|---|---|
| "Tipologia Merce" | `RKS!D3` | "----", "Colli", "Bancale" (file ships with "Bancale") |
| kg typed for parcels (label "Inserire Kg  " when Colli) | `RKS!K3` (label `J3`) | number; real or volumetric kg computed by hand |
| "Triangolazione  " | `RKS!K7` | "SI", "NO" (file ships with "NO") |
| Up to 5 pallet rows: "Peso", "Larg.", "Lung.", "Altez." | `RKS!C8:F12` | kg, cm, cm, cm |

`RKS!G8:G12` = m³ (`D*E*F/1000000`). Column B, headed "Qta", is a row counter (1…5), not
a quantity: identical pallets need one row each.

### 5.3 Constants

| Constant | Value | Cell | Label in the file |
|---|--:|---|---|
| Volumetric ratio | 200 kg/m³ | `RKS!K6` | "Rapporto PV  " |
| Line haul, fixed | 1.10 € | `RKS!CALCOLI!B4` | "fisso NTW", "FISSO SPED LINEA" |
| Line haul, per kg | 0.0135 € | `RKS!CALCOLI!C4` | "KG NTW" |
| Distribution, fixed | 1.90 € | `RKS!CALCOLI!E4` | "fisso consegna", "FISSO SPED. DISTRIBUZIONE" |
| Distribution, per kg | 0.0315 € | `RKS!CALCOLI!F4` | "kg consegna", "KG DISTRIBUZIONE" |
| Long-goods surcharge | 0.5 (+50%) | `RKS!CALCOLI!J6` | "Lungha" |
| Hub handling, parcels | 0.15 € (unit unknown) | `RKS!CALCOLI!B12` | "Passag. Magazz. Colli*" |
| Hub handling, pallet | 1.80 € | `RKS!CALCOLI!B13` | "Passag. Magazz. Bancale" |
| Max weight per pallet | 1100 kg | `RKS!CALCOLI!F17` | "Peso massimo" |
| Long goods above | 220 cm | `RKS!CALCOLI!F18` | "Dimensione massime" |
| Max height | 240 cm | `RKS!CALCOLI!F19` | "Altezza massima" |
| Minimum taxable | 3 kg | `RKS!J19` (note) | "*Minimo tassabile 3 kg" |
| Parcel threshold | 29.90 kg | `RKS!J20` (note) | "*Fino 29,90 kg peso effettivo/volumetrico viene tassato come collo" |

### 5.4 Formulas (as the sheet computes them)

```text
m3[i]        = width_cm[i] * length_cm[i] * height_cm[i] / 1_000_000      # G8:G12
if goods_type == "Bancale":
    real_kg  = sum(kg[i])                                                # C14 -> K4
    vol_kg   = sum(m3[i]) * 200                                          # G14*K6 -> K5
else:
    real_kg  = vol_kg = 0                                                # "" counts as 0
taxable_kg   = max(K3 or 0, real_kg, vol_kg)                             # K10 = MAXA(K3:K4:K5)
line         = 1.10 + 0.0135 * taxable_kg                                # CALCOLI!C7
distribution = 1.90 + 0.0315 * taxable_kg                                # CALCOLI!F7
total        = line + distribution        # = 3.00 + 0.045 * taxable_kg  # CALCOLI!H7
is_long      = width_cm[row 8] > 220 or length_cm[row 8] > 220           # J8 (row 8 only)
long_total   = 1.5 * line + 1.5 * distribution   # = 1.5 * total         # CALCOLI!H9

# displayed
K12 "Costo Consegna / Distribuzione" = 1.5 * distribution if is_long
                                       else (blank if taxable_kg < 3 else distribution)
K14 "Costo spedizione / Nolo"        = blank if is_long
                                       else (total if triangulation == "NO" else blank)
K16 "Costo Triangolazione"           = total + K12 if triangulation == "SI" else blank
K18 "Costo totale merce Lungha "     = long_total if is_long else blank

# computed, never displayed
CALCOLI!B19 "NORD" = total + 1.80        # hub handling per pallet
```

* The fixed 3.00 € is charged once for all rows together, because the rows are summed
  before the tariff is applied (inferred).
* There is no destination input: the cost is the same for every region. The labels
  "NORD" (`RKS!CALCOLI!H4`, `A19`) and the unused NORD/SUD list suggest it is the northern
  hub tariff only (inferred; PR-13).
* Exact Excel formulas, cached values and all inputs/outputs are in
  `data/rks-cost-model.json`.

### 5.5 Verified examples (LibreOffice re-computation)

| # | Inputs | Result |
|---|---|---|
| R1 | Bancale, NO; 400 kg, 80 × 120 × 150 | vol 288 kg, taxable 400; K12 14.50; **K14 21.00**; hidden B19 22.80 |
| R2 | Bancale, NO; 300 kg 80 × 120 × 200 + 100 kg 80 × 120 × 100 | real 400, vol 576 → taxable 576; K12 20.044; **K14 28.92** |
| R3 | Bancale, NO; 200 kg, 80 × 240 × 100 | "Merce Lungha"; taxable 384; K12 20.994; K14 blank; **K18 30.42** |
| R4 | as R1 with triangulation SI | K14 blank; **K16 35.50** (21.00 + 14.50) |
| R5 | Colli, K3 = 20 kg, NO | K12 2.53; **K14 3.90** |
| R6 | Colli, K3 = 2 kg, NO | K12 blank; **K14 3.09** (3 kg minimum not applied) |
| R7 | Colli, K3 = 2 kg, SI | **K16 #VALUE!** |
| R8 | rows: 100 kg 80 × 120 × 100, then 200 kg 80 × 240 × 100 | long row 9 not detected; **K14 28.92** |
| R9 | rows: 100 kg, then 1150 kg | no weight warning (row 9); **K14 59.25** |
| R10 | as R3 with triangulation SI | **K16 41.274** (20.28 + 20.994) and **K18 30.42** shown together |
| R11 | 1150 kg, 80 × 120 × 250 | both warnings shown; **K14 54.75** still computed |
| R12 | Bancale, 400 kg, with 900 left in K3 | taxable 900; **K14 43.50** |
| R13 | empty sheet | **K14 3.00** |

### 5.6 Limits and warnings

| Warning text (verbatim) | Cell | Checks |
|---|---|---|
| "⚠️Larghezza superiore 220 cm fare spediz. Singola⚠️" / "⚠️Lunghezza superiore 220 cm fare spediz. Singola ⚠️" | `RKS!B16` | row 8 only |
| "⚠️Altezza massima 240 cm⚠️" | `RKS!B18` | rows 8–12 |
| "⚠️Peso massimo per singolo bancale 1100 kg⚠️" | `RKS!B20` | row 8 only |

Notes on the sheet (`RKS!J19:J22`), verbatim:

* "*Minimo tassabile 3 kg"
* "*Fino 29,90 kg peso effettivo/volumetrico viene tassato come collo"
* "*Oltre 220 cm larghezza/lunghezza si applica 50% costo spedizione"
* "*Altezza massima 240 cm"

Warnings never block the computation (R11).

### 5.7 Bugs and inconsistencies

1. Long goods are detected on row 8 only (`RKS!J8`, `B16`): rows 9–12 are priced as normal
   (R8).
2. The 1100 kg limit is checked on row 8 only (`RKS!B20`) (R9).
3. `RKS!CALCOLI!F17:F19` (1100, 220, 240) are not referenced: the formulas hard-code the
   same numbers.
4. "*Minimo tassabile 3 kg" is not applied: K14 uses the real kg (R6, R13); K12 is just
   blanked under 3 kg.
5. "*Fino 29,90 kg … tassato come collo" is not implemented: there is no parcel tariff.
6. Hub handling (0.15 / 1.80) reaches only `RKS!CALCOLI!B19`, which nothing displays;
   `RKS!CALCOLI!A17` (`LOOKUP(B17,…)` with `B17` hard-coded to 1.8) always selects the
   pallet value, also for parcels.
7. `HUB` (NORD/SUD) is defined but unused; no destination input exists.
8. "Qta" is a row counter, not a quantity; at most 5 rows.
9. K14 "Costo spedizione / Nolo" holds line + distribution (`CALCOLI!H7`) while K12 shows
   the distribution again: adding K12 and K14 double-counts the distribution.
10. Triangulation `K16 = H7 + K12` (inferred meaning: one extra distribution leg) gives
    #VALUE! under 3 kg (R7), and with long goods mixes the normal total with the long
    distribution, shown next to the long total (R10).
11. With long goods, J14 still shows "Costo spedizione / Nolo" next to an empty K14 (R3).
12. `RKS!K3` keeps its value when the goods type changes and still enters `MAXA` (R12).
13. Long goods: `B16` says "fare spediz. Singola", `J21`/`K8` say +50%: two instructions
    for the same case.
14. `RKS!C18` and `C20` (`IF(B17="⚠️","concordate con la filiale","")`,
    `IF(B19="⚠️",…)`) test cells inside merged ranges that are always empty and are
    hidden by merges themselves: dead formulas.
15. Conditional formatting: the rule on `B10:G11` references `#REF!`; the rule on `B9:G9`
    references `$G6` instead of `$D$3` (cosmetic).
16. "Lungha"/"Merce Lungha" is a typo of "lunga"; the formulas rely on case-insensitive
    comparison ("merce lungha", "MERCE LUNGHA").
17. No volumetric weight for parcels: the user computes it by hand into K3.
18. Some formula cells are unlocked (B8:B12, G8:G12, K8, J3, J4) and can be overwritten.

### 5.8 Cost against the MOSCA prices (illustrative)

Cost of one pallet at the limits of each class (100 × 120 cm footprint, real weight =
max weight) with the formulas above, against the MOSCA price:

| Class at its limits | Taxable kg | RKS cost | Lombardia price | Veneto price | Sicilia price |
|---|--:|--:|--:|--:|--:|
| MQP 150 kg, 60 cm | 150 | 9.75 | 15 | 30 | 55 |
| QP 300 kg, 100 cm | 300 | 16.50 | 22.7 | 46.6 | 65 |
| ELP 450 kg, 150 cm | 450 | 23.25 | 31.8 | 58.8 | 100 |
| HP 600 kg, 150 cm | 600 | 30.00 | 33.91 | 51.75 | 117.62 |
| ULP 350 kg, 240 cm | 576 (volumetric) | 28.92 | **27.9** | 67.2 | 125 |
| LP 750 kg, 240 cm | 750 | 36.75 | 41.04 | 66.03 | 182.19 |
| FP 1200 kg, 240 cm | 1200 | 57.00 | **55.2** | 73.26 | 193.59 |

The only prices below this cost are LOMBARDIA ULP and FP (and 1200 kg exceeds the
network's 1100 kg anyway). Whether Lombardia deliveries go through RKS at all, and whether
the same cost applies to the South and the islands, is (unknown) (PR-13): a flat
3,00 € + 0,045 €/kg to Sicily looks too low to be the whole cost (inferred).

## 6. Proposed pricing module (M2)

### 6.1 Principles

* The staff-typed amount stays the authority. The engine only **suggests**; nothing is
  priced, approved or e-mailed automatically.
* Price lists are **data with versions**, imported from the owner's Excel files; the code
  contains no prices.
* Every suggestion stores the list version and a full breakdown, so it can be explained
  later even after the list changes.
* Customer-specific prices are shown only to staff and to that customer after login;
  public estimates read only a list marked public (6.7).
* Each feature sits behind its flag from chapter 09, off by default.

### 6.2 Data model

**`pallet_class`** (catalogue; not confidential; seed from `data/pallet-classes.json`)

| Column | Type | Notes |
|---|---|---|
| `code` | varchar(8) PK | MQP, QP, ELP, HP, ULP, LP, FP |
| `name` | text | "Mini Quarter Pallet" … |
| `max_weight_kg` | numeric(7,2) | 150 … 1200 |
| `max_height_cm` | numeric(6,1) | 60 … 240 |
| `max_width_cm`, `max_length_cm` | numeric(6,1) | 100, 120 |
| `sort_order` | int | column order of the customer files |
| `active` | boolean | codes are never reused with new limits; new limits = new code |

**`price_list`**

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `name` | text | "Tariffario MOSCA 2024" |
| `scope` | enum `CUSTOMER`, `STANDARD`, `PUBLIC` | `CUSTOMER` = belongs to one customer; `STANDARD` = staff template assignable to many; `PUBLIC` = the only kind the public estimate may read |
| `owner_customer_id` | FK customer, null | required if and only if `scope = CUSTOMER` |
| `currency` | char(3) | `EUR` |
| `vat_included` | boolean not null | chosen at import (Q-14 default: false) |
| `notes` | text | |
| `created_at/by`, `archived_at` | | |

**`price_list_version`** (immutable once active)

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `price_list_id` | FK | |
| `version_no` | int | unique per list |
| `status` | enum `DRAFT`, `ACTIVE`, `RETIRED` | only `DRAFT` is editable |
| `valid_from`, `valid_to` | date, date null | no overlapping `ACTIVE` ranges per list (exclusion constraint) |
| `pv_ratio_kg_m3` | int null | 300 for MOSCA |
| `source_file_name`, `source_sha256`, `source_object_key` | text | original Excel in a private bucket |
| `import_id` | FK `price_list_import` | |
| `activated_by/at`, `change_note` | | |

**`price_list_rate`** (region × class)

| Column | Type | Notes |
|---|---|---|
| `version_id`, `region_code`, `class_code` | PK | |
| `price` | numeric(12,4) null | keeps `103.502`; null = "da preventivare" for that cell. Do not use the `numeric(10,2)` money type of chapter 10 here: it would turn 103.502 into 103.50 silently |
| `source_cell` | varchar(10) | e.g. `G19` |

**`price_list_transit_time`**: `version_id`, `region_code` (PK), `raw_text` (`24/48`),
`min_hours`, `max_hours`, `source_cell`.

**`price_list_exclusion`**: `id`, `version_id`, `kind` (`MINOR_ISLANDS`, `LAGOON`,
`PROVINCE`, `COUNTRY`, `CAP_SET`, `REGION`), `value` (e.g. `BZ`, `SM`, a CAP list),
`raw_text` ("ISOLE E LAGUNE BOLZANO E SAN MARINO DA PREVENTIVARE."), `source_cell`.

**`price_list_rule`** (the rule set): `id`, `version_id`, `type`, `params` (jsonb),
`effect` (`APPLIED` = the engine computes it, `BLOCKING` = the engine answers "manual",
`INFO` = shown only), `raw_text` (verbatim), `source_cell`, `confirmed_by/at`.

| `type` | `params` for MOSCA | `effect` |
|---|---|---|
| `INCLUDED_SERVICE` | `{"service": "SPONDA_IDRAULICA"}`, `{"service": "PREAVVISO_TELEFONICO"}` | APPLIED (price 0) |
| `SERVICE_PRICE` | none in MOSCA | APPLIED |
| `NO_SURCHARGE` | `{"fuel": true, "remoteAreas": true, "privateRecipient": true}` | APPLIED |
| `PV_RATIO` | `{"kgPerM3": 300, "appliesTo": "COLLI"}` | INFO until PR-07 |
| `MIN_TAXABLE_KG` | `{"kg": 3}` | INFO until PR-07 |
| `COLLI_LIMITS` | `{"maxKgPerCollo": 25, "palletiseFrom": 22, "regions": ["Lombardia", "Veneto"]}` | BLOCKING |
| `PICKUP_FEE` | `{"minKg": 10, "minEur": 6, "stepKg": 50, "stepEur": 9, "interpretation": null}` | BLOCKING until interpreted |
| `STORAGE_RATE` | `{"eurPerPalletPerDay": 7}` | INFO |
| `VOLUME_DISCOUNT` | `{"period": "MONTH", "base": "imponibile", "tiers": [{"over": 8000, "pct": 3}, {"over": 16000, "pct": 5}]}` | INFO (invoice level) |
| `PAYMENT_TERMS` | `{"options": [{"days": 45, "discountPct": 1}, {"days": 60}], "methods": ["Ri.Ba", "bonifico bancario"]}` | INFO |
| `NOTE` | tracking included, goods declaration, liability, storage address | INFO |

**`customer_price_list`** (assignment): `customer_id`, `price_list_id`, `valid_from`,
`valid_to`, `assigned_by/at`; no overlapping assignments per customer.

**`price_list_import`**: `id`, `file_name`, `sha256`, `object_key`, `status`
(`UPLOADED`, `PARSED`, `FAILED`, `COMMITTED`, `DISCARDED`), `report` (jsonb: errors,
warnings, proposed rules, diff), `committed_version_id`, `created_by/at`.

**`order_price`** (one row per order, history in the audit log)

| Column | Type | Notes |
|---|---|---|
| `order_id` | FK PK | |
| `price_list_version_id` | FK null | |
| `suggestion_status` | enum `OK`, `PARTIAL`, `MANUAL_REQUIRED`, `NO_LIST`, `DISABLED` | |
| `suggested_amount` | numeric(10,2) null | only when `OK` |
| `breakdown` | jsonb | as the E6 example in 3.4 |
| `final_amount` | numeric(10,2) | the staff amount = legacy `importoAssicurato`/`importo` |
| `vat_included` | boolean | copied from the list or set by staff |
| `override_reason` | text null | optional when `final ≠ suggested` |
| `decided_by/at` | | |
| `cost_model_version_id`, `cost_estimate` | FK null, numeric(10,2) null | staff-only (6.8) |

**`cost_model_version`**: `id`, `network` (`RKS`), `valid_from/to`, `params` (jsonb:
the constants of 5.3), `applicable_regions` (text[]; empty until the owner confirms),
`source_sha256`.

**Reference data:** `region` (stable code, the 20 names used by eta.js, site zone) and
`cap_special` (CAP → `MINOR_ISLAND` / `LAGOON`, seeded from eta.js, plus the Bolzano and
San Marino rules).

**Audit events** (platform audit log): list imported, version activated or retired, list
assigned or unassigned, suggestion computed, amount differs from suggestion, public list
published.

### 6.3 Excel import (layout of the MOSCA file)

1. Upload `.xlsx`; store the original in the private bucket with its SHA-256.
2. Find the header row: column A equals "REGIONI" (trimmed, case-insensitive; row 3 in
   MOSCA). The cells to its right up to "TEMPO DI RESA" are class codes.
3. Validate:
   * **errors** (block the commit): unknown class code; region name that matches none of
     the 20 regions after trimming, case-folding and hyphen/apostrophe normalisation;
     duplicate region; negative price; no header row.
   * **warnings** (shown, commit allowed): missing region or blank/text price (stored as
     "da preventivare"); more than 2 decimals (`103.502`); a class cheaper than a class it
     covers in the same row (Veneto, Liguria); an outlier against the other regions
     (e.g. Basilicata ULP); unparsable "TEMPO DI RESA"; contact or VAT lines that differ
     from the company data (`131130809067`, `32923969627`).
4. Notes below the table: keep every non-empty line verbatim with its cell; propose
   structured rules by pattern (e.g. `RAPPORTO P/V (\d+)`, `MINIMO TASSABILE (\d+)\s*KG`,
   `Deposito merce: €(\d+)`, `DA PREVENTIVARE`, `superiore ai €\.?([\d.]+).*?del (\d+)%`).
   Staff confirm or edit each proposed rule; nothing is parsed silently.
5. Preview: the grid with errors/warnings highlighted, the proposed rules, the class that
   would be charged for a few sample pallets per region, and the diff against the
   current version (changed cells, % change).
6. Commit creates a `DRAFT` version; activation asks `valid_from`, closes the previous
   version's `valid_to`, and is audited. Images in the file are ignored (the classes come
   from the catalogue).

### 6.4 Price computation

```text
suggest(order):
  if not flag("pricing.suggestedAmount"): return DISABLED
  version = activeVersion(order.customer, order.createdAt)    # assignment + version valid on that date
  if version is null: return NO_LIST
  dest = geo.resolve(order.recipient.cap)                     # region + special kind
  if dest is null: return MANUAL_REQUIRED("CAP non riconosciuto")
  if version.exclusions.match(dest): return MANUAL_REQUIRED("Zona da preventivare")
  lines = []
  for row in order.lines:
    if row.kind == COLLI:
      lines += colliPrice(version, row, dest)                 # MANUAL until PR-07 is answered
      continue
    fitting = classes where row.weightKg <= c.maxWeightKg
                        and row.heightCm <= c.maxHeightCm
                        and footprintFits(row, c)             # 100 x 120, rotation allowed
    priced  = [(c, version.rate(dest.region, c)) for c in fitting if rate is not null]
    if priced is empty:
      lines += MANUAL(fitting is empty ? "Fuori classe" : "Classe da preventivare")
      continue
    best = min(priced, by price, then c.maxWeightKg, then c.maxHeightCm)
    unit = roundHalfUp(best.price, 2)                         # policy PR-06
    lines += OK(best.class, unit, row.quantity, unit * row.quantity, alternatives = priced)
  services = [version.servicePrice(s) for s in order.services]   # included -> 0; unknown -> MANUAL
  status = all OK ? OK : (any OK ? PARTIAL : MANUAL_REQUIRED)
  amount = status == OK ? sum(line totals) + sum(service prices) : null
  return { status, amount, lines, transit: version.transit(dest.region), versionId }
```

* Monthly discounts, payment terms and storage are not part of an order price; they belong
  to invoicing, which is out of scope (chapter 09 backlog).
* A `PARTIAL` result never prefills the amount; it shows the priced lines for reference.

### 6.5 Manual amount and suggested amount together (ENH-02)

* The approval dialog keeps the required "Importo Totale (per cliente)" field exactly as
  in M1.
* With `pricing.suggestedAmount` on, the field is prefilled when the status is `OK`, and a
  panel shows the breakdown (class, alternatives, unit price, quantity, list and version,
  transit time). For `MANUAL_REQUIRED` it shows the reason and leaves the field empty.
* Staff may change the value. On approval store `suggested_amount`, `final_amount`, the
  breakdown and the version; if they differ, ask an optional reason and write an audit
  event.
* The suggestion is never sent to the customer; only the approved amount is, as today.

### 6.6 Indicative price for a logged-in customer (ENH-03)

Same engine, same list, shown while the customer builds an order
("indicativo, confermato all'approvazione", chapter 09). Only after a real login: the legacy portal has no
authentication (chapter 01), so an estimate on an unauthenticated page would reveal a
customer's prices to anyone who knows the e-mail address.

### 6.7 Public indicative estimate (ENH-04)

* Reads **only** versions of a list with `scope = PUBLIC`; enforce it in the service and
  with a test. Customer lists are never used, not even as a source of averages at run
  time. No public list exists today; the owner creates and approves it (Q-14).
* Inputs from the quote wizard: `consegna_cap`, `tipo_pallet` (`eur`, `industriale`,
  `mezzo`, `quarto`, `altro`), `numero`, `peso` (total), `altezza` (optional)
  (ls-home@fe4b056:netlify/functions/form.mjs:26, 80-83, 92-94).
* Per-pallet weight = `peso / numero` (approximation, inferred). `altro` → no estimate.
  Excluded areas → "Su richiesta". Without `altezza`, compute the cheapest fitting class
  for each height limit (60, 100, 150, 240 cm) and take the lowest (`P_low`) and the
  highest (`P_high`) of those prices; with `altezza`, `P_low = P_high` = the engine price.
* Show a range, never a single price: from `P_low` to `P_high × (1 + band)`, rounded to
  whole euros; the band is an owner setting. Copy to be approved by the owner; the blog sentence
  "Per questo non pubblichiamo un listino valido per tutti"
  (ls-home@fe4b056:public/blog/quanto-costa-spedire-un-pallet/index.html:165)
  must be revised before the flag is turned on.
* Flag `pricing.publicEstimate`, off by default; no change to the wizard's fields,
  validation or e-mail (PUB-06 parity).

### 6.8 Staff-only margin view (ENH-11)

* Cost per order from `cost_model_version` with the model of 5.4, bugs fixed: every row is
  checked for long goods (> 220 cm) and limits (1100 kg, 240 cm); the 3 kg minimum is
  applied; hub handling is shown as a separate line; parcels ≤ 29.9 kg and limit
  violations give "costo non calcolabile" instead of a number.
* Expected values after the fix: R1 = 21.00, R2 = 28.92, R8 = 43.38 (1.5 × 28.92), R9 =
  not computable (1150 kg > 1100 kg).
* Loading the cost model: ADMIN only, in `/gestione/impostazioni/`, behind
  `pricing.marginView`. Either a form for the constants of 5.3 with a validity date, or an
  upload of the RKS calculator read by cell address as listed in `data/rks-cost-model.json`
  (inputs, constants), with the original stored in the private bucket with its SHA-256.
  Saving creates a new `cost_model_version` and writes the audit log. Never load it from
  `docs/brief/data/` in a migration, seed or startup loader. Tests use synthetic constants,
  plus R1–R13 as documented worked examples.
* Margin = `final_amount − cost`. Visible to the admin role only; never in customer views,
  exports or e-mails. Shows "non confermato" for regions outside
  `applicable_regions` (empty until PR-13 is answered).

### 6.9 Delivery times (ENH-10)

One source: the transit times of the price list that applies (customer list in the
customer area and at approval; public list for the public site). Until a public list
exists, the public CAP widget keeps eta.js's zones unchanged (M1 parity). Treating Bolzano
and San Marino as "Su richiesta" on the site is a copy change that needs the owner.

### 6.10 Tests

* Unit: E1–E9 (section 3.4), plus a property test: the chosen class always fits and no
  fitting class is cheaper.
* Import: a synthetic workbook in the MOSCA layout (changed numbers) covering every error
  and warning of 6.3.
* Cost model: R1–R13 with the legacy results documented and the fixed results asserted.
* Security: public endpoints never return data from non-`PUBLIC` lists; customer estimates
  require a session.

## 7. Confidentiality

* The prices of `MOSCA!` and the costs of `RKS!` are commercial secrets: a competitor or
  another customer must never see them.
* The new repository must be **private**, with access limited to the people working on
  it; no public forks, gists, issue attachments or screenshots of these files.
* `docs/brief/data/*.json` is reference material for the build. Production price lists and
  cost models are loaded through the import features of §6.3 (price lists) and §6.8 (cost
  model) from the owner's files, kept in a
  private, encrypted bucket readable only by the app; they are not seeded by migrations
  or committed as fixtures. Tests use synthetic lists, except the worked examples of this
  chapter (as the repository's `CLAUDE.md` and chapter 10 §6 allow).
* Never put customer-specific prices or costs in public HTML, JavaScript bundles, public
  APIs, logs, analytics events or error messages.
* The MOSCA file also holds personal contact data (PEC, a second phone number): treat it
  as personal data.

## 8. Open questions on pricing

Summarised in chapter 11 as Q-32 (Q-05 and Q-14 there cover the meaning of the amount,
VAT, the public estimate and delivery times). The IDs are `PR-xx` so they cannot be confused
with the production probes `P-01…P-07` of chapter 02 §11.2.

| ID | Question | Recommended default |
|---|---|---|
| PR-01 | When a pallet fits several classes, do you charge the cheapest one (e.g. Veneto 400 kg/140 cm → HP 51,75 € instead of ELP 58,80 €)? | Yes, cheapest fitting class. |
| PR-02 | Are the class limits inclusive? Does the height include the pallet base? Is 100 × 120 cm a hard footprint limit (EPAL fits; rotation allowed)? The backend's unused `PalletEnum` says HP 150 kg and ULP/LP/FP 150 cm: which is right? | Inclusive; total height; 100 × 120 with rotation; the limits of the image (§3.1). |
| PR-03 | Is the MOSCA list still valid, from which date, until when? | Import it as valid from 2024-01-01 (the year in the file name), open-ended, and confirm with you before activating it. |
| PR-04 | Are Veneto HP 51.75 < ELP 58.8, Veneto/Liguria LP < ULP and Basilicata ULP 65.9 intended? | Keep as written; flag at import. |
| PR-05 | Is the weight for the class the real weight only, or also volumetric (P/V 300) for pallets? | Real weight only. |
| PR-06 | Rounding of prices with three decimals (103.502): unit price first (5 × 103,50 = 517,50) or the exact total (517,51)? | Unit price rounded half-up first. |
| PR-07 | Parcels: what is the freight price; what does "Presa in carico minimo 10kg = 6€ ogni 50KG=9€" mean; how does the 22-parcel rule work; is "Lombardia, Veneto" the destination; is P/V 300 for parcels only? | No automatic parcel price until answered. |
| PR-08 | Exactly which areas are "da preventivare": which islands and lagoons; the whole province of Bolzano (CAP 39…); San Marino CAP 47890–47899? | eta.js islands and lagoon + CAP 39… + 47890–47899. |
| PR-09 | Monthly discounts: is 5% instead of 3% or on top; does the total include storage? Who applies them? | 5% replaces 3%; transport only; applied on the invoice outside the app. |
| PR-10 | Accessory services not in the list (appointment, insurance, cash on delivery): price per customer? | Manual when selected and not included. |
| PR-11 | Pallets over 1200 kg or 240 cm, and pallets over the network's 1100 kg: how are they priced and shipped? | Manual quote; warn staff above 1100 kg. |
| PR-12 | Contacts in the list: may future lists fix the VAT number and phone typos? Whose are the PEC (MOSCA!A83) and the second mobile number (MOSCA!A84)? | Use the company data of chapter 01. |
| PR-13 | RKS: does the cost apply to all destinations or only "NORD"; is there a "SUD" tariff; do hub handling 1,80 €/0,15 € apply; which deliveries go through RKS (also Lombardia)? Is the 2024 calculator still current? | Margin view off until answered. |
| PR-14 | Storage 7 € per pallet per day: free days, day counting, billed through the app? | Information only. |
