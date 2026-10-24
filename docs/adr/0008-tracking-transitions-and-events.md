# 0008 · Tracking transitions as events; the delivery e-mail

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** ADM-06, EML-09, TRK-02 (chapter 09) · **Questions:** Q-38 (temporary codes not trackable) · **Findings:** D-05, D-29, D-74 (plan §7)

## Context

The legacy CMS set the tracking state of any order by code, in any order of states (chapter 04
§4.6), overwrote the single `tracking` column and e-mailed the customer at `CONSEGNATO` with a
template that printed the staff notes (D-05). Chapter 08 §2.4 asks for an append-only history
and for the delivery e-mail only when the state becomes `CONSEGNATO`.

## Decision

* `OrderService.setTrackingState(orderId, staffUserId, stateId)` works on **approved orders
  only** (D-74; pending orders have no public code, Q-38) and accepts **any of the six seeded
  states after any other**, as the CMS did. The id must be a seeded `tracking_state` (typed,
  D-29).
* **Setting the current state again records nothing** (no event, no e-mail, no audit row).
* A change updates `shipment_order.tracking_state_id` and `tracking_updated_at`, inserts one
  `tracking_event` (source `STAFF`, `occurred_at` = now, the staff user), writes
  `order.tracking` to the audit log and publishes `TrackingChanged`. The `PRESA` event of the
  approval has source `APPROVAL` (ADR 0007).
* **EML-09** («Consegna effettuata per ordine N. {codice}», legacy L-E8 text, the detail
  blocks, the tracking link, Reply-To the office) is queued **only when the state becomes
  `CONSEGNATO`**, each time it does so after being something else; never on a re-set. It
  prints the customer's own note only, never the internal note (D-05).
* The `tracking_event` entity and the transitions live in the `orders` module next to the
  order's state (one transaction, one service); the `tracking` module keeps the public lookup,
  the compatibility API and the `TrackingCatalog` of seeded states and services that the other
  modules read.

## Consequences

* The history (`OrderService.trackingHistory`) is ready for the M2 timeline (ENH-05) and for
  the back-office detail; the public lookup (ADR 0010) still reads only the current state.
* The confirmation the UI should ask for backwards moves (chapter 08 §2.4 proposal) belongs to
  the back-office screens.
