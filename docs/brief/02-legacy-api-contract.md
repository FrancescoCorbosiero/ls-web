# 02 · Legacy API contract (as observed from its clients)

> **Read with chapter 12a.** This chapter was written before the backend source was
> available: it rebuilds the contract from the backend's clients. Chapter 12a reads the
> source (`ls@migration`, `ca1b0d0`) and **wins where the two disagree**; its §4 gives the
> verdict on every EP and CU below, and its §4.3 lists 16 backend endpoints that no client
> calls. The corrections that change the target are applied here: what the server protects
> (§1, §4.3), the tracking answer for unknown codes (EP-13, §10.2, §10.4), the accessory
> services (§9.2), old e-mail links (§10.3) and probe P-04 (§11.2).

The legacy backend (`https://app.logisticasanchez.com/mono/...`) is a Spring Boot
application (chapter 12). This chapter rebuilds its contract from the code of every known
client: the order portal (`ls-order`, chapter 03), the CMS (`ls-cms`, chapter 04), the old
public site (`ls-home@v1`, chapter 05) and the new static site (`ls-home@fe4b056`, chapter
06). It covers environments, path prefixes, authentication, CORS, error handling, one
section per endpoint, the enumerations, what must survive the cut-over, and what only
real traffic or the owner can settle.

Use it for three jobs:

1. Build and test the compatibility endpoints API-01…API-03 (chapter 09). The fixtures are
   in §10.4.
2. Run the API-export fallback of the data migration (chapter 08 §4.3).
3. Check what the old system did whenever a requirement in chapter 09 refers to it.

How to read it:

* Facts come from client code. Server behaviour is a fact only when a client depends on it
  (for example, the portal moves on only after a 2xx). Everything else is marked
  "(inferred)" or "(unknown)".
* "Read" means a client reads the field. The server may send other fields (unknown).
* Refs: `ls-order@dev` = `dbe7292` (2024-06-30), `ls-cms@dev` = `f0c881c` (2024-06-06),
  `ls-home@v1` = `8201716` (2024-06-04), `ls-home@fe4b056` = the new static site (branch
  `claude/determined-lovelace-ggeitw`), `owner` = stated by the owner.
* Sample values are illustrative. Shipment codes such as `LS-2024-0118` are **not** the
  real format (Q-06), temporary codes are shown as placeholders, and e-mail addresses use
  the reserved `.example` domain.
* IDs: endpoints `EP-01…EP-20`, contract unknowns `CU-01…`, read-only probes `P-01…`,
  fixtures `FX-01…`.

## 1. At a glance

| | |
|---|---|
| Endpoints | 20 distinct paths: portal 4, CMS 16, public site 1 (`GET …/enumeration/servizioAccessorio` is called by both the portal and the CMS). The list is complete for the three repositories on every branch (a `git grep "/mono/"` over all refs finds no other path). |
| Production base URL | `https://app.logisticasanchez.com` (owner; ls-home@fe4b056:public/js/config.js:31) |
| Prefixes | `/mono/api` (11 endpoints), `/mono/cms` (8), `/mono/auth` (1) (§3) |
| Format | JSON over HTTP. Everything is `POST` except the two enumeration `GET`s; the CMS reads lists with `POST` and an empty `{}` body. |
| Authentication | Staff only: `POST /mono/auth/login` returns `token`, which the CMS sends as `Authorization: Bearer …`. The portal and the public sites send no credentials at all. The server protects only the methods annotated for role ADMIN: EP-09 and EP-18 have no check and are **open** (§4.3; 12a §2.7–2.8). |
| Errors | Every client checks only `response.ok`. No client reads an error body or treats status codes differently (§6). |
| Personal data in transit | Over plain HTTP whenever the UAT host configured in the repositories is used (OP-S3, CMS-27, LPS-S3). |
| After the cut-over | Keep `POST /mono/api/order/trace/state` and the two enumeration `GET`s (API-01, API-02). Retire the other 17 with their frontends; they answer `410 Gone` (API-03) (§10). |

## 2. Environments and base URLs

