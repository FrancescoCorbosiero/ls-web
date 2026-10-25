# 0003 · Staff authentication, sessions, roles and account provisioning

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** ADM-01 (chapter 09) · **Questions:** Q-17 (roles, accounts recreated), PD-10 (lock-out figures), Q-55 (no legacy accounts) · **Findings:** CMS-01, CMS-02, CMS-03 (chapter 04 §8), SEC-08, SEC-10 (12a §5); session lifetimes of chapter 10 §2

## Context

The legacy CMS logged in with `POST /mono/auth/login` and kept the bearer token, the username
**and the typed password** in page memory for the whole session, with the password visible in
a plain text field (CMS-01…03; chapter 04 §1.5, §4.1). The backend issued a refresh token that
never expired and had no logout (SEC-08) and no brute-force protection (SEC-10); its roles
were `ADMIN`/`CUSTOMER` with an unused `policy` (chapter 02 §7.1, D-66). Chapter 09 ADM-01 asks
for username + password hashed, roles `ADMIN` and `OPERATOR`, logout, lock-out, no seeded
account outside `local`/`test`, a first ADMIN created by a one-off command that prints a
one-time set-password link, passwords of at least 12 characters checked against common
passwords, and the other accounts created by an ADMIN with the same link. Chapter 10 §2 sets
the sessions (staff 30 minutes idle, 10 hours absolute, `__Host-` cookie, id rotated on login)
and §4 the hashing (bcrypt/argon2). Legacy staff accounts are not migrated (Q-55, chapter 08
§3.9): passwords are unknown or hashed with an unknown scheme, so accounts are recreated.

## Decision

### Login and roles

