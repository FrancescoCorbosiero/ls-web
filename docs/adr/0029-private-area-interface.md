# 0029 · Private-area interface: one design layer for the customer area and the back office, the one-page order form

* **Status:** Accepted
* **Date:** 2026-10-25
* **Requirement IDs:** CUS-03, CUS-04, CUS-05, CUS-06, CUS-07, ADM-01…ADM-12, NFR-01, NFR-02 (chapter 09) · **Questions:** Q-12, Q-24, Q-35 · **Findings:** OP-01…OP-07, OP-16 (chapter 03 §10), CMS-15…CMS-19 (chapter 04 §8)

## Context

Phase 1 built the customer area and the back office on `main.css`, the public site's
stylesheet, with the bare minimum on top: plain tables, unstyled forms, a three-page order
wizard (ADR 0006: lines, then recipient, then summary) in which each pallet line was added
through its own small form. On 2026-10-25 the owner wrote: «the dashboards (admin and
customers) are definitely lacking UX/UI elements. But they are working solid as I was able to
test so good job! It's just matter of some CSS and some direct UX changes on how orders are
prepared.»

## Decision

### One design layer for both private areas

* **`static/css/app.css`**, loaded after `main.css` by `layout/private.html` only (the public
  pages are untouched, PUB-01). It uses the public tokens (colours, Barlow and Barlow Condensed,
  radii, shadows) and adds the components of the private screens: the app shell and its
  navigation with counters, page heads with actions, panels and grids, fact lists, data tables
  that become **stacked cards under 760 px** (`.data-table.stacked`, each cell labelled by
  `data-label`), pills for order and customer states and the tracking label, the six tracking
  steps, a history timeline, buttons (primary, light, danger, sizes), form grids with errors and
  hints, filter and search bars, status tabs, callouts and flash messages, empty states,
  dashboard tiles and a two-step stepper. Mobile first; no inline style attributes (the CSP of
  ADR 0018 is unchanged); states never told by colour alone.
* **Icons**: `static/img/app-icons.svg`, a sprite of 30 Lucide icons (ISC licence, credited in the
  file) used with `<use href>` and `aria-hidden`, next to the public `/img/icons.svg`.
* **`static/js/app.js`**: vanilla JavaScript loaded with the page nonce, progressive enhancement
  only — every screen works without it (below).

### The order form (CUS-04, CUS-07, ADM-11; amends ADR 0006)

* **One page** with three numbered sections — «Pallet e colli» (the lines as editable rows with
  the legacy labels «Tipo Pallet», «Altezza (cm)»…), «Dati della spedizione», «Destinatario» —
  and a summary panel (sticky on wide screens) with the weight, the volume and the number of
  units; then «Riepilogo e invio» (the summary of ADR 0006, step 2 of 2) and the final page with
  the legacy wording. The same form edits a pending order (CUS-07) and serves the back office
  (ADM-11, with the sender customer and, for an approved order seen by an ADMIN, the code and the
  amount); `OrderForm` holds its model.
* **Rows**: inputs named `line.<n>.<field>`, at most 50 rows (a guard, not a business rule);
  rows left entirely empty are ignored; every rule of `OrderValidator` applies row by row and
  the errors appear under their field, with a summary at the top.
* **Without JavaScript** «Aggiungi riga» and «Elimina» are submit buttons
  (`action=addLine`, `action=removeLine.<n>`) answered by the server with the page and
  everything typed; a first, visually hidden «Continua» button (`tabindex=-1`, `aria-hidden`)
  makes Enter submit the form instead of deleting a row.
* **With JavaScript** rows are added and removed in place (names, ids, labels and the accessible
  names of the buttons renumbered), «Epal» fills the 120 × 80 cm base when it is empty, and the
  totals follow every keystroke with the same arithmetic as `OrderTotals` (weight × quantity;
  volume rounded half-up to hundredths of m³ per line; Italian formatting); the server
  recomputes them anyway on every round trip.

### The customer area

«I miei ordini» opens on tiles (in attesa, in viaggio, consegnati, inviati) and status tabs
(`?stato=attesa|viaggio|consegnati|chiusi`); the order detail shows the tracking steps, the
history (sent, last change, approval or decline, cancellation) and, while the order is pending
and Q-12 allows it, «Modifica» and «Annulla l'ordine» (ADR 0028).

### The back office

* **«Panoramica»** (`/gestione/`, `BackofficeHome`) replaces the Phase 1 placeholder: the
  queues of ADM-04 and ADM-02 with their counts, the shipments still travelling (approved, not
  `CONSEGNATO`; the order list gains the matching filter «Non ancora consegnati»,
  `tracking=in-corso`), searches by order code, tracking code and customer, «Nuovo ordine» and
  «Nuovo cliente», the latest orders. `auth` renders it through the `StaffHomePage` interface,
  so it does not depend on `backoffice` (chapter 10 §2).
* The navigation shows the pending counts on every page (`BackofficeModelAttributes`); lists,
  detail, approval, decline, tracking, reports, settings and privacy pages keep the conventions
  of ADR 0017 (pages instead of dialogs, confirmations naming the subject) on the new layer.

## Consequences

* The order is prepared on one screen with its totals in view; mistakes are fixed in place.
* The look of the private areas is now ours to maintain (`app.css`, about 1,300 lines); the
  public pages still follow the verbatim port of ADR 0012.
* Checked end to end: `journey.spec.ts` [NFR-01] runs axe (no serious or critical finding) and
  checks for horizontal scroll at 1280 and 390 px on 17 customer and staff screens, and every
  step listens for console errors and CSP violations; `OrderWizardIT` covers the form without
  JavaScript (rows added, removed, renumbered; Enter; the 50-row guard).
* New Italian texts (section titles, hints, buttons, flash messages) are listed for the owner's
  review in `docs/phase1/log.md`, step D.

## References

* Chapter 03 §6 (the wizard's content and rules), chapter 04 (the CMS screens), chapter 09
  CUS-04…CUS-07, ADM-01…ADM-12, NFR-01; ADR 0006 (validation and totals, amended for the page
  layout), 0012 (public port), 0017 (back-office conventions), 0018 (CSP), 0028 (record
  editing).
* Code: `OrderForm`, `OrderWizardController`, `CustomerOrderChangesController`,
  `BackofficeOrderChangesController`, `BackofficeHome`, `StaffHomePage`; templates
  `orders/form.html`, `orders/order-form.html`, `backoffice/order-form.html`,
  `backoffice/home.html`, `layout/private.html`; `static/css/app.css`, `static/js/app.js`,
  `static/img/app-icons.svg`.