| Environment | Base URL | Evidence | Notes |
|---|---|---|---|
| Production | `https://app.logisticasanchez.com`, paths `/mono/...` | owner; ls-home@fe4b056:public/js/config.js:31; ls-home@fe4b056:public/_headers:7 (`connect-src … https://app.logisticasanchez.com`) | The live public site calls it from `Origin: https://logisticasanchez.com` (owner's curl). Since October 2025 it runs on a Contabo VPS with Docker Compose (nginx, Spring Boot, MySQL); before, on AWS Elastic Beanstalk and RDS in eu-north-1 (12d §1). |
| UAT | `http://uat-sanchez-logistica.eu-north-1.elasticbeanstalk.com` (plain HTTP) | ls-order@dev:js/constant/rest-constant.js:2; ls-cms@dev:js/constant/rest-constant.js:2; ls-home@v1:js/custom.js:68 | AWS Elastic Beanstalk, eu-north-1 (owner). Every repository still points here. Whether it holds real personal data is (unknown). |
| Commented alternative | `https://api.logisticasanchez.com` | ls-order@dev:js/constant/rest-constant.js:3; ls-cms@dev:js/constant/rest-constant.js:3 | Never active in any commit. Whether the name resolves or serves anything is (unknown). |
| Local development | Static servers on `http://localhost:8000` calling the UAT host | ls-order@dev:local-server.py:4, 21; ls-cms@dev:local-server.py:4, 21 | The UAT backend presumably allows this origin for CORS (inferred). |

* `HOST` is a hard-coded constant in both legacy frontends; there is no environment
  switch. The builds deployed to production must therefore carry a hand-edited `HOST`
  (inferred). Which commit and which `HOST` each production frontend uses is (unknown)
  (CU-21).
* The order portal is served over HTTPS at `https://ordini.logisticasanchez.com` (owner).
  An HTTPS page cannot call a plain-HTTP API (mixed content), so the production portal
  must call an HTTPS host, presumably `https://app.logisticasanchez.com` (inferred).
* The old S3 addresses of the portal and of the separate tracking app are plain-HTTP
  website endpoints (ls-home@v1:index.html:138, 166). Which backend their builds call is
  (unknown) (LPS-U4).

## 3. Path prefixes

| Prefix | Endpoints | Callers | Token sent by the client | What the split suggests |
|---|---|---|---|---|
| `/mono/auth` | `login` | CMS | no | Authentication controller (inferred). |
| `/mono/api` | `customer/state`, `customer/register`, `order/register`, `order/trace/state`, `enumeration/statoTracking`, `enumeration/servizioAccessorio` | portal, public sites, CMS (enumerations only) | no | Public, customer-facing API (inferred). |
| `/mono/api` | `customer/pending`, `customer/registered`, `order/pending`, `order/registered`, `order/detail` | CMS | yes | Staff reads that sit under the public prefix. The server protects all of them **except `order/pending`** (EP-09), which is open (§4.3, Q-21). |
| `/mono/cms` | `customer/confirm`, `customer/decline`, `order/confirm`, `order/decline`, `order/trace`, `notes`, `notes/update`, `order/report` | CMS | yes | Staff actions and the notes read. All protected per method **except `order/report`** (EP-18): the URL rule for `/mono/cms/**` never matches (§4.3). |

`/mono` is the servlet context path of a single Spring Boot application ("mono", Java 17,
Spring Boot 3.2.2; 12a §1.1, 12d §2).

## 4. Authentication

### 4.1 Model

* Staff log in with `POST /mono/auth/login` `{username, password}` and get
  `{token, refreshToken}` (EP-01). The CMS keeps both in page memory only
  (ls-cms@dev:js/rest/rest-caller.js:14-15).
* Every later CMS `POST` sends `Authorization: Bearer <token>` through one helper
  (ls-cms@dev:js/rest/rest-caller.js:170-187). The two enumeration `GET`s and the login
  are sent without it (ls-cms@dev:js/rest/rest-caller.js:119-168).
* The refresh token is stored and never sent; no refresh, logout or "who am I" endpoint is
  called anywhere. From the source (12a §2.3–2.5): the token is an HS256 JWT with claims
  `sub`, `operations`, `iat`, `exp`, valid 6 hours by default; the refresh token is the id
  of the user's single `user_session` row, sliding 2 hours, used by
  `POST /mono/auth/refresh-token`; roles `ADMIN` and `CUSTOMER`; no logout.
* The portal and the public sites have no credentials at all. The portal identifies the
  customer only by the e-mail in the body (`clienteEmail`, EP-08), which is why anyone who
  knows a registered e-mail can order in that customer's name (OP-S1).

### 4.2 Which calls carry the token

| Token sent | Endpoints |
|---|---|
| Yes (CMS) | EP-04, EP-05, EP-06, EP-07, EP-09, EP-10, EP-11, EP-12, EP-14, EP-15, EP-16, EP-17, EP-18 |
| No | EP-01 (login), EP-02, EP-03, EP-08 (portal), EP-13 (public tracking), EP-19, EP-20 (enumerations) |

### 4.3 What the server enforces

From the source (12a §2.7–2.9):

* The URL rule meant to protect `/mono/cms/**` never matches: the servlet context path is
  `/mono` and the matcher compares the path inside it. Every other request is permitted,
  so only the methods annotated `@PreAuthorize` (role ADMIN) are protected.
* **Protected (ADMIN):** EP-04, EP-05, EP-06, EP-07, EP-10, EP-11, EP-12, EP-14, EP-15,
  EP-16, EP-17, and the backend-only `POST /mono/cms/order?email=`, `/mono/cms/order/update`,
  `/mono/sendEmail` and `/mono/utils/**`.
* **Open by design:** EP-01 (login), EP-02, EP-03, EP-08 (portal), EP-13 (tracking), EP-19,
  EP-20, `POST /mono/auth/refresh-token`.
* **Open by mistake (chapter 11 Q-21):**
  * EP-09 `order/pending`: every pending order of the last 6 months, with the sender's
    e-mail, the recipient's name, address and phone, the DDT number and the note;
  * `POST /mono/api/order/active` and `/mono/api/order/delivered`, unknown to the clients
    of this chapter: with `{}`, every approved order of the last 6 months;
  * EP-18 `cms/order/report`: sends e-mail from the company's SES identity (12a SEC-05);
  * `GET /mono/report` and `GET /mono/download-report?email=…`: any customer's shipment
    report as a page or a PDF (12a SEC-03).
* A protected endpoint called **without** a token answers **500** with the generic error
  body, not 401/403; an expired or invalid token answers **401** with the raw library
  message. The CMS cannot tell: any non-2xx is "Ops, si è verificato un errore" (CMS-03,
  CMS-17).
* The new app requires a staff session on every back-office read and write (ADM-01), with a
  test per route (12a SEC-04).

## 5. Transport, CORS and origins

### 5.1 Request format

| Aspect | Behaviour | Source |
|---|---|---|
| Body | `JSON.stringify(request)`; numbers typed in forms travel as strings (§8) | ls-order@dev:js/rest/rest-caller.js:44-51; ls-cms@dev:js/rest/rest-caller.js:135-142, 170-178 |
| `Content-Type` | `application/json` on every `POST`; the CMS also sends it on its two `GET`s; the portal's `GET` sends no header | ls-cms@dev:js/rest/rest-caller.js:153-159; ls-order@dev:js/rest/rest-caller.js:62-65 |
| `Accept` | Only the new static site sends `Accept: application/json` | ls-home@fe4b056:public/js/tracking.js:175 |
| Cookies | None. No client sets `credentials`, and the fetch default (`same-origin`) sends no cookies cross-origin. | all callers |
| Timeouts, retries | None, except the new site's 8-second abort | ls-home@fe4b056:public/js/config.js:34; ls-home@fe4b056:public/js/tracking.js:169-170 |

### 5.2 CORS

Every client calls the API with an absolute URL, from another origin. Browsers therefore
send a preflight `OPTIONS` before each `POST` with a JSON content type, and before any
request carrying `Authorization`. The portal's header-less `GET` is a "simple" request:
no preflight, but the answer still needs `Access-Control-Allow-Origin` to be readable
(inferred from the Fetch standard). No client sends cookies, so
`Access-Control-Allow-Credentials` is not needed.

| Origin | What it calls | Evidence | Status |
|---|---|---|---|
| `https://logisticasanchez.com` | EP-13 | owner (curl with this `Origin`) | Allowed today (inferred: the live tracking works). The backend allows the origins of its `ALLOWED_ORIGINS` setting, with credentials; the repository lists the apex, `www` and `app.` (12a §2.10). The live value is on the server (P-03). |
| `https://www.logisticasanchez.com` | EP-13, if the live site also answers on `www` | — | (unknown) |
| `https://ordini.logisticasanchez.com` | EP-02, EP-03, EP-08, EP-20 | owner (portal URL) | Must be allowed for the portal to work (inferred). |
| `http://sanchez-logistica-fe.s3-website.eu-north-1.amazonaws.com` | portal calls | owner (older portal address); ls-home@v1:index.html:138 | (unknown): still serving? allowed? |
| `http://sanchez-logistica-tracking.s3-website.eu-north-1.amazonaws.com` | EP-13 probably (inferred) | owner; LPS-U4 | (unknown) |
| CMS origin | EP-01, EP-04…EP-07, EP-09…EP-12, EP-14…EP-20; preflight must allow `Authorization` | — | (unknown): the CMS's production URL is unknown (Q-16). |
| Netlify origins of the new static site | EP-13 | ls-home@fe4b056:public/js/config.js:31 | (unknown) |

## 6. Error semantics

All clients reduce the answer to "ok" or "failed". Nothing else from the server ever
reaches the user.

| Client | Success means | On failure the user sees | Source |
|---|---|---|---|
| Order portal | `response.ok`; EP-02 and EP-20 also need a JSON body | The dialog "Errore" / "Ops, si è verificato un errore riprova più tardi. Se il problema persiste contattaci via email o telefonicamente"; an EP-20 failure shows nothing and leaves the buttons disabled (OP-03) | ls-order@dev:js/rest/rest-caller.js:53-57, 67-71; ls-order@dev:js/function/state-machine.js:140-143 |
| CMS | `response.ok`; calls that read the body also need valid JSON | Snackbars "Ops, si è verificato un errore", "Codice non presente" (EP-14), "Dati non validi" (EP-18); some failures show nothing (CMS-09, CMS-15) | ls-cms@dev:js/rest/rest-caller.js:144-148, 161-165, 180-184; chapter 04 §4.10 |
| Old site v1 | `response.ok` + JSON | "Codice non valido" for any failure | ls-home@v1:js/custom.js:59-62, 85-89 |
| New static site | `response.ok` + JSON with a non-empty `status` | Non-2xx: the "not found" block. Network error, timeout or non-JSON 2xx: the "error" block (chapter 06 §5.7) | ls-home@fe4b056:public/js/tracking.js:168-184, 221-226 |

What a compatible server must respect:

* Only the 2xx / non-2xx split matters to the legacy clients; the exact status code and
  the error body are free.
* A 2xx whose body is not JSON breaks every call whose body is read: EP-01, EP-02, EP-04,
  EP-05, EP-09, EP-10, EP-13, EP-14, EP-15, EP-17, EP-19, EP-20.
* No client retries, and none sends an idempotency key. A repeated click repeats the
  request (OP-02, CMS-07, CMS-25), so the legacy data may contain duplicates (chapter 08
  §5).

## 7. Endpoints

| ID | Method and path | Area | Token sent | Callers | After cut-over |
|---|---|---|---|---|---|
| EP-01 | `POST /mono/auth/login` | auth | no | CMS | retire |
| EP-02 | `POST /mono/api/customer/state` | customers | no | portal | retire |
| EP-03 | `POST /mono/api/customer/register` | customers | no | portal | retire |
| EP-04 | `POST /mono/api/customer/pending` | customers | yes | CMS | retire |
| EP-05 | `POST /mono/api/customer/registered` | customers | yes | CMS | retire |
| EP-06 | `POST /mono/cms/customer/confirm` | customers | yes | CMS | retire |
| EP-07 | `POST /mono/cms/customer/decline` | customers | yes | CMS | retire |
| EP-08 | `POST /mono/api/order/register` | orders | no | portal | retire |
| EP-09 | `POST /mono/api/order/pending` | orders | yes | CMS | retire |
| EP-10 | `POST /mono/api/order/registered` | orders | yes | CMS | retire |
| EP-11 | `POST /mono/cms/order/confirm` | orders | yes | CMS | retire |
| EP-12 | `POST /mono/cms/order/decline` | orders | yes | CMS | retire |
| EP-13 | `POST /mono/api/order/trace/state` | tracking | no | public sites | **keep** (API-01) |
| EP-14 | `POST /mono/cms/order/trace` | tracking | yes | CMS | retire |
| EP-15 | `POST /mono/cms/notes` | notes | yes | CMS | retire |
| EP-16 | `POST /mono/cms/notes/update` | notes | yes | CMS | retire |
| EP-17 | `POST /mono/api/order/detail` | detail | yes | CMS | retire |
| EP-18 | `POST /mono/cms/order/report` | report | yes | CMS | retire |
| EP-19 | `GET /mono/api/enumeration/statoTracking` | enumerations | no | CMS | **keep** (API-02) |
| EP-20 | `GET /mono/api/enumeration/servizioAccessorio` | enumerations | no | portal, CMS | **keep** (API-02) |

### 7.1 Auth

#### EP-01 · `POST /mono/auth/login`

> **From the source, EP-01** (corrected; 12a §4.1, details in 12a §1.4): The response has five keys: `token`, `refreshToken`, `expTokenSec`, `expRefreshTokenSec` and `userData{id, email(=username), role, operations}`. A wrong password gives 401 `ErrorRepr`. Login upserts the user's `user_session` row. The token is a JWT HS256 valid 6 h (A1, 12a §2.4).

| | |
|---|---|
| Auth | none |
| Callers | CMS: `executeLogin` → `doLoginRestCall` (ls-cms@dev:js/function/component-handler.js:131-168) |
| Source | ls-cms@dev:js/rest/rest-caller.js:7-16; ls-cms@dev:js/rest/dto/login-request.js:3-8; ls-cms@dev:js/constant/rest-constant.js:6 |

Request (whether the username is a name or an e-mail is (unknown); the placeholder says
"username", ls-cms@dev:js/constant/language-messages.js:22):

```json
{ "username": "ufficio", "password": "<password>" }
```

Response, fields read:

```json
{ "token": "<access token>", "refreshToken": "<refresh token>" }
```

* Any non-2xx shows "Ops, si è verificato un errore": a wrong password and an outage look
  the same (CMS-17).
* Side effects: none known.
* Compatibility: retire. Staff accounts are not migrated; the new back office has its own
  login (ADM-01, chapter 08 §3.9).

### 7.2 Customers

#### EP-02 · `POST /mono/api/customer/state`

> **From the source, EP-02** (corrected; 12a §4.1, details in 12a §1.4): `NEW` also covers unverified customers and customers declined since 2024-07-22. `PENDING` means "e-mail verified, waiting for the office". `DECLINED` can appear for rows declined before 2024-07-22, and the portal then shows its error dialog. 400 for a malformed e-mail (B1).

| | |
|---|---|
| Auth | none |
| Callers | portal start screen: `getCustomerStatus` after the e-mail check (ls-order@dev:js/function/state-machine.js:65-79) |
| Source | ls-order@dev:js/rest/rest-caller.js:6-15; ls-order@dev:js/rest/dto/customer-request.js:3-7; ls-order@dev:js/constant/rest-constant.js:6 |

Request. The e-mail is sent exactly as typed, with no trimming or lower-casing
(ls-order@dev:js/function/data-handler.js:8-10):

```json
{ "email": "ufficio@mobilificiorossi.example" }
```

Response, field read:

```json
{ "status": "REGISTERED" }
```

| `status` (exact, case-sensitive) | Portal reaction | Meaning (inferred) |
|---|---|---|
| `"NEW"` | registration form | e-mail unknown to the backend |
| `"PENDING"` | "La tua richiesta di registrazione è andata a buon fine, a breve verrà approvata", no way forward | registered, waiting for office approval |
| `"REGISTERED"` | loads EP-20, then the order wizard | approved: may order |
| anything else | `console.error` + error dialog | values for declined or unconfirmed customers are (unknown) (CU-03) |

Source of the values: ls-order@dev:js/constant/costant.js:17-19;
ls-order@dev:js/function/state-machine.js:146-168.

* Side effects: none implied (a read).
* Security: an unthrottled oracle that tells anyone whether an e-mail is a customer
  (OP-S2).
* Compatibility: retire. The new customer area answers the same way for every e-mail
  (CUS-01).

#### EP-03 · `POST /mono/api/customer/register`

> **From the source, EP-03** (corrected; 12a §4.1, details in 12a §1.4): It answers 200 with `CustomerRepr`. The customer is created **NEW**, not pending; PENDING comes only after the e-mail link. Duplicates: NEW gives 200 and the e-mail is re-sent, PENDING gives 500, REGISTERED gives 409. `telefono` is required. Size limits: 50/50/50/50/9/20/50. The e-mail is "Sanchez Logistica - Conferma indirizzo email" with the link `/mono/email-confirm` (B2).

| | |
|---|---|
| Auth | none |
| Callers | portal registration confirm step: `sendCustomer` (ls-order@dev:js/function/state-machine.js:125-129) |
| Source | ls-order@dev:js/rest/rest-caller.js:17-23; ls-order@dev:js/rest/dto/customer-request.js:9-19; ls-order@dev:js/constant/rest-constant.js:7 |

Request. All values are strings; `telefono` is `null` only when empty, which the
registration form never allows (OP-08) (ls-order@dev:js/function/data-handler.js:77-86):

```json
{
  "email": "ufficio@mobilificiorossi.example",
  "ragioneSociale": "Mobilificio Rossi S.r.l.",
  "indirizzo": "Via Garibaldi 12",
  "provincia": "MB",
  "citta": "Lissone",
  "cap": "20851",
  "telefono": "039 2456789"
}
```

Response: not read; any 2xx is success.

* Side effects: creates a customer waiting for approval (inferred: from then on EP-02
  answers `PENDING`, and the CMS lists pending customers). An e-mail with instructions to
  complete the registration is sent: the portal says "Ci siamo quasi, per completare la
  registrazione segui le istruzioni contenute nella mail che ti abbiamo mandato"
  (ls-order@dev:js/constant/language-messages.js:9), and the owner confirms a
  registration confirmation e-mail. Its content, link format and landing endpoint are
  (unknown) (CU-24).
* The answer for an e-mail that already exists, or differs only in case, is (unknown)
  (CU-04). The request can be re-sent with "Indietro" → "Avanti" (OP-02).
* Compatibility: retire.

#### EP-04 · `POST /mono/api/customer/pending` and EP-05 · `POST /mono/api/customer/registered`

> **From the source, EP-04, EP-05** (corrected; 12a §4.1, details in 12a §1.4): **Protected (ADMIN); 500 without a token.** Items also have `customer` (boolean). `emailConfirmed` is always true in both lists. No time window and no `ORDER BY` (B3/B4).

| | |
|---|---|
| Auth | Bearer sent |
| Callers | CMS after login (`initCms`) and on Refresh in the customers view (ls-cms@dev:js/function/component-handler.js:193-211; ls-cms@dev:js/function/state-machine.js:43-48) |
| Source | ls-cms@dev:js/rest/rest-caller.js:19-37; ls-cms@dev:js/constant/rest-constant.js:9-10; mapping ls-cms@dev:js/components/types/data-tables-component.js:56-66, 71-81 |

Request: `{}`.

Response: a JSON array (required: the CMS calls `.map` on it). Fields read per item:

```json
[
  {
    "id": 42,
    "ragioneSociale": "Mobilificio Rossi S.r.l.",
    "email": "ufficio@mobilificiorossi.example",
    "indirizzo": "Via Garibaldi 12",
    "provincia": "MB",
    "citta": "Lissone",
    "cap": "20851",
    "telefono": "039 2456789",
    "emailConfirmed": false
  }
]
```

* `emailConfirmed` is tested for truthiness ("SI"/"NO"); `telefono` may be `null`. No
  status, date or price-list field is read; others may exist (unknown).
* Order: the pending list is shown as returned; the registered list is reversed, so newest
  first if the server returns ascending ids (inferred; CU-15)
  (ls-cms@dev:js/components/types/data-tables-component.js:67).
* Side effects: none.
* Compatibility: retire. These are also the migration's source of customers (chapter 08
  §4.3).

