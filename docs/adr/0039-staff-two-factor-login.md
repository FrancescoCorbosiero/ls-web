# 0039 · Two-factor login for staff (ENH-09)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-09 (flag `security.adminTotp`); touches ADM-01, PD-10, NFR-02 · **Owner:** request of 2026-10-26 («procedi con quelli che puoi fare ora»); Q-17 default «2FA in M2»

## Context

Chapter 09: «Two-factor authentication (TOTP) for staff.» ADR 0003 left the seam: the column
`staff_user.totp_secret` (V002, «bytea, encrypted» in chapter 08) and the flag. Staff sign in
with username and password (Spring Security form login), lock out after 10 failures (PD-10),
and the back office holds every customer's data.

## Decision

* **Everyone, when the flag is on.** With `security.adminTotp` on, every staff login asks, after
  the right password, the 6-digit code of an authenticator app (RFC 6238: HMAC-SHA1, 30-second
  steps, 6 digits — what Google Authenticator, Microsoft Authenticator and the like make). Turning
  the flag on is the owner's choice for the whole office: no per-user opt-out.
* **The second step.** The form login's success handler keeps the password result out of the
  security context: the session holds only a *pending login* (user, time, wrong codes) and the
  browser goes to `/gestione/verifica` (a public sign-in entry of the back office, like the login
  and the set-password link, ADR 0002). The right code opens the staff session exactly as the form
  login does (new session id, registry, CSRF token, absolute-limit stamp) and records the login.
  The pending login lasts 5 minutes; 5 wrong codes start the login again; **each wrong code is a
  failed login** for the PD-10 lock-out; an account disabled or locked meanwhile is not signed in.
* **Set-up on the same page.** A user without a secret gets one (20 random bytes) and sees it as
  a QR code — the `otpauth://` URI encoded by **ZXing core** in the app and drawn as one inline SVG
  path (no image, no script, nothing sent anywhere; a new library, ADR 0001) — and as a Base32 key
  for typing by hand; the first right code stores it. Nothing is stored before that code.
* **The secret at rest:** AES-256-GCM in `staff_user.totp_secret`; the key is derived
  (HMAC-SHA256 with a purpose label) from `LS_TOKEN_ENCRYPTION_KEY`, already required in
  production: no new credential, and the outbox tokens and the TOTP secrets never share a key.
  Changing that key means every staff user sets the app up again (an ADMIN reset each, or the
  command).
* **Each code once.** `staff_user.totp_last_step` (V020) keeps the step of the last accepted code;
  codes of that step or earlier are refused; a code is accepted one step around now (a phone
  clock a little off). The check locks the user row.
* **Lost phone.** An ADMIN resets a user's secret from «Utenti della gestione» («Azzera»; the
  list shows who has it set up); the user sets the app up at the next login. For an ADMIN who
  cannot sign in: `java -jar app.jar reset-staff-totp --username=…` on the server (audited as a
  system action). No recovery codes: one office, an ADMIN or the server is always at hand.
* Audit: `staff.totp.enable` (the user), `staff.totp.reset` (the ADMIN, or system).
* The unused setting `ls.security.staff.totp.enabled` of ADR 0003 is removed: the flag is the
  switch.

### Flag off

The login is the M1 one (password only) even for a user with a stored secret; `/gestione/verifica`
and the reset answer 404; «Utenti della gestione» shows nothing of two-factor
(`StaffTwoFactorOffIT`). The flag is «Disponibile» in «Impostazioni».

## Consequences

* A stolen password alone no longer opens the back office.
* Each staff member needs a phone with an authenticator app; whoever turns the flag on should
  tell the office first.
* New Italian texts (code page, set-up, staff list, flashes) wait for the owner's review.

## References

Chapter 09 ENH-09, ADM-01; chapter 08 `staff_user`; Q-17; PD-10; ADR 0001 (stack), 0002
(routes), 0003 (staff authentication), 0025 (flags). Code: `auth.StaffTwoFactorService`,
`StaffTwoFactorController`, `StaffSignIn`, `PendingStaffLogin`, `Totp`, `TotpQr`, `TotpSecrets`,
`ResetStaffTotpCommand`, `StaffLoginSuccessHandler`, `backoffice.BackofficeSettingsController`;
V020. Tests: `TotpTest`, `StaffTwoFactorIT`, `StaffTwoFactorOffIT`, `e2e/tests/m2.spec.ts` [ENH-09].