* **Form login** on the page `/gestione/` («Login», fields `username` and `password`, a real
  `type="password"`), processed by Spring Security's form login on `POST /gestione/login`
  (CSRF-protected). Every failure — wrong password, unknown username, account locked, account
  without a password yet, account deactivated — answers the one legacy sentence
  **«Username o password errate.»** (redirect to `/gestione/?errore=1`); nothing reveals which
  part was wrong or whether the account exists or is locked. The provider mitigates timing
  differences for unknown usernames (Spring's `DaoAuthenticationProvider`).
* **Usernames** are stored and matched trimmed and lower-case (database CHECK). Legacy
  usernames may be reused (chapter 08 §3.9); nothing else of the legacy accounts is kept.
* **Roles** are the `StaffRole` enum `ADMIN` / `OPERATOR` (`// Q-17`), granted as `ROLE_ADMIN` /
  `ROLE_OPERATOR`; the route table decides which routes need `STAFF` (either) or `ADMIN`
  (ADR 0002). The session principal is `StaffPrincipal` (id, username, display name, role).
* **Passwords** are hashed with **bcrypt, cost 12**, through a `DelegatingPasswordEncoder`
  whose hashes carry the `{bcrypt}` prefix, so the algorithm can change later (argon2) without
  touching stored rows. The **policy** (`PasswordPolicy`): at least **12 characters** and not
  in the bundled common-password list (`auth/common-passwords.txt`: the SecLists
  10k-most-common list in full plus every entry of at least 12 characters of the NCSC 100k
  list, MIT-licensed, compared case-insensitively). No composition rule beyond that.
* **Lock-out (PD-10)**: `failed_logins` grows on every failure of an existing account; at
  `ls.security.staff.lockout.threshold` (default 10) the account is locked for
  `ls.security.staff.lockout.duration` (default 15 min, `locked_until`); a success resets both.
  After the lock expires the count starts again from one. Independently, the login endpoint is
  **rate-limited per client IP** with the platform key `staff.login` (`ls.ratelimit.staff-login`,
  default 20 attempts per 15 min; 429 with `Retry-After` before any password check).

### Sessions

* **Idle 30 minutes, absolute 10 hours** (`ls.security.sessions.staff.*`, chapter 10 §2; the
  absolute limit is enforced by `SessionLimitsFilter` from the stamp the login handler writes).
  Session id rotated on login (`SessionFixationProtectionStrategy`, no attribute migrated).
  Cookie `__Host-LS_SESSION`, `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/` in staging and
  production; plain `LS_SESSION` without `Secure` in `local`/`test` (ADR 0002).
* **Logout** is `POST /gestione/logout` with the CSRF token; it invalidates the session and
  redirects to `/gestione/`. The session registry (`SessionRegistryImpl`, fed by
  `HttpSessionEventPublisher`) also holds staff sessions, so an administrative "end every
  session of this account" is one call away when the back office needs it (Phase 2).

### Provisioning

* **No staff account is created by a migration or a seed outside `local`/`test`** (tests
  `NoSeededStaffAccountsTest`, `NoSeedDataOutsideLocalAndTestProfilesTest`). The `dev-seed`
  command (Phase 1 stage 6) creates demo users with random passwords printed once.
* **The first ADMIN** comes from the command
  `create-staff-user --username=… --role=ADMIN [--name=…] [--email=…]`
  (`java -jar app.jar create-staff-user …` or
  `./mvnw spring-boot:run -Dspring-boot.run.arguments=create-staff-user,--username=…,--role=ADMIN`):
  the app starts once with the server on an ephemeral loopback port and no background job,
  creates the account **without a password** and prints the one-time link
  `https://<site-url>/gestione/imposta-password?t=<token>`, then exits (0; 1 username taken;
  2 bad arguments). The command never reads or prints a password.
* **Set-password links** live in the table `staff_setup_token` (V010): SHA-256 of a 256-bit
  random token, **24 hours**, **single use**; the `GET` of `/gestione/imposta-password?t=…`
  only renders the form (and `Referrer-Policy: no-referrer`), the CSRF-protected `POST` checks
  the policy, stores the hash, clears the lock-out counters and consumes the link. A used or
  expired link shows «Il link non è più valido» and asks for a new one from an ADMIN.
* **Other accounts** are created by an ADMIN through `StaffAccountService.createByAdmin`
  (`@PreAuthorize("hasRole('ADMIN')")`; the screen is the Phase 2 back office): the link goes
  to the account's e-mail **through the outbox** as kind `EML-S1`, the raw token held only
  encrypted (`token_ciphertext`, no `login_token` reference) and wiped after delivery; the
  action is audited (`staff.create`). An ADMIN may issue a fresh link for an existing account.
* TOTP for staff stays an M2 seam (`ls.security.staff.totp.enabled=false`, flag
  `security.adminTotp`, ENH-09).

## Consequences

* Customers and the office never see a password in a page, a log or an e-mail; the office
  sets passwords through links and can be locked out for 15 minutes after 10 failures (the
  owner may change both figures and the lifetimes in the settings).
* The outbox gained a staff e-mail kind (`EML-S1`) outside the chapter 09 catalogue and the
  rule "token ciphertext needs a `login_token`" was dropped (V010); ADR 0015 lists `EML-S1` when
  the Phase 2 `notifications` module completes the catalogue.
* The chain has a second extension point, `SecurityChainContributor`, used only by `auth`
  to add the form login, the logouts and the session registry; the authorisation stays the
  route table of ADR 0002.
* The owner confirms at the next gate (Q-17): the list of staff accounts and roles; the
  texts of the login, set-password and e-mail screens (new copy, Italian informal register).

## References

Chapter 04 §1.5, §4.1, §7.1, §8 (CMS-01…03); chapter 08 §2.3 `staff_user`, §3.9; chapter 09
ADM-01; chapter 10 §2 (sessions), §4 (passwords, lock-out); 12a §5 (SEC-08, SEC-10);
`docs/plan.md` §8.1 Q-17, §8.2 PD-10, §7.2 D-66; reader notes `docs/phase0/readers/04-04-legacy-cms.md`;
ADR 0002 (chain, cookie, route matrix), ADR 0004 (customer sign-in), ADR 0015 (outbox),
ADR 0020 (`staff_user`, V010). Code: `it.logisticasanchez.web.auth` (`StaffAccountService`,
`StaffUserDetailsService`, `StaffPrincipal`, `PasswordPolicy`, `AuthChainContributor`,
`StaffController`, `CreateStaffUserCommand`), `V010__staff_setup_tokens.sql`; tests
`StaffLoginIT`, `StaffLoginRealServerIT`, `CreateStaffUserCommandIT`, `StaffAccountServiceIT`,
`PasswordPolicyTest`, `SessionCookieConfigurationTest`.