#### EP-06 · `POST /mono/cms/customer/confirm` and EP-07 · `POST /mono/cms/customer/decline`

> **From the source, EP-06** (corrected; 12a §4.1, details in 12a §1.4): Approval requires state PENDING, i.e. a verified e-mail; otherwise, or for an unknown e-mail, 500. It returns `CustomerRepr`. E-mail "Sanchez Logistica - Conferma nuovo cliente" (B5).
>
> **From the source, EP-07** (corrected; 12a §4.1, details in 12a §1.4): It sets the customer back to **NEW** without deleting it (DECLINED before 2024-07-22), from any state. E-mail "Sanchez Logistica - Aggiornamento indirizzo email". The customer can re-verify with the old link (B6).

| | |
|---|---|
| Auth | Bearer sent |
| Callers | CMS pending customers, after "Sei sicuro di voler procedere?" (ls-cms@dev:js/function/component-handler.js:323-389) |
| Source | ls-cms@dev:js/rest/rest-caller.js:39-45; ls-cms@dev:js/constant/rest-constant.js:12-13; bodies ls-cms@dev:js/function/component-handler.js:339-341, 374-376 |

Request (the same for both). The customer is identified by e-mail, not by `id`:

```json
{ "email": "ufficio@mobilificiorossi.example" }
```

Response: not read.

* Confirm: the customer moves to the registered list and EP-02 then answers `REGISTERED`
  (inferred). The CMS allows it even when `emailConfirmed` is false; whether the server
  refuses that is (unknown) (CMS-20, CU-22).
* Decline: the effect is (unknown). The record may be deleted or flagged, and declined
  customers are listed nowhere (Q-09, CU-16).
* E-mails sent on either action are (unknown) (Q-07).
* Compatibility: retire.

### 7.3 Orders

#### EP-08 · `POST /mono/api/order/register`

> **From the source, EP-08** (corrected; 12a §4.1, details in 12a §1.4): It answers 200 with `Spedizione` (`codice` = `TEMP`+UUID, `stato` PENDING). **An unknown e-mail or a non-REGISTERED customer gives 500** (CU-05). A `null` service gives 500. A `0` dimension gives 400. Each line is expanded per unit and totals are computed. Only the office is e-mailed (C1).

| | |
|---|---|
| Auth | none; the ordering customer is identified only by `clienteEmail` |
| Callers | portal order confirm step: `sendOrder` (ls-order@dev:js/function/state-machine.js:106-111) |
| Source | ls-order@dev:js/rest/rest-caller.js:25-31; ls-order@dev:js/rest/dto/order-request.js:3-43; ls-order@dev:js/constant/rest-constant.js:10 |

Request, exactly as the `dev` code builds it, with one service chosen and no recipient
phone. The service id `3` is illustrative: the real ids are unknown (§9.2).

