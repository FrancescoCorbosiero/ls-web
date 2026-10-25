# 0028 · Record editing: the customer changes its pending orders, staff create and correct orders and customers

* **Status:** Accepted
* **Date:** 2026-10-25
* **Requirement IDs:** CUS-07, ADM-11, ADM-12 (new, chapter 09), CUS-04, CUS-05, ADM-04, ADM-09, EML-03, EML-06, API-01, NFR-03 · **Questions:** Q-12 (decided by the owner on 2026-10-25), Q-06 (code uniqueness), Q-13 (sender snapshot), Q-28 (approval of verified customers) · **Findings:** SEC-15, SEC-16, 12b §3.2 O6, 12c §6

## Context

The legacy portal gives the customer no way to change or withdraw an order once sent
(chapter 03), and the CMS has no screen to create or correct an order or a customer
(chapter 04): the office fixes mistakes with the backend's admin tools — hard deletes, an
order update sent from Postman, free-text e-mails through SES (12c §6, SEC-15, SEC-16), with
no validation, no audit and no protection against two people editing at once. Chapter 09
left those tools out of M1 («Corrections after approval, if the owner wants them, are an ADMIN
action with an audit entry», chapter 08 §2.4) and Q-12 defaulted to «only while pending, by
asking the office» (`ls.orders.customer-changes=OFFICE_ONLY`, ADR 0007).

On 2026-10-25 the owner asked for it explicitly: «CRUD for records details is completely
missing, I thought it was one of the things to develop in this new codebase phase. If you
built everything right, CRUD like this should be easy to build and fast. And I mean this
both from admin side but also i'd be amazing feature for customers to be able to CRUD their
own orders before they get confirmed by admin.» That answers Q-12 and adds three
requirements (chapter 09 CUS-07, ADM-11, ADM-12).

## Decision

### Common rules

* **Nothing is deleted.** «Delete» is a cancellation: the order keeps its row, its snapshots,
  its lines and its history with the new status **`CANCELLED`** (V013: `cancelled_at`,
  `cancelled_by_type` CUSTOMER/STAFF, `cancelled_by`, `cancel_reason`; database checks keep the
  columns consistent with the status). A customer is never deleted from these screens: erasure
  remains the privacy tool's anonymisation (NFR-03, ADR 0024).
* **Optimistic check on every change.** Each edit or cancel page carries the version of the
  record it showed (`versione`, the JPA `@Version`); a change made meanwhile by the other side
  or a colleague is refused with «L'ordine è stato modificato nel frattempo: controlla i dati
  aggiornati e riprova.» (for a customer: «I dati del cliente sono stati modificati nel
  frattempo…») and the page comes back with the current data. Every edit stamps `edited_at` and
  `edited_by_type`, so the version always moves on.
* **Same rules as creation.** An edited order goes through the order form and
  `OrderValidator` of CUS-04 (ADR 0006, ADR 0029); an edited or staff-created customer through
  `RegistrationValidator` of CUS-02. The totals are recomputed from the lines (OP-R5).
* **Audited.** `order.update`, `order.cancel`, `order.create`, `customer.update`,
  `customer.create`: the non-personal facts before and after in the audit row, the personal
  values (recipient, notes, registration data, the cancel reason) in the detail table that the
  privacy tools erase.
* **The sender snapshot is never touched** (Q-13): a customer's corrected data apply to its next
  orders, not to the ones already sent.

### The customer (CUS-07)

* The Q-12 seam `ls.orders.customer-changes` defaults to **`SELF_SERVICE`** (`OrdersProperties`,
  `OrderService.customerSelfService()`); `OFFICE_ONLY` restores the M1 behaviour of CUS-05 (the
  detail page names the office address and phone, the change pages lead back to it).
* While the order is **PENDING**, «Modifica» opens the order form with the stored lines,
  shipment data and recipient (`/area-clienti/ordini/{codice}/modifica`); «Salva le modifiche»
  replaces them at once. «Annulla l'ordine» asks for confirmation on its own page
  (`…/annulla`) and sets `CANCELLED`. Once the office approved or declined it, both pages lead
  back to the detail, which explains why.
