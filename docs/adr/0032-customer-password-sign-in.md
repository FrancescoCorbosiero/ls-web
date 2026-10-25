# 0032 · Customers sign in with e-mail and password; the link stays for the first access

* **Status:** Accepted
* **Date:** 2026-10-25
* **Requirement IDs:** CUS-01, CUS-02, CUS-06 (chapter 09), NFR-02, NFR-03 · **Questions:** Q-56 (decided by the owner on 2026-10-25) · **Findings:** OP-S1, OP-S2 (chapter 03 §10); amends ADR 0004 and ADR 0005, extends ADR 0031

## Context

ADR 0004 signs customers in only by e-mailed link (CUS-01), and the session prompt of
2026-10-25 listed «customers sign in with magic links only, no passwords» among the decisions
not to reopen. ADR 0031 then kept a device signed in for 60 days. The same day the owner
reversed the first decision: «se questo maledetto link non è comodo mettiamo una semplice basic
auth! non mi sembra di star chiedendo la luna!». The customers want to type an e-mail and a
password, as on any other site, without waiting for an e-mail.

Literal HTTP Basic authentication (the browser's own pop-up) was not used: it has no logout, no
Italian or branded page, no «password dimenticata», resends the password with every request and
does not fit the session, CSRF and remember-me model of the app. A sign-in form with e-mail and
password gives the same simplicity.

## Decision

* **Sign-in page** (`/area-clienti/`): e-mail and password first (`POST /area-clienti/password`,
  CSRF), with «Resta connesso» (ADR 0031); below, «Prima volta o password dimenticata?» with the
  link form of ADR 0004 (button «Ricevi il link»), which also leads unknown addresses to the
  registration as before.
* **Refusals look the same.** An unknown e-mail, a login without a password, a disabled login
  and a wrong password all answer the same page, «E-mail o password non corrette.», with the
  e-mail typed back and never the password; the same bcrypt work is done in every case, so the
  timing says nothing either (OP-S2 stays closed). Attempts are rate-limited per client IP (30
  per 15 minutes) and per normalised e-mail (10 per 15 minutes); past the limit the error model
  answers 429 (`ls.ratelimit.signin-password-*`).
* **Storage:** `customer_user.password_hash` and `password_changed_at` (V015), bcrypt through
  the application's `DelegatingPasswordEncoder` (as staff, ADR 0003). A successful sign-in
  stamps `last_login_at`; it does not verify the e-mail (only a link does).
* **Policy:** at least 8 characters (`ls.security.customer-password.min-length`; NIST's floor
  for passwords people choose; staff keep 12) and not in the common-password list of ADR 0003;
  no composition rules.
* **Choosing the password.** A new customer chooses it on the registration summary, right
  before «Conferma» (the e-mail is already proven by the registration link; nothing is created
  until the password is acceptable and repeated identically). Every other customer — created
  by staff (ADM-12), or who never set one — signs in by link and sets it in «Il tuo profilo»;
  the orders home shows «Scegli una password per entrare senza aspettare l'e-mail.» until then.
* **Changing it, forgetting it.** The profile asks for the current password unless the session
  was opened by an e-mailed link in this browser session (the «password dimenticata» path: ask
  for a link, then set a new password without the old one). A change ends the login's other
  sessions and remembered devices (`CustomerSessionService.endAllSessions`); this browser goes
  on with a new session, remembered if it was. Setting and changing are audited
  (`customer.password.set`, `customer.password.change`).
* The anonymisation (NFR-03) clears the password; an e-mail change (CUS-06) keeps it and ends
  the sessions as before; a decline keeps it (the customer still sees its status page).
* The `dev-seed` ACTIVE customer gets a printed random password, so the local app can be tried
  with e-mail and password.

## Consequences

* Customers sign in as on any site; the link is needed only the first time, for a forgotten
  password, or for customers created by the office.
* A password is one more secret the customer can reuse elsewhere; the rate limits, the
  common-password list and the uniform answer limit guessing. There is no e-mail notice of a
  password change yet (open point for the owner); the e-mail change still needs the mailbox.
* The e-mail button of the link form reads «Ricevi il link» (was «Avanti»), on the sign-in page
  and on the «Il link non è più valido» page.
* New copy for the owner's review: the sign-in page («Accedi all'area clienti», «E-mail o
  password non corrette.», «Prima volta o password dimenticata?», «Ti mandiamo un link per
  entrare; se sei un nuovo cliente, il link ti porta alla registrazione.», «Ricevi il link»), the
  profile section «Password» and its messages, the registration's «Scegli la password», the home
  callout.

## References

Chapter 09 CUS-01, CUS-02, CUS-06; chapter 10 §2, §4; chapter 11 Q-56; ADR 0003 (bcrypt,
common-password list), ADR 0004 (link sign-in), ADR 0005 (registration), ADR 0031 (remembered
device). `V015__customer_password.sql`; `auth.CustomerPasswordService`,
`CustomerSignInController`, `CustomerSessionService.SignInMethod`, `customers.ProfileController`,
`RegistrationController`, `orders.CustomerOrdersController`; templates
`auth/customer-request.html`, `customers/profile.html`, `customers/registration-review.html`,
`orders/list.html`; tests `CustomerPasswordIT`, `DevSeedCommandIT`, `journey.spec.ts`.
