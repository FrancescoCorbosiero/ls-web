# 0002 · Deny-by-default authorisation, route × role matrix, HTTP error model and rate limiter

* **Status:** Accepted
* **Date:** 2026-10-23
* **Requirement IDs:** NFR-02, ADM-01, API-03, CUS-01, TRK-04, PUB-06, PUB-07 (rate-limit keys) · **Questions:** Q-21 (what not to reproduce), Q-30, PD-13 (one proxy hop) · **Findings:** SEC-01, SEC-02, SEC-04, SEC-09, SEC-10, SEC-18 (12a §5)

## Context

The legacy backend's only URL rule (`/mono/cms/**`) never matched because of the servlet
context path, so protection depended on per-method annotations alone; pending orders and
whole customer histories were readable without a token (SEC-01, SEC-02, SEC-04; 12a §2.7–2.8).
Authorisation failures and most framework errors answered **500** with the generic Italian
reason; invalid tokens answered 401 with a raw library message under a JSON content type
(SEC-09; 12a §2.9, §3.3). No brute-force protection existed (SEC-10). Chapter 10 §4 asks
for deny by default "with a test per route", explicit 401/403/404/405/415 mapping, one JSON
error schema, rate limits keyed on the one trusted `X-Forwarded-For` hop, and chapter 10 §8
asks for one security extension point per module. Phase 1 stage 3 builds all of it in the
`platform` module.

## Decision

### Deny by default, written against real request paths

* The chain is `authorizeHttpRequests` with `anyRequest().denyAll()` at the end. The explicit
  permit list is the **`RouteRegistry`**: every route a module maps is declared through the
  one extension point **`SecurityRouteContributor`** (`routes.permitGet(...)`,
  `permit(method, ...)`, `customer(...)`, `staff(...)`, `admin(...)`), collected in bean order,
  platform first. Rules match with `PathPatternRequestMatcher` on the request path (there is
  no servlet context path, so the dead-rule bug of SEC-04 cannot recur).
* **Access classes:** `PUBLIC` (no session), `CUSTOMER` (`ROLE_CUSTOMER`), `STAFF`
  (`ROLE_ADMIN` or `ROLE_OPERATOR`), `ADMIN`. Method security is on; services such as
  `FeatureFlagService.toggle` carry `@PreAuthorize("hasRole('ADMIN')")` as a second line.
* `/area-clienti/**` and `/gestione/**` are the **private areas**: a `PUBLIC` rule inside them
  is refused at start-up except the sign-in entries (`/area-clienti/`, `/area-clienti/accesso`,
  `/area-clienti/registrazione/`, `/gestione/`, `/gestione/login`, the logouts).
* **An undeclared path does not exist**: it answers 404 to everyone (anonymous, customer,
  staff), so nothing is revealed about which routes exist. A declared route without a session
  answers 302 to the area's sign-in page for browsers and 401 (JSON) for HTMX/JSON callers;
  a declared route with the wrong role answers 403. Never 500.
* **CSRF** is on for every form of the customer area and back office (session token
  repository). The only exemptions a contributor may declare are `POST /api/form` and
  `/mono/**` (session-free, protected by honeypot, rate limit and Origin/Referer check in
  their modules, chapter 10 §4); `SecurityRoutes.csrfExempt` refuses any other path. HTMX
  sends the token from the `_csrf` meta tag through `hx-headers` on `<body>` (ADR 0018).
* **Sessions:** cookie `__Host-LS_SESSION` (`HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`) in
  staging and production; plain `LS_SESSION` without `Secure` in `local` and `test`, because
  browsers refuse `__Host-` cookies over `http://localhost`. Session fixation → new session.
  Idle and absolute timeouts from `ls.security.sessions.*` (customers 60 min / 12 h, staff
  30 min / 10 h, chapter 10 §2): `SessionLimitsFilter` sets the idle limit per session class
  and invalidates the session past the absolute limit counted from the first authenticated
  request. `Cache-Control: no-store` on both private areas. **No saved request**
  (`NullRequestCache`): the default cache creates a session, hence a cookie, on every refused
  anonymous request, and public pages stay cookie-free (chapter 10 §2, ADR 0018); after
  sign-in the user lands on the area's home page.
* **Forwarded headers:** exactly one proxy hop (`ls.security.proxy.trust-forwarded-headers`,
  true in staging/prod, false elsewhere). `ForwardedHeadersFilter` takes the **last**
  `X-Forwarded-For` value as the client IP (the one the proxy appended; anything the client
  wrote further left is ignored) and lets Spring's `ForwardedHeaderFilter` apply scheme, host
  and port; with trust off the headers are ignored and the socket address is the client.
  `ClientIpResolver.resolve(request)` is the only way code reads the client IP.

### Route × role matrix test