* The office gets an **EML-06 notice** for each change («Richiesta di spedizione {codice
  provvisorio} modificata dal cliente», with the full current detail, or «… annullata dal
  cliente»; Reply-To the customer); the back-office detail flags an edited pending order («Il
  cliente ha modificato l'ordine il …: controlla i dati prima di approvarlo»). The customer gets
  no e-mail: it sees the result on screen.
* Another customer's order is a 404, as for the detail (CUS-05).

### Staff: orders (ADM-11)

* **Create** («Nuovo ordine», `/gestione/ordini/nuovo`, also from a customer's page): for an
  **ACTIVE** customer only, the customer as sender (snapshot), `source = BACKOFFICE`, a temporary
  code, `PENDING`; it is then approved like any other (ADM-04), which sends the customer EML-05.
  One UUID per form (`chiave`, the idempotency key) makes a double submission one order. No
  e-mail at creation.
* **Edit** («Modifica»): a PENDING order by every staff member; an **APPROVED** order only by an
  **ADMIN** (chapter 08 §2.4), who may also correct the definitive code (unique ignoring case,
  Q-06, the legacy refusal sentence) and the amount (> 0, ADM-04 rules). DECLINED and CANCELLED
  orders are closed. No e-mail to the customer.
* **Cancel** («Annulla ordine», with an optional reason up to 500 characters, shown to staff
  only): a PENDING order by every staff member — for a customer who withdraws by phone;
  «Rifiuta» stays the office's refusal, with its e-mail — and an APPROVED one only by an ADMIN.
  A cancelled order disappears from public tracking, which answers as for an unknown code
  («Non gestito», API-01), and from the reports (ADM-09 counts approved orders); the customer's
  pages show it cancelled («Il centro logistico ha annullato questo ordine il …»), without the
  amount (shown to customers for approved orders only). No e-mail.

### Staff: customers (ADM-12)

* **Create** («Nuovo cliente», `/gestione/clienti/nuovo`): a customer who asked by phone or
  e-mail is **ACTIVE at once**, approved by the staff member (`approved_by`), `source =
  BACKOFFICE`, with its OWNER login; it signs in with the usual link (CUS-01), whose first use
  verifies the address (`email_verified_at`; V013 relaxes the «active means verified» check for
  BACKOFFICE customers only — self-registered ones still need a verified e-mail before approval,
  Q-28). The welcome e-mail (EML-03 «Conferma nuovo cliente», with the link to the customer area)
  is sent only when «Invia al cliente l'e-mail di benvenuto» stays ticked (the default). An
  address already used by a customer or a login is refused.
* **Edit** («Modifica dati»): the registration fields with the CUS-02 rules, never the login
  e-mail (it changes only on the customer's confirmed request, CUS-06), never an anonymised
  customer. No e-mail to the customer.

### Not ported

The backend's admin tools (hard deletes, the Postman order update, free-text e-mails; 12c §6,
SEC-15, SEC-16) stay retired: the screens above replace them with validated, versioned and
audited changes through the same services as the rest of the app.

## Consequences

* The office handles phone and e-mail requests in the app instead of asking customers to
  register or order again; corrections leave a trail in the audit log.
* Customers fix their own mistakes before approval; the office sees each change in its inbox and
  on the order. An order the customer cancels leaves «Ordini da approvare» at once.
* New customer-facing and staff texts (buttons, confirmations, flash messages, the two EML-06
  notices) are listed for the owner's review in `docs/phase1/log.md`, step D.
* A cancelled approved order is excluded from reports and public tracking; if the owner wants
  cancelled shipments in the reports, that is a change to ADM-09.
* `OFFICE_ONLY` remains available without code changes (`CustomerOrderChangesOfficeOnlyIT`).

## References

* Chapter 08 §2.4 (states, corrections after approval), chapter 09 CUS-05, CUS-07, ADM-04,
  ADM-11, ADM-12, the «Not ported» note; chapter 11 Q-12, Q-13, Q-28; 12c §6.
* ADR 0005 (customer states), 0006 and 0029 (the order form), 0007 (submission and approval,
  amended here for Q-12), 0017 (back-office conventions), 0024 (privacy).
* Code: `OrderService.updateByCustomer`, `cancelByCustomer`, `createByStaff`, `updateByStaff`,
  `cancelByStaff`; `CustomerService.createByStaff`, `update`; `CustomerOrderChangesController`,
  `BackofficeOrderChangesController`, `BackofficeCustomerChangesController`; migration
  `V013__record_editing.sql`.
* Tests: `CustomerOrderChangesIT`, `CustomerOrderChangesOfficeOnlyIT`,
  `BackofficeOrderChangesIT`, `BackofficeCustomerChangesIT`, `DevSeedCommandIT` (a cancelled
  order), `route-matrix.tsv`; `e2e/tests/journey.spec.ts` [CUS-07], [ADM-11], [ADM-12].
