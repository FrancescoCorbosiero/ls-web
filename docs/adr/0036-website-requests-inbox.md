# 0036 · The website requests in the back office (ENH-06)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-06 (flag `leads.inbox`); touches PUB-11, EML-08, ADM-11, ADM-12, NFR-02, NFR-03 · **Owner:** request of 2026-10-26 («procedi con quelli che puoi fare ora»)

## Context

Since M1 every quote and contact request of the public site is stored as a `lead` row and
e-mailed to the office (PUB-11, EML-08, V006); chapter 08 §2.4 gives its states
(`NEW → CONTACTED → WON | LOST`; `NEW | CONTACTED → SPAM`) and chapter 09 asks for the inbox:
«Quote and contact requests listed in the back office with a status (new, contacted, won, lost)
and "convert to customer / order draft"». The plan named a `leads` module for the inbox and the
saved quotes (ENH-20); the `lead` table belongs to `site`, which writes it.

## Decision

* **Where the rules live.** The `lead` table stays with its writer, `site.forms`: `LeadService`
  gains the inbox queries, the state machine (`LeadStatus`, exactly chapter 08 §2.4: WON, LOST and
  SPAM are final), the link to a customer and the conversion into an order, each change audited
  (`lead.status`, `lead.customer`, `lead.convert`: states and ids only, never the person's data).
  The screens are in `backoffice`, which checks the flag (one entry point per screen). No
  migration: V006 already has `status`, `customer_id`, `converted_order_id`.
* **«Richieste»** (`/gestione/richieste/`, every staff role): newest first, tabs by state
  («Nuove» by default, then «Contattate», «Vinte», «Perse», «Spam», «Tutte»), a search on name,
  company, e-mail, phone, cities and subject, 25 per page; the navigation shows «Richieste» with
  the number of new ones. An anonymised request (retention or erasure, NFR-03) is never listed
  nor opened. The page of a request shows what the form sent with the labels of the e-mail
  EML-08, `mailto:` and `tel:` links, and one button per allowed move.
* **The customer of a request.** A request is linked to a customer when staff create the
  customer from it, or with «Collega la richiesta al cliente» when a customer is registered with
  the same e-mail (the page says so). «Crea cliente» opens «Nuovo cliente» (ADM-12) filled with
  the company (else the name), e-mail, phone and the pick-up city and CAP; address and province
  are typed. Saving it links the request and returns to it.
* **«Crea ordine»** (an open request whose customer may order) opens «Nuovo ordine» (ADM-11) for
  that customer filled from the request (`LeadDrafts`): one line from the pallet format (EPAL
  120×80, industrial 120×100, half 80×60, quarter 60×40, «altro» with no size), the height, the
  quantity when whole and the weight of one unit (total ÷ quantity, to the kg); «Sponda
  idraulica» as the accessory service, the only extra of the public form with a service of the
  same name; the goods as notes; the delivery city and CAP for the recipient, whose name, address
  and province staff type with the transport document. The order is a normal pending order (ADM-11
  rules, no e-mail); saving it marks the request **WON** and points it to the order — the one move
  from NEW straight to WON, since the order is the proof of contact.
* **Privacy (NFR-03).** A request linked to a customer is exported with the customer's data and
  anonymised with them, even when it was sent from another e-mail
  (`LeadService.exportFor`, `anonymiseByCustomer`, called by `PrivacyService`).
* **`dev-seed`** adds three synthetic requests (a quote and a message, new; a quote contacted)
  through the form's own validation, so the inbox can be tried locally.

### Flag off

`BackofficeLeadsController` answers 404, the navigation has no «Richieste», «Nuovo cliente» and
«Nuovo ordine» ignore `?richiesta=` (`LeadsInboxOffIT`): requests are stored and e-mailed as in
M1. The flag is «Disponibile» in «Impostazioni».

## Consequences

* The office works the website requests where it works the orders, and each one ends with a state
  and, when won, its order; the e-mail EML-08 stays the first notice.
* A request never becomes a customer or an order by itself: staff decide and type what the
  public form does not ask (address, recipient, transport document).
* Saved quotes (ENH-20) start from the page of a request.
* The back-office navigation gains «Richieste»; on a desktop its items are a little tighter and
  wrap instead of scrolling, so «Impostazioni» stays in sight (`app.css`).

## References

Chapter 08 §2.3 `lead`, §2.4; chapter 09 ENH-06, PUB-11, ADM-11, ADM-12; ADR 0024 (privacy), 0025
(flags), 0028 (staff forms). Code: `site.forms.LeadService`, `LeadStatus`, `LeadView`,
`LeadRepository`; `privacy.PrivacyService`; `backoffice.BackofficeLeadsController`, `LeadDrafts`; `OrderForm.prefilled`;
`DevSeedCommand`. Tests: `LeadStatusTest`, `LeadDraftsTest`, `LeadsInboxIT`, `LeadsInboxOffIT`,
`DevSeedCommandIT`, `e2e/tests/m2.spec.ts` [ENH-06].
