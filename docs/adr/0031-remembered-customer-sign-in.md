# 0031 · Customers stay signed in on a device for 60 days

* **Status:** Accepted
* **Date:** 2026-10-25
* **Requirement IDs:** CUS-01, CUS-02 (chapter 09), NFR-02, NFR-03 · **Questions:** PD-10 (session and token lifetimes; decided by the owner on 2026-10-25) · **Findings:** chapter 10 §2 «Sessions (the owner may change these)»; amends ADR 0004 («Identity from the session»)

## Context

ADR 0004 implements chapter 10 §2: the e-mailed sign-in link is valid 15 minutes and single
use, the customer session lasts 60 minutes idle and 12 hours at most. A customer who comes
back the next day types the e-mail again and opens the inbox again; every restart of the app
(a deploy) ends every session as well, because sessions live in memory.

On 2026-10-25 the owner asked (in the chat): «Also set the lifetime of magic link of 60 days
so customers don't have to always check email inbox». The aim is stated in the sentence: open
the e-mail once, then stay signed in. Two readings were weighed:

* **The e-mailed link valid for 60 days.** It does not reach the aim: the link is single use
  (CUS-01, the `POST` consumes it), so every sign-in would still need a new e-mail. Making it
  reusable for 60 days would turn every sign-in e-mail — forwarded, archived, synced to shared
  devices, scanned by mail filters — into a 60-day key to the customer area.
* **The sign-in lasts 60 days on the device.** It reaches the aim with the usual pattern of
  e-mail-link sign-ins: the e-mail is opened once per device every 60 days.

The second is implemented; the first is not.

## Decision

* **The link does not change:** `SIGN_IN` tokens stay 15 minutes, single use, consumed only by
  the CSRF-protected `POST` of the «Accedi» page (chapter 10 §2, ADR 0004).
* **«Resta connesso».** The «Accedi» page and the registration summary show a checkbox,
  ticked by default: «Resta connesso su questo dispositivo per 60 giorni», with the hint «Togli
  la spunta se usi un computer condiviso.» (the number of days comes from the setting). Ticked,
  the sign-in (`CustomerSessionService.signIn(principal, true, …)`) stores a
  `remembered_sign_in` row (V014: the customer user, the SHA-256 of a random 256-bit token,
  `expires_at` = sign-in time + 60 days, `last_used_at`) and sets the token in the cookie
  **`__Secure-LS_REMEMBER`**: `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/area-clienti/`, no
  `Domain`, `Max-Age` 60 days. The local and test profiles, whose session cookie is plain
  `LS_SESSION` without `Secure` (ADR 0002), use plain `LS_REMEMBER` the same way: the remember
  cookie follows `server.servlet.session.cookie.secure`. A new sign-in on the same device
  replaces its previous token; a sign-in with the box unticked deletes the token the device had,
  so a shared computer never reopens the previous customer's account once the new session ends.
* **Restoring.** `RememberedSignInFilter` (module `auth`, in the chain right after
  `SessionLimitsFilter` and before the CSRF filter) looks only at requests under
  `/area-clienti/` that carry the cookie: without a signed-in user, a token whose row exists,
  has not expired and belongs to a login that is not disabled opens a new customer session
  exactly as the link does (new session id, registry entry, fresh CSRF token, 12-hour stamp);
  `last_used_at` is stamped. Any other cookie is cleared. Requests without the cookie never
  touch the database. The token is not rotated and the 60 days are **not extended by use**:
  after them the customer asks for a new link.
* **The session limits stay** (60 minutes idle, 12 hours absolute): they bound the server
  session and its CSRF token; the cookie opens a new session when one ends, and survives a
  restart of the app.
* **Ending it.** «Esci» (`POST /area-clienti/logout`) deletes the row and clears the cookie,
  before the CSRF check, so it works also from a page whose session had already ended (the
  rest of the logout still needs the token). `CustomerSessionService.endAllSessions` — called
  by a decline (ADM-02), an e-mail change (CUS-06) and the anonymisation (NFR-03) — now also
  deletes every remembered device of the login. The retention job `remembered-sign-ins`
  deletes the rows that have expired.
* **Seam:** `ls.security.sessions.customer.remember` (default `60d`; environment
  `LS_SECURITY_SESSIONS_CUSTOMER_REMEMBER`). `0` turns the feature off: no checkbox, no cookie,
  rows already stored are ignored — the sign-in of M1.
* Staff sessions do not change (30 minutes, 10 hours; two-factor login is ENH-09).
* Public pages stay cookie-free: the cookie is limited to the customer area, and the cookie
  page («Cookie tecnici dell'area clienti e della gestione») lists it.

## Consequences

* A customer opens the sign-in e-mail once per device every 60 days; on a shared computer they
  untick the box or press «Esci».
* Whoever holds the device (or its cookie) can use the customer area for up to 60 days without
  the mailbox: see orders, send orders that the office still approves, ask for an e-mail
  change (which needs the new mailbox). The cookie cannot be read by scripts, travels only
  over HTTPS and only to `/area-clienti/`, is stored only as a hash, and ends with «Esci», an
  e-mail change, a decline and the anonymisation. There is no staff action to sign a customer
  out of every device yet; the e-mail change does it.
* One more table (`remembered_sign_in`, no personal data besides the customer-user id) and one
  more technical cookie; no consent needed (art. 122 Codice privacy), listed on the cookie page.
* New copy for the owner's review at the next gate: the checkbox label and hint, the cookie
  page row («Ti mantiene connesso all'area clienti su questo dispositivo, se all'accesso lasci
  la spunta «Resta connesso»» — «60 giorni dall'accesso, o fino a quando premi «Esci»»).

## References

Chapter 09 CUS-01, CUS-02; chapter 10 §2 «Customer authentication (magic link)»; chapter 11
PD-10 (Decision column); ADR 0002 (cookie naming per profile), ADR 0004 (link, sessions,
`endAllSessions`), ADR 0005 (registration), ADR 0024 (cookie page, anonymisation).
`V014__remembered_sign_in.sql`; `auth.RememberedSignIn`, `RememberedSignInRepository`,
`RememberedSignInService`, `RememberedSignInFilter`, `ExpiredRememberedSignInRetentionJob`,
`CustomerSessionService`, `CustomerSignInController`, `customers.RegistrationController`,
`templates/auth/remember.html`; tests `RememberedSignInIT`, `RememberedSignInOffIT`,
`RememberedSignInServiceTest`.