```json
{
  "clienteEmail": "ufficio@mobilificiorossi.example",
  "destinatario": {
    "ragioneSociale": "Ferramenta Bianchi S.n.c.",
    "indirizzo": "Via Roma 5",
    "provincia": "BG",
    "citta": "Bergamo",
    "cap": "24121",
    "telefono": null,
    "email": "ufficio@mobilificiorossi.example"
  },
  "dettaglioColli": {
    "palletList": [
      { "altezza": "150", "peso": "420", "larghezza": "80", "lunghezza": "120", "quantity": "2", "type": "1" },
      { "altezza": "40", "peso": "12", "larghezza": "30", "lunghezza": "30", "quantity": "3", "type": "0" }
    ]
  },
  "codiceDocumentoTrasporto": "DDT 245/2024",
  "servizioAccessorio": "3",
  "note": "Consegna solo al mattino, chiamare il destinatario prima"
}
```

| Path | Type as sent | Notes |
|---|---|---|
| `clienteEmail` | string | the e-mail typed on the start screen |
| `destinatario.*` | strings; `telefono` string or `null` | recipient; `destinatario.email` is the **customer's** e-mail (ls-order@dev:js/rest/dto/order-request.js:15; OP-14) |
| `dettaglioColli.palletList[]` | array of objects | an object wrapping the array, not an array (OP-15) |
| `…altezza`, `larghezza`, `lunghezza` | strings of digits | cm |
| `…peso` | string of digits | kg per unit (inferred: the portal multiplies it by the quantity, ls-order@dev:js/function/data-handler.js:91) |
| `…quantity` | string of digits | English key |
| `…type` | `"0"` = "Pallet Generico/Colli", `"1"` = "Epal"; number `1` in the fallback (OP-04) | ls-order@dev:js/constant/costant.js:86-90; ls-order@dev:js/function/data-handler.js:23-26 |
| `codiceDocumentoTrasporto` | string | required in `dev` (OP-U7) |
| `servizioAccessorio` | the enumeration `id` as a string, or the number `0` when none is chosen | ls-order@dev:js/function/data-handler.js:58-61 |
| `note` | string, `""` when empty | |

Not sent: "Numero Colli" (OP-07), totals, pick-up data, price.

Response: not read; any 2xx is success. No order code is shown to the customer.

* Side effects: creates an order waiting for approval, with a temporary code containing
  `TEMP` (inferred from the CMS, ls-cms@dev:js/components/types/data-tables-component.js:242).
  The portal then says "Il tuo ordine è stato inoltrato correttamente! Ti preghiamo di
  attendere la conferma via email dal nostro centro logistico. Grazie per aver scelto
  Sanchez Logistica!" (ls-order@dev:js/constant/language-messages.js:10). The
  confirmation e-mail therefore comes at approval (inferred). Whether an e-mail is also
  sent at submission is (unknown) (CU-06).
* Whether the server refuses an e-mail that is not `REGISTERED` is (unknown) (CU-05). If it
  does not, OP-S1 is exploitable without the portal.
* Compatibility: retire. Never re-implement these semantics in the new app: the customer
  area takes the sender from the session (CUS-04).

#### EP-09 · `POST /mono/api/order/pending` and EP-10 · `POST /mono/api/order/registered`

> **From the source, EP-09** (corrected; 12a §4.1, details in 12a §1.4): **Open, no token needed (Q-21 incident).** Only orders created in the last 6 months. Items are the full `Spedizione` with 20 keys (C2).
>
> **From the source, EP-10** (corrected; 12a §4.1, details in 12a §1.4): Protected (ADMIN); last 6 months only (C3).

| | |
|---|---|
| Auth | Bearer sent |
| Callers | CMS: first visit of the orders view, then Refresh (ls-cms@dev:js/function/state-machine.js:13-26, 37-42) |
| Source | ls-cms@dev:js/rest/rest-caller.js:56-74; ls-cms@dev:js/constant/rest-constant.js:18-19; mapping ls-cms@dev:js/components/types/data-tables-component.js:149-163, 240-254 |

Request: `{}`.

Response: a JSON array (required). Fields read per item, all flat:

```json
[
  {
    "id": 118,
    "codice": "<codice temporaneo che contiene TEMP>",
    "email": "ufficio@mobilificiorossi.example",
    "codiceDocumentoTrasporto": "DDT 245/2024",
    "ragioneSociale": "Ferramenta Bianchi S.n.c.",
    "indirizzo": "Via Roma 5",
    "citta": "Bergamo",
    "provincia": "BG",
    "cap": "24121",
    "telefono": "035 7654321",
    "importo": null,
    "servizioAccessorio": "<formato sconosciuto>",
    "stato": "<valore sconosciuto>",
    "tracking": "<formato sconosciuto>"
  }
]
```

| Field | What the clients prove | Unknown |
|---|---|---|
| `id` | key sent back by EP-11 and EP-12 | type (number, inferred) |
| `codice` | must be a string in the pending list (the CMS calls `.includes("TEMP")`, so a `null` code breaks the view); key of EP-14, EP-15, EP-17 | format, uniqueness, case (Q-06, CU-17) |
| `email` | shown as "Cliente mittente"; sent back by EP-12 | whether it is the customer's e-mail or `destinatario.email` (equal by construction for portal orders) |
| `ragioneSociale` … `telefono` | shown under "Destinatario", "Indirizzo", "Città", "Cap", "Telefono": the recipient (inferred) | — |
| `codiceDocumentoTrasporto` | `null` prints "-" | — |
| `importo` | `null` prints "-" | relation to `importoAssicurato` written by EP-11 (Q-05, CU-08) |
| `servizioAccessorio`, `stato`, `tracking` | printed raw | id, name, label or object (CU-08). If they were objects the CMS would print "[object Object]", so primitives are likely (inferred, weak). |

* No date field is read, although EP-18 filters by date range: orders carry a date
  (inferred). Extra fields may exist in the raw answer (CU-08), so the migration exporter
  must store the answers verbatim (chapter 08 §4.3).
* Order: pending as returned; registered reversed (ls-cms@dev:js/components/types/data-tables-component.js:165).
* Side effects: none.
* Compatibility: retire.

#### EP-11 · `POST /mono/cms/order/confirm`

> **From the source, EP-11** (corrected; 12a §4.1, details in 12a §1.4): It needs `req.codice`, **`req.importo`** and `req.tracking`. The `ls-cms@dev` body (`importoAssicurato`) answers **500** since 2024-07-18, and `"180,50"` always 500. A duplicate code gives 409. An order that is not PENDING gives 500; an unknown id gives 404. Tracking is forced to PRESA. `importoContrassegno` is overwritten with `importoAssicurato`. It returns `Spedizione` and e-mails "Sanchez Logistica - Conferma ordine N. {codice}" (C6).

| | |
|---|---|
| Auth | Bearer sent |
| Callers | CMS "Approva ordine" dialog (ls-cms@dev:js/function/component-handler.js:391-448) |
| Source | ls-cms@dev:js/rest/rest-caller.js:47-49; ls-cms@dev:js/constant/rest-constant.js:14; body ls-cms@dev:js/function/component-handler.js:420-434 |

Request. `id` is the row's id, `codice` is the definitive code typed by staff, and
`importoAssicurato` is the text typed in "Importo Totale (per cliente)"
(ls-cms@dev:js/components/types/dialog-components.js:113-116), sent as typed:

```json
{
  "id": 118,
  "req": {
    "codice": "LS-2024-0118",
    "importoAssicurato": "180,50",
    "tracking": 1
  }
}
```

* `tracking: 1` is `PRESA` "Presa in carico" (§9.1). It is a number here, but a string in
  EP-14 (CMS-21).
* The only check is "not empty": spaces only, "abc" and "180,50" all pass (CMS-13).
* Keys present in the code but commented out, never sent: `note: null`,
  `servizioAccessorio: null`, `stato: null`, `updateShippingTs: true`,
  `updateDeliveryTs: false` (ls-cms@dev:js/function/component-handler.js:427-432). They
  suggest the server's request type has these optional fields, and that orders store
  shipping and delivery timestamps (inferred) (CU-18).
* Unused constants name a cash-on-delivery amount (`importoContrassegno`) and a DDT field
  for this dialog (ls-cms@dev:js/constant/costant.js:199-201). The amount field's own id is
  `importoAssicurato-field-id` (:200), so the field was probably first meant as an
  "insured amount" (inferred); staff now type the customer price into it (owner). Whether
  the backend stores it in the column the lists read as `importo` is (unknown) (Q-05,
  CU-08).

Response: not read.

* Side effects: the order leaves the pending list and appears among the registered ones
  (inferred from the reload, chapter 04 §9.1). The definitive code replaces the TEMP code
  (inferred), the amount is stored and tracking becomes 1. The order confirmation e-mail
  (owner) is presumably sent here (inferred) (Q-07).
* Uniqueness of the code on the server is (unknown). The dialog keeps the previous order's
  code and amount (CMS-12), so duplicate codes are possible in the data (chapter 08 §5).
* History: the CMS calls this endpoint only since `ls-cms@e5a72fc` (2024-05-25); no
  earlier commit calls any order-approval endpoint (`git log -S` on the URL constant;
  chapter 04 §2.4). How orders were approved before that build was deployed is (unknown)
  (inferred: outside the CMS).
* Compatibility: retire.

#### EP-12 · `POST /mono/cms/order/decline`

