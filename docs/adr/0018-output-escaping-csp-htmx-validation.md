# 0018 · Output escaping, CSP without `unsafe-*`, nonce, HTMX configuration, input validation

* **Status:** Accepted
* **Date:** 2026-10-23
* **Requirement IDs:** NFR-02, NFR-01, PUB-05 · **Questions:** Q-22 · **Findings:** CMS-04, CMS-27…CMS-29 (same origin, self-hosted assets, nothing ported from the CMS), OP-S3 (HTTPS only, HSTS on every host), OP-S5, SEC-13; F-15, F-16 (CORS with credentials and plain-HTTP origins not reproduced)

## Context

The legacy CMS rendered free text unescaped (stored XSS, CMS-04, SEC-13) and loaded assets
from third parties; the new static site ships a strict `public/_headers` set (chapter 06
§6.4) that the monolith must reproduce exactly (PUB-05), and chapter 10 §4 asks for a CSP at
least as strict, without `'unsafe-inline'` or `'unsafe-eval'`, in the customer area and the
back office, with HTMX configured for it (chapter 06 §8.5). The headers must be set by the
app, not the proxy, so tests can assert them (plan §4.3).

## Decision

### Output escaping and templates

* Thymeleaf escapes every `th:text` and attribute value; `th:utext` is forbidden on any
  user-supplied value (review lens of Phase 3; no exception in M1). Free-text fields are
  stored as text and rendered escaped; HTML in legacy data is moot (Q-55).
* No inline `<script>` or `style=""` in any template: public templates because of the
  `_headers` CSP, private templates because of the nonce policy (an inline style has no
  nonce). JS sets styles through the CSSOM only; HTMX indicator CSS lives in `main.css`.

### Headers set by the app (`SecurityHeadersFilter`)

* On every response: `X-Content-Type-Options: nosniff`, `Referrer-Policy:
  strict-origin-when-cross-origin`, `X-Frame-Options: SAMEORIGIN`, `Permissions-Policy:
  camera=(), microphone=(), geolocation=(), payment=(), browsing-topics=()`;
  `Strict-Transport-Security: max-age=31536000` only where TLS terminates for real
  (`ls.security.hsts.enabled`, true in the `prod` profile; the deploy stage adds
  `includeSubDomains` once every host serves HTTPS, OP-S3).
* **Public routes:** exactly the CSP of `legacy/ls-home/public/_headers` with one change,
  `connect-src 'self'` instead of `'self' https://app.logisticasanchez.com`, because the
  tracking endpoint is same-origin now (chapter 10 §2): `default-src 'self'; script-src
  'self' 'inline-speculation-rules'; style-src 'self'; img-src 'self' data:; font-src 'self';
  connect-src 'self'; frame-src https://www.google.com https://maps.google.com; form-action
  'self'; base-uri 'self'; frame-ancestors 'self'; object-src 'none';
  upgrade-insecure-requests`. `PublicHeadersIT` (`PUB-05`) reads the legacy file at test time,
  so a drift between the two fails the build.
* **Private areas** (`/area-clienti/**`, `/gestione/**`): `default-src 'self'; script-src
  'self' 'nonce-<random>'; style-src 'self'; img-src 'self' data:; font-src 'self';
  connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none';
  object-src 'none'` — never `'unsafe-inline'` or `'unsafe-eval'`. The nonce is 128 random
  bits per request, exposed to templates as `${cspNonce}` for the few inline scripts the
  layout needs (`<script th:attr="nonce=${cspNonce}">`) and for the vendored htmx script
  tag. `Cache-Control: no-store` on both areas.
* **Caching (PD-02):** `/fonts/**` `public, max-age=31536000, immutable`; `/img/**` and
  `/*.png` `public, max-age=604800`; every other public answer `no-cache` with an `ETag`
  (`PublicEtagFilter`, 304 on `If-None-Match`); `ls.site.cache.*` holds the two max-ages;
  compression is on for text types. A public path declared `noStore` by its module (the link
  page `/area-clienti/accesso`) is `no-store` and never gets an ETag.
* **Public pages are cookie-free** (chapter 10 §2): nothing outside the private areas touches
  the session — in particular the deferred CSRF token is read only inside them, since reading
  it creates the session that stores it. `PublicPagesCookieFreeIT` asserts it on a real port
  (a mock request never shows the container's session cookie).

### HTMX under the CSP

The private layout includes `~{fragments/htmx :: meta}` in `<head>`, which renders
`<meta name="htmx-config" content='{"includeIndicatorStyles":false,"allowEval":false,
"allowScriptTags":false,"selfRequestsOnly":true}'>` (the value is the constant
`HtmxConfig.META_CONTENT`, asserted by `PrivateHeadersIT`) and the `_csrf` /
`_csrf_header` meta tags; `<body th:attr="hx-headers=${hxHeaders}">` sends the CSRF token as
`X-CSRF-TOKEN` with every HTMX request (model attribute of `PlatformModelAttributes`). Not
used anywhere: `hx-on`, `hx-vars`, `js:` expressions, `hx-boost` towards other origins. The
htmx script is vendored under `/js/` (ADR 0001), never a CDN.

### Input validation conventions

* Bean Validation on request records (`@Valid`), lengths and character sets from chapter 08
  §2.3 (the database `CHECK`s are the last line, never the first); a violation answers **422**
  with the error model (ADR 0002), a malformed body **400**.
* Validation rejects, it never silently truncates or strips (chapter 06 §5.6 quirks 5–6 not
  reproduced); the only normalisations are the documented ones (trim, e-mail lower-case, the
  tracking-code rule of Q-03).
* Query parameters and path variables bind to typed values; a type mismatch is 400.
* Uploads (M2) follow chapter 10 §4 (content sniffing, size limits) in their own ADR.
* CORS exists only on the compatibility endpoint, exact HTTPS origins, no credentials
  (Q-39, ADR 0010); nothing else answers a cross-origin request (F-15, F-16 not reproduced).

## Consequences

* Any inline script or style added to a template shows up as a CSP violation: the E2E suite
  fails the run on a violation listener (chapter 10 §6), and `PublicHeadersIT` fails on a
  header drift.
* Framework error pages, CSRF meta scripts and import maps are all ruled out (chapter 06
  §8.5); the error pages of ADR 0002 are CSP-clean.
* Google Maps stays behind consent on the public pages (`frame-src` keeps the two Google
  hosts); the private areas allow no frame at all.

## References

Chapter 06 §6.4, §8.5; chapter 10 §2 "Public site inside the app", §4; `docs/plan.md` §4.3,
§8.2 PD-02; ADR 0002 (error model, CSRF), ADR 0012 (public-site port), ADR 0001 (htmx pin);
`legacy/ls-home/public/_headers`.
