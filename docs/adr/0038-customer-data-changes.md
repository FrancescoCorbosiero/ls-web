# 0038 · The customer's own data changes (ENH-16)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-16 (flag `customers.profileEdit`); touches CUS-06, ADM-02, ADM-12, EML (EML-06, new EML-12), NFR-03 · **Owner:** request of 2026-10-26 («procedi con quelli che puoi fare ora»)

## Context

Chapter 09: «Customers edit their data; changes to company name or VAT go through approval;
e-mail change as CUS-06.» In M1 the profile is read-only with «Chiedi una modifica», an e-mail
to the office (CUS-06), and staff correct the data (ADM-12). The pick-up address of an order is
the customer's registered address (Q-13), copied into each order as its sender.

## Decision

* **Who.** An **ACTIVE** customer, from «Il tuo profilo» → «Modifica i dati»
  (`/area-clienti/profilo/dati`). A customer in another state keeps the M1 «Chiedi una modifica»;
  the form is a 404 for it.
* **The form** has the registration fields and rules of CUS-02 (`RegistrationValidator`) and is
  saved on the version the page showed: a change meanwhile (another device, an approval, a
  correction by the office) is refused with the current data.
* **At once:** street, city, province, CAP and phone. They are the next orders' sender; the
  orders already sent keep theirs (Q-13).
* **With approval:** the company or personal name, the VAT number and — the agent's decision,
  for the same reason as the VAT number: it identifies the customer on its documents — the tax
  code. A different value becomes the customer's **one pending request** (V019
  `customer_change_request`, a partial unique index): a new save replaces its values, a save with
  the present values again takes it back, and the profile shows it with «Ritira la richiesta».
  While it waits, the form shows the values asked for, the present ones under them.
* **The office** sees the requests on «In Approvazione» (counted with the customers to approve in
  the navigation and on the home) and on the customer's page, values now and asked for, with
  «Approva la modifica» (the values are copied to the customer) and «Rifiuta la modifica» (an
  optional reason, ≤ 500 characters, that the customer reads). Every staff role decides, as for
  the customers' approval (ADM-02); ADM-12 corrections stay as they are.
* **E-mails, through the outbox.** Each save that changes something tells the office (EML-06,
  «Dati del cliente aggiornati» or «Modifica dei dati da approvare», what changed and the link to
  the customer); the decision goes to the customer as **EML-12** («Dati aggiornati» with the new
  values, or «Modifica dei dati non accettata» with the reason).
* **The login e-mail** stays CUS-06 (a link to the new address), unchanged.
* **Audit** on the customer, values in the personal detail: `customer.update` (actor CUSTOMER),
  `customer.change.request`, `customer.change.withdraw`, `customer.change.approve`,
  `customer.change.decline`.
* **Privacy (NFR-03):** the export lists the requests («richiesteModificaDati»); the erasure
  deletes them (`CustomerChangeService.erase`).
* One save at a time per customer: the save and the decisions lock the customer row.

### Flag off

`/area-clienti/profilo/dati` and its withdrawal answer 404, the profile is the M1 one with
«Chiedi una modifica», the back office shows no request and its approve and decline routes
answer 404; a request left from a time the flag was on stays as it is (`ProfileEditOffIT`). The
flag is «Disponibile» in «Impostazioni».

## Consequences

* The office no longer retypes an address or a phone number; it still decides who the customer
  is on its documents.
* The office learns of an address change from EML-06, since it is the pick-up address.
* New Italian texts (form, profile, back-office panel, EML-06 notices, EML-12) wait for the
  owner's review at the next gate.

## References

Chapter 09 ENH-16, CUS-06, ADM-02, ADM-12; Q-13; ADR 0015 (outbox), 0024 (privacy), 0025
(flags), 0028 (record editing). Code: `customers.CustomerChangeService`, `CustomerChangeRequest`,
`ProfileEditController`, `ProfileController`, `CustomerMails`, `backoffice.BackofficeCustomersController`,
`WorkQueue`; V019. Tests: `ProfileEditIT`, `ProfileEditOffIT`, `e2e/tests/m2.spec.ts` [ENH-16].
