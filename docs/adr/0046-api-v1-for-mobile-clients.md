# 0046 · API v1: a second door for a mobile client (plan)

* **Status:** Proposed (a plan: nothing is built until the owner answers Q-57 and asks for it)
* **Date:** 2026-10-26
* **Requirement IDs:** CUS-01…CUS-07 and the customer M2 items (the use cases the API serves),
  NFR-02 (security baseline), NFR-03 (erasure) · chapter 09 «Later»: «an API for large
  customers», «driver app», «PWA/offline» · **Questions:** Q-57…Q-61 (new, chapter 11)
  · **Owner:** request of 2026-10-26 («scrivi l'ADR con il piano per l'API v1. E' tutto solo
  teorico ma facciamo bene a prepararci in vista di un eventuale client per mobile»)

## Context

The app is a server-rendered web app by design (chapter 10; ADR 0001, 0018, 0029): Thymeleaf
and htmx, no client framework, every screen works without JavaScript, sessions kept by a cookie
with CSRF tokens. On 2026-10-26 the owner asked whether the back end and the front end can be
decoupled, what of this code a future iOS/Android app could reuse, and asked for a plan to be
ready for a mobile client.

What already makes it possible (checked in the code):

* **The rules are in the module services, not in the pages.** Controllers are thin: they read
  the request, call a service and fill the model. `OrderService` already has every customer
  operation an app needs — `submit` (idempotent, ADR 0007), `listForCustomer`,
  `findForCustomer`, `updateByCustomer` and `cancelByCustomer` with the version the customer
  saw (ADR 0028), `trackingHistory` — and returns immutable records (`OrderView`,
  `CustomerView`) that serialise to JSON as they are. Validation and totals stay on the server
  (`OrderValidator` takes a field map and answers per-field Italian messages; `OrderTotals`).
* **JSON is already spoken:** the compatibility tracking API and its enumerations (ADR 0010),
  `POST /api/form` (ADR 0014), and the error model answers `ApiError` JSON under `/api/**`
  (ADR 0002).
* **Extension points exist:** the domain events of ADR 0025 (`OrderApproved`, `OrderDeclined`,
  `TrackingChanged`, …) for notifications; hashed, expiring, revocable device tokens
  (`remembered_sign_in`, ADR 0031) and one-time tokens (`login_token`, ADR 0004) as the model
  for app tokens; the rate limiter's keys; the deny-by-default route registry.

What does not fit a native client: the session cookie and its CSRF token (browser
credentials), the HTML forms (`OrderForm` turns form fields into an `OrderDraft` kept in the
HTTP session), and the templates, CSS and scripts — the web front end.

Why the web app stays the base: the public site must be web (search engines, the tracking
page for recipients); the back office is desk work; customers are companies whose orders
(documents, pallets, recipients) are mostly typed at a desk, and the web needs no install on
any device. An app adds what the web does poorly: push notifications, a quick look at
tracking, and — for drivers — camera, signature, position and offline work. So the app is a
second client of the same back end, not a replacement.

## Decision

### One back end, a second door

* The monolith stays one deployable. **`/api/v1/**` is added next to the HTML pages and calls
  the same module services**: no rule lives in the API layer, which only maps JSON and checks
  the token. The web pages do not change.
* **Flag `api.v1`** (ADR 0025, seeded off): with it off every `/api/v1/**` path answers 404 as
  an undeclared route, and the app is exactly today's.
* **A module `api`**, like `backoffice`: controllers and DTO mapping only, calling services,
  never repositories (chapter 10 §2). Tokens belong to `auth`.
* **v1 serves the customer**: the use cases of the customer area (CUS-01…CUS-07) and, flag by
  flag, its M2 items. The back office stays web-only. A driver app and the API for large
  customers get their own ADRs (they need new roles, scopes and, for photos, a file store).

### Authentication: bearer tokens, never the cookie

* **Sign-in** at `POST /api/v1/auth/token`, OAuth-shaped answer (`access_token`,
  `refresh_token`, `token_type: "Bearer"`, `expires_in`):
  * `grant_type=password`: e-mail and password through `CustomerPasswordService.authenticate`
    (ADR 0032), same uniform refusal and rate limits as the web;
  * `grant_type=sign_in_link`: the `t` of an e-mailed link that the app opened through
    Universal Links / App Links, consumed by `CustomerSignInService.signIn` (ADR 0004: 15
    minutes, single use); `POST /api/v1/auth/link` asks for the e-mail with the web's uniform
    answer;
  * `grant_type=refresh_token`: rotation (below).
