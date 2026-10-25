# 0004 · Customer sign-in by one-time link consumed by `POST`; identity from the session

* **Status:** Accepted (amended on 2026-10-25 by ADR 0031: the device stays signed in for 60 days; and by ADR 0032: customers also sign in with e-mail and password, the link stays for the first access and a forgotten password)
* **Date:** 2026-10-24
* **Requirement IDs:** CUS-01, EML-01, EML-06 (chapter 09) · **Questions:** Q-11 (one login per customer), Q-30 (old links), PD-14 (`/area-clienti/accesso` without trailing slash, D-100), D-69 (unverified = known) · **Findings:** OP-S1, OP-S2 (chapter 03 §10), SEC-11, SEC-12, SEC-19 (12a §5)

## Context

The legacy portal had no authentication: whoever knew a registered customer's e-mail could
order in that customer's name (OP-S1), and `POST /mono/api/customer/state` told anyone,
without limits, whether an e-mail was NEW, PENDING or REGISTERED (OP-S2). The e-mail was sent
as typed, so `Mario@Azienda.it` and `mario@azienda.it` could be two customers (OP-10, OP-11).
The backend's verification link never expired and confirmed on `GET` (12c §1.4). Chapter 09
CUS-01 keeps the e-mail-first entry and asks for a uniform answer, a one-time sign-in link for
known addresses, a single-use registration link for unknown ones, and a link that only opens a
confirmation page whose `POST` uses the token. Chapter 10 §2 fixes the mechanics (random
≥ 128-bit tokens stored hashed, 15 minutes, registration 24 hours, `GET` renders / `POST`
consumes, `no-store` and `no-referrer`, redirect without the token, sessions 60 min / 12 h).

## Decision

### Requesting a link

* `GET /area-clienti/` shows the e-mail form with the legacy copy adapted: **«Inserisci la tua
  e-mail»**, field «Email», button «Avanti», hint **«Assicurati di aver inserito la mail
  corretta.»** (the second sentence «Se sei già registrato NON è necessario ripetere la
  registrazione.» is dropped: there is nothing to repeat), and the privacy-notice link.
