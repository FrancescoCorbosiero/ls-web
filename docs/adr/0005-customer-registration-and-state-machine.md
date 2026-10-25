# 0005 · Customer registration from the link, the state machine, re-registration and the e-mail change

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** CUS-02, CUS-03, CUS-06, ADM-02, EML-01, EML-02, EML-03, EML-06 (chapter 09) · **Questions:** Q-09 (declined may register again), Q-10 (VAT / tax code optional), Q-11 (one login), Q-28 (approval only after verification), Q-36 (phone required), Q-37 (brand) · **Findings:** OP-08…OP-11, OP-16, OP-20, OP-21, OP-22, OP-23 (chapter 03 §10), D-69

## Context

The legacy portal registered a customer with six placeholder-labelled fields and US-style
regexes (chapter 03 §5.3): the phone was required although labelled optional (OP-08), a ZIP+4
passed as a CAP (OP-09), nothing was trimmed (OP-10), the e-mail was stored as typed (OP-11),
the confirmation step showed no data (OP-16) and the success screen offered «Effettua un nuovo
ordine» (OP-20). The backend e-mailed only the office at registration and verified the e-mail
by a never-expiring `GET` link (12c §1.4). Chapter 08 §2.4 fixes the states
`PENDING_VERIFICATION → PENDING_APPROVAL → ACTIVE | DECLINED` with `DECLINED → register again`
(Q-09) and the rule that only a verified customer is approved (Q-28). ADR 0004 made the
`REGISTER` link store only the HMAC of the unknown address.

## Decision

### Registration (`/area-clienti/registrazione/`)

* The form opens from a usable `REGISTER` link (the `GET` never consumes it) with the legacy
  title **«Inserisci i dati dell'azienda»** and real labels: «Email», «Ragione
  Sociale/Nominativo», «Via», «Provincia», «Città», «CAP», «Telefono», «Partita IVA
  (facoltativo)», «Codice fiscale (facoltativo)». The stray «B» heading is dropped (OP-21).
* **The e-mail is typed again and checked against the link**: the token row holds only
  `HMAC(e-mail)` (ADR 0004), so the address cannot be pre-filled from it; the typed address,
  normalised, must hash to the token's `email_hash`, otherwise the form shows the field error
  «L'indirizzo deve essere quello a cui hai ricevuto il link.» on the first `POST` already and
  consumes nothing (the service refuses the mismatch a second time at confirm). The hint says
  so («deve essere quella a cui hai ricevuto il link») and keeps the legacy sentence «Se sei
  già registrato NON è necessario ripetere la registrazione».
* **Italian rules** (`RegistrationValidator`, `ItalianFormats`): every field trimmed; name,
  street, province, city required within the column lengths; **CAP exactly five digits**;
  **phone required** (`ls.customers.registration.phone-required`, Q-36) and normalised to
  E.164 (`0362 123456` → `+390362123456`, `0039…` → `+…`, no prefix → `+39`); VAT number 11
  digits (an `IT` prefix is stripped) and tax code 16 characters (or 11 digits) **optional**
  (Q-10 seams `vat-number-required`, `tax-code-required`); e-mail trimmed and lower-cased.
  Every error is shown at once under its field.
* A **summary page** («Controlla i dati inseriti») with «Conferma» and «Modifica» replaces the
  legacy «clicca nuovamente Avanti» (OP-16). The hidden fields are re-validated on «Conferma».
* **Confirming** takes one permit per client IP (`registration`, default 5 per hour; 429
  otherwise), then in one transaction: the `REGISTER` token is consumed, the `customer` row is
  created **`PENDING_APPROVAL` with `email_verified_at` = now** (the link proved the mailbox:
  chapter 08 §2.4 "skipped when opened from a REGISTER link"), its OWNER `customer_user` is
  created (Q-11), EML-02 goes to the customer and EML-06 to the office **once**, the audit
  log gets `customer.register`, `CustomerVerified` is published, and the customer is **signed
  in** (same session handling as a sign-in link) and sent to `/area-clienti/ordini/`, where it
  reads the legacy pending sentence (CUS-03). `PENDING_VERIFICATION` is therefore never produced
  by the customer area in M1; the state, the transition on a sign-in link
  (`CustomerSignInListener` → `email_verified_at`, EML-02 + EML-06) and `VERIFY_EMAIL` tokens
  stay for customers created another way (back office, later).
* **A known e-mail** (already a login or a `customer` row, for example registered meanwhile from
  another device) creates nothing: a sign-in link is sent and the page answers exactly like the
  sign-in page («Ti abbiamo inviato un'email: segui il link per continuare»).
* **Honeypot**: a filled hidden `website` field answers the same «sent» page and stores nothing.

### Re-registration of a declined customer (Q-09)

A `DECLINED` customer signs in with its ordinary sign-in link (D-69), sees «La tua richiesta di
registrazione non è stata accettata» with the reason staff gave, and «Registrati di nuovo con i
dati corretti», which opens the same form **without a token**, pre-filled with the current data.
Confirming applies the new data to the same `customer` row and moves it to `PENDING_APPROVAL`
(the sign-in proved the mailbox), with EML-02 + EML-06 and `customer.reregister` in the audit
log. The `customer_pending_registration` table of V003 is therefore **unused in M1**: it stays
for a token-based re-registration if one is ever needed.

### State machine (`Customer`, `CustomerService`)

* `approve(customerId, staffUserId)`: only `PENDING_APPROVAL` **and** `email_verified_at` set
  (Q-28) → `ACTIVE`, `approved_at/by`, EML-03 «Conferma nuovo cliente» (legacy L-E3 text,
  button «Area clienti»), audit `customer.approve`, `CustomerApproved`.
* `decline(customerId, staffUserId, reason)`: from either pending state → `DECLINED`,
  `declined_at/by`, optional reason, EML-03 with a **truthful subject** «Registrazione non
  accettata» and the legacy L-E4 body plus the reason, audit `customer.decline`,
  `CustomerDeclined`; every session of the customer is ended.
* Any other move throws `RuleViolationException` (422 through the error model) with an Italian
  sentence the back office shows. `ACTIVE` and the illegal moves are final in M1.

### Profile and e-mail change (CUS-06)

* `/area-clienti/profilo/` shows the registration data **read-only**, the status, and «Chiedi
  una modifica» (a `mailto:` to the office with the customer's e-mail in the subject): company
  data change only through the office.
* The login e-mail changes in two steps: the signed-in customer types the new address; the auth
  module stores it as `customer_user.pending_email` (V011) and queues EML-01 with a
  `CHANGE_EMAIL` link (15 minutes) to the **new** address and a notice (Reply-To the office) to
  the **current** one; an address already in use gets nothing and the page answers the same way.
  `GET /area-clienti/cambio-email?t=…` only renders «Conferma»; its CSRF `POST` consumes the
  token, applies the address to `customer_user` and `customer` (`CustomerEmailChangedListener`),
  writes `customer.email.changed` with both addresses in `audit_log_detail`, **ends every
  session** of the user and shows «E-mail aggiornata» with a sign-in link.

## Consequences

* The customer area never shows a form that is not a real `<form>` with labels (OP-23); all
  pages work without JavaScript.
* Staff never change a login e-mail themselves (chapter 09 CUS-06); the back office gets no
  such action.
* New Italian texts for the owner's review at the next gate: the e-mail hint, the summary
  page, the re-registration callout, EML-02, the EML-03 decline subject, the profile page.