* **Tokens** (`auth.ApiTokenService`, table `api_token`): random 256-bit values stored only as
  SHA-256 hashes, per device (platform, optional label), like `remembered_sign_in`.
  * Access token: 15 minutes. Opaque, looked up by hash: one indexed query per request, and a
    revocation takes effect at once (signed JWTs would stay valid until they expire).
  * Refresh token: rotated at every use; a refresh token used twice revokes its whole family
    (theft detection). The family lasts 60 days from the sign-in like «Resta connesso»
    (PD-10, ADR 0031); `Q-60` seam `ls.security.api.*`.
  * `POST /api/v1/auth/logout` revokes the device. `CustomerSessionService.endAllSessions` —
    decline, e-mail change, password change, anonymisation — also revokes every API token of
    the login. A retention job deletes expired rows.
* **A chain of its own**: a `SecurityFilterChain` for `/api/v1/**` ahead of the web chain,
  stateless (no session read or created), bearer token only, no CSRF (there is no ambient
  credential to forge), the same route registry (deny by default, `Access.CUSTOMER` or
  `PUBLIC`), the same `ErrorResponder`. The web chain ignores bearer tokens and the API chain
  ignores the session cookie: a browser's cookies can never act on the API.
* No CORS on `/api/v1` (native apps do not use it; Q-39 stays limited to the compatibility
  API). Same origin as the site; `Cache-Control: no-store` on private answers; the
  `Authorization` header and tokens never logged (ADR 0019). Rate limits per token and per IP.
* Staff get no API tokens in v1.

### The contract

* JSON in UTF-8, `camelCase`. Instants ISO-8601 in UTC (`2026-10-26T14:32:00Z`, the client
  shows them in Europe/Rome as ADR 0044 does); days `YYYY-MM-DD`; money as a decimal string
  in EUR (`"120.50"`) with `amountVatIncluded` (ADR 0020), never a float; weights and volumes
  as decimal strings.
* **Errors**: the `ApiError` body for every refusal, plus an optional `fields` map for 422
  `validation_failed` with the validator's Italian messages — the same texts as the web. 401
  for a missing or expired access token (the app refreshes); 404 for another customer's order
  (never 403: nothing to enumerate); 409 `stale_data` for a version conflict; 429 with
  `Retry-After`.
* **Concurrency**: a changeable resource carries its version as `ETag`; a write sends
  `If-Match` with it and reaches the services' `expectedVersion`; a stale one answers 409.
* **Idempotency**: `POST /api/v1/orders` requires `Idempotency-Key` (a UUID the app creates
  with the new order and reuses on retries); the existing unique key returns the same order
  (ADR 0007).
* **Paging**: `?page=&size=`, answered as `{items, page, size, totalItems, totalPages}`.
* **Flags**: an endpoint of an M2 item answers 404 while its flag is off, as its web page does;
  `GET /api/v1/meta` tells the client which items are on, the minimum supported app version
  per platform and the business contacts and hours (ADR 0027).
* **Versioning**: inside v1 only additions (clients ignore unknown fields); a breaking change
  opens v2, and v1 stays up until the minimum supported app version has moved past it (apps in
  the stores are not updated the same day).
* **OpenAPI**: springdoc generates the specification; a snapshot in `docs/api/openapi-v1.yaml`
  is compared in CI, so no change of the contract goes unnoticed. The live specification is
  not served in production.

### The resources of v1

