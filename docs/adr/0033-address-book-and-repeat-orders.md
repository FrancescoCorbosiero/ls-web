# 0033 · Saved recipients and «Ordina di nuovo» (ENH-07)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-07 (flags `customers.addressBook`, `orders.repeat`); touches CUS-04, CUS-07, ADM-11, NFR-03 · **Questions:** Q-24 (the transport document stays required), Q-13 (the sender stays the snapshot of the customer) · **Owner:** M2.1 scope of 2026-10-26, item 1

## Context

The owner asked for «saved recipients per customer: pick one in the order form, "save this
recipient", manage the list in the profile; staff can use the customer's address book when
entering an order by phone (ADM-11)» and for «Ordina di nuovo on an order: opens the order form
prefilled with lines, service, recipient and notes; a new transport document code is still
required». Chapter 09 lists both under ENH-07 behind two flags; the plan names a
`customer_address` table. «Keep it light: a small CRM for one office.»

## Decision

### The address book (`customers.addressBook`)

* **Table `customer_address`** (V016), owned by the **`customers` module**: a saved recipient is
  customer data, and `orders` already depends on `customers`, so `orders` reads and writes the
  book through `AddressBookService` and no dependency points back (the requirement stays the
  `orders` owner's in the plan for its tests). Columns: the recipient fields of an order (name,
  street, city, province, CAP, optional phone and e-mail) with the same lengths; `created_at`,
  `updated_at`.
* **Rules:** those of the order's recipient (OP-R7), applied by the service itself (a copy of
  `OrderValidator.recipient`, which stays untouched: an entry used in an order passes the order's
  own rules again). **One entry per name, street and CAP**, ignoring case and outer spaces (a
  unique index); adding it again from the profile is refused («Questo destinatario è già nella
  rubrica.»), saving it again from an order updates its city, province, phone and e-mail. At most
  **200 entries** per customer (`AddressBookService.MAX_ENTRIES`), a guard against abuse, not a
  business rule; an order whose recipient cannot be saved because the book is full is still sent,
  with a message.
* **Profile** (`/area-clienti/profilo/`): «Rubrica dei destinatari» lists the entries with
  «Modifica» and «Elimina» and links «Aggiungi destinatario»; the forms are
  `/area-clienti/profilo/rubrica/nuovo` and `/area-clienti/profilo/rubrica/{id}`, deletion is
  `POST …/{id}/elimina` with no confirmation page (an entry is cheap to type again). Another
  customer's entry is a 404.
* **Order form** (new order and the CUS-07 change): above the recipient fields, «Scegli dalla
  rubrica» with «Usa questo destinatario» — a submit button the server answers, everything typed
  kept (`action=useAddress`); `/js/app.js` fills the fields as soon as an entry is chosen. Below
  them, «Salva il destinatario nella rubrica»: for a new order the choice travels in the session
  draft and the recipient is saved when the order is confirmed on the summary (which says so);
  for a change, when it is saved.
* **Staff** (ADM-11): the staff order form shows the chosen customer's book — after choosing the
  customer in the list, «Mostra la rubrica del cliente» reloads the page with it (without
  JavaScript there is no other way to know the choice); «Salva il destinatario nella rubrica del
  cliente» saves it for the customer. Staff do not manage the list elsewhere.
* **Audit:** `address.create`, `address.update`, `address.delete`, by the customer's login or the
  staff user, recorded **on the customer** (`entity_type = customer`, the entry id in the values,
  the recipient in the personal detail), so that the erasure of the customer (NFR-03) removes
  those details with the customer's. **Erasure** deletes the entries; the **export** lists them
  (`rubricaDestinatari`, only when there are some: the M1 document is otherwise unchanged).

### «Ordina di nuovo» (`orders.repeat`)

* On the customer's order detail, for any of its orders (pending, confirmed, declined,
  cancelled) while the customer can order: a `POST /area-clienti/ordini/{codice}/ripeti` button
  that replaces the session draft with a **new draft** (its own idempotency key) holding the
  order's lines, accessory service, notes and recipient, and opens the order form with a message.
  The **transport document and «Numero colli» stay empty**: every shipment has its own (Q-24),
  so the form refuses to continue until a new DDT is typed. The new order then follows CUS-04
  (summary, confirmation, temporary code, EML-04 and EML-06); nothing is copied silently.
* In the back office, «Ripeti ordine» on an order's page opens «Nuovo ordine»
  (`/gestione/ordini/nuovo?ripeti={id}`) for the same customer with the same data and an empty
  DDT; nothing is stored until «Inserisci l'ordine» (ADM-11 rules, form key).

### Flags off

`OrderAddressBook` is the one place where the order forms read `customers.addressBook`; the
profile, the order pages and the staff order page read their flag in their controller. Off: the
routes above answer 404, no picker, checkbox or button is rendered, the posted `addressId` and
`saveRecipient` are ignored, `action=useAddress` or `showAddressBook` is read as M1 reads an
unknown button (continue), `?ripeti=` opens the blank M1 form. `FlagKey.built()` is true for both
flags, so «Impostazioni» lets an ADMIN turn them on (ADR 0025).

## Consequences

* A customer who ships to the same places stops retyping them; a repeat order is two clicks plus
  a DDT, with the same checks as a new one.
* The book is per customer, not per login: Q-11 (several users per company) would share it.
* A recipient's data now also live outside the order snapshots, under the customer's control
  and erased with it.

## References

Chapter 09 ENH-07, CUS-04, CUS-07, ADM-11; ADR 0025 (flags), 0028 (record editing), 0029 (order
form), 0024 (privacy). Code: `customers.AddressBookService`, `AddressBookController`,
`CustomerAddress`, `orders.OrderAddressBook`, `OrderDraft.repeatOf`, `OrderForm.repeat`,
`OrderWizardController.repeat`; V016. Tests: `AddressBookIT`, `AddressBookOffIT`,
`AddressBookServiceTest`, `e2e/tests/m2.spec.ts` [ENH-07].