> **From the source, EP-12** (corrected; 12a §4.1, details in 12a §1.4): The order becomes `stato` NEW and `tracking` NON_GESTITO and is kept (DECLINED before 2024-07-22). Ownership and state are not checked. The e-mail "Sanchez Logistica - Aggiornamento ordine rifiutato" goes to the e-mail in the request and shows the order id (C7).

| | |
|---|---|
| Auth | Bearer sent |
| Callers | CMS pending orders, after "Sei sicuro di voler procedere?" (ls-cms@dev:js/function/component-handler.js:450-482) |
| Source | ls-cms@dev:js/rest/rest-caller.js:51-53; ls-cms@dev:js/constant/rest-constant.js:15; body ls-cms@dev:js/function/component-handler.js:465-468 |

Request. `email` is the row's "Cliente mittente":

```json
{ "id": 118, "email": "ufficio@mobilificiorossi.example" }
```

Response: not read.

* Side effects: the order disappears from the pending list. Whether it is deleted or kept
  with a status is (unknown) (Q-09, CU-16). Passing the e-mail suggests the customer is
  notified (inferred) (Q-07).
* Compatibility: retire.

### 7.4 Tracking

#### EP-13 · `POST /mono/api/order/trace/state` (public)

> **From the source, EP-13** (corrected; 12a §4.1, details in 12a §1.4): `status` = label (Q-03: label). **An unknown or TEMP code gives 200 `{"status":"Non gestito"}`**. `""` or `{}` gives 400. Matching is SQL equality, so the collation decides (D1).

| | |
|---|---|
| Auth | none |
| Callers | the live public site `https://logisticasanchez.com` (owner; its code is not available); the new static site (ls-home@fe4b056:public/js/tracking.js:168-184, endpoint in public/js/config.js:31); the old site v1 (ls-home@v1:js/custom.js:67-92, in the page only from 31 May to 4 Jun 2024, dead code at v1); probably the old S3 tracking app (inferred, LPS-U4) |
| Source | ls-home@v1:js/custom.js:67-74; owner (curl) |

Request:

```json
{ "codice": "LS-2024-0118" }
```

