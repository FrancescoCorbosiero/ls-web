# 0016 · Report delivery through authenticated downloads; report semantics

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** ADM-09, EML-07 (chapter 09) · **Questions:** Q-08 (recipients, delivery, allow-list) · **Findings:** CMS-06, CMS-22, SEC-03, SEC-05 (chapter 04 §8, 12a §2.7–2.8, 12c §3.5)

## Context

The legacy report (`POST /mono/cms/order/report`, 12c §3) selected one sender's `REGISTERED`
orders by creation date in UTC days, with no `from ≤ to` check, and e-mailed **public,
never-expiring** links (`/mono/report`, `/mono/download-report`) to the office and, on request,
to any address typed in the form; the endpoint itself needed no token. The PDF (iText 2.1.7,
A4 landscape) printed a partner network's billing header, the customer block «I tuoi dati» and
13 columns, with Java doubles for numbers and an odd «Dt/Cons.» column (12c §3.4).

## Decision

* **Selection.** `OrderService.approvedForReport(customerId, from, to, onlyDelivered)`: the
  **approved** orders of **one customer**, by **submission date** between `from` and `to`
  **inclusive, in Europe/Rome days** (`ReportPeriod`: `from ≤ to`, neither after today),
  optionally only those whose tracking is `CONSEGNATO`. Never another customer's order; pending
  and declined orders never appear.
* **Recipients (Q-08).** The office address (`ls.business.office-email`) **always**; the customer
  the report is about **only if staff tick «Invia anche al cliente»** (and
  `ls.reports.customer-can-receive` allows it); any other typed address must be on the
  **ADMIN allow-list** (`report_recipient`, V012, managed in `/gestione/impostazioni/`) or in
  `ls.reports.recipients.allow-list`, otherwise the **whole request is refused** and nothing is
  stored or queued.
* **Delivery.** One `report_run` row (V007) per request; one EML-07 per recipient, through the
  outbox. The e-mail carries a **link to an authenticated download**, never an attachment and
  never a public URL: `/gestione/report/{id}/download` for the office and allow-listed
  addresses (staff session), `/area-clienti/report/{id}/download` for the customer (its own
  session only: another customer gets 403, an anonymous visitor the sign-in redirect). The PDF is
  **regenerated at each download** from the stored parameters; no file is kept.
* **Audit.** `report.send` with the customer id, the range, the filter, the row count and the
  recipient count in the log row; the addresses only in `audit_log_detail`.
* **PDF (OpenPDF 3.0.5, A4 landscape).** The 13 legacy headers verbatim («Codice», «Codice
  Tras.», «Dt/Sped.», «Dt/Cons.», «Servizio», «Destinazione», «Cliente», «Cap», «Città»,
  «Colli», «Peso», «Volume», «Importo»), the «I tuoi dati» block (company name, address,
  province, city, CAP, e-mail), «-» for a missing value, `indirizzo(città)` and
  `città(provincia)` as the legacy. Deliberate differences: **no partner-network header**
  (confidential, 12c §3.4), the period printed under the title, the table header repeated on
  every page, relative column widths, **Italian numbers** (`1.236,00`), **«Dt/Cons.» = the
  delivery date** (the time the order became `CONSEGNATO`) or «-» (the legacy printed the last
  update time or a re-stamped transit time, never the delivery). Weight and volume are the
  stored totals; «Colli» = the typed package count, else the number of generic pallets. No
  internal notes, no margins. File name `report-ordini-{dd_MM_yyyy}_{dd_MM_yyyy}.pdf` (the
  legacy put the local part of an e-mail in the name).
* **EML-07.** Subject «Report ordini PDF», «Date di riferimento: {dal} / {al}», the filter in
  words, «Puoi scaricare il report qui…», button «Scarica PDF»; the office copy adds the
  customer's name, e-mail and the row count.

## Consequences

* A forwarded or logged e-mail no longer gives access to anyone's data (SEC-03, SEC-05); a
  report of customer A can never reach customer B.
* Reports around midnight may differ from the legacy by one day (UTC vs Rome); accepted.
* The office must add external addresses to the allow-list before using them (CMS-06 fixed:
  the typed address is checked on every click).