`RouteMatrixIT` (`NFR-02`) reads every handler mapping of the running app
(`RequestMappingInfoHandlerMapping`, actuator included), calls each route as anonymous,
CUSTOMER, OPERATOR and ADMIN and compares the status with
`src/test/resources/security/route-matrix.tsv`. A mapped route missing from the table, a
stale line, any 5xx, or a 200 from a private area without a session fails the test. **Every
module that adds a route adds its line** (method, pattern, four expected statuses).

### One error model

* **JSON schema** for API (`/api/**`, `/mono/**`, `/actuator/**`), HTMX (`HX-Request`),
  JSON-sending and JSON-accepting requests:
  `{ "status", "code", "reason", "detail"?, "requestId", "timestamp" }` — `code` a stable
  machine string (`not_found`, `csrf_rejected`, …), `reason` the Italian sentence, `detail`
  optional and never an exception message, `requestId` the `X-Request-Id` of the response,
  `timestamp` ISO-8601 UTC. `Content-Type: application/json` (UTF-8 by definition, no
  charset parameter).
* **HTML page** from `templates/error/{401,403,404,429}.html` and `error/error.html` for
  browsers (minimal now, restyled with the layout in stage 5); `noindex`, no inline script or
  style (the public CSP applies), the office contacts from the business configuration.
* **Statuses:** 400 (unreadable body, missing/mistyped parameter), 401, 403 (role, CSRF),
  404 (undeclared or missing), 405 with `Allow`, 409, 415, 422 (bean validation), 429 with
  `Retry-After`; 500 only for unexpected exceptions, with the generic reason and the stack
  trace in the log keyed by request id. The Boot whitelabel page is disabled and `/error` is
  served by `PlatformErrorController` with the same model.
* **Italian reasons:** the legacy `ErrorEnum` texts (12a §3.2) are reused where they fit:
  400/422 «Richiesta non valida. Controlla nuovamente i dati inseriti.», 401 «Non disponi delle
  autorizzazioni necessarie per procedere.», «Username o password errate.», 403 «Non disponi
  dei permessi necessari per questo tipo di operazione.», 404 «Impossibile recuperare i dati
  richiesti.», 409 the two registration/code texts, 422 «Indirizzo email di destinazione non
  valido.», 500 and 503 the two generic texts. New texts (owner review at the next gate):
  CSRF «La sessione non è più valida. Ricarica la pagina e riprova.», 405 «Operazione non
  consentita per questa risorsa.», 415 «Formato della richiesta non supportato.», 429 «Hai
  fatto troppe richieste in poco tempo. Riprova tra qualche minuto.».
* Modules raise `ClientErrorException(ErrorCode, detail?)` for business refusals; 5xx codes
  are refused by that constructor.

### Rate limiter

`RateLimiter` is an in-memory token bucket keyed by `(key, subject)`; subjects are the client
IP from `ClientIpResolver` or a normalised e-mail, never logged. Keys registered by
`platform` from `ls.ratelimit.*` with their defaults: `signin.link.email` 5/15 min,
`signin.link.ip` 20/15 min (CUS-01), `staff.login` 20/15 min per IP (ADM-01; the per-account
lock-out is PD-10), `tracking.lookup` 60/min (TRK-04), `form.submit` 10/10 min (PUB-06/07),
`registration` 5/h (CUS-02). Modules register further keys with `register(key, limit)`; an
unregistered key is a programming error. `require(key, subject)` throws
`RateLimitExceededException`, answered as **429** in the error model with `Retry-After`
(whole seconds, at least 1). **One app instance runs** (ADR 0001, one VPS, one container), so
no shared store is needed; if a second instance ever appears the limiter gets a database or
Redis backend behind the same interface.

## Consequences

* Every Phase 2 module ships one `SecurityRouteContributor` bean, its lines in
  `route-matrix.tsv` and, where it rate-limits, its key; a forgotten declaration shows up as
  a 404 in its own tests and as a failing matrix.
* No framework page, exception text or stack trace reaches a client; attacks are visible as
  401/403/404/429 in the access log instead of being hidden in 500 noise (SEC-09).
* The legacy frontends' "only `response.ok`" rule (12a §3.5) still holds for the retained
  compatibility endpoint; the new widgets read `code` and `reason`.
* Local development uses a plain cookie name: a test asserting `__Host-` runs with the
  staging/prod configuration (deploy stage).

## References

Chapter 10 §2 (URL map, sessions), §4 (security baseline), §8; 12a §2.7–2.9, §3, §5;
`docs/plan.md` §4.1 platform row, §4.3, §5 Phase 1 item 5, §8.2 PD-10, PD-13; ADR 0003/0004
(login flows that plug into this chain), ADR 0018 (headers and CSP), ADR 0019 (logging);
`docs/phase0/readers/legacy-ls-api-security.md`.