| Client | How `codice` is built | Headers |
|---|---|---|
| Old site v1 | the input as typed: no trim, no case change (ls-home@v1:js/custom.js:49, 69) | `Content-Type` |
| New static site | all whitespace removed; must match `` ^[^\s<>"'`]{1,64}$ `` (1–64 characters, no spaces, `<`, `>`, quotes or backticks); case kept (ls-home@fe4b056:public/js/tracking.js:32, 204-206) | `Content-Type`, `Accept: application/json` |
| Live site | (unknown) (LPS-U2) | `Origin: https://logisticasanchez.com` (owner) |

Response, field read:

```json
{ "status": "Presa in carico" }
```

* v1 writes `status` into the page as received (ls-home@v1:js/custom.js:56-57, 72-73). The
  new site recognises it by id (0–5), name (`PRESA`) or label ("Presa in carico"), and
  also accepts an object `{id, name, label}` or a bare string or number
  (ls-home@fe4b056:public/js/tracking.js:23-30, 81-91).
* `status` is the **label**
  (ls@migration:src/main/java/it/logisticasanchez/mono/api/controller/OrderController.java:33-38;
  12a §1.4 D1). Builds of 22 April–5 May 2024 answered `{"label": …}` instead (CU-01).
* **Unknown code, and a pending order's TEMP code: `200 {"status": "Non gestito"}`**, not
  an error. `{}` or `{"codice": ""}`: `400` with the standard error body. Matching is SQL
  equality without trimming, so case and trailing spaces follow the database collation
  (`utf8mb4_unicode_ci` in the repository: case-insensitive, trailing spaces ignored;
  production unknown) (12a §4.2, CU-02).
* The comment in the new site at `fe4b056` ("The backend answers an error status for
  unknown codes", ls-home@fe4b056:public/js/tracking.js:178) was wrong. From `ff58d4f` the
  site shows a neutral "no updates yet" message for `Non gestito` (chapter 06 §5.7).
* Side effects: none (a read).
* Compatibility: **keep (API-01)**, §10.

#### EP-14 · `POST /mono/cms/order/trace` (staff)

> **From the source, EP-14** (corrected; 12a §4.1, details in 12a §1.4): It returns the updated `Spedizione`. An unknown code gives 404; an order that is not REGISTERED gives 500; an id outside 0–5 gives 500. It sets `shippingTs`/`deliveryTs` and e-mails the customer on CONSEGNATO only. Any transition is allowed; no history (D2).

| | |
|---|---|
| Auth | Bearer sent |
| Callers | CMS "Imposta tracking" dialog on registered orders (ls-cms@dev:js/function/component-handler.js:497-538) |
| Source | ls-cms@dev:js/rest/rest-caller.js:77-85; ls-cms@dev:js/rest/dto/order-request.js:3-8; ls-cms@dev:js/constant/rest-constant.js:22 |

Request. The code field is editable, so any code can be sent. `tracking` is the menu
item's id read from an HTML attribute, hence a **string**
(ls-cms@dev:js/function/component-handler.js:514):

```json
{ "codice": "LS-2024-0118", "tracking": "2" }
```

Response: parsed as JSON and pushed to `userData.tracedOrders`, never shown in `dev`
(ls-cms@dev:js/rest/rest-caller.js:82-84). `ls-cms@main` showed these answers as a table
whose columns were the answer's keys (ls-cms@main:js/function/component-handler.js:202-204),
so the answer is a JSON object (inferred). Its fields are (unknown) (CU-11).

* A 2xx with a non-JSON body counts as a failure, so the server must return JSON on
  success (inferred: the CMS works).
* Any failure shows "Codice non presente" (ls-cms@dev:js/function/component-handler.js:533).
* Side effects: sets the order's current tracking state. No rule on transitions is visible
  in the client: any state, backwards included. Whether the server keeps a history,
  timestamps or sends e-mails is (unknown) (CU-18).
* Compatibility: retire. ADM-06 replaces it and records every change as an event.

### 7.5 Notes

#### EP-15 · `POST /mono/cms/notes` and EP-16 · `POST /mono/cms/notes/update`

> **From the source, EP-15, EP-16** (corrected; 12a §4.1, details in 12a §1.4): The note is the order's `note` column, i.e. the customer's EP-08 note (CU-10). The server itself answers `"Nessuna nota presente"` for a null note and for an unknown code. Update: an unknown code gives 404, a null note 500, more than 255 characters 400 (E1/E2).

| | |
|---|---|
| Auth | Bearer sent |
| Callers | CMS "Aggiorna note" dialog on registered orders (ls-cms@dev:js/function/component-handler.js:254-321) |
| Source | ls-cms@dev:js/rest/rest-caller.js:88-96; ls-cms@dev:js/constant/rest-constant.js:25-26; bodies ls-cms@dev:js/function/component-handler.js:265-269, 304-307 |

Read request and response (`notes` is a string or `null`):

```json
{ "codice": "LS-2024-0118" }
```

```json
{ "notes": "Consegna solo al mattino, chiamare il destinatario prima" }
```

Update request (response not read):

```json
{ "codice": "LS-2024-0118", "notes": "Consegna solo al mattino, chiamare il destinatario prima" }
```

* One note per order, replaced on save: no history, author or date is visible.
* When the note is `null` the CMS writes "Nessuna nota presente" into the field's value.
  Saving without typing stores that literal, and line breaks are lost (CMS-11).
* Whether this is the customer's `note` sent with EP-08 or a separate staff note is
  (unknown) (CU-10).
* Compatibility: retire (ADM-07).

### 7.6 Detail

#### EP-17 · `POST /mono/api/order/detail`

> **From the source, EP-17** (corrected; 12a §4.1, details in 12a §1.4): There are **four** keys: it adds `mittente`. All inner keys are known (12a §1.3). `palletList` has one entry per physical unit. An unknown code gives 404 (F1).

| | |
|---|---|
| Auth | Bearer sent |
| Callers | CMS "Dettaglio" on pending and registered orders (ls-cms@dev:js/components/types/data-tables-component.js:202-209, 293-300) |
| Source | ls-cms@dev:js/rest/rest-caller.js:99-106; ls-cms@dev:js/constant/rest-constant.js:29; rendering ls-cms@dev:js/function/component-handler.js:80-129 |

Request. The real code is sent: the definitive one, or the TEMP code of a pending order
(ls-cms@dev:js/components/types/data-tables-component.js:298):

```json
{ "codice": "LS-2024-0118" }
```

Response. Only the three top-level keys are known; every inner entry is printed as
"key: value" with the raw key as label:

```json
{
  "spedizione": { "<chiave>": "<valore>" },
  "dettaglioColli": { "<chiave>": "<valore>" },
  "palletList": [ { "<chiave>": "<valore>" } ]
}
```

* `spedizione` and `dettaglioColli` must be objects and `palletList` an array (the CMS
  iterates them, ls-cms@dev:js/function/component-handler.js:93, 104, 115).
* `dettaglioColli` values are printed raw, so they are primitives (inferred: a nested
  `palletList` would print "[object Object]"). The backend therefore reshapes the
  portal's `dettaglioColli.palletList` into a top-level `palletList` (inferred) (OP-15).
* The inner keys are (unknown) (CU-09). Plausible ones are the portal's names
  (`altezza`, `peso`, `larghezza`, `lunghezza`, `quantity`, `type`; recipient fields,
  `codiceDocumentoTrasporto`, `servizioAccessorio`, `note`) (inferred).
* History: since `ls-cms@bf9d484` (2024-06-03).
* Compatibility: retire (ADM-08). It is also the migration's only source of order lines
  through the API (chapter 08 §4.3).

### 7.7 Report

#### EP-18 · `POST /mono/cms/order/report`

> **From the source, EP-18** (corrected; 12a §4.1, details in 12a §1.4): **Open (no auth).** The request also has `sendToCustomer` (default false) and `onlyDelivered` (no default: **500 when absent**, as in the CMS body, since 2024-08-25). A link is e-mailed (not an attachment) to the **office** always, and to `email` only when `sendToCustomer` is true. `email` filters the customer. `to` is inclusive and the period applies to the creation date (G1).

| | |
|---|---|
| Auth | Bearer sent |
| Callers | CMS tab "Report" (ls-cms@dev:js/function/component-handler.js:213-238) |
| Source | ls-cms@dev:js/rest/rest-caller.js:109-116; ls-cms@dev:js/constant/rest-constant.js:32 |

Request. Dates are `YYYY-MM-DD`, between 2024-01-01 and today in UTC
(ls-cms@dev:js/components/types/form-components.js:68, 74). "From" after "to" is not
checked (CMS-22):

```json
{ "email": "amministrazione@mobilificiorossi.example", "from": "2024-06-01", "to": "2024-06-30" }
```

Response: not read. Success shows "Report inviato correttamente"; failure or an invalid
e-mail shows "Dati non validi".

* Side effects: the backend e-mails a report (owner: through AWS SES). Its content,
  format, attachment, sender, whether `email` is only the recipient or also filters that
  customer's orders ("Email Cliente" placeholder), and whether `to` is inclusive are
  (unknown) (Q-08, CU-12).
* Compatibility: retire (ADM-09).

### 7.8 Enumerations

#### EP-19 · `GET /mono/api/enumeration/statoTracking`

> **From the source, EP-19** (confirmed; 12a §4.1, details in 12a §1.4): Exactly §9.1 of this chapter (12a H2).

| | |
|---|---|
| Auth | none; the CMS sends `Content-Type: application/json` on this `GET` |
| Callers | CMS, first visit of the orders view (ls-cms@dev:js/function/state-machine.js:19) |
| Source | ls-cms@dev:js/rest/rest-caller.js:119-125; ls-cms@dev:js/constant/rest-constant.js:35 |

Response: a JSON array of `{id, name, label}` (§9.1). In `dev` the answer is stored and
never displayed (CMS-10).

Compatibility: **keep (API-02)**.

#### EP-20 · `GET /mono/api/enumeration/servizioAccessorio`

> **From the source, EP-20** (corrected (values now known); 12a §4.1, details in 12a §1.4): Six items, ids 0–5 as numbers, with `name`. **Id 0 = "Nessun servizio accessorio"** (H1).

| | |
|---|---|
| Auth | none; the portal sends no header, the CMS sends `Content-Type` |
| Callers | portal, when EP-02 answers `REGISTERED` (ls-order@dev:js/rest/rest-caller.js:33-42; ls-order@dev:js/function/state-machine.js:148-151); CMS, first visit of the orders view (ls-cms@dev:js/rest/rest-caller.js:127-133) |
| Source | ls-order@dev:js/constant/rest-constant.js:11; ls-cms@dev:js/constant/rest-constant.js:36 |

Response: a JSON array; the portal reads `id` and `label` of each item (§9.2).

Compatibility: **keep (API-02)**.

## 8. Wire types and variants across legacy builds

Values typed into forms travel as strings, and several fields changed type or default
between builds. Which build was live when is (unknown). The importer must therefore accept
every variant (chapter 08 §3).

What the backend did with them (12a §4.4 point 4; 12b §4): a `"0"` dimension and a
recipient `telefono: ""` are rejected with 400; `servizioAccessorio: null` is rejected (400
while the field was `@NotNull`, 500 in the `migration` build); the number `1` sent as "no
service" (May 2024 portal builds) is stored as "Prenotazione telefonica"; a missing line
`type` never reaches the database (`PALLET`/`EPAL` labels only). Each line is stored as one
row per physical unit. Timestamps travel as ISO strings before 2024-07-27 and as decimal
epoch seconds after. With the dump as the migration source (chapter 08 §4), the wire
variants matter only for the API-export fallback.

| Field | Variants on the wire | Builds | Source |
|---|---|---|---|
| line `altezza`, `larghezza`, `lunghezza`, `peso`, `quantity` | strings of digits, `"0"` allowed | all | ls-order@dev:js/rest/dto/order-request.js:32-36; ls-order@dev:js/function/form-validator.js:128-136 |
| line `type` | `"0"`, `"1"` | all | ls-order@dev:js/constant/costant.js:87-88 |
| | `null` when no type was chosen (OP-04 path) | `main` and builds before `e183526` (2024-05-19) | ls-order@main:js/function/data-handler.js:22 (no fallback) |
| | number `1` when no type was chosen (OP-04 path) | from `e183526` | ls-order@dev:js/function/data-handler.js:23-26 |
| `servizioAccessorio` when no service is chosen | `null` | `main` (2024-05-09) and builds before `e183526` | ls-order@main:js/function/data-handler.js:51 |
| | number `1` | `e183526` (2024-05-19) to before `6176e85` | ls-order@e183526:js/function/data-handler.js:58-61 |
| | number `0` | from `6176e85` (2024-05-25) | ls-order@dev:js/function/data-handler.js:58-61 |
| `servizioAccessorio` when a service is chosen | the item's `id` as a **string** (an HTML attribute value) | all | ls-order@dev:js/function/data-handler.js:58 |
| `codiceDocumentoTrasporto` | required string | `main`, and from `1c84556` (2024-06-03) | ls-order@dev:js/function/form-validator.js:103-108 |
| | `null` when empty (optional; from `6176e85` the empty field only shakes, the order is still sent) | `e183526` to before `1c84556` | ls-order@e183526:js/function/data-handler.js:54-57; ls-order@6176e85:js/function/form-validator.js:102 |
| recipient `telefono` | required string | before `1c84556` (2024-06-03) | chapter 03 §9 |
| | `""` when empty | `1c84556` only | ls-order@1c84556:js/function/data-handler.js:83 |
| | `null` when empty | from `1cb5dd2` (2024-06-04) | ls-order@dev:js/function/data-handler.js:85 |
| `note` | `""` when empty | all | ls-order@dev:js/function/data-handler.js:65 |
| e-mail (`email`, `clienteEmail`) | as typed: case kept, never trimmed or lower-cased; whitespace cannot occur because `EMAIL_REGEX` rejects it (OP-11) | all | ls-order@dev:js/function/data-handler.js:8-10 |
| tracking id | number `1` (EP-11), string (EP-14) | CMS `dev` | ls-cms@dev:js/function/component-handler.js:425, 514 |
| amount | free text as typed (`"180,50"`, `"180.50"`, anything non-empty) | CMS from `e5a72fc` (2024-05-25) | ls-cms@dev:js/function/component-handler.js:408, 424 |

Two consequences matter for the migration:

* "No service" and a real service can collide. The chosen id travels as a string and the
  default as a number. If the enumeration has an item with id `1` (or `0`), a backend that
  stores the value as an integer cannot tell, for the `e183526` builds, "none" from that
  service (inferred) (chapter 08 §5, DQ-07).
* A line `type` of `1` may mean "Epal" or "no type chosen" (OP-04).

## 9. Enumerations

### 9.1 `statoTracking`

Values as hard-coded in the CMS since `ls-cms@e5a72fc` (2024-05-25)
(ls-cms@dev:js/components/data/user-data.js:22-53):

```json
[
  { "id": 0, "name": "NON_GESTITO",   "label": "Non gestito" },
  { "id": 1, "name": "PRESA",         "label": "Presa in carico" },
  { "id": 2, "name": "TRANSITO",      "label": "Transito" },
  { "id": 3, "name": "TERMINAL_DEST", "label": "Terminal destinatario" },
  { "id": 4, "name": "ASSEGNATO",     "label": "Assegnato" },
  { "id": 5, "name": "CONSEGNATO",    "label": "Consegnato" }
]
```

* Evidence for the server's shape. Before `e5a72fc`, the CMS started with an empty list
  (ls-cms@main:js/components/data/user-data.js:21), fetched EP-19 before building the
  orders view (ls-cms@main:js/function/state-machine.js:15-19), and built the tracking
  menu from that answer, reading `id` and `label`
  (ls-cms@main:js/components/types/form-components.js:66;
  ls-cms@dev:js/components/types/dropdown-component.js:55-77). So the server's items have
  at least `id` and `label`. That the hard-coded list, `name` included, is a copy of the
  real answer is likely but (inferred) (CU-07).
* Ids travel as numbers in this list; EP-14 sends the chosen id as a string, EP-11 the
  number 1.
* Consumers today: the CMS menu (hard-coded copy), EP-14, the `status` of EP-13 (label or
  name, Q-03), and the new site's `STATES`, which adds a customer-facing title and a
  progress step to each id (ls-home@fe4b056:public/js/tracking.js:14-21; chapter 06 §5.7).
* Target: seed exactly these ids, names and labels (ADM-10), and serve them unchanged at
  EP-19 (API-02).

### 9.2 `servizioAccessorio`

The values are known from the source
(ls@migration:src/main/java/it/logisticasanchez/mono/business/model/enumeration/ServizioAccessorioEnum.java:16-21;
12b §2.2–2.3). EP-20 answers exactly:

```json
[{"id":0,"name":"NESSUN_SERVIZIO","label":"Nessun servizio accessorio"},
 {"id":1,"name":"PRENOTAZIONE_TEL","label":"Prenotazione telefonica"},
 {"id":2,"name":"SPONDA_IDR","label":"Sponda idraulica"},
 {"id":3,"name":"MERCE_LUNGA","label":"Segnalazione merce lunga"},
 {"id":4,"name":"FACCHINAGGIO","label":"Facchinaggio"},
 {"id":5,"name":"GDO","label":"GDO"}]
```

* `id` is a JSON number. Item 0 is the "none" entry, a real item of the list. The database
  stores the **label**; a `null` id fails with 500.
* Portal builds `e183526`…`6176e85` (May 2024) sent `1` for "none", which every backend
  from 2024-05-06 stores as "Prenotazione telefonica" (DQ-07; 12a §4.4 point 4).
* The portal injects the label as HTML without escaping (OP-S5). The CMS fetches this list
  but never uses it; its order tables print the stored label (CMS-10).
* The new static site lists other services on `/servizi/` and in the quote form
  ("Consegna su appuntamento", "Preavviso al destinatario", …; chapter 06): that is
  marketing copy, not this enumeration; do not seed from it.
* Target: seed exactly these six items (ADM-10) and serve them unchanged at EP-20
  (API-02). Probe P-01 only confirms that the live build answers the same.

### 9.3 Client-side constants (no backend enumeration known)

| Constant | Values | Source |
|---|---|---|
| Pallet type (line `type`) | `"0"` = `PALLET`, menu text "Pallet Generico/Colli"; `"1"` = `EPAL`, menu text "Epal" | ls-order@dev:js/constant/costant.js:86-90; ls-order@dev:js/constant/language-messages.js:62-63 |
| Customer status (EP-02) | `NEW`, `PENDING`, `REGISTERED` | ls-order@dev:js/constant/costant.js:17-19 |

Whether the backend has an enumeration for pallet types, and which ids it uses, is
(unknown) (CU-19).

## 10. Compatibility after the cut-over

### 10.1 Decision per endpoint

After the DNS switch, `app.logisticasanchez.com` points to the new app (chapter 10 §7).
Any request still sent to a legacy path reaches the new app.

| Endpoint | Decision | Who may still call it after the switch | Behaviour in the new app |
|---|---|---|---|
| EP-13 `POST /mono/api/order/trace/state` | **keep** (API-01) | the new app's own `/tracking/` page (same origin, chapter 10 §2); open tabs or cached copies of the live site; the Netlify static site while it is online (Q-20; it calls `https://app.logisticasanchez.com`); the old S3 tracking app if it is online and calls production (LPS-U4); unknown third parties (probe P-06) | Same contract, answered from the new database (§10.2) |
| EP-19, EP-20 enumerations | **keep** (API-02) | no known caller once the portal and the CMS are gone; kept because unknown consumers may exist and they cost nothing | The legacy JSON, unchanged |
| EP-02, EP-03, EP-08 (portal) | retire (API-03) | stale tabs of `ordini.logisticasanchez.com`; the old S3 portal if it is online and its `HOST` points to production | `410 Gone`. The portal shows its generic error dialog and nothing is created. The hosts themselves redirect to `/area-clienti/` (PUB-04, LPS-S4). |
| EP-01, EP-04…EP-07, EP-09…EP-12, EP-14…EP-18 (CMS) | retire (API-03) | stale CMS tabs | `410 Gone`. Staff use `/gestione/`. |

Risk to close at the cut-over: if a legacy frontend calls the old backend through another
name (the EB host, `api.logisticasanchez.com`; CU-21), switching the DNS of `app.` does
not stop it. It would keep writing into the old database after the freeze. The runbook
therefore blocks the legacy write endpoints at the infrastructure level, or stops the old
backend (chapter 08 §4.7).

### 10.2 Rules for the kept endpoints

**EP-13 (API-01, TRK-01…04)**

1. `POST`, JSON body `{"codice": "<string>"}`. Also accept the codes the legacy clients
   produce: padded with spaces (v1) or without spaces (new site). Match per TRK-02: trimmed,
   spaces removed, case rule as decided (default case-insensitive).
2. Known code of an approved order → `200`, `Content-Type: application/json`,
   `{"status": "<state>"}`, with a non-empty string `status`. Use the representation
   decided for Q-03 (label by default), in one place in the code. Never answer 2xx with a
   missing or `null` `status`: v1-style clients would print "undefined" or nothing (LPS-03).
3. Unknown code, and a pending order's temporary code → `200 {"status": "Non gestito"}`,
   exactly as the legacy answers (12a §1.4 D1; Q-03, Q-38). Empty or missing `codice` →
   `400`. One answer for "unknown" and "not handled yet" also avoids telling a caller
   which codes exist. Answering `404` instead would be a deliberate change: only with the
   owner's decision and an ADR.
4. Extra fields may be added, never removed. Only fields without personal data (TRK-03);
   the richer format the new site can render is in chapter 06 §5.7.
5. CORS on `app.logisticasanchez.com`: allow `https://logisticasanchez.com` (add others
   only on the owner's word, LPS-U9), methods `POST, OPTIONS`, header `Content-Type`
   (`Accept` is safelisted), no credentials, a preflight cache (`Access-Control-Max-Age`).
   The legacy allows the origins of `ALLOWED_ORIGINS` with credentials (12a §2.10).
6. Serve the path on both hosts: the apex (same origin for the ported widget, chapter 10
   §2) and `app.logisticasanchez.com` (external callers).
7. Rate limit per IP (TRK-04) with `429`. Every known client shows any non-2xx as "not
   found"; chapter 06 §5.7 discusses that trade-off.

**EP-19, EP-20 (API-02)**: `GET`, no auth, `200` with exactly the legacy arrays (§9),
items in the legacy order, ids with their legacy JSON types. Answer the CORS preflight the
CMS used to trigger as well (harmless).

### 10.3 Retired endpoints (API-03)

* Every retired path answers `410 Gone` with a short JSON body, for example
  `{"error": "gone", "message": "Servizio non più disponibile: usa https://logisticasanchez.com/area-clienti/"}`
  (proposed wording, owner to review). No legacy client reads it.
* No CORS headers are needed. A refused preflight gives the same result in the legacy
  frontends: their generic error.
* A retired path never performs its old action. In particular, never accept EP-08 orders
  identified only by e-mail (OP-S1).
* Links in e-mails the legacy backend already sent point to `https://{EMAIL_ORIGIN}/mono/...`:
  `/mono/email-confirm?email=…&verificationCode=…` (address verification; never expires),
  `/mono/report?…` and `/mono/download-report?…` (reports), and the logo
  `/mono/img/logistica-sanchez-logo.jpg` in every e-mail. `EMAIL_ORIGIN` defaults to the
  **apex** (ls@migration:docker-compose.yml:83); the production value is Q-50. E-mails sent
  before 2025-11-16 use `http://` and older hosts (12c §1.4). Answer these `GET` paths **on
  the apex and on `app.`**: the logo as a static file; every other legacy `GET /mono/**`
  with a small HTML page (status 410): "Il link non è più valido: accedi all'area clienti"
  with a link to `/area-clienti/` (proposal). Never confirm an address and never serve
  report data from such a link. A pending customer who signs in verifies the e-mail at the
  same time (chapter 08 §2.4). Until the cut-over, the new static site passes `/mono/*` on
  to the legacy backend (ls-home `public/_redirects` from `9b78785`).
* List the retired paths in the ADR required by API-03.

### 10.4 Contract fixtures

For the contract tests of chapter 10 §6. "Legacy" says what the legacy clients prove;
"Target" is the decided behaviour of the new app.

| ID | Request | Legacy evidence | Target response |
|---|---|---|---|
| FX-01 | `POST /mono/api/order/trace/state` `{"codice":"LS-2024-0118"}`, order approved, state PRESA | 2xx + `status` (v1, new site) | `200` `{"status":"Presa in carico"}`, or `"PRESA"` if Q-03 says so |
| FX-02 | the same for one order in each of the six states | — | `status` = label (or name) of states 0…5, §9.1 |
| FX-03 | `{"codice":"NONESISTE"}` | `200 {"status":"Non gestito"}` (12a §1.4 D1) | `200` `{"status":"Non gestito"}` (Q-03) |
| FX-04 | `{"codice":"  ls-2024-0118 "}` (spaces, lower case) | collation: case and trailing spaces ignored, leading spaces not (inferred, 12a CU-02) | same answer as FX-01 under TRK-02 defaults |
| FX-05 | `{}` or `{"codice":""}` | `400` (12a §1.4 D1) | `400` |
| FX-06 | the temporary code of a pending order | `200 {"status":"Non gestito"}` (12a §1.4 D1) | `200` `{"status":"Non gestito"}` (Q-38) |
| FX-07 | `OPTIONS`, `Origin: https://logisticasanchez.com`, `Access-Control-Request-Method: POST`, `Access-Control-Request-Headers: content-type` | `200` with `Access-Control-Allow-Credentials: true` (inferred, 12a §2.10) | `204` with `Access-Control-Allow-Origin: https://logisticasanchez.com`, `POST` allowed, `content-type` allowed, no `Access-Control-Allow-Credentials` |
| FX-08 | the same preflight from `https://example.org` | (unknown) | no `Access-Control-Allow-Origin` |
| FX-09 | more lookups than the per-IP limit | none | `429` |
| FX-10 | any FX-01/FX-02 answer | — | only the keys allowed by TRK-03 |
| FX-11 | `GET /mono/api/enumeration/statoTracking` | shape §9.1 | `200`, exactly the array of §9.1 |
| FX-12 | `GET /mono/api/enumeration/servizioAccessorio` | §9.2 (from the source) | `200`, exactly the array of §9.2 |
| FX-13 | `POST /mono/api/order/register` with the EP-08 sample | — | `410`, and nothing is written to the database |
| FX-14 | `POST /mono/auth/login` | — | `410` |

Expected bodies (FX-01; FX-03 and FX-06):

```json
{ "status": "Presa in carico" }
```

```json
{ "status": "Non gestito" }
```

## 11. Contract unknowns

### 11.1 List

Written before the backend source was available. **Most are now answered: 12a §4.2 gives
the status of each** (and 12b §5.8 the data-related ones). Still open: CU-14 (the live
CORS origins), CU-21 (which CMS build runs in production, Q-47), the production collation
behind CU-02, CU-03 and CU-17, and the number of staff accounts in CU-13 (Q-17). Defaults for
the new app are in chapter 11 and in §10.

| ID | Unknown | Why it matters | How to settle |
|---|---|---|---|
| CU-01 | EP-13 `status`: label ("Presa in carico") or name ("PRESA")? A string or an object? (Q-03) | The compatibility answer must match | P-02 |
| CU-02 | EP-13 for unknown, empty, padded, lower-case and TEMP codes: status code and body; is matching case-sensitive or trimmed? | FX-03…FX-06, TRK-02 | P-02, source |
| CU-03 | EP-02: other fields; other `status` values (declined? e-mail not confirmed?); answer for a malformed e-mail; case and space handling | Customer state mapping (chapter 08 §3.1) | source, P-07 |
| CU-04 | EP-03 and EP-08: success codes; errors (duplicate e-mail, validation); server-side length and format limits | Data quality to expect | source, DB |
| CU-05 | Does EP-08 refuse an e-mail that is not `REGISTERED`? | Whether OP-S1 was exploitable without the portal; orphan orders | source |
| CU-06 | E-mails sent on EP-03, EP-06, EP-07, EP-08, EP-11, EP-12, EP-14: triggers, recipients, wording (Q-07) | EML-01…09 | source, SES logs, sample e-mails from the owner |
| CU-07 | `servizioAccessorio` values, id type, `name` key, whether an item is "none"; does the real `statoTracking` answer equal §9.1? | ADM-10 seed, API-02, migration | P-01 |
| CU-08 | Order list items: values of `stato`, `tracking`, `servizioAccessorio` (id, name, label, object?); is `importo` the stored `importoAssicurato`? Extra fields not read by the CMS (dates, customer id, `importoAssicurato`, `importoContrassegno`)? | Migration mapping (chapter 08 §3.2) | P-05, DB |
| CU-09 | Inner keys of the EP-17 answer (`spedizione`, `dettaglioColli`, `palletList[]`) | Migration of lines and shipment data | P-05, DB |
| CU-10 | Are EP-15 notes the customer's EP-08 `note` or a separate staff note? | Where notes land in the new model | P-05 (compare with EP-17), DB |
| CU-11 | EP-14 answer body; status code for an unknown code | — (retired) | source |
| CU-12 | EP-18 report: content, format, attachment, sender; is `email` a recipient, a customer filter or both; is `to` inclusive? (Q-08) | ADM-09 parity | source, a sample report from the owner |
| CU-13 | Is the token enforced on EP-04, EP-05, EP-09, EP-10, EP-17 and `/mono/cms/**`? Token format, lifetime, refresh endpoint, roles, number of staff accounts (Q-17) | Possible public exposure of personal data (CMS-05); staff accounts | P-04, source |
| CU-14 | CORS allow-list of the legacy backend (apex, `www`, `ordini.`, S3 origins, CMS origin, localhost) | §5.2, §10.2 | P-03, source |
| CU-15 | Do the lists come back in ascending id order? | "Newest first" in the CMS (reversal) | P-05 |
| CU-16 | Decline of a customer or an order: delete or status? Can a declined e-mail register again? (Q-09) | State mapping, migration completeness | source, DB |
| CU-17 | Format, uniqueness and case of TEMP and definitive codes (Q-06) | Code constraints, matching | DB, P-05 |
| CU-18 | What `updateShippingTs` / `updateDeliveryTs` do; which timestamps orders store; tracking history kept? | Dates in the migration and in the report | source, DB |
| CU-19 | How the backend stores line `type` (`"0"`/`"1"`/`1`/`null`) and numeric strings; does it compute weight and volume totals? | Line mapping | source, DB |
| CU-20 | Does the backend use `destinatario.email` (e.g. to write to the recipient)? | OP-14 | source |
| CU-21 | Which commit and `HOST` each production frontend (portal, CMS, old S3 apps) uses; whether the old S3 apps still serve | Cut-over: who keeps writing to the old backend | owner, AWS console |
| CU-22 | Does the server refuse approval of customers whose e-mail is not confirmed? | CMS-20, customer state machine | source |
| CU-23 | Rate limits and server-side validation on the public endpoints | Abuse already in the data | source, logs |
| CU-24 | Endpoints no frontend calls: the registration-confirmation link handler (URL, token, expiry), any refresh-token or admin endpoint | Old e-mail links after the switch (§10.3) | source, access logs (P-06) |

### 11.2 Read-only probes for Gate 0

These touch production, so the building agents **never run them on their own**. They
write the probes as a script with a fixed read-only allow-list (`tools/probes/`, like the
exporter of chapter 08 §4.3); the owner runs it, or authorises one run in the chat. All
are reads. None calls EP-14, which writes despite its name, or EP-18, which sends e-mails.
The agents never receive staff credentials or tokens.

Where the answers go: P-01 and P-03 outputs and the P-04 status codes may be committed
under `docs/legacy-captures/`. P-02, P-05, P-06 and P-07 answers contain personal data:
they are kept like a dump (chapter 08 §4.1: encrypted, outside git and outside the agents'
session); the agents get only a schema summary (key names, JSON types, counts, value
patterns such as the code format), never values.

| ID | Who | Request | Settles |
|---|---|---|---|
| P-01 | agents, after the owner's go-ahead | `GET /mono/api/enumeration/servizioAccessorio` and `GET /mono/api/enumeration/statoTracking`, no auth | Confirms 12b §2.3 on the live build (CU-07) |
| P-02 | agents, after the go-ahead, with codes the owner gives | `POST /mono/api/order/trace/state` with: a real code in each tracking state; the same code in lower case and with spaces; an unknown code; `""`; a TEMP code | CU-01, CU-02 |
| P-03 | agents, after the go-ahead | `OPTIONS` preflights with `Origin` = apex, `www`, `ordini.`, the two S3 origins, `https://example.org` | CU-14 |
| P-04 | owner only | Without any `Authorization` header: EP-04, EP-05, EP-09, EP-10, `order/active` and `order/delivered` with `{}`; EP-17 and EP-15 with one known code. Record only the status and the size, never the body, e.g. `curl -s -o /dev/null -w '%{http_code} %{size_download}' -X POST -H 'Content-Type: application/json' -d '{}' https://app.logisticasanchez.com/mono/api/customer/pending` (the same for each path). A protected endpoint answers **500** without a token (12a §2.9): read 500 as "protected" and a 2xx with a non-empty body as "open". The source already shows EP-09, `order/active` and `order/delivered` open; the probe confirms that the live build is the repository's. Never probe EP-18, `/mono/report` or `/mono/download-report` (they send e-mail or serve personal data). Repeat on the UAT host if it is still online (Q-46). | Q-21 on the live build. Any 2xx with a non-empty body is an incident (chapter 11 Q-21). |
| P-05 | owner only, with the exporter of chapter 08 §4.3 and its allow-list, never by hand or by an agent | With a staff token: EP-04, EP-05, EP-09, EP-10, then EP-17 and EP-15 for a few codes | CU-08, CU-09, CU-10, CU-15, CU-17 |
| P-06 | owner only | Export the access logs of the legacy VPS nginx (volume `nginx_logs`, 12d §1.10) for a few weeks (method, path, status, `Origin`/`Referer`, user agent); give the agents only aggregated counts per path, origin and status | Unknown callers of EP-13, CU-21, CU-24 |
| P-07 | owner only | `POST /mono/api/customer/state` for one e-mail of each list, one unknown e-mail, one upper-case variant; give the agents the statuses only | CU-03 |
