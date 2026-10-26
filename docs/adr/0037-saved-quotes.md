# 0037 · Saved quotes: from a website request to the customer's order (ENH-20)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-20 (flag `quotes.saved`); touches ENH-06, CUS-04, ADM-04, EML (new EML-11), NFR-03 · **Owner:** request of 2026-10-26 («procedi con quelli che puoi fare ora»)

## Context

Chapter 09: «From a lead (ENH-06) staff prepare a saved quote (lines, amount, validity); the
customer gets an unguessable link and a PDF and converts it into a pending order after sign-in»;
the parity matrix asks for «conversion into a pending order only after sign-in and before
expiry». The plan named a `leads` module for the inbox and the quotes; the inbox stayed with the
`lead` table in `site.forms` (ADR 0036). Customers sign in with e-mail and password or the
e-mailed link (ADR 0032); the sign-in does not return to the page that asked for it.

## Decision

* **Module `quotes`** (`it.logisticasanchez.web.quotes`, the plan's `leads` module under a clearer
  name) owns the new tables of **V018**: `quote` (request, customer, SHA-256 of the link token,
  status, amount, last valid day, accessory service, destination city and CAP, note, staff, dates,
  the order made from it) and `quote_line` (the lines, as order lines). V018 also lets
  `email_outbox.kind` accept `EML-10`…`EML-19`; the quote e-mail is **EML-11**.
* **Who gets a quote.** A quote is for a customer who may order (ACTIVE) and is prepared from an
  **open** request (NEW or CONTACTED) **linked** to that customer (ADR 0036: created from it, or
  «Collega la richiesta al cliente»): the person ordering is the one the quote was sent to, signed
  in. Preparing a quote moves a NEW request to CONTACTED. A request may have several quotes.
* **«Prepara preventivo»** (`/gestione/richieste/{id}/preventivo`, every staff role, with
  `leads.inbox` and `quotes.saved` on): the order form's line rows (OP-R4 rules, filled from the
  request as «Crea ordine» does), the amount (euro, comma or point, > 0), the last valid day (from
  today to a year; 30 days proposed), the accessory service, the destination and a note for the
  customer (≤ 1,000 characters: conditions, VAT, times — printed on the page and the PDF; the app
  states nothing about VAT). «Invia il preventivo» saves it as SENT and queues EML-11 to the
  customer's sign-in address. The request page lists its quotes with their state («Inviato»,
  «Ordinato», «Ritirato», «Scaduto»), the PDF and «Ritira» (SENT → WITHDRAWN).
* **The link.** `/preventivi/{token}`: 32 random bytes in URL-safe Base64; only their SHA-256 is
  stored. The page and `/preventivi/{token}/pdf` are public to whoever holds the link (the
  customer may forward it), never indexed (`X-Robots-Tag`, the private layout's meta) nor cached
  (`no-store`); they show the reference `PRV-00007`, validity, route, lines, service, amount, note
  and «Ordina il preventivo» while the quote is open, or why it is not (ordered, withdrawn,
  expired). The PDF (OpenPDF, A4) carries the same and the customer's company and address.
* **Ordering it.** `/area-clienti/preventivi/{id}/ordina` behind the customer session, only for
  the quote's customer (anything else is a 404): the quote's lines and service as priced (read
  only — the server uses the stored ones, whatever the request carries), then the transport
  document (required, Q-24), packages, notes and the recipient (destination city and CAP filled
  in). It makes a normal **pending order** through `OrderService.submit` (CUS-04 rules and
  e-mails); in the same transaction the quote becomes ACCEPTED (only from SENT and before the end
  of its last day, Europe/Rome) and the request WON (actor CUSTOMER). A second submission is
  refused with the order already made. Since the sign-in lands on «I miei ordini», that page lists
  the quotes the customer can still order (`QuoteNotices`, an interceptor of this module, so
  `orders` knows nothing of quotes).
* **Approval.** The staff order page shows «Dal preventivo PRV-…» and «Approva» proposes the
  quote's amount (still to confirm, ADM-04).
* **Privacy (NFR-03).** The export lists the customer's quotes; the erasure withdraws the open
  ones and empties notes and destinations (`PrivacyService` → `QuoteService.erase`).
* Audit: `quote.create`, `quote.withdraw` (staff), `quote.accept` (customer), `lead.convert`.

### Flag off

Every quote route answers 404 (`QuotePageController`, `QuoteAcceptController`,
`BackofficeQuotesController`), the request page has no «Prepara preventivo» nor quote list, «I miei
ordini» lists no quote (`SavedQuotesOffIT`). The flag is «Disponibile» in «Impostazioni».

## Consequences

* A request can end with a priced proposal the customer turns into an order without retyping the
  goods; the office still approves the order with code and amount.
* The quote's price is not a price list (ENH-01…04 stay with the owner): staff type it.
* Whoever holds a link can read the quote; only the customer can order it.

## References

Chapter 09 ENH-20, ENH-06, CUS-04, ADM-04; ADR 0015 (outbox), 0024 (privacy), 0025 (flags), 0032
(sign-in), 0036 (requests). Code: `quotes.*` (`QuoteService`, `QuoteRepository`, `QuoteTokens`,
`QuotePdf`, `QuoteMails`, `QuotePageController`, `QuoteAcceptController`, `QuoteNotices`),
`backoffice.BackofficeQuotesController`, `OrderForm.validateLines`; V018. Tests: `QuoteTokensTest`,
`SavedQuotesIT`, `SavedQuotesOffIT`, `e2e/tests/m2.spec.ts` [ENH-20].
