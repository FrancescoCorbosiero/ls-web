# 0035 · Office tools: bulk tracking, «Oggi», free-text order search, CSV exports (ENH-15, ENH-08)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-15 (flag `orders.bulkTracking`), ENH-08 (flags `admin.dashboard`, `admin.exports`); touches ADM-05, ADM-06, ADM-03, EML-09, ENH-05, NFR-03 · **Owner:** M2.1 scope of 2026-10-26, item 3

## Context

The owner asked for «office tools: ENH-15 (flag orders.bulkTracking): set one tracking state on
many codes at once (paste a list); a «Oggi» list on the Panoramica: to approve, to pick up, in
transit for more than N days; free-text search on orders (recipient, city, transport document,
customer); ENH-08 exports (flag admin.exports): CSV of the order and customer lists, cells
written as text». Chapter 09 puts the home counters and the exports under ENH-08 (two flags) and
the bulk change under ENH-15; chapter 10 §4 sets the rule for spreadsheet output. «Keep it light:
a small CRM for one office.»

## Decision

### ENH-15 `orders.bulkTracking` — one state on many codes

* On «Tracking» (`/gestione/tracking/`), under the single-code form (ADM-06, unchanged), «Aggiorna
  molte spedizioni»: a text area for the codes and the state. Codes are split on spaces, new
  lines, commas and semicolons, each kept once ignoring case, **at most 200** per request
  (`BackofficeToolsController.BULK_MAX_CODES`, a guard, not a business rule).
* **Two steps, no JavaScript needed:** «Controlla l'elenco» (`POST /gestione/tracking/in-blocco`,
  `action=preview`) shows the confirmed orders found with their customer, recipient and current
  state, and the codes that name none («Codici non trovati o non ancora confermati»: unknown, or
  the temporary code of an order not yet confirmed — tracking is for confirmed orders only, ADR
  0008); «Imposta «stato» su N spedizioni» (`action=apply`, N the orders not yet in that state; no
  button when there is none) reads the same list again and applies. Nothing changes before the
  confirmation, and the state has no default («Scegli lo stato»).
* **One transaction, one time, the rules of a single change** (`OrderService.setTrackingStates`,
  which shares `applyTrackingState` with `setTrackingState`): for each order whose state changes,
  one `tracking_event` with source **`BULK`** and the staff user, the audit entry
  `order.tracking` (with `"source": "BULK"`), EML-09 at CONSEGNATO and the ENH-05 e-mail when
  `tracking.notifyCustomer` is on; an order already in the state is counted as unchanged and gets
  no event and no e-mail. An order that is no longer confirmed between the two steps refuses the
  whole change with a message. The flash says how many changed, how many were already in the
  state and which codes were not found.
* Every staff role may use it (as ADM-06); CSRF as every form.

### ENH-08 `admin.dashboard` — «Oggi» and the free-text order search

* **«Oggi»** on the Panoramica (`BackofficeHome.today`), above the latest orders, three groups,
  oldest first, the first 8 rows each and the total («e altri N», linking the full list):
  * **Da approvare** — the orders waiting for approval (the ADM-04 queue), oldest submission first;
  * **Da ritirare** — confirmed orders still «Presa in carico» (or «Non gestito»), longest
    confirmed first;
  * **In viaggio da più di N giorni** — confirmed orders in TRANSITO, TERMINAL_DEST or ASSEGNATO
    confirmed before the start of the Europe/Rome day N days ago (the approval day is the pick-up
    day, as in ADR 0034). **N = 3** by default, `ls.backoffice.today-transit-days` (1–60).
* **Free-text search** in «Ordini»: the same field and parameter as the M1 code search
  (`codice`), so links and bookmarks keep working; with the flag on it is labelled «Cerca»
  («Codice, destinatario, città, DDT o cliente») and matches, ignoring case, any part of the
  definitive or temporary code, the recipient's name or city, the transport document or the
  customer's company name (the sender snapshot of the order). It combines with the other filters
  and with the CSV export. Flag off: codes only, as M1.
* The two share a flag because chapter 09 groups the home's dashboard under `admin.dashboard`
  and the search is how the office reaches what «Oggi» shows; a separate flag would only add a
  toggle.

### ENH-08 `admin.exports` — CSV of «Ordini» and «Registrati»

* «Esporta CSV» on «Ordini» (`GET /gestione/ordini/esporta`, the list's filters, every page, 22
  columns: codes, dates, customer, DDT, packages, recipient with address and contacts, weight,
  volume, amount, accessory service, state, tracking) and on «Registrati»
  (`GET /gestione/clienti/esporta`, the list's search, 13 columns: company, e-mail, address,
  phone, VAT and tax codes, e-mail confirmed, state, registration date). The file is
  `ordini-AAAA-MM-GG.csv` / `clienti-AAAA-MM-GG.csv` (the Europe/Rome day), `Content-Disposition:
  attachment`.
* **Format** (`backoffice.CsvFile`): UTF-8 with a byte-order mark and `;` as separator, as Excel
  in Italian opens it with a double click; CRLF; every cell quoted; dates `gg/mm/aaaa hh:mm`
  (Europe/Rome) and numbers with the decimal comma, as on screen. **Cells as text** (chapter 10
  §4): a value starting with `=`, `+`, `-`, `@`, a tab or a carriage return gets a leading
  apostrophe, so no value is ever read as a formula (tested with `=HYPERLINK(`).
* **Only CSV**, not XLSX: it opens in every spreadsheet and needs no library; XLSX can follow if
  the office asks. Only the two lists the owner named; the other lists (approval queues, audit)
  are not exported.
* **At most 10,000 rows** per file (`EXPORT_MAX_ROWS`), read in pages of 500: more than the office
  keeps, a guard against a runaway query.
* Every staff role may export (they already see the same rows); **every export is audited**
  (`orders.export`, `customers.export`, with the row count — personal data leaves the system,
  NFR-03). An export is a GET with no side effect but the audit row.

### Flags off

`BackofficeToolsController` answers 404 for the export and bulk routes while their flag is off;
`BackofficeHome`, `BackofficeOrdersController` and `BackofficeCustomersController` read the
flags for the «Oggi» panel, the search label and the export links. Off, the back office is the
M1 one (`OfficeToolsOffIT`). The three flags are «Disponibile» in «Impostazioni».

## Consequences

* A morning's round of tracking updates is one paste and one confirmation, with the same e-mails
  and audit as one change at a time; a wrong code is reported, never guessed.
* «Oggi» needs no new table nor job: three queries on `shipment_order` per home view, bounded by
  8 rows each.
* An export is a copy of personal data outside the system: the audit row says who took it and
  how many rows; the CSV itself is the office's to keep safe.

## References

Chapter 09 ENH-08, ENH-15, ADM-05, ADM-06; chapter 10 §4 (spreadsheet output); ADR 0008
(tracking events), 0017 (lists), 0025 (flags), 0029 (private areas), 0034 (ENH-05). Code:
`backoffice.BackofficeToolsController`, `backoffice.CsvFile`, `backoffice.OrderFilters`,
`BackofficeHome.today`, `OrderService.approvedByCodes` / `setTrackingStates` / `toPickUp` /
`travellingSince`, `OrderSearch.text`. Tests: `OfficeToolsTest`, `OfficeToolsIT`,
`OfficeToolsOffIT`, `e2e/tests/m2.spec.ts` [ENH-15] [ENH-08].