* `POST /area-clienti/` (CSRF) **trims and lower-cases** the e-mail (OP-10, OP-11), takes one
  permit per client IP (`signin.link.ip`, default 20 per 15 min) and per normalised e-mail
  (`signin.link.email`, default 5 per 15 min; both 429 with `Retry-After`), and **always answers
  the same page and status** («Ti abbiamo inviato un'email: segui il link per continuare»),
  whatever the address. In one transaction:
  * a **known login** (`customer_user` row — verified, unverified or declined alike, plan D-69:
    the sign-in link verifies the e-mail) → a `SIGN_IN` token (256 random bits in URL-safe
    base64; only the SHA-256 is stored; **15 minutes**; single use) and the EML-01 sign-in
    e-mail with `https://<site-url>/area-clienti/accesso?t=<token>`;
  * an **unknown address** → a `REGISTER` token (**24 hours**, the row holds only the HMAC of
    the e-mail, `email_hash`; no `customer` row is created) and the registration e-mail with
    `https://<site-url>/area-clienti/registrazione/?t=<token>`;
  * a **disabled login** (`disabled_at`) → nothing.
  The e-mail body carries the `{{token}}` placeholder; the raw token reaches the outbox only
  encrypted (`token_ciphertext`, `login_token_id`) and is wiped after delivery (ADR 0015).
  `requested_ip_hash` is the HMAC of the client IP. The e-mail HMAC uses the same key as the
  IP hashes (`ls.security.ip-hash-key`) with a domain separator, so no second secret is needed.

### Using a link

* `GET /area-clienti/accesso?t=…` (PD-14: no trailing slash, it is a route) **only renders** a
  page with one «Accedi» button in a CSRF-protected form that works without JavaScript; the
  token is checked but **never consumed** — a `GET` or `HEAD` by a mail scanner or a link
  preview cannot burn it. The page (and the registration and set-password pages) send
  `Cache-Control: no-store` and `Referrer-Policy: no-referrer`. A used, expired, unknown or
  wrong-purpose token shows **«Il link non è più valido»** with the request form.
* `POST /area-clienti/accesso` (CSRF) **consumes** the token (marked `used_at`), stamps
  `customer_user.last_login_at`, calls every `CustomerSignInListener` (the Phase 2
  `customers` module sets `email_verified_at` and moves `PENDING_VERIFICATION` on, chapter 08
  §2.4), opens the session and redirects to `/area-clienti/ordini/` **without the token in the
  URL**. A second `POST` is refused with the same «Il link non è più valido» page.
* `GET /area-clienti/registrazione/?t=…` validates a `REGISTER` token and renders the page
  without consuming it; the registration form and its consuming `POST` are the Phase 2
  `customers` module (CUS-02). In Phase 1 the page is a stub.
* The same `LoginTokenService` serves `VERIFY_EMAIL` and `CHANGE_EMAIL` (15 minutes,
  `ls.security.tokens.*`): `issue`, `peek` (what a `GET` may do), `consume` (what only a `POST`
  does), with the purpose checked.

### Identity from the session

* The session principal is `CustomerPrincipal` (`customer_user` id, `customer` id, e-mail at
  sign-in) with `ROLE_CUSTOMER`; every customer-area controller takes the customer from the
  session, **never from a request parameter** (OP-S1, SEC-11, SEC-12). One login per customer
  in M1 (`ls.customers.max-users-per-customer=1`, `// Q-11`); the model allows more later.
* Sessions: **60 minutes idle, 12 hours absolute** (`ls.security.sessions.customer.*`), id
  rotated at sign-in, cookie as in ADR 0002. **Logout** is `POST /area-clienti/logout` (CSRF)
  → `/area-clienti/`. `CustomerSessionService.endAllSessions(customerUserId)` expires every
  session of the login in the `SessionRegistry`; the next request of an expired session is
  invalidated and sent to the sign-in page (HTMX/JSON callers get 401). The `customers` module
  calls it on a decline and an e-mail change (chapter 10 §2).
* Old legacy links (`/mono/email-confirm`, Q-30) never confirm anything: ADR 0011 answers 410
  with a page pointing to `/area-clienti/`.

## Consequences

* The customer types only an e-mail, as today, but the order is placed by the authenticated
  session (OP-S1 closed) and the page reveals nothing about which addresses exist (OP-S2
  closed; the only difference is the e-mail received, and only by the mailbox owner).
* A company mailbox whose scanner opens links no longer burns the link; the customer must
  press «Accedi». A link forwarded to someone else still works once within 15 minutes: the
  mailbox is the credential, as in the brief.
* `login_token` rows are deleted 24 hours after expiry (retention job of stage 3); outbox
  bodies 30 days after delivery (Phase 2).
* New copy for the owner's review at the next gate: the request page, the «Accedi» page, the
  «Il link non è più valido» page, the two e-mails (EML-01 sign-in and registration variants),
  the Phase 1 stub texts.

## References

Chapter 03 §5.1, §8, §10 (OP-S1, OP-S2, OP-10, OP-11); chapter 08 §2.3 `customer_user`,
`login_token`, §2.4 "Login token"; chapter 09 CUS-01, EML-01; chapter 10 §2 "Customer
authentication (magic link)", "E-mail"; 12a §5 (SEC-11, SEC-12, SEC-19); 12c §1.4;
`docs/plan.md` §7.1 D-69, §7.2 D-100, §8.1 Q-11, §8.2 PD-14; reader notes
`docs/phase0/readers/03-03-legacy-order-portal.md`; ADR 0002 (chain, headers), ADR 0003 (staff),
ADR 0011 (old links), ADR 0015 (outbox), ADR 0020 (`login_token`). Code:
`it.logisticasanchez.web.auth` (`CustomerSignInService`, `LoginTokenService`,
`CustomerSessionService`, `CustomerPrincipal`, `CustomerSignInListener`, `EmailHasher`,
`AuthMails`, `CustomerSignInController`); tests `CustomerSignInIT`, `LoginTokenServiceIT`.