| Method and path | Service (module) | Notes |
|---|---|---|
| `POST /api/v1/auth/token`, `/auth/link`, `/auth/logout` | `CustomerPasswordService`, `CustomerSignInService`, `ApiTokenService` (`auth`) | public, rate-limited |
| `GET /api/v1/meta` | flags, `BusinessProperties` | public |
| `GET /api/v1/me` | `CustomerService.find` (`customers`) | status, `canOrder`, password set |
| `GET /api/v1/orders?group=` | `OrderService.listForCustomer` (`orders`) | groups and counts of «I miei ordini» |
| `GET /api/v1/orders/{code}` | `OrderService.findForCustomer`, `trackingHistory`, `DeliveryEstimate` | code or temporary code; timeline with `tracking.history` |
| `POST /api/v1/orders` | `OrderService.submit` | `Idempotency-Key`; ACTIVE customers only (CUS-03) |
| `PUT /api/v1/orders/{code}`, `POST …/{code}/cancel` | `updateByCustomer`, `cancelByCustomer` | `If-Match`; pending orders only (CUS-07) |
| `GET /api/v1/catalog` | `TrackingCatalog`, accessory services, line kinds and limits | what the order form needs |
| `GET /api/v1/tracking/{code}` | `TrackingLookupService` (`tracking`) | public, the TRK-03 allow-list |
| `GET`/`PUT /api/v1/profile`, `/api/v1/addresses` | `customers` services | data changes ENH-16, address book ENH-07 (flags) |
| `GET /api/v1/quotes`, `POST /api/v1/quotes/{token}/accept` | `quotes` | ENH-20 (flag) |

### When a mobile client is built (Q-57…Q-61)

* **The sign-in link opens the app**: `/.well-known/apple-app-site-association` and
  `/.well-known/assetlinks.json` for `/area-clienti/accesso`; without the app the link opens
  the web page as today. Registration stays on the web in v1.
* **Push notifications** (Q-58): the app registers its push token
  (`PUT /api/v1/devices/current`); a `push` channel of the notifications outbox follows the
  same events as EML-05, EML-09 and EML-10, with minimal texts («Ordine TEMP-… confermato»),
  behind a flag of its own.
* **Offline**: the app keeps the last lists and details it read; writes need the network and
  are safe to retry (idempotency key, versions).
* **Account deletion from the app** (Q-61) through the anonymisation of ADR 0024.

### Order of work, when the owner asks for it

1. **Seams, no visible change**: `OrderService.submit(customerId, customerUserId, key,
   OrderContent)` next to the draft version (as `createByStaff` takes its key); the groups and
   counts of «I miei ordini» from `CustomerOrdersController` into `OrderService`; the version
   check raises its own exception (today a rule violation with the Italian text) so that the
   API can answer 409. Rule from now on: controllers stay thin, rules stay in services.
2. **Tokens and chain**: migration `api_token`, `ApiTokenService`, the stateless chain, the
   flag `api.v1` (off), `meta`, the token endpoints. Tests: bearer tokens refused by the web,
   cookies refused by the API, revocation on decline / e-mail change / password change /
   anonymisation, rate limits, 404 with the flag off.
3. **Reading**: `me`, orders, catalog, tracking; the OpenAPI snapshot in CI.
4. **Writing**: orders, profile, address book, data changes, quotes, each behind its own flag
   as on the web.
5. **The client** (Q-57): PWA or store app; link files, push (Q-58), store accounts (Q-59),
   account deletion (Q-61).
6. **Later, separate ADRs**: the driver app (roles, tasks, proof of delivery with photos and a
   file store) and the API for large customers (credentials per customer, scopes, bulk upload).

## Consequences

* One back end and one set of rules and texts for the web and an app; the same API is the base
  for the integrations of large customers.
* A second surface to secure and keep compatible: versioning discipline, old app versions in
  use for months, one more table and one indexed query per API request.
* An app is a product of its own: store accounts and reviews, releases, the stores' privacy
  forms, an app section in `/privacy/`; Apple and Google become processors only with push
  (Q-58), decided by the owner.
* Nothing changes until the owner asks: no code, no migration, no flag seed now.

## References

Chapter 09 «Later»; chapter 10 §2 (modules, sessions), §4 (security baseline); ADR 0002 (route
registry, error model), 0004 and 0032 (customer sign-in), 0007 (idempotency), 0010
(compatibility API, CORS), 0019 (logging), 0020 (money, time), 0024 (erasure), 0025 (flags,
events), 0028 (versions), 0029 (private-area interface), 0031 (remembered device), 0044 (times).
Code read: `orders.OrderService`, `OrderValidator`, `OrderContent`, `OrderDraft`,
`CustomerOrdersController`; `auth.RememberedSignInService`, `CustomerSessionService`;
`platform.web.error.ApiError`, `ErrorCode`; `platform.events.DomainEvent`.
