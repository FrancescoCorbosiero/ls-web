# 0017 · Back-office screens: pages instead of dialogs, lists, pagination, detail, UX conventions

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** ADM-02, ADM-03, ADM-04, ADM-05, ADM-06, ADM-07, ADM-08 (chapter 09) · **Questions:** Q-17 (roles), Q-26 (notes), Q-28 (approval of verified customers) · **Findings:** CMS-06, CMS-07, CMS-12, CMS-15…CMS-19, CMS-23…CMS-26 (chapter 04 §8)

## Context

The legacy CMS was a single page of tabs and modal dialogs (chapter 04 §2–§4): actions keyed by
e-mail and row position, duplicated requests, confirmations without the subject's name, a
return to the first tab after every action, icon-only buttons, 14-column tables unusable on a
phone, full lists limited by the backend to the last six months (CMS-26), a tracking dialog with
an editable code and a stale menu (CMS-12), no search, no pagination, no audit.

## Decision

* **Pages, not dialogs.** The `backoffice` module serves `/gestione/**` as server-rendered pages
  on the private layout, without JavaScript: `clienti/in-approvazione/`, `clienti/`,
  `clienti/{id}/`, `ordini/in-approvazione/`, `ordini/`, `ordini/{id}/`, `tracking/`,
  `report/`, `impostazioni/` (ADMIN: flags, report recipients, `utenti/`, `privacy/`). The home
  lists the sections as cards. Every page shows the section navigation («In Approvazione»,
  «Registrati», «Ordini da approvare», «Ordini», «Tracking», «Report», «Impostazioni» for
  ADMIN only).
* **Copy.** The chapter 04 §7 wording is reused: tabs, «Approva ordine», «Imposta tracking»,
  «Aggiorna note», «Dettaglio», «Sei sicuro di voler procedere?», «Conferma» / «Annulla» /
  «Imposta stato» / «Invia Report», the four empty states, «Email confermata» «SI»/«NO»,
  «Importo Totale (per cliente)», the nine customer columns and the 13 order columns in the
  legacy order; temporary codes shown as «-». New texts (hints, flash messages, filters) are
  listed for the owner's review in the Phase 1 log.
* **Actions by id, one request, named confirmation.** Every action is a `POST` keyed by the
  internal id with the CSRF token; approve/decline of a customer and decline of an order go
  through a confirmation page that names the subject and takes the optional reason; approval
  of an order is its own form («Codice», «Importo Totale (per cliente)»). After an action the
  user returns to the list they came from with a flash message naming what happened; a rule
  refused by a domain service is shown on the page that sent the form, with the typed values
  kept. Idempotency is the domain services' (ADR 0007, 0008).
* **Lists.** «In Approvazione» lists `PENDING_APPROVAL` customers only (PD-06 seam
  `ls.backoffice.show-unverified-customers`) and `PENDING` orders, oldest first, as the legacy.
  «Registrati» and «Ordini» are **newest first, paginated server-side (25 rows)** with **no time
  window**; customers are searched on name, e-mail or city; orders are filtered by state
  (default `APPROVED`, the legacy «registered»), tracking state, customer and inclusive
  submission dates (Europe/Rome) and searched by code or temporary code (`OrderSearch`,
  `OrderService.search`). Anonymised customers (ADR 0024) never appear.
* **Detail.** Labelled sections «Dettaglio Spedizione», «Dettaglio Colli», «Pallets» (one row
  per line with its quantity, not per physical unit — CMS-16), labels instead of raw keys,
  «-» for missing values, the sender linked to its customer page; the customer's own note is
  read-only next to the staff «Note interne» (ADR 0007, Q-26); the tracking form shows the
  current state preselected and the history below.
* **Tracking.** Both from the order page and from `/gestione/tracking/` by code: approved orders
  only, any of the six states after any other (ADR 0008); no confirmation on backward moves in
  M1 (the history keeps every change).
* **Roles.** OPERATOR reaches every customer, order, tracking and report screen; `impostazioni/`
  and below are ADMIN only (route rules of ADR 0002 plus `@PreAuthorize` on the services).
* **Escaping.** Templates auto-escape every value; hostile names are rendered inert (CMS-04,
  ADR 0018).

## Consequences

* The office sees every order, not the last six months; the counts, exports and dashboards of
  ENH-08 stay behind their flags.
* HTMX is not used yet; the pages work with plain forms and can be enhanced later without
  changing the controllers.
