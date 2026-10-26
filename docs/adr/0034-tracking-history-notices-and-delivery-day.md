# 0034 · Tracking history, the customer's e-mail at every change, the indicative delivery day (ENH-05)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-05 (flags `tracking.history`, `tracking.notifyCustomer`); touches TRK-03, ADM-06, EML-09, CUS-05, PUB-08 · **Questions:** Q-14 (delivery times: the price list's are ENH-10's) · **Owner:** M2.1 scope of 2026-10-26, item 2

## Context

The owner asked for «an e-mail to the customer at every tracking state change (through the
outbox, once per transition; PRESA is already covered by the approval e-mail and CONSEGNATO by
EML-09); a timeline with dates in the customer's order detail and on public tracking, within the
TRK-03 limits; an indicative estimated delivery date from the recipient's CAP, using the
DeliveryZones dataset and working days from pickup». The events exist since M1 (ADM-06,
`tracking_event`, ADR 0008); ADR 0010 already reserved `eta` and `events {time, status,
location}` for ENH-05 in the TRK-03 allow-list; the site's tracking widget (ls-home) already
renders both.

## Decision

### `tracking.notifyCustomer` — EML-10 «Aggiornamento spedizione»

* Queued by `OrderService.setTrackingState` in the transaction that writes the event, so it goes
  **once per transition** and never when the same state is set again (no event, ADR 0008). The
  decision is one component, `orders.TrackingUpdateNotice` (the flag's single entry point): only
  **TRANSITO, TERMINAL_DEST and ASSEGNATO** e-mail the customer — PRESA is told by the approval
  e-mail (EML-05), CONSEGNATO by EML-09 (unchanged), NON_GESTITO is a correction, not news. A
  bulk change (ENH-15) goes through the same method, so the same rule applies.
* To the customer's address of the order (the sender snapshot, as EML-05/09), Reply-To the office;
  subject «Aggiornamento spedizione N. {codice}: {public title}» («In transito», «Arrivata al
  terminal di destinazione», «Affidata al mezzo per la consegna»); body: what the state means
  (the wording of the legend of `/tracking/`), code, state, recipient name and city, transport
  document, the indicative delivery day when there is one, the tracking button. No staff note,
  no amount.
* Outbox kind **`EML-10`** (V017 widens `ck_email_outbox_kind`): the next number of the chapter 09
  catalogue, tagged with ENH-05 rather than a new requirement row.

### `tracking.history` — the timeline and the delivery day

* **Public tracking** (`POST /mono/api/order/trace/state`, `CompatApiController`): for an approved
  code the answer adds `eta` (an ISO date) and `events` — the states newest first, each
  `{time, status, location}`: `time` ISO-8601 UTC, `status` the legacy label as the top-level
  `status`, `location` only when staff typed one (never today). Exactly the TRK-03 allow-list:
  no name, address, CAP, phone, note, DDT or document; unknown and temporary codes keep the bare
  `{"status": "Non gestito"}`. The ls-home widget renders «Cronologia» and «Consegna prevista» /
  «Consegnata» unchanged; `eta` is a plain date (`2026-10-27`, the format of the widget's own
  demo), which the widget reads as midnight UTC and shows in the browser's zone: the right day in
  Italy and east of it, the day before only for a browser west of UTC (accepted: the widget is
  ported unchanged from ls-home, PUB-01).
* **Customer area**: the order detail of a confirmed order shows, under the steps of «Dove si
  trova», the delivery day and every state with its date and time («Presa in carico», «In
  transito»…, the public titles).
* **The delivery day** (`tracking.DeliveryEstimate`): while the shipment travels, the zone of the
  recipient's CAP in the dataset of the public CAP widget (PUB-08, `DeliveryZones`: zone 1 «24
  ore», 2 «48 ore», 3 «72 ore») read as **1, 2 or 3 working days after the pick-up day** — the
  Europe/Rome day of the approval, when the state becomes PRESA; working days are Monday to Friday
  except the Italian national holidays (1 and 6 January, Easter Monday, 25 April, 1 May, 2 June,
  15 August, 1 November, 8, 25 and 26 December); no estimate for the places the site says are
  agreed case by case (minor islands, the Venice lagoon) nor for an unknown CAP. Once
  CONSEGNATO, the day it was delivered. It is labelled «indicativa» everywhere; the price-list
  transit times stay ENH-10's (Q-14).

### Flags off

`tracking.history` is read by `CompatApiController` and `CustomerOrdersController`;
`tracking.notifyCustomer` by `TrackingUpdateNotice`. Off: the public answer is the M1
`{status, updatedAt}`, the order detail is the M1 one, no EML-10 is ever queued
(`TrackingHistoryOffIT`). Both flags are «Disponibile» in «Impostazioni» (`FlagKey.built()`).

## Consequences

* Customers follow a shipment without calling the office; the office still sets each state by
  hand (ADM-06) or in bulk (ENH-15), and each set sends at most one e-mail.
* The delivery day is a promise only as good as the zones of the site; the office may prefer to
  turn on the timeline (`tracking.history`) and leave the e-mails off, or the reverse.
* The holiday list is national only (no local patron saints); a closure list would come with
  ENH-18/19.

## References

Chapter 09 ENH-05, TRK-03, EML-09, PUB-08; ADR 0008 (events), 0010 (allow-list), 0015 (outbox),
0025 (flags). Code: `tracking.DeliveryEstimate`, `TrackingLookupService.history`,
`CompatApiController.TraceResponse`, `orders.TrackingUpdateNotice`, `OrderMails.trackingUpdate`;
V017. Tests: `DeliveryEstimateTest`, `TrackingHistoryIT`, `TrackingHistoryOffIT`,
`e2e/tests/m2.spec.ts` [ENH-05].
