# 0045 · One sign-in per browser; the back office has its own tint

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ADM-01, CUS-01, NFR-02 (chapter 09) · **Owner:** observation and request of 2026-10-26 («from same device, if I log as admin and then log on another tab as customer, I get logout from the admin and vice-versa even if I checked the flag for 60 days … this won't happen for our real production customers right?»; «slight visual difference between admin dashboard and customers dashboard being simply a different background color»)

## Context

The two private areas live on one origin — `ordini.` and `dashboard.` only redirect to
`/area-clienti/` and `/gestione/` (plan §4.3, `HostRoutingFilter`) — and share the one session
cookie of chapter 10 §2 (`__Host-LS_SESSION`, path `/`; `LS_SESSION` in the local and test
profiles). A session holds one principal, and every sign-in starts a new session and invalidates
the old one (ADR 0003, ADR 0004). The owner tried the admin and a customer in two tabs of the
same browser:

* the customer's sign-in ended the staff session: in the admin's tab `/gestione/` showed «Login»
  and the other back-office pages answered 403 (an authenticated principal without the role,
  ADR 0002);
* the admin's sign-in did the same to the customer. The remember cookie of ADR 0031 (path
  `/area-clienti/`) was still there, but it only opens a session for a browser with nobody
  signed in, so the customer area answered 403 while the staff session lived and opened again
  by itself after «Esci» from the back office.

Both areas also looked the same (`--paper-2` page, white header), so with two tabs open nothing
said which was which.

## Decision

* **One sign-in per browser stays.** A browser profile holds the staff session or the customer
  session, never both; the later sign-in ends the earlier session on the server. This is the
  security baseline at work (one `__Host-` cookie, a new session at every sign-in) and needs no
  code. Not chosen: a session cookie per area (the container keeps one session per request: it
  would take a session layer of our own, for the convenience of whoever uses both roles in one
  browser) and a host of its own for the back office (host-only cookies would give each area its
  own session, but it is a DNS and deployment change and undoes the `dashboard.` redirect; the
  owner can ask for it at any time).
* **Customers in production are not affected.** Each uses their own devices with a customer
  account only, and nothing done elsewhere — a staff sign-in or «Esci», a colleague's sign-in,
  the same customer on a phone — touches another browser's session or remembered device. Only
  whoever uses a staff and a customer account in the same browser meets it (the owner or the
  office trying the customer area; a person with two customer accounts, where the last sign-in
  wins). They use a private window or a second browser profile; locally,
  `http://localhost:8080` for one role and `http://127.0.0.1:8080` for the other (cookies are
  kept per host).
* **Pinned by tests:** `SignInPerBrowserRealServerIT` on a real server with one cookie jar per
  browser, `e2e/tests/areas.spec.ts` with two tabs of one browser context and with two contexts.
* **The back office has its own tint.** The private header names its area (`data-area`,
  `'staff'` or `'customer'`, on `layout/private :: siteHeader`); `app.css` gives the pages whose
  header is the staff one a teal-tinted background (`#e4f1f4` instead of `--paper-2`) and a
  3-pixel `--teal-500` line on top of the header. The customer area does not change. The
  back-office login, two-factor and set-password pages carry the tint too, so a tab shows its
  area before anyone signs in. The background uses `:has()`, already used by `main.css`; a
  browser without it shows the line only. The muted text keeps 4.7:1 on the tint (WCAG AA);
  axe runs on the tinted pages in the E2E suite.

## Consequences

* The owner and the office tell the two areas apart at a glance, in a tab or on a screenshot.
* Using both roles at the same time needs two browsers or a private window; the Italian summary
  for the owner says so.
* No new Italian text; the colours are reviewed by the owner with the rest of ADR 0029.

## References

Chapter 10 §2 (sessions, cookie); ADR 0002 (refusals), ADR 0003 and 0004 (sessions, rotation),
ADR 0029 (private-area interface), ADR 0031 (remembered device); plan §4.3 (host routing).
Code: `templates/layout/private.html`, `static/css/app.css`. Tests:
`SignInPerBrowserRealServerIT`, `e2e/tests/areas.spec.ts`.
