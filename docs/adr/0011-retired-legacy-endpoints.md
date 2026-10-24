# 0011 · Retired legacy endpoints answer 410; old e-mail links get an Italian page

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** API-03, CUS-01 · **Questions:** Q-30, Q-50, PD-05 · **Findings:** OP-S1, SEC-14, SEC-15, SEC-16, D-27, D-55, D-98

## Context

After the DNS switch every request still aimed at the legacy backend (`app.logisticasanchez.com`
and, through the Netlify proxy line of `_redirects`, the apex) reaches the new app. Chapter 02
§10 keeps three endpoints (ADR 0010) and retires the 33 other mappings of
`legacy-ls-api-security.md` §5: the portal and CMS APIs, the free e-mail sender, the hard
deletes and the id oracle. E-mails the legacy already sent carry `GET` links —
`/mono/email-confirm?email=…&verificationCode=…` (never expires), `/mono/report?…`,
`/mono/download-report?…`, the view pages under `/mono/mono/*.html`, `/mono/request-success.html`
— and embed the logo `/mono/img/logistica-sanchez-logo.jpg`. `EMAIL_ORIGIN` defaulted to the
apex, so these links exist on both hosts.

## Decision

* **Every other `/mono/**` path answers `410 Gone`** with the platform JSON error schema
  (`MonoGoneController`, mapped on `/mono/**` after the explicit kept mappings): `{"status":410,
  "code":"gone","reason":"Questa pagina non esiste più.","detail":"Servizio non più
  disponibile: usa https://logisticasanchez.com/area-clienti/","requestId","timestamp"}`. The
  chapter 02 §10.3 wording is kept in `detail`; the owner reviews it with the other new texts.
  No CORS headers, no body is read, nothing is written: a retired path never performs its old
  action (OP-S1, SEC-14…16). The list of retired mappings is the `CompatApiIT` table (33 rows
  plus unknown paths and methods).
* **Old `GET` links** (`/mono/email-confirm`, `/mono/report`, `/mono/download-report`,
  `/mono/request-success.html`, `/mono/mono/*.html`) get the Italian page `compat/gone.html`
  with **status 410**, the public layout, `noindex`, the same text for every parameter («Link
  non più valido» → «Vai all'area clienti» `/area-clienti/`). The page never confirms an
  address (a pending customer verifies by signing in, ADR 0004) and never serves report data.
  Served on every host, so Q-50 (the production `EMAIL_ORIGIN`) needs no answer.
* **The logo** `/mono/img/logistica-sanchez-logo.jpg` is served from the site assets
  (`static/img/brand/logo-square.png`, `image/png`, one week of caching): old e-mails keep
  their image.
* `ls.compat.hosts` (Q-30) stays declared but unused: the page is served wherever the app
  answers; the host rules of the platform (`www`, `ordini.`, `dashboard.`) run first.

## Consequences

* A stale portal or CMS tab shows its generic error dialog; the S3-hosted old portal, if still
  online, cannot create orders or read customers.
* Chapter 10 §7's cut-over block list and this ADR's retired list are the same table: the
  MIG-03 runbook (now DNS plus a fresh database, Q-55) cites `CompatApiIT`.
* `GET` on a kept path (e.g. `GET /mono/api/order/trace/state`) falls into the catch-all and
  answers 410 rather than 405; no legacy client does that.

## References

Chapter 02 §10.1, §10.3, §10.4 FX-13/FX-14; chapter 09 API-03; chapter 11 Q-30, Q-50;
`docs/phase0/readers/legacy-ls-api-security.md` §5; ADR 0002, 0004, 0010. Code:
`MonoGoneController`, `templates/compat/gone.html`, `CompatApiIT`.
