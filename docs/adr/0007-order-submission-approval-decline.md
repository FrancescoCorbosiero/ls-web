# 0007 · Order submission, temporary codes, idempotency, approval and decline rules

* **Status:** Accepted; Q-12 is amended by [ADR 0028](0028-record-editing.md) (owner decision of 2026-10-25: the customer changes or cancels its own pending order, `SELF_SERVICE`; staff create, correct and cancel orders)
* **Date:** 2026-10-24
* **Requirement IDs:** CUS-03, CUS-04, CUS-05, ADM-04, ADM-07, EML-04, EML-05, EML-06 (chapter 09) · **Questions:** Q-05 (amount VAT flag), Q-06 (code uniqueness), Q-12 (changes via the office), Q-13 (sender snapshot) · **Findings:** OP-02, OP-S1, D-03, D-06, D-79 (plan §7)

## Context

The legacy backend created a pending order with `"TEMP" + UUID`, tracking `NON_GESTITO`, for
any `REGISTERED` sender named by e-mail, e-mailed only the office, and let staff approve with a
typed code and an amount (`importo` > 0 only when the body carried `req`, D-03) or decline
with an e-mail whose recipient was not checked against the order (D-06). Chapter 08 §2.4 and
§2.5 fix the order states and codes; chapter 09 adds EML-04 and the tracking link in EML-05.

## Decision

### Submission (`OrderService.submit`)

* The sender is the **signed-in customer** (ADR 0004); only an `ACTIVE` customer may submit
  (OP-R2, CUS-03): pending and declined customers see their status on `/area-clienti/ordini/`,
  get no «Nuovo ordine» entry and are redirected away from the wizard.
* The order is created `PENDING` with **`TEMP-` + 8 random base-32 characters** (chapter 08
  §2.5: keeps the legacy marker, cannot be guessed), `tracking_state_id` 0, the **sender
  snapshot** from the customer's registered data (Q-13: pick-up = registered address), the
  recipient, the lines with their legacy totals, `package_count`, the service id (null = none)
  and the notes.
* **Idempotency**: the draft carries a UUID from its creation; a submission whose key already
  produced an order returns that order and creates nothing (OP-02). The session draft is
  cleared after the submission, so «Indietro» or a reload cannot resubmit.
* In the same transaction: **EML-04** to the customer (new: «Abbiamo inoltrato la tua
  richiesta», the temporary code, the detail blocks, «Controlla la tua casella postale in attesa
  di conferma», Reply-To the office) and **EML-06** to the office («Nuova richiesta di
  spedizione in attesa di conferma», Reply-To the customer, link to `/gestione/`), audit
  `order.submit`, `OrderCreated`.

### The customer's list and detail (CUS-05)

`/area-clienti/ordini/` lists only the signed-in customer's orders — temporary code while
pending, definitive code, date, recipient, state, tracking label and amount once approved —;
`/area-clienti/ordini/{codice}/` opens by code **or** temporary code, trimmed and ignoring case;
another customer's code and an unknown code are **404** through the error model. Changes and
cancellations go through the office (Q-12 default `OFFICE_ONLY`): the detail says so. *(Superseded on
2026-10-25 by ADR 0028: the default is now `SELF_SERVICE`; `OFFICE_ONLY` keeps this behaviour.)*

### Approval (`OrderService.approve`, ADM-04)

* Only a `PENDING` order. The **code** is trimmed, non-empty, at most 64 characters and
  **unique ignoring case** among definitive codes (Q-06; the legacy sentence «E' già stata
  effettuata una registrazione con questo codice…» is the refusal); it is stored as typed.
* The **amount** is parsed accepting the Italian comma or the point (`180,50`, `1.234,56`,
  `99.9`), must be **> 0** with at most two decimals (12c §4.2, D-79), stored as
  `numeric(10,2)` with `amount_vat_included` = `ls.orders.amount.vat-included-default` (Q-05).
* Effects: `APPROVED`, `approved_at/by`, **tracking `PRESA`** with one `tracking_event`
  (source `APPROVAL`), **EML-05** «Conferma ordine N. {codice}» (legacy L-E6 text, the detail
  blocks with the amount and the tracking label, the tracking link
  `/tracking/?code={codice}`), audit `order.approve`, `OrderApproved` + `TrackingChanged`.
  There is no `promote` path (D-03).

### Decline (`OrderService.decline`)

Only a `PENDING` order → `DECLINED`, `declined_at/by`, optional reason, **EML-05** to the
order's own customer (D-06) with a subject that quotes the temporary code («Aggiornamento
ordine rifiutato N. TEMP-…»), the legacy L-E7 sentence and the reason, audit `order.decline`,
`OrderDeclined`. `APPROVED` and `DECLINED` are final.

### Internal note (ADM-07)

`order_internal_note` holds one staff note per order, separate from the customer's
`customer_notes`; every edit writes `order.note` with the before/after text in
`audit_log_detail` only; the note never appears in the customer area or in any e-mail.

## Consequences

* The back office of Phase 2 calls these services only; the rules stay here with their tests.
* The customer's e-mail address is read from the sender snapshot at the time of each e-mail,
  so an e-mail change (ADR 0005) does not rewrite past orders; staff see both.
