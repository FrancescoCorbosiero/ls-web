# 12a · Legacy backend: API layer and security (from the source)

> **Status.** Written from the legacy backend source (`ls`, branch `migration`, commit
> `ca1b0d0`) on 2026-10-16. Its corrections to chapters 01–11 have been applied there.
> Where this chapter and an older chapter still disagree about how the legacy system
> works, **this chapter wins**: it reads the server code, while 02–08 inferred the server
> from its clients. Sections titled "Reconciliation" or "Corrections to the brief" record
> what changed.

This note documents the HTTP API and the security model of the legacy backend "mono"
(Spring Boot, base package `it.logisticasanchez.mono`) as its source code defines them.
Chapter 02 rebuilt the contract from the frontends only. Where the two disagree, this note
wins; §4 lists every difference. Use it to build the compatibility endpoints
(API-01…API-03), to fix the API-export fallback of chapter 08 §4.3, to write parity
tests, and to tell the owner what the live system exposes today.

## How to read

* **Source.** `ls@migration` = branch `migration`, head `ca1b0d0` (2025-11-16), which the
  owner believes is production. The extracted copy matches the branch byte for byte
  (all 230 tracked files compared with `git hash-object`). Path abbreviations:
  * `J/` = `src/main/java/it/logisticasanchez/mono/`
  * `R/` = `src/main/resources/`
  * `T/` = `src/test/`

  So `ls@migration:J/api/controller/OrderController.java:55` means line 55 of
  `src/main/java/it/logisticasanchez/mono/api/controller/OrderController.java`.
  `ls@<commit>:…` cites history (`git -C legacy/ls show <commit>:<path>`).
* **Other branches.** `dev_API_v2` (`91cf025`, 2025-01-07) has the same Java code except
  `J/business/util/LinkUtil.java`, where it builds `http://` e-mail links and points to the
  S3 portal and CMS (`git diff origin/dev_API_v2 origin/migration -- src/main/java`). Everything
  below holds for both unless stated. `main` is a 2024-02-03 skeleton plus an
  `IMPROVEMENTS.md` (2026-07-29) that analysed only that skeleton. `dev_API`,
  `dev_API_fullSQL` and `dev_thymeleaf` are February 2024 prototypes.
* **Which build is live cannot be proven from the repository.** §2.16 lists the behaviour
  that changed over time, so that a read-only probe can tell which build answers in
  production.
* **Labels.** "(inferred)" means the claim follows from the code plus documented
  Spring/Jackson/Tomcat behaviour but was not observed. "(log)" means it was observed in
  the logs committed under `ls@migration:logs/` (21 files, 2024-06-12 to 2025-01-07,
  environment unknown, DispatcherServlet at DEBUG). Only aggregate counts are reported,
  never log content.
* **Samples and secrets.** Samples use fictional Italian values and `.example` addresses.
  Shipment codes such as `LS-2024-0118` are illustrative. Tokens, passwords, keys and the
  real e-mail addresses found in the repository are never reproduced. For secrets, only the
  key name is given, together with the fact that a value is committed.

## 0. Key answers

1. **Q-21 / CMS-05 / CU-13: are the staff reads open?** Partly. In the source:
   * **Protected** (only a JWT of a user with role `ADMIN`): customer lists (EP-04,
     EP-05), registered orders (EP-10), order detail (EP-17) and notes (EP-15). Without
     such a token they return **HTTP 500** and no data, not 401/403.
   * **Open: `POST /mono/api/order/pending` (EP-09).** Anyone without a token gets every
     pending order created in the last 6 months, with the sender's e-mail, the
     recipient's name, address and phone, the DDT number and the customer's note.
   * **Open as well, and unknown to chapter 02:** `POST /mono/api/order/active` and
     `POST /mono/api/order/delivered` (with `{}` they return every approved order of the
     last 6 months), and `GET /mono/download-report?email=…` (a PDF of any customer's
     shipments, by e-mail address).

   Details in §2.8.
2. The URL rule written to protect `/mono/cms/**` never matches, because the servlet
   context path is `/mono` and the matcher compares paths *inside* that context
   (§2.7). Protection rests entirely on per-method `@PreAuthorize`, and
   `POST /mono/cms/order/report` (EP-18) has none: it is open.
3. **Authentication.** Staff log in with `POST /mono/auth/login` and receive:
   * an HS256 JWT with claims `sub`, `operations`, `iat` and `exp`, valid 6 h by default;
   * an opaque refresh token, which is the UUID of the user's single row in table
     `user_session` and slides 2 h on each refresh.

   A refresh endpoint exists (`POST /mono/auth/refresh-token`). There is no master login,
   no logout and no "who am I" endpoint (§2.2–2.6).
4. **Error model.** Errors use one JSON body, `{"reason": "<Italian text>", "detail":
   "<text or 'No detail'>"}` (§3). A catch-all handler turns every unexpected exception
   into **500**, including:
   * authorisation denials;
   * wrong HTTP methods and unknown paths;
   * malformed JSON and unknown JSON fields.

   An invalid or expired token gets **401** with the raw exception text as its body.
5. **Tracking (EP-13).** `status` carries the **label** (`"Presa in carico"`). Unknown
   codes and the TEMP codes of pending orders answer **200 `{"status": "Non gestito"}`**,
   not an error (§1.4 D1).
6. **The `ls-cms@dev` code cannot be the CMS that works with this backend.** Since
   2024-07-18, order approval (EP-11) requires `req.importo`. Since 2024-08-25, the report
   (EP-18) requires `onlyDelivered`. The CMS sends neither, so both actions fail with 500
   (§4.1). The logs also show calls to three endpoints that no repository frontend calls
   (`order/active`, `order/delivered`, `cms/order/update`). The backend itself links to a
   CMS at `https://dashboard.logisticasanchez.com/`.

## 1. Endpoint inventory

### 1.1 Runtime frame (applies to every endpoint)

| Aspect | Fact | Source |
|---|---|---|
| Stack | Spring Boot 3.2.2 (hence Spring Framework 6.1 and Spring Security 6.2, inferred from the Boot BOM), Java 17, jjwt 0.11.5, MySQL, AWS SES, Thymeleaf | ls@migration:pom.xml:5-9, 17, 22-66 |
| Context path | `/mono` in every profile; full path = `/mono` + controller mapping | ls@migration:R/application.yml:3-4; R/application-dev.yml:3-4; R/application.prod.yml:42-43; T/resources/application-h2.yml:3-4 |
| Mappings | Paths are constants relative to the context (`/api/…`, `/cms/…`, `/auth/…`, `/utils/…`, `/email-confirm`, `/report`, `/download-report`). The only class-level prefix is `EnumController` (`/api/enumeration`). | ls@migration:J/configuration/constant/PathConstants.java:10-61; J/api/controller/EnumController.java:20 |
| JSON mapper | Since 2024-07-27, a plain `new ObjectMapper()` with `JavaTimeModule` replaces Boot's mapper (ls@0f1a1fe). Consequences:<br>(a) `OffsetDateTime` fields are written as **decimal epoch seconds**, e.g. `1719738000.123456000` (log: 16 numeric values, all after 2024-07-27; earlier builds wrote ISO strings with offset, e.g. `"2024-06-30T10:00:00.123456+02:00"`, log: 260 values);<br>(b) unknown JSON properties in a request are rejected (inferred, Jackson default), which ends as HTTP 500 (§3.3);<br>(c) numeric strings are coerced (`"150"` becomes 150.0; `"3"` becomes 3), but `"180,50"` fails, also with 500 (inferred, Jackson default) | ls@migration:J/configuration/JacksonConfig.java:13-18 |
| Enumerations on the wire | Every enum is serialised as its **label** string (`@JsonValue getLabel()`), e.g. `"stato": "PENDING"`, `"tracking": "Presa in carico"`, `"servizioAccessorio": "Sponda idraulica"`. Before 2024-05-06 they were objects such as `{"label": "…"}` (ls@540303d:J/business/model/enumeration/ServizioAccessorioEnum.java:10). | ls@migration:J/business/model/enumeration/state/TrackingState.java:24-27; ServizioAccessorioEnum.java:25-28; state/ConfirmationState.java:22-25 |
| Body validation | `@RequestBody @Valid` on every DTO, so a violation gives **400**. The services are `@Validated`, so a violated method constraint also gives **400**. | ls@migration:J/api/error/ErrorController.java:45-61 |
| Automatic e-mails | Every e-mail goes through `sendHtml`/`send`, which return silently when `app.email.enableAutomaticEmails` is false. The default is true (`ENABLE_AUTOMATIC_EMAILS`). | ls@migration:J/business/service/AwsEmailService.java:37, 53, 71; R/application.yml:80 |
| Sender | `From` = `app.email.author`: default `servizioclienti@logisticasanchez.com` in base and prod configuration; in the `dev` profile the default is a developer's personal address (not reproduced) | ls@migration:R/application.yml:78; docker-compose.yml:82; R/application-dev.yml:78 |

### 1.2 Inventory (every mapping)

Auth legend (exactly as enforced):
* **public**: the request falls through `anyRequest().permitAll()` and the method has no
  security annotation (ls@migration:J/configuration/security/WebSecurityConfig.java:42).
* **ADMIN**: `@PreAuthorize("hasRole('ADMIN')")` (ls@migration:J/configuration/constant/MonoConstants.java:6),
  active through `@EnableMethodSecurity` (WebSecurityConfig.java:27).

No endpoint uses "authenticated", and the URL rule for `/mono/cms/**` never applies (§2.7).
"Seen in logs" counts requests in the committed logs (log).

| # | Method and full path | Controller method (ls@migration) | Auth | Ch. 02 | Known caller | Seen in logs |
|---|---|---|---|---|---|---|
| 1 | `POST /mono/auth/login` | `AuthController.login`, J/api/controller/AuthController.java:21-24 | public | EP-01 | CMS | 43 |
| 2 | `POST /mono/auth/refresh-token` | `AuthController.refreshToken`, AuthController.java:26-29 | public | — | none | 0 |
| 3 | `POST /mono/api/customer/state` | `CustomerController.state`, J/api/controller/CustomerController.java:27-33 | public | EP-02 | portal | 63 |
| 4 | `POST /mono/api/customer/register` | `CustomerController.register`, CustomerController.java:35-39 | public | EP-03 | portal | 36 |
| 5 | `POST /mono/api/customer/pending` | `CustomerController.pendingCustomers`, CustomerController.java:41-46 | ADMIN | EP-04 | CMS | 8 |
| 6 | `POST /mono/api/customer/registered` | `CustomerController.registeredCustomers`, CustomerController.java:48-53 | ADMIN | EP-05 | CMS | 6 |
| 7 | `POST /mono/api/order/trace/state` | `OrderController.trackingState`, J/api/controller/OrderController.java:33-39 | public | EP-13 | public sites | 0 |
| 8 | `POST /mono/api/order/state` | `OrderController.state`, OrderController.java:41-47 | public | — | none known | 2 |
| 9 | `POST /mono/api/order/register` | `OrderController.register`, OrderController.java:49-53 | public | EP-08 | portal | 28 |
| 10 | `POST /mono/api/order/pending` | `OrderController.pendingOrders`, OrderController.java:55-59 | **public** | EP-09 | CMS | 20 |
| 11 | `POST /mono/api/order/registered` | `OrderController.registeredOrders`, OrderController.java:61-66 | ADMIN | EP-10 | CMS | 1 |
| 12 | `POST /mono/api/order/active` | `OrderController.activeOrders`, OrderController.java:68-72 | **public** | — | unknown client | 19 |
| 13 | `POST /mono/api/order/delivered` | `OrderController.deliveredOrders`, OrderController.java:74-78 | **public** | — | unknown client | 14 |
| 14 | `POST /mono/api/order/detail` | `OrderController.detail`, OrderController.java:80-85 | ADMIN | EP-17 | CMS | 13 |
| 15 | `GET /mono/api/enumeration/servizioAccessorio` | `EnumController.servizioAccessorio`, J/api/controller/EnumController.java:25-34 | public | EP-20 | portal, CMS | 1 |
| 16 | `GET /mono/api/enumeration/tipoPallet` | `EnumController.palletType`, EnumController.java:36-45 | public | — | none | 0 |
| 17 | `GET /mono/api/enumeration/statoConferma` | `EnumController.confirmationState`, EnumController.java:47-56 | public | — | none | 0 |
| 18 | `GET /mono/api/enumeration/statoTracking` | `EnumController.trackingState`, EnumController.java:58-67 | public | EP-19 | CMS | 1 |
| 19 | `POST /mono/sendEmail` (note: not under `/cms`) | `CmsController.sendEmail`, J/api/controller/CmsController.java:44-49 | ADMIN | — | none | 0 |
| 20 | `POST /mono/cms/customer/confirm` | `CmsController.confirm(EmailReq)`, CmsController.java:51-56 | ADMIN | EP-06 | CMS | 13 |
| 21 | `POST /mono/cms/order/confirm` | `CmsController.confirm(OrderConfirmationReq)`, CmsController.java:58-63 | ADMIN | EP-11 | CMS | 9 |
| 22 | `POST /mono/cms/customer/decline` | `CmsController.decline(EmailReq)`, CmsController.java:65-70 | ADMIN | EP-07 | CMS | 0 |
| 23 | `POST /mono/cms/order/decline` | `CmsController.decline(OrderDeclinationReq)`, CmsController.java:72-77 | ADMIN | EP-12 | CMS | 0 |
| 24 | `POST /mono/cms/order/report` | `CmsController.report`, CmsController.java:79-109 | **public** | EP-18 | CMS | 21 |
| 25 | `POST /mono/cms/order/trace` | `CmsController.trace`, CmsController.java:111-123 | ADMIN | EP-14 | CMS | 5 |
| 26 | `POST /mono/cms/notes` | `CmsController.notes`, CmsController.java:125-130 | ADMIN | EP-15 | CMS | 1 |
| 27 | `POST /mono/cms/notes/update` | `CmsController.updateNotes`, CmsController.java:132-137 | ADMIN | EP-16 | CMS | 0 |
| 28 | `POST /mono/cms/order?email=…` | `CmsController.customerOrders`, CmsController.java:139-144 | ADMIN | — | none | 0 |
| 29 | `POST /mono/cms/order/update` | `CmsController.updateOrder`, CmsController.java:146-151 | ADMIN | — | unknown client | 13 |
| 30 | `POST /mono/utils/order/delete` | `AdminToolsController.deleteOrder`, J/api/controller/AdminToolsController.java:31-36 | ADMIN | — | none (Postman) | 8 |
| 31 | `POST /mono/utils/order/deleteAll` | `AdminToolsController.deleteAllOrders`, AdminToolsController.java:38-43 | ADMIN | — | none (Postman) | 0 |
| 32 | `POST /mono/utils/order/deleteBeforeDate` | `AdminToolsController.deleteAllOrdersBeforeDate`, AdminToolsController.java:45-50 | ADMIN | — | none (Postman) | 0 |
| 33 | `POST /mono/utils/customer/delete` | `AdminToolsController.deleteCustomer`, AdminToolsController.java:52-57 | ADMIN | — | none (Postman) | 0 |
| 34 | `GET /mono/email-confirm?email=&verificationCode=` | `WebController.customerEmailConfirm`, J/web/WebController.java:34-37 | public | (CU-24) | link in the registration e-mail | 23 (+3 `POST`) |
| 35 | `GET /mono/download-report?email=&from=&to=&onlyDelivered=` | `WebController.downloadReport`, WebController.java:39-54 | **public** | — | link in report e-mails | 17 |
| 36 | `GET /mono/report?email=&from=&to=&onlyDelivered=` | `WebController.report`, WebController.java:56-68 | **public** | — | link in report e-mails | 5 |
| — | `GET /mono/request-success.html`, `GET /mono/img/logistica-sanchez-logo.jpg` | static files, R/static/ | public | — | none known | — |
| — | `GET /mono/mono/request-success.html`, `…/email-success.html`, `…/report.html`, `…/error.html` | view controllers, J/configuration/MvcConfig.java:12-15. The mapping already contains `/mono`, which is added to the context path, hence the double prefix (inferred). | public | — | none | — |
| — | `/mono/error` | Spring Boot's default error endpoint (inferred) | public | — | — | — |

Each mapping accepts only the method shown; any other method answers 500 (§3.3). Of the 36
controller mappings, chapter 02 knows 20.

### 1.3 Shared response types

**StatusRepr**: `{ "status": string }` (ls@migration:J/api/repr/StatusRepr.java:15-16).

**CustomerRepr** (ls@migration:J/api/repr/CustomerRepr.java:17-54; built in
J/business/factory/CustomerFactory.java:16-28):

| Field | Type | Meaning |
|---|---|---|
| `id` | number | customer id |
| `ragioneSociale`, `indirizzo`, `provincia`, `citta`, `cap`, `telefono`, `email` | string | as registered |
| `customer` | boolean | `stato == REGISTERED` (CustomerFactory.java:26) |
| `emailConfirmed` | boolean | `stato` is `PENDING` or `REGISTERED` (CustomerFactory.java:27) |

```json
{
  "id": 42,
  "ragioneSociale": "Mobilificio Rossi S.r.l.",
  "indirizzo": "Via Garibaldi 12",
  "provincia": "MB",
  "citta": "Lissone",
  "cap": "20851",
  "telefono": "039 2456789",
  "email": "ufficio@mobilificiorossi.example",
  "customer": false,
  "emailConfirmed": true
}
```

**Spedizione**, the "order" object returned by every order list and by EP-08, EP-11 and
EP-14 (ls@migration:J/business/model/Spedizione.java:23-73; built in
J/business/factory/SpedizioneFactory.java:25-61):

| Field | Type | Meaning |
|---|---|---|
| `id` | number | order id |
| `codice` | string | `TEMP` + UUID until approval; then the code typed by staff (§1.4 C6) |
| `email` | string | **the `destinatario.email` sent with EP-08** (SpedizioneFactory.java:71), commented "Email mittente" (Spedizione.java:34). The portal fills it with the customer's e-mail. |
| `ragioneSociale`, `indirizzo`, `provincia`, `citta`, `cap`, `telefono` | string / null | recipient |
| `importoContrassegno`, `importoAssicurato`, `importo` | number / null | amounts. `importo` is the stored column (SpedizioneFactory.java:38). |
| `codiceDocumentoTrasporto` | string / null | DDT |
| `servizioAccessorio` | string | label, e.g. `"Nessun servizio accessorio"` |
| `note` | string / null | the customer's EP-08 note, also edited by staff through EP-16 |
| `stato` | string | `NEW`, `PENDING`, `REGISTERED` or `DECLINED` |
| `tracking` | string | tracking label, e.g. `"Non gestito"` |
| `confirmationTs`, `shippingTs`, `deliveryTs` | number / null | decimal epoch seconds (§1.1). Zone Europe/Rome before serialisation (J/business/util/TemporalUtil.java:10-13). |

No creation date (`createTms`) and no customer id are exposed.

```json
{
  "id": 118,
  "codice": "LS-2024-0118",
  "email": "ufficio@mobilificiorossi.example",
  "ragioneSociale": "Ferramenta Bianchi S.n.c.",
  "indirizzo": "Via Roma 5",
  "provincia": "BG",
  "citta": "Bergamo",
  "cap": "24121",
  "telefono": null,
  "importoContrassegno": null,
  "importoAssicurato": null,
  "importo": 180.5,
  "codiceDocumentoTrasporto": "DDT 245/2024",
  "servizioAccessorio": "Segnalazione merce lunga",
  "note": "Consegna solo al mattino, chiamare il destinatario prima",
  "stato": "REGISTERED",
  "tracking": "Presa in carico",
  "confirmationTs": 1719738000.123456000,
  "shippingTs": null,
  "deliveryTs": null
}
```

**Soggetto**, used as `mittente` in EP-17 (ls@migration:J/business/model/Soggetto.java:17-54):
`id`, `ragioneSociale`, `indirizzo`, `provincia`, `citta`, `cap`, `telefono`, `email`,
`stato` (label). `codiceVerifica` is `@JsonIgnore` (:53-54), so it is never serialised.

**DettaglioColli** (ls@migration:J/business/model/DettaglioColli.java:15-37): `id`,
`colli` (number or null; the portal never sends it), `pallet` (count of type-PALLET units),
`epal` (count of EPAL units), `peso` (sum of unit weights, kg), `volume`. `volume` is the sum
of `h·w·l/1 000 000` per unit, rounded to an integer, in m³ for cm input
(J/business/service/OrderService.java:80-94; J/business/util/MathUtil.java:5-7).

**Pallet** (ls@migration:J/business/model/Pallet.java:15-36): `id`, `altezza`, `peso`,
`larghezza`, `lunghezza` (numbers), `type` (`"PALLET"` or `"EPAL"`). EP-08 expands each line
into `quantity` rows (OrderService.java:71-79).

**OrderDetailRepr** (ls@migration:J/api/repr/OrderDetailRepr.java:20-27): `spedizione`
(Spedizione), `mittente` (Soggetto), `dettaglioColli` (DettaglioColli), `palletList`
(Pallet[]).

**NotesRepr**: `{ "notes": string }` (ls@migration:J/api/repr/NotesRepr.java:16-18).

**EnumerationRepr**: `{ "id": number, "name": string, "label": string }`, where `id` is the
enum ordinal (ls@migration:J/api/repr/EnumerationRepr.java:14-19; EnumController.java:29-31).

**UserTokenResponseDto / LoginUserResponseDto**: see §1.4 A1.

**ErrorRepr**: see §3.1.

### 1.4 Endpoint details

Common error paths (malformed JSON, wrong method, missing or invalid token, CORS) are
listed once in §3.3. The status lists below add the endpoint-specific ones.

#### A. Authentication

**A1 · `POST /mono/auth/login` (EP-01), public**
* Code: AuthController.java:21-24 calls `AuthenticationService.login`
  (ls@migration:J/business/service/AuthenticationService.java:35-54). The JWT filter skips
  exactly this URI (J/configuration/security/JwtAuthFilter.java:33).
* Request `LoginRequestDto` (J/api/req/security/LoginRequestDto.java:5-6):
  * `username`: string, `@NotBlank`. It is the staff username, not an e-mail.
  * `password`: string, `@NotBlank`.
* Response 200 `UserTokenResponseDto` (J/api/repr/security/UserTokenResponseDto.java:3-7):
  * `token`: the JWT;
  * `refreshToken`: the `user_session` UUID;
  * `expTokenSec`, `expRefreshTokenSec`: numbers, the configured lifetimes;
  * `userData`: `LoginUserResponseDto` (J/api/repr/security/LoginUserResponseDto.java:5-9)
    with `id`, `email`, `role` (`"ADMIN"`/`"CUSTOMER"`) and `operations` (string[] =
    `[users.policy]`). **`email` actually holds the username** (AuthenticationService.java:80-84).
* Statuses:
  * 200.
  * 400: a blank field.
  * **401** `{"reason": "Username o password errate.", …}` for a wrong password or an
    unknown username (AuthenticationService.java:37-42; J/api/error/ErrorEnum.java:31;
    log: 25 × 401).
  * 500 when the user's `policy` column is NULL (`List.of(null)`, AuthenticationService.java:84,
    inferred).
  * 500 when the user has two `user_session` rows (`findByUserId` returns an Optional,
    J/persistence/repository/UserSessionRepository.java:12, inferred).
  * 500 when the configured key is too short for HS256 (§2.4, inferred).
* Side effects: finds or creates the user's single `user_session` row and sets its expiry
  to now + `jwt-exp-refresh-token` (AuthenticationService.java:45-53, 74-77). The same
  refresh token is returned at every login (§2.5).

```json
{ "username": "ufficio", "password": "<password>" }
```
```json
{
  "token": "<JWT HS256>",
  "refreshToken": "<UUID>",
  "expTokenSec": 21600,
  "expRefreshTokenSec": 7200,
  "userData": { "id": 1, "email": "ufficio", "role": "ADMIN", "operations": ["none"] }
}
```
(`"operations": ["none"]` is the value in the Postman examples,
ls@migration:api/sanchez-logistica.postman_collection.json:146, 236.)

**A2 · `POST /mono/auth/refresh-token` (not in chapter 02), public**
* Code: AuthController.java:26-29 calls `AuthenticationService.refreshToken`
  (AuthenticationService.java:56-72).
* Request `RefreshTokenRequestDto`: `refreshToken`, string, `@NotBlank`
  (J/api/req/security/RefreshTokenRequestDto.java:5).
* Response 200: the same `UserTokenResponseDto` with a new access token and **the same**
  refresh token. The session expiry is pushed to now + 2 h (sliding)
  (AuthenticationService.java:69-71).
* Statuses:
  * 200.
  * 400: blank.
  * **403** `{"reason": "Non disponi dei permessi necessari per questo tipo di operazione.", …}`
    when the session is unknown or expired. An expired row is deleted
    (AuthenticationService.java:60-64; ErrorEnum.java:33).
  * 401 when the client also sends an expired `Authorization: Bearer` header: the filter
    rejects it before the endpoint runs (JwtAuthFilter.java:33-45, inferred).
* Called by no frontend (chapter 02 §4.1). Example in the Postman collection
  (ls@migration:api/sanchez-logistica.postman_collection.json:241-258).

#### B. Customers

Customer life cycle in this build:
1. `register` creates the customer in state **NEW** (J/business/service/crud/SoggettoService.java:63-70).
2. The e-mail link `/mono/email-confirm` promotes NEW to **PENDING** (SoggettoService.java:53-61).
3. Staff confirm PENDING to **REGISTERED** (SoggettoService.java:117-121).
4. Staff decline sends the customer back to **NEW** (J/business/service/CustomerService.java:70-77).
   Before 2024-07-22 decline set **DECLINED** (ls@ae74561:J/business/service/CustomerService.java:74,
   changed in ls@5debd84).

So `PENDING` means "e-mail verified, waiting for the office", and unverified customers are
NEW.

**B1 · `POST /mono/api/customer/state` (EP-02), public**
* Code: CustomerController.java:27-33 calls `SoggettoService.state`
  (SoggettoService.java:93-97).
* Request `EmailReq` (J/api/req/EmailReq.java:17-20): `email`, `@NotEmpty @Email @Size(min=1)`.
* Response 200 `StatusRepr`. `status` is one of:
  * `NEW`: an unknown e-mail, an unverified customer, or a customer declined since
    2024-07-22;
  * `PENDING`;
  * `REGISTERED`;
  * `DECLINED`: only rows declined before 2024-07-22.

  Values from J/business/model/enumeration/state/ConfirmationState.java:15-18.
* Statuses:
  * 200.
  * 400: malformed or blank e-mail.
  * 500 when two customer rows share the e-mail: the table is unique only on
    (`ragione_sociale`, `email`, `codiceVerifica`), J/persistence/model/SoggettoEntity.java:15-17,
    and `findByEmail` returns an Optional (J/persistence/repository/SoggettoRepository.java:15).
    Inferred. The logs show 6 × 500 on this path, cause not visible.
* Matching is SQL equality, so case and padding follow the database collation (inferred
  case-insensitive; §6).

```json
{ "email": "ufficio@mobilificiorossi.example" }
```
```json
{ "status": "PENDING" }
```

**B2 · `POST /mono/api/customer/register` (EP-03), public**
* Code: CustomerController.java:35-39 calls `CustomerService.register` (@Transactional,
  CustomerService.java:47-55), which calls `SoggettoService.register`
  (SoggettoService.java:99-115).
* Request `CreateSoggettoReq` (J/api/req/crud/CreateSoggettoReq.java:21-49). Every field is
  `@NotBlank`. Size limits come from J/business/model/constant/SoggettoConst.java:5-25:

  | Field | Limits |
  |---|---|
  | `ragioneSociale`, `indirizzo`, `provincia`, `citta` | 1–50 characters |
  | `cap` | 1–9 characters |
  | `telefono` | 1–20 characters, and must fully match `\+?(\d{1,4})?[-.\s]?\(?\d{1,4}\)?[-.\s]?\d{1,4}[-.\s]?\d{1,4}[-.\s]?\d{1,9}` (e.g. `039 2456789` and `+39 340 123 4567` pass; `""` fails) |
  | `email` | `@Email`, 1–50 characters |

* Response 200: `CustomerRepr` (`customer: false`, `emailConfirmed: false`).
* Statuses:
  * 200 for a new customer.
  * 200 when (`email`, `ragioneSociale`) already exists in state NEW or DECLINED. No new
    row is created, and the verification e-mail is re-sent with the **same** code.
  * **500** when that pair is PENDING (SoggettoService.java:109).
  * **409** `{"reason": "E' già stata effettuata una registrazione con questi dati. Si prega di riprovare con dati diversi.", …}`
    when it is REGISTERED (SoggettoService.java:110; ErrorEnum.java:37).
  * 400 on validation.
  * 500 if SES fails. The transaction rolls back, so no customer row is kept (inferred).
* The same e-mail with a different `ragioneSociale` creates a second row
  (SoggettoService.java:102-104), which then breaks B1, B5 and C1 for that e-mail (inferred).
* Side effects:
  * a customer row in state NEW with `codiceVerifica` = a random UUID (SoggettoService.java:65-66);
  * e-mail "Sanchez Logistica - Conferma indirizzo email" to the customer, with link
    `https://{app.email.origin}/mono/email-confirm?email={e-mail, not URL-encoded}&verificationCode={UUID}`
    (CustomerService.java:51-53; J/business/util/EmailReqFactory.java:25-41;
    J/business/util/LinkUtil.java:32-35). See §1.5.

**B3 · `POST /mono/api/customer/pending` (EP-04) and B4 · `POST /mono/api/customer/registered` (EP-05), ADMIN**
* Code: CustomerController.java:41-53 calls `SoggettoService.getAllByState` with PENDING or
  REGISTERED (SoggettoService.java:123-127).
* There is no request body: whatever is sent, including `{}`, is ignored.
* Response 200: an array of `CustomerRepr`. Pending items always have
  `emailConfirmed: true` and `customer: false`; registered items have both true. There is
  no time window and no `ORDER BY` (SoggettoRepository.java:19); the order is the database's
  (inferred: ascending id).
* Statuses: 200; **500 without an ADMIN token** (§2.9).

**B5 · `POST /mono/cms/customer/confirm` (EP-06), ADMIN**
* Code: CmsController.java:51-56 calls `CustomerService.confirm` (@Transactional,
  CustomerService.java:57-62), which calls `SoggettoService.confirm`
  (SoggettoService.java:117-121).
* Request `EmailReq`.
* Response 200: `CustomerRepr` (`customer: true`, `emailConfirmed: true`).
* Statuses:
  * 200.
  * 400: bad e-mail.
  * **500** for an unknown e-mail (SoggettoService.java:38-40).
  * **500** when the customer is not PENDING (SoggettoService.java:119, `INVALID_ENUM_500`).
    So approving a customer whose e-mail is unverified (NEW) is refused.
* Side effects: state becomes REGISTERED. E-mail "Sanchez Logistica - Conferma nuovo cliente"
  to the customer, with a link to `https://ordini.logisticasanchez.com/`
  (CustomerService.java:60; EmailReqFactory.java:44-58; LinkUtil.java:24-26).

```json
{ "email": "ufficio@mobilificiorossi.example" }
```

**B6 · `POST /mono/cms/customer/decline` (EP-07), ADMIN**
* Code: CmsController.java:65-70 calls `CustomerService.decline` (@Transactional,
  CustomerService.java:70-77).
* Request `EmailReq`.
* Response 200 with an empty body.
* Statuses: 200; 400; 500 for an unknown e-mail (SoggettoService.java:38-40).
* Side effects:
  * The state becomes **NEW** from any state, REGISTERED included: there is no check
    (CustomerService.java:73-75).
  * The row and its `codiceVerifica` are kept, so the customer can click the original link
    again and become PENDING (inferred from SoggettoService.java:53-61).
  * E-mail "Sanchez Logistica - Aggiornamento indirizzo email", built from the `error`
    template with the line "Richiesta: Indirizzo email e/o dati cliente rifiutati dall'amministratore"
    (EmailReqFactory.java:60-72; J/business/model/enumeration/NotificationEnum.java:17).

#### C. Orders

**C1 · `POST /mono/api/order/register` (EP-08), public**
* Code: OrderController.java:49-53 calls `OrderService.register` (@Transactional,
  OrderService.java:53-112), which calls `SpedizioneService.register`/`create`
  (J/business/service/crud/SpedizioneService.java:75-88, 132-143).
* Request `CreateOrderReq` (J/api/req/CreateOrderReq.java:22-39):

  | Path | Validation |
  |---|---|
  | `clienteEmail` | `@NotBlank @Email`, 1–50 characters |
  | `destinatario` | `@NotNull @Valid` `CreateDestinatarioReq` (J/api/req/CreateDestinatarioReq.java:20-46) |
  | `destinatario.ragioneSociale`, `.indirizzo`, `.provincia`, `.citta` | `@NotBlank`, 1–50 characters |
  | `destinatario.cap` | `@NotBlank`, 1–9 characters |
  | `destinatario.telefono` | optional; when present, 1–20 characters and the phone pattern (`""` gives 400) |
  | `destinatario.email` | optional; `@Email`, 1–50 characters. Stored as the order's `email`. The column is NOT NULL (J/persistence/model/SpedizioneEntity.java:30-31), so a missing value would fail with 500 (inferred). |
  | `dettaglioColli` | `@NotNull @Valid` (J/api/req/OrderDettaglioColliReq.java:19-23) |
  | `dettaglioColli.colli` | optional integer ≥ 0 |
  | `dettaglioColli.palletList[]` | `@NotNull` |
  | `….altezza`, `….peso`, `….larghezza`, `….lunghezza` | `@NotNull @Positive` numbers (J/api/req/OrderPalletReq.java:16-37). **`0` gives 400.** |
  | `….quantity` | `@NotNull @Positive` integer |
  | `….type` | `@NotNull` integer; 0 = PALLET, 1 = EPAL; any other value gives 500 (J/business/model/enumeration/PalletTypeEnum.java:36-43) |
  | `codiceDocumentoTrasporto` | null or ≥ 1 character (`""` gives 400) |
  | `servizioAccessorio` | integer, the ordinal of the enumeration in H1. **`null` gives 500** (unboxed into `fromId(int)`, OrderService.java:65), and so does a value outside 0–5. |
  | `note` | ≤ 255 characters |

  Numeric strings such as `"150"` or `"3"` are accepted (§1.1).
* Response 200: the created `Spedizione` with `stato: "PENDING"`, `tracking: "Non gestito"`
  and `codice` = `"TEMP"` + UUID v4 (40 characters) (SpedizioneService.java:82-84, 136;
  J/business/util/TextCodeFactory.java:8-10).
* Statuses:
  * 200.
  * 400 on validation.
  * **500 for an unknown `clienteEmail`** (SoggettoService.java:38-40).
  * **500 when the customer is not REGISTERED** (SpedizioneService.java:78-79).
  * 500 for an invalid enum id or a null `servizioAccessorio`.
  * 500 if SES fails (rollback, inferred).
* Side effects:
  * one order row, one `dettaglioColli` row with computed totals, and one pallet row per
    unit (OrderService.java:71-108);
  * **one e-mail, to the office only**: "Sanchez Logistica - Nuova richiesta di spedizione in attesa di conferma"
    (OrderService.java:110; EmailReqFactory.java:149-163). The customer gets no e-mail at
    submission.

The chapter 02 sample (two lines, service `"3"`) is valid and yields `pallet: 3`, `epal: 2`,
`peso: 876.0`, `volume: 3.0` and `servizioAccessorio: "Segnalazione merce lunga"`.

**C2 · `POST /mono/api/order/pending` (EP-09), public (no protection), and C3 · `POST /mono/api/order/registered` (EP-10), ADMIN**
* Code: OrderController.java:55-66 calls `SpedizioneService.getAllByState`
  (SpedizioneService.java:177-181). That uses the repository default
  `findAllByStatoAndCreateTmsBetween(stato)`, which restricts to **orders created in the last
  6 months** (J/persistence/repository/SpedizioneRepository.java:39-45).
* No request body.
* Response 200: an array of `Spedizione` (PENDING or REGISTERED). No `ORDER BY` (inferred:
  ascending id).
* Statuses:
  * C2: 200 with or without a token.
  * C3: 200, or **500 without an ADMIN token**.

**C4 · `POST /mono/api/order/state` (not in chapter 02), public**
* Code: OrderController.java:41-47 calls `SpedizioneService.state(Long)`
  (SpedizioneService.java:126-130).
* Request `OrderReq`: `id`, `@NotNull @Positive` (J/api/req/OrderReq.java:16-18).
* Response 200: `{"status": "<NEW|PENDING|REGISTERED|DECLINED>"}`. An unknown id gives
  `"NEW"`.

**C5 · `POST /mono/api/order/active` and C5b · `POST /mono/api/order/delivered` (not in chapter 02), public (no protection)**
* Code: OrderController.java:68-78 calls `SpedizioneService.getActiveOrders` /
  `getDeliveredOrders` (SpedizioneService.java:268-292).
* Request `OrderFiltersReq`: `email` is **optional** (`@Email @Size(min=1)`,
  J/api/req/OrderFiltersReq.java:16-18).
* Response 200: an array of `Spedizione`, REGISTERED and created in the last 6 months.
  `active` returns those not yet CONSEGNATO; `delivered` returns the CONSEGNATO ones.
  * With an `email`: that customer's orders.
  * **Without one (`{}`): every customer's orders** (SpedizioneService.java:275, 288).
* Statuses: 200; 404 for an unknown e-mail (`orElseThrow()`, SpedizioneService.java:270, 283;
  log: 3 × 404); 400 for `"email": ""`.
* Caller: unknown. The logs show 19 and 14 calls; no repository frontend makes them.

**C6 · `POST /mono/cms/order/confirm` (EP-11), ADMIN**
* Code: CmsController.java:58-63 calls `OrderService.confirm` (@Transactional,
  OrderService.java:114-121), which calls `SpedizioneService.confirm`
  (SpedizioneService.java:145-175).
* Request `OrderConfirmationReq` (J/api/req/OrderConfirmationReq.java:18-22):
  * `id`: `@NotNull @Positive`;
  * `req`: `PatchSpedizioneReq` (J/api/req/crud/PatchSpedizioneReq.java:19-45), optional.
  `req` fields:

  | Field | Type and validation | Used at approval? |
  |---|---|---|
  | `codice` | string | **required** (`requireNonNull`, SpedizioneService.java:152) |
  | `importo` | number, `@Positive` | **required** (:153) |
  | `tracking` | integer | **required** (:154) but **overridden to 1 (PRESA)** (:170) |
  | `importoContrassegno`, `importoAssicurato` | numbers, `@Positive` | optional; `importoContrassegno` is overwritten with `importoAssicurato` (:166-167, a bug) |
  | `codiceDocumentoTrasporto` | `@Size(min=1)` | optional |
  | `servizioAccessorio`, `stato` | integers | optional; `stato` is overridden to REGISTERED (:169) |
  | `note` | ≤ 255 characters | optional |
  | `updateConfirmationTs`, `updateShippingTs`, `updateDeliveryTs` | booleans | forced to true, false, false (:171-173) |

* Response 200: the updated `Spedizione` (`stato: "REGISTERED"`, `tracking: "Presa in carico"`,
  `confirmationTs` set).
* Statuses:
  * 200.
  * 400 on validation (e.g. `importo: 0`).
  * **404** for an unknown `id` (:148).
  * **409** `{"reason": "E' già stata effettuata una registrazione con questo codice. Si prega di riprovare con un nuovo codice.", …}`
    when `codice` already exists (:163; ErrorEnum.java:38).
  * **500** when the order is not PENDING (:164).
  * **500** when `codice`, `importo` or `tracking` is missing.
  * **500** for `"180,50"`, which fails JSON parsing.
* With `req` absent the order is only promoted to REGISTERED, keeping its TEMP code and
  tracking (:150).
* Side effects: e-mail "Sanchez Logistica - Conferma ordine N. {codice}" to the customer's
  registered e-mail, with the order detail (OrderService.java:118-119;
  EmailReqFactory.java:74-88).
* Contract history: approval required `importoAssicurato` until 2024-07-17
  (ls@b10d42b:J/business/service/crud/SpedizioneService.java:157) and `importo` from
  2024-07-18 (ls@d7b2c56:…/SpedizioneService.java:157).

```json
{ "id": 118, "req": { "codice": "LS-2024-0118", "importo": 180.5, "tracking": 1 } }
```

**C7 · `POST /mono/cms/order/decline` (EP-12), ADMIN**
* Code: CmsController.java:72-77 calls `OrderService.decline` (@Transactional,
  OrderService.java:135-152).
* Request `OrderDeclinationReq` (J/api/req/OrderDeclinationReq.java:15-22):
  * `email`: `@NotEmpty @Email`, 1–255 characters;
  * `id`: `@NotNull @Positive`.
* Response 200 with an empty body.
* Statuses: 200; 400; 500 when `email` is no known customer (:140); 404 for an unknown `id`
  (SpedizioneService.java:59-61).
* Neither the order's owner nor its state is checked: any order, REGISTERED included, can
  be declined (inferred from :140-146).
* Side effects:
  * `stato` becomes **NEW** and `tracking` becomes **NON_GESTITO**; the TEMP code is kept and
    the order is not deleted (:142-146). It disappears from both CMS lists. Until
    2024-07-21 decline set DECLINED (ls@ae74561:J/business/service/OrderService.java:140).
  * E-mail "Sanchez Logistica - Aggiornamento ordine rifiutato" to the **e-mail given in
    the request**, from the `error` template, showing the order **id** as "codice" and
    "Richiesta: Ordine invalido rifiutato dall'amministratore" (:147-151;
    EmailReqFactory.java:90-105).

**C8 · `POST /mono/cms/order?email=…` (not in chapter 02), ADMIN**
* Code: CmsController.java:139-144 calls `SpedizioneService.customerOrders`
  (SpedizioneService.java:261-266).
* Request: query parameter `email`, `@NotEmpty`. Any body is ignored.
* Response 200: **every** order of that customer, any state, **no time window**.
* Statuses: 200; 404 for an unknown e-mail; 400 for an empty `email`; 500 when the
  parameter is missing.

**C9 · `POST /mono/cms/order/update` (not in chapter 02), ADMIN**
* Code: CmsController.java:146-151 calls `OrderService.updateOrder` (OrderService.java:205-211).
* Request `OrderConfirmationReq`. Any `PatchSpedizioneReq` field is applied as given,
  including `stato`/`tracking` ids and the three timestamp flags
  (J/business/factory/SpedizioneFactory.java:83-109).
* Response 200: `Spedizione`.
* Statuses:
  * 200.
  * 409 when the new `codice` exists. Re-sending the order's own code also gives 409,
    inferred from OrderService.java:208-209.
  * 404 for an unknown id.
  * 500 for invalid ids.
* No transition rule and no e-mail. Caller unknown (log: 13 calls).

#### D. Tracking

**D1 · `POST /mono/api/order/trace/state` (EP-13), public**
* Code: OrderController.java:33-39 calls `SpedizioneService.trackingState`
  (SpedizioneService.java:116-119), which runs `findTrackingByCodice`
  (SpedizioneRepository.java:30-31).
* Request `OrderDetailReq`: `codice`, `@NotEmpty @Size(min=1)` (J/api/req/OrderDetailReq.java:16-18).
* Response 200 `StatusRepr`, where `status` is the **label** of the order's tracking state
  (OrderController.java:36-38):
  `"Non gestito"`, `"Presa in carico"`, `"Transito"`, `"Terminal destinatario"`,
  `"Assegnato"` or `"Consegnato"`.
* Unknown codes: **200 `{"status": "Non gestito"}`** (`orElse(NON_GESTITO)`,
  SpedizioneService.java:117-118). Since 2024-04-22 this has been true of every build
  (ls@540303d:J/business/service/crud/SpedizioneService.java:121-123). The same answer
  comes for:
  * a pending order's TEMP code (its tracking is NON_GESTITO);
  * a declined order;
  * a code whose tracking is NULL.
* Statuses:
  * 200.
  * **400** for `{"codice": ""}` or `{}`.
  * 500 for malformed JSON or, on builds from 2024-07-27, an extra JSON field (inferred).
* Matching is exact SQL equality. Case and trailing spaces follow the collation (inferred
  case-insensitive; leading spaces never match).
* History: from 2024-04-22 to 2024-05-05 the endpoint returned the enum object
  (`{"label": "…"}`) (ls@540303d:J/api/controller/OrderController.java:32-34;
  ls@540303d:J/business/model/enumeration/state/TrackingState.java:10). `{"status": label}`
  dates from 2024-05-06 (ls@40891b1).

```json
{ "codice": "LS-2024-0118" }
```
```json
{ "status": "Presa in carico" }
```

**D2 · `POST /mono/cms/order/trace` (EP-14), ADMIN**
* Code: CmsController.java:111-123 calls `OrderService.trace` and
  `SpedizioneService.trace` (SpedizioneService.java:236-259).
* Request `TraceReq` (J/api/req/TraceReq.java:18-23):
  * `codice`: `@NotEmpty`;
  * `tracking`: `@NotNull` integer 0–5. `"2"` is coerced to 2.
* Response 200: the updated **`Spedizione`** (CU-11).
* Statuses:
  * 200.
  * 400.
  * **404** for an unknown code (:237).
  * **500** when the order is not REGISTERED (:239).
  * **500** for a tracking id outside 0–5 (J/business/model/enumeration/state/TrackingState.java:39-46).
  * 500 when SES fails. The new state is already saved, because the e-mail is sent after a
    non-transactional save (CmsController.java:113-121, inferred).
* Side effects:
  * The state is set; any transition is allowed, backwards included.
  * `TRANSITO` sets `shippingTs`. `CONSEGNATO` sets `deliveryTs`, and `shippingTs` if it is
    empty. Any other state sets `shippingTs` if empty (:245-256).
  * No history is kept.
  * On `CONSEGNATO` only, e-mail "Sanchez Logistica - Consegna effettuata per ordine N. {codice}"
    to the customer (CmsController.java:120-121; EmailReqFactory.java:133-147).

#### E. Notes

**E1 · `POST /mono/cms/notes` (EP-15), ADMIN**
* Code: CmsController.java:125-130 calls `OrderService.notes` (OrderService.java:170-179).
* Request `NotesReq`: `codice`, `@NotEmpty` (J/api/req/NotesReq.java:17-19).
* Response 200 `NotesRepr`. `notes` is the order's `note` column, the same field the
  customer filled in EP-08. **When the note is null, and also when the code is unknown, the
  server answers `"Nessuna nota presente"`** (:172-174).
* Statuses: 200; 400; 500 without an ADMIN token.

**E2 · `POST /mono/cms/notes/update` (EP-16), ADMIN**
* Code: CmsController.java:132-137 calls `OrderService.updateNotes` (OrderService.java:181-203).
* Request `UpdateNotesReq` (J/api/req/UpdateNotesReq.java:17-21):
  * `codice`: `@NotEmpty`;
  * `notes`: unconstrained here; the service validates ≤ 255 characters through
    `PatchSpedizioneReq` (J/api/req/crud/PatchSpedizioneReq.java:35-36).
* Response 200 `NotesRepr` with the stored text.
* Statuses:
  * 200.
  * 404 for an unknown code (SpedizioneService.java:63-65).
  * 500 when `notes` is null (`notes.equals`, OrderService.java:186).
  * 400 when it is longer than 255 characters.
* Side effect: **it overwrites the customer's own order note**. Nothing is written when the
  text is unchanged.

#### F. Detail

**F1 · `POST /mono/api/order/detail` (EP-17), ADMIN**
* Code: OrderController.java:80-85 calls `SpedizioneService.detail` (SpedizioneService.java:183-196).
* Request `OrderDetailReq`.
* Response 200 `OrderDetailRepr` with four keys (§1.3). When `tracking` is not PRESA and
  `importo` is null, `importo` falls back to `importoAssicurato` (:194). In the lists this
  fallback is dead code (SpedizioneFactory.java:29-38).
* Statuses: 200; 400; **404** for an unknown code (:184); 500 without an ADMIN token.

```json
{
  "spedizione": { "id": 118, "codice": "TEMP5f0c2d7e-3b1a-4c8e-9d2f-6a7b8c9d0e1f", "email": "ufficio@mobilificiorossi.example",
                  "ragioneSociale": "Ferramenta Bianchi S.n.c.", "indirizzo": "Via Roma 5", "provincia": "BG", "citta": "Bergamo",
                  "cap": "24121", "telefono": null, "importoContrassegno": null, "importoAssicurato": null, "importo": null,
                  "codiceDocumentoTrasporto": "DDT 245/2024", "servizioAccessorio": "Segnalazione merce lunga",
                  "note": "Consegna solo al mattino, chiamare il destinatario prima", "stato": "PENDING", "tracking": "Non gestito",
                  "confirmationTs": null, "shippingTs": null, "deliveryTs": null },
  "mittente":   { "id": 42, "ragioneSociale": "Mobilificio Rossi S.r.l.", "indirizzo": "Via Garibaldi 12", "provincia": "MB",
                  "citta": "Lissone", "cap": "20851", "telefono": "039 2456789", "email": "ufficio@mobilificiorossi.example",
                  "stato": "REGISTERED" },
  "dettaglioColli": { "id": 97, "colli": null, "pallet": 3, "epal": 2, "peso": 876.0, "volume": 3.0 },
  "palletList": [
    { "id": 301, "altezza": 150.0, "peso": 420.0, "larghezza": 80.0, "lunghezza": 120.0, "type": "EPAL" },
    { "id": 302, "altezza": 150.0, "peso": 420.0, "larghezza": 80.0, "lunghezza": 120.0, "type": "EPAL" },
    { "id": 303, "altezza": 40.0,  "peso": 12.0,  "larghezza": 30.0, "lunghezza": 30.0,  "type": "PALLET" },
    { "id": 304, "altezza": 40.0,  "peso": 12.0,  "larghezza": 30.0, "lunghezza": 30.0,  "type": "PALLET" },
    { "id": 305, "altezza": 40.0,  "peso": 12.0,  "larghezza": 30.0, "lunghezza": 30.0,  "type": "PALLET" }
  ]
}
```

#### G. Report

**G1 · `POST /mono/cms/order/report` (EP-18), public (no protection)**
* Code: CmsController.java:79-109. Its `@PreAuthorize` was removed on 2024-04-22
  (ls@db5e84c:J/api/controller/CmsController.java:77 had it; ls@540303d:…/CmsController.java:78
  does not).
* Request `ReportReq` (J/api/req/ReportReq.java:20-31):

  | Field | Validation / default |
  |---|---|
  | `email` | `@NotEmpty @Email`, 1–255 characters |
  | `from` | `@PastOrPresent`; null means `LocalDate.MIN` |
  | `to` | null means today |
  | `sendToCustomer` | Boolean; null means false |
  | `onlyDelivered` | Boolean, **no default**: null gives a NullPointerException, because it is unboxed into `boolean` when the link is built (CmsController.java:84, 100; LinkUtil.java:14, 19) |

* Response 200 with an empty body; no PDF is returned.
* Statuses:
  * 200.
  * 400 (e.g. `from` in the future).
  * **500 when `onlyDelivered` is absent**, as in the CMS body. True since 2024-08-25
    (ls@96a8b25), inferred.
  * 500 when SES fails.
  * The customer is not checked. An unknown e-mail still gets 200, and the links in the
    e-mail then answer 404.
* Side effects:
  * Always: e-mail "Sanchez Logistica - Report ordini" to the **office mailbox**
    (EmailReqFactory.java:165-189).
  * Only if `sendToCustomer` is true: the same e-mail to `email`, which may be any address
    (CmsController.java:104-106).
  * The e-mail carries two links, `https://{origin}/mono/report?email=…&from=…&to=…&onlyDelivered=…`
    and `https://{origin}/mono/download-report?…` (LinkUtil.java:14-21). Each is also wrapped
    in a `https://www.google.com/url?q=` "safe link" attribute (EmailReqFactory.java:111-112, 169-170).
  * No attachment is sent.

**G2 · `GET /mono/download-report` (not in chapter 02), public (no protection)**
* Code: WebController.java:39-54 calls `ReportService.generatePdf`
  (J/business/service/ReportService.java:33-43), which calls `SpedizioneService.reportList`
  (SpedizioneService.java:206-234).
* Query parameters:
  * `email`: required;
  * `from`: optional, `@PastOrPresent`;
  * `to`: optional;
  * `onlyDelivered`: default false.

  Dates are `YYYY-MM-DD`.
* Response 200: `application/pdf`, `Content-Disposition: attachment; filename="<local-part>-dd_MM_yyyy_dd_MM_yyyy.pdf"`
  (WebController.java:49-53; J/business/util/ReportUtil.java:113-126).
* Selection: the customer's REGISTERED orders whose **creation** instant lies in
  [`from` 00:00 UTC, `to`+1 day 00:00 UTC), so `to` is inclusive. With `onlyDelivered` only
  CONSEGNATO orders are kept (SpedizioneService.java:212-223).
* PDF content: a customer block (Ragione Sociale, Indirizzo, Provincia, Città, CAP, Email),
  then one row per order with columns "Codice", "Codice Tras.", "Dt/Sped.", "Dt/Cons.",
  "Servizio", "Destinazione", "Cliente", "Cap", "Città", "Colli", "Peso", "Volume",
  "Importo" (J/business/util/HorizontalPdfGenerator.java:79-175).
* Statuses: 200; 404 for an unknown e-mail; 400 for a future `from`; 500 for a malformed
  date (log: 12 × 200, 3 × 400, 2 × 500).

**G3 · `GET /mono/report` (not in chapter 02), public**
* Code: WebController.java:56-68.
* Response 200 `text/html`: the page "Sanchez Logistica - Report ordini PDF" with a "Scarica"
  link to G2 (R/templates/report.html:60-69). The `safeLink` model attribute concatenates the
  `LinkUtil` object itself (WebController.java:65), a harmless bug.

#### H. Enumerations (public, `GET`, no parameters)

Every list is built from `Enum.values()` with `id` = ordinal (EnumController.java:25-67).
Before 2024-05-06, `id` was ordinal + 1 (ls@540303d:J/api/controller/EnumController.java:29, 40).

**H1 · `/mono/api/enumeration/servizioAccessorio` (EP-20)**, from
J/business/model/enumeration/ServizioAccessorioEnum.java:16-21:
```json
[
  { "id": 0, "name": "NESSUN_SERVIZIO",  "label": "Nessun servizio accessorio" },
  { "id": 1, "name": "PRENOTAZIONE_TEL", "label": "Prenotazione telefonica" },
  { "id": 2, "name": "SPONDA_IDR",       "label": "Sponda idraulica" },
  { "id": 3, "name": "MERCE_LUNGA",      "label": "Segnalazione merce lunga" },
  { "id": 4, "name": "FACCHINAGGIO",     "label": "Facchinaggio" },
  { "id": 5, "name": "GDO",              "label": "GDO" }
]
```
The same six labels exist in every version since February 2024, e.g.
ls@540303d:…/ServizioAccessorioEnum.java:13-18. Logged order lists contain
"Nessun servizio accessorio" (300 values), "Prenotazione telefonica" (8) and
"Segnalazione merce lunga" (3) (log).

**H2 · `/mono/api/enumeration/statoTracking` (EP-19)**, from
J/business/model/enumeration/state/TrackingState.java:15-20. It is exactly chapter 02 §9.1:
ids 0–5 as numbers, names `NON_GESTITO`, `PRESA`, `TRANSITO`, `TERMINAL_DEST`, `ASSEGNATO`,
`CONSEGNATO`, and labels "Non gestito", "Presa in carico", "Transito",
"Terminal destinatario", "Assegnato", "Consegnato".

**H3 · `/mono/api/enumeration/tipoPallet`** (not in chapter 02):
`[{"id":0,"name":"PALLET","label":"PALLET"},{"id":1,"name":"EPAL","label":"EPAL"}]`
(J/business/model/enumeration/PalletTypeEnum.java:16-17).

**H4 · `/mono/api/enumeration/statoConferma`** (not in chapter 02):
`[{"id":0,"name":"NEW","label":"NEW"},{"id":1,"name":"PENDING","label":"PENDING"},{"id":2,"name":"REGISTERED","label":"REGISTERED"},{"id":3,"name":"DECLINED","label":"DECLINED"}]`
(ConfirmationState.java:15-18).

#### I. E-mail confirmation, free e-mail, static pages

**I1 · `GET /mono/email-confirm?email=…&verificationCode=…` (the CU-24 landing), public**
* Code: WebController.java:34-37 calls `CustomerService.emailConfirm`
  (CustomerService.java:38-41), which calls `SoggettoService.emailConfirm`
  (SoggettoService.java:53-61).
* It returns HTML with **status 200 in both cases**:
  * success, view `email-success`: "Abbiamo verificato il tuo indirizzo mail" /
    "Rimani in attesa della email di conferma diretta da parte del nostro centro logistico."
    (R/templates/email-success.html:39-42);
  * failure, view `error`: "Abbiamo riscontrato un errore nella tua richiesta."
    (R/templates/error.html:46-50).
* Success needs the customer to be NEW and the code to match exactly. Then NEW becomes
  PENDING. The code never expires and the link can be used again after a decline.
* Side effect on **every** call, before the code is checked: e-mail
  "Sanchez Logistica - Nuovo cliente in attesa di conferma" to the office mailbox, with the
  `email` parameter in its body (CustomerService.java:39).
* A missing parameter, or a `POST`, gives 500 (log: 3 × 500 for `POST`).
* The link carries the raw e-mail. A `+` in an address arrives as a space, so such customers
  cannot verify (inferred).

**I2 · `POST /mono/sendEmail` (not in chapter 02), ADMIN**
* Code: CmsController.java:44-49 calls `AwsEmailService.send` (AwsEmailService.java:36-50).
* Request `EmailNotificationReq` (J/api/req/EmailNotificationReq.java:16-30): `fromAddress`,
  `toAddress`, `subject`, `content`, each `@NotEmpty`, 1–255 characters.
* Response 200 with an empty body.
* Effect: a plain-text e-mail through SES with any `From` the SES identity allows.

**I3 · static and view pages**: see the last rows of §1.2. In
R/templates/email-confirmation.html:56 the logo image is commented out, so old e-mails do
not load `/mono/img/logistica-sanchez-logo.jpg`.

#### J. Admin utilities (ADMIN, hard deletes, no frontend; examples only in Postman)

All four look the customer up by e-mail and answer 500 when it is unknown
(J/business/service/AdminToolsService.java:47, 64, 87, 116). Pallet, `dettaglioColli` and
order rows are deleted physically.

| Endpoint | Request | Response 200 | Notes |
|---|---|---|---|
| `POST /mono/utils/order/delete` | `DeleteOrderReq` (J/api/req/DeleteOrderReq.java:18-24): `email` (`@NotEmpty @Email`), `id` (`@NotNull`) | `["<codice>"]` | 500 when the id is not one of that customer's orders (AdminToolsService.java:84-109) |
| `POST /mono/utils/order/deleteAll` | `DeleteAllOrdersReq` (J/api/req/DeleteAllOrdersReq.java:20-25): `email`, optional `state` (label, e.g. `"PENDING"`) | codes deleted | AdminToolsService.java:112-135 |
| `POST /mono/utils/order/deleteBeforeDate` | `DeleteOrdersBeforeReq` (J/api/req/DeleteOrdersBeforeReq.java:20-26): `email`, `before` (`@PastOrPresent`) | codes deleted | only delivered orders confirmed before `before`; an order without `confirmationTs` gives 500 (AdminToolsService.java:63-81) |
| `POST /mono/utils/customer/delete` | `EmailReq` | `CustomerRepr` of the deleted customer | deletes the customer and all its orders (AdminToolsService.java:46-60) |

### 1.5 E-mails sent by the backend

All automatic e-mails are HTML from Thymeleaf templates, which escape output (no `th:utext`
in R/templates/; grep). The `From` is `app.email.author`. The office mailbox is a hard-coded
consumer Gmail address (J/business/util/EmailReqFactory.java:159, 185; not reproduced; see
Q-43).

| Trigger | Recipient | Subject (verbatim) | Template | Inside the DB transaction? | Source |
|---|---|---|---|---|---|
| B2 register (new, or re-sent for NEW/DECLINED) | the customer's e-mail | "Sanchez Logistica - Conferma indirizzo email" | `email-confirmation` (verification link) | yes | CustomerService.java:47-55; EmailReqFactory.java:25-41 |
| I1 any call to `/mono/email-confirm` | office | "Sanchez Logistica - Nuovo cliente in attesa di conferma" | `admin-notification` | no transaction | CustomerService.java:39; EmailReqFactory.java:149-163; AdminNotificationEnum.java:15 |
| B5 customer confirm | customer | "Sanchez Logistica - Conferma nuovo cliente" | `customer-success` (link to `https://ordini.logisticasanchez.com/`) | yes | CustomerService.java:57-62; EmailReqFactory.java:44-58 |
| B6 customer decline | customer | "Sanchez Logistica - Aggiornamento indirizzo email" | `error` + "Richiesta: Indirizzo email e/o dati cliente rifiutati dall'amministratore" | yes | CustomerService.java:70-77; EmailReqFactory.java:60-72 |
| C1 order register | office | "Sanchez Logistica - Nuova richiesta di spedizione in attesa di conferma" | `admin-notification` | yes | OrderService.java:110; AdminNotificationEnum.java:16 |
| C6 order confirm | customer (registered e-mail) | "Sanchez Logistica - Conferma ordine N. {codice}" | `order-detail` | yes | OrderService.java:114-121; EmailReqFactory.java:74-88 |
| C7 order decline | the e-mail in the request | "Sanchez Logistica - Aggiornamento ordine rifiutato" | `error` + "Richiesta: Ordine invalido rifiutato dall'amministratore" (shows the order id) | yes | OrderService.java:147-151; EmailReqFactory.java:90-105 |
| D2 tracking set to CONSEGNATO | customer | "Sanchez Logistica - Consegna effettuata per ordine N. {codice}" | `tracking-state` | no (state already saved) | CmsController.java:120-121; EmailReqFactory.java:133-147 |
| G1 report | office, always | "Sanchez Logistica - Report ordini" | `email-report` (links to G3/G2) | no DB write | CmsController.java:106; EmailReqFactory.java:165-189 |
| G1 with `sendToCustomer: true` | `email` (any address) | "Sanchez Logistica - Report ordini" | `email-report` | no DB write | CmsController.java:104-105; EmailReqFactory.java:107-131 |
| I2 `sendEmail` | any | any (plain text) | — | — | AwsEmailService.java:36-50 |

Link host: `app.email.origin`. The defaults are:
* `localhost:9050` in the base config (R/application.yml:79);
* the UAT Elastic Beanstalk host in `dev` (R/application-dev.yml:79);
* **`logisticasanchez.com`, the apex, in the Docker deployment** (docker-compose.yml:83).

The committed `.env` sets `EMAIL_SENDER_ORIGIN` (ls@migration:.env:21), which no Docker
setting reads. So production links probably point to `https://logisticasanchez.com/mono/…`
unless the server sets `EMAIL_ORIGIN` (inferred). The links used `http://` until 2025-11-16
(ls@285d2b1:J/business/util/LinkUtil.java:15-33; ls@ca1b0d0).

## 2. Security model

### 2.1 Components and filter chain

| Element | Configuration | Source |
|---|---|---|
| Security chain | `authorizeHttpRequests`: `/mono/cms/**` → `hasRole("ADMIN")`, `anyRequest()` → `permitAll()`; CORS from a bean; CSRF disabled; session `STATELESS`; DAO authentication provider; `JwtAuthFilter` added after `UsernamePasswordAuthenticationFilter` | ls@migration:J/configuration/security/WebSecurityConfig.java:37-49 |
| Ignored requests | requests whose URI does not contain `/mono` bypass Spring Security. Every request reaching this app carries the context path, so in practice nothing is ignored (inferred). | WebSecurityConfig.java:51-55 |
| Method security | `@EnableMethodSecurity`; `@PreAuthorize("hasRole('ADMIN')")` on 18 methods, the only `@PreAuthorize` uses in the code (§2.7) | WebSecurityConfig.java:27; MonoConstants.java:6; `grep -rn @PreAuthorize src/main/java` |
| JWT filter | `OncePerRequestFilter`. It reads `Authorization`; with no header, a non-`Bearer ` header, or the URI `/mono/auth/login`, it passes the request through untouched. Otherwise it parses the token, loads the user from the database and sets the authentication. Any exception gives 401 with `e.getMessage()` as the body. | J/configuration/security/JwtAuthFilter.java:30-62 |
| Body logging | `RequestResponseLoggingFilter`, a `@Component` filter auto-registered for all paths, logs every request and response body at INFO (§2.13) | J/configuration/RequestResponseLoggingFilter.java:15-45 |

### 2.2 Accounts, passwords and roles

* **Users table `users`**: `id`, `username` (unique, NOT NULL), `psw`, `role`, `policy`
  (ls@migration:J/persistence/model/security/UserEntity.java:11-25). `role` is stored as
  `"ADMIN"` or `"CUSTOMER"` (J/business/model/enumeration/UserRoleEnum.java:15-16;
  J/persistence/model/converter/UserRoleEnumConverter.java:12-19).
* **No endpoint creates, lists or changes users.** Staff accounts are managed directly in
  the database (inferred). How many exist is unknown (Q-17).
* **Passwords**: `BCryptPasswordEncoder` with default strength 10, used by
  `DaoAuthenticationProvider` (J/configuration/security/DatabaseAuthenticationConfig.java:22-29, 46-49).
  So `users.psw` must hold BCrypt hashes. The test fixture inserts 13-character plain
  values instead (T/resources/init.sql:1-7, values not reproduced), which cannot match
  BCrypt (inferred).
* **Granted authority**: `"ROLE_" + role.name()`. Account-status flags are always true.
  There is no lockout, no expiry and no disabled state
  (J/configuration/security/UserPrincipalDetails.java:21-54).
* `policy` is copied into the token claim `operations` but is **never used for
  authorisation**. Roles are re-read from the database on every request
  (JwtAuthFilter.java:51; DatabaseAuthenticationConfig.java:31-44).
* Role `CUSTOMER` exists and can log in, but customers (table `SOGGETTI`) are not users. A
  CUSTOMER token can call nothing that `ADMIN` endpoints guard.

### 2.3 Login flow

1. The `username` and `password` go to `AuthenticationManager.authenticate`
   (AuthenticationService.java:37-39).
2. On failure: 401 "Username o password errate." (:40-42). An unknown username throws
   `NoSuchElementException` in the user service (DatabaseAuthenticationConfig.java:34).
   Spring wraps it as an internal authentication error, so the dummy BCrypt check that hides
   timing differences is skipped: unknown usernames answer faster (inferred).
3. On success, the server finds or creates the user's `user_session` row, sets its expiry
   to now + `jwt-exp-refresh-token`, and returns the token pair (:44-53).

### 2.4 Access token

| Property | Value | Source |
|---|---|---|
| Format | JWS, `alg` HS256 | J/configuration/security/JwtService.java:30-38 |
| Claims | `sub` = username; `operations` = `[policy]`; `iat`; `exp`. No role claim. | JwtService.java:22-27, 33-35; Postman examples decode to exactly these claims (ls@migration:api/sanchez-logistica.postman_collection.json:146, 236) |
| Lifetime | `app.jwt.jwt-exp-token` seconds, **default 21600 (6 h)** in every profile, `docker-compose.yml` and `.env`. In February 2024 the Postman examples show 43200 (12 h). | R/application.yml:72; docker-compose.yml:74; .env:12; postman:146 |
| Signing key | `Keys.hmacShaKeyFor(Base64.encode(jwtKey.getBytes()))`: the configured string is Base64-encoded, and the encoded ASCII is used as the HMAC key. Its strength equals the string's entropy. A string shorter than 24 characters makes HS256 throw a weak-key error at first use (inferred). | JwtService.java:40-42 |
| Validation | the signature and `exp` are checked by the parser; the `sub` must equal a loaded user. `isExpired` is redundant, because the parser already rejects expired tokens. | JwtService.java:53-68; JwtAuthFilter.java:48-56 |
| Revocation | none. The token is valid until `exp`, even after logout (which does not exist) or a password change. Deleting the user makes later requests fail with 401 "No value present". | JwtAuthFilter.java:51; DatabaseAuthenticationConfig.java:34 |

### 2.5 Refresh token and the sessions table

* Table `user_session` (J/persistence/model/security/UserSessionEntity.java:13-21):
  * `session_id`: the PK, a UUID; **this value is the refresh token**;
  * `user_id`;
  * `session_expiration_date`: a `LocalDateTime` in server local time; the container runs
    in Europe/Rome (Dockerfile:34-36).
* There is **one row per user** by convention. Login reuses the existing row even when it
  has expired, re-extends it and returns the same UUID (AuthenticationService.java:45-53).
  So a user's refresh token never changes until a refresh attempt after expiry deletes it
  (:60-64).
* The refresh window is `jwt-exp-refresh-token`, **default 7200 s (2 h)**, sliding
  (R/application.yml:73; AuthenticationService.java:74-77). The refresh window is shorter
  than the access token's lifetime (6 h), so refresh adds nothing in practice (inferred:
  misconfiguration).
* There is no logout, no revocation and no "who am I" endpoint (§1.2).

### 2.6 Master login

None. No branch has a master login endpoint. The only trace is the unused DTO
`LoginMasterRequestDto(@NotBlank Long userId)`
(ls@migration:J/api/req/security/LoginMasterRequestDto.java:5), added on 2024-02-29
(ls@6187a37) and never referenced. `@NotBlank` on a `Long` would fail at validation time
anyway. `git grep -i master` on every branch finds nothing else.

### 2.7 Authorisation as enforced

**The URL rule never matches.** `requestMatchers("/mono/cms/**")`
(WebSecurityConfig.java:41; J/configuration/constant/PatternConstants.java:6) goes through
Spring Security 6.2. With Spring MVC present, it compares the pattern with the path
**inside** the servlet context, e.g. `/cms/order/confirm`. The context path `/mono`
(R/application.yml:4) is not part of that path, so the pattern would only match
`/mono/mono/cms/…` (inferred from framework behaviour).

Evidence that the rule is dead (log): four responses with status 500 and body
`{"reason":"Qualcosa è andato storto. Si prega di riprovare oppure contattare il centro logistico.","detail":"Access Denied"}`
on `POST /mono/cms/customer/confirm`. A working URL rule would have stopped those calls in
the filter chain, with 403 and no `ErrorRepr`. Instead they reached the controller, where
`@PreAuthorize` denied them and the catch-all handler answered 500.

**Effective rule per path:**

| Path | Rule | Evidence |
|---|---|---|
| `/mono/auth/login`, `/mono/auth/refresh-token` | public | AuthController.java:21-29 |
| `/mono/api/customer/state`, `/register` | public | CustomerController.java:27-39 |
| `/mono/api/customer/pending`, `/registered` | **ADMIN** | CustomerController.java:41, 48 |
| `/mono/api/order/trace/state`, `/state`, `/register` | public | OrderController.java:33-53 |
| `/mono/api/order/pending` | **public** (never annotated since it was created, ls@6187a37:J/api/controller/OrderController.java:37) | OrderController.java:55-59 |
| `/mono/api/order/registered`, `/detail` | **ADMIN** since 2024-03-26 (ls@ebcc438) | OrderController.java:61, 80 |
| `/mono/api/order/active`, `/delivered` | **public** since 2024-07-16 (ls@d7732eb) | OrderController.java:68-78 |
| `/mono/api/enumeration/*` | public | EnumController.java:25-67 |
| `/mono/cms/order/report` | **public** (the dead URL rule, and no annotation since 2024-04-22) | CmsController.java:79 |
| every other `/mono/cms/**` mapping | **ADMIN** | CmsController.java:51, 58, 65, 72, 111, 125, 132, 139, 146 |
| `/mono/sendEmail` | ADMIN | CmsController.java:44 |
| `/mono/utils/**` | ADMIN | AdminToolsController.java:31, 38, 45, 52 |
| `/mono/email-confirm`, `/mono/download-report`, `/mono/report`, static files, `/mono/error` | public | WebController.java:34-68 |

### 2.8 Answer to Q-21 / CMS-05 / CU-13

| Staff read | Protected? | What an anonymous caller gets |
|---|---|---|
| EP-04 `POST /mono/api/customer/pending` | yes, ADMIN | 500, no data |
| EP-05 `POST /mono/api/customer/registered` | yes, ADMIN | 500, no data |
| EP-09 `POST /mono/api/order/pending` | **no** | **200 with every pending order created in the last 6 months**: sender e-mail, recipient name, address, phone, DDT, amounts, the customer's note |
| EP-10 `POST /mono/api/order/registered` | yes, ADMIN | 500 |
| EP-17 `POST /mono/api/order/detail` | yes, ADMIN | 500 |
| EP-15 `POST /mono/cms/notes` | yes, ADMIN | 500 |
| (new) `POST /mono/api/order/active` / `delivered` with `{}` | **no** | **200 with every approved order of the last 6 months**, same fields |
| (new) `GET /mono/download-report?email=<customer>` | **no** | **a PDF of that customer's shipments** (recipients, places, weights, amounts) for any period |
| (new) `GET /mono/report?email=…` | **no** | an HTML page linking to the PDF |
| EP-18 `POST /mono/cms/order/report` | **no** | 200, and an e-mail to the office (and to any address with `sendToCustomer: true`) |

Consequences for the brief:
1. Q-21 is an incident if production runs any build since 2024-02-29: EP-09 has been open
   since its creation.
2. Probe P-04 should read **500 as "protected"** and any 2xx with a body as exposure. It
   should add `POST /mono/api/order/active` and `/delivered` with `{}`, recording status and
   size only. `download-report` can only be checked with the owner's own test customer.
3. Until the cut-over, the owner can close these paths in the live nginx with a `location`
   block that allows only the office IPs (`nginx/conf.d/app-https.conf:54` is the single
   `location /mono/`). The staff CMS uses EP-09 itself, so a plain block would break it.

The new app requires a staff session on every back-office read (ADM-01), as chapter 02
already decided.

### 2.9 Missing, expired or invalid token

| Situation | Public endpoint | ADMIN endpoint | Source |
|---|---|---|---|
| No `Authorization` header, or a header not starting with `Bearer ` | normal answer | **500** `ErrorRepr`: `reason` "Qualcosa è andato storto. Si prega di riprovare oppure contattare il centro logistico.", `detail` "No detail" (or "Access Denied" when details are shown) | JwtAuthFilter.java:33-36; ErrorController.java:30-33; (log) |
| Valid ADMIN token | normal | normal | |
| Valid CUSTOMER token | normal | 500, same as above | UserPrincipalDetails.java:21-24 |
| Expired token | **401**, body = raw jjwt message such as `JWT expired at …`, sent as `Content-Type: application/json` although the body is plain text | **401** | JwtAuthFilter.java:41-45 (message format inferred from jjwt) |
| Bad signature or malformed token | 401, raw message | 401 | same |
| Token of a deleted user | 401 `No value present` | 401 | DatabaseAuthenticationConfig.java:34 |
| Any token on `/mono/auth/login` | ignored | — | JwtAuthFilter.java:33 |

The 401 path wraps the whole downstream chain (`filterChain.doFilter` sits inside the
`try`, JwtAuthFilter.java:38-45). Any exception escaping later filters while a Bearer header
is present also becomes 401 with its message (inferred). Nothing in the CMS refreshes a
token, so after 6 h every CMS call answers 401 until staff log in again (inferred).

### 2.10 CORS

| Setting | Value | Source |
|---|---|---|
| Origins | `app.allowed-origins`, a list (environment `ALLOWED_ORIGINS`), exact match | WebSecurityConfig.java:64; J/configuration/property/AllowedOriginsProperties.java:10-14 |
| Methods | `GET, POST, PUT, DELETE, OPTIONS` | WebSecurityConfig.java:61 |
| Headers | `*`; the preflight echoes the requested headers (inferred) | :62 |
| Credentials | `Access-Control-Allow-Credentials: true` | :63 |
| Max age | 3600 s | :60 |
| Scope | `/**` | :66 |
| Disallowed origin | **403** with the plain body `Invalid CORS request`, for preflights and for actual requests carrying a foreign `Origin` (Spring default, inferred). Requests without `Origin`, such as curl, are not checked. | — |
| Allowed preflight | 200 with `Access-Control-Allow-Origin: <origin>`, the methods above, credentials true and max age 3600 (inferred) | — |

Committed origin lists (the server's real value is not in the repository):
* **Docker/VPS deployment** (the committed `.env`, `SPRING_PROFILE=prod`):
  `https://logisticasanchez.com`, `https://www.logisticasanchez.com`,
  `https://app.logisticasanchez.com` (ls@migration:.env:25, 31; docker-compose.yml:87).
  `docker compose` reads `.env` from the project directory, and the deploy script backs it
  up (scripts/deploy.sh:74). This `.env` holds placeholder secrets, so the server's own copy
  differs (inferred).
* **`dev` profile** (UAT on Elastic Beanstalk, inferred):
  `http://sanchez-logistica-cms.s3-website.eu-north-1.amazonaws.com`,
  `http://sanchez-logistica-fe.s3-website.eu-north-1.amazonaws.com`,
  `https://logisticasanchez.com`, `http://logisticasanchez.com`, plus three entries without a
  scheme, which match no browser origin (R/application-dev.yml:69).
* **Base default**: `localhost`, which matches nothing (R/application.yml:69).
* **Local**: `https://localhost`, `http://localhost`, `localhost`, `http://localhost:8000`
  (R/application.local.yml:69). That file is not loaded; see §2.14.
* **In no committed list**: `https://ordini.logisticasanchez.com` and
  `https://dashboard.logisticasanchez.com`, the portal and CMS URLs the backend links to
  itself (LinkUtil.java:24-30); the S3 tracking app; the Netlify site. If those frontends call
  `app.` cross-origin, the server's list must contain them (inferred). P-03 is still needed.
* History:
  * Until 2024-05-05 no origin was configured at all, so every cross-origin browser call was
    refused (ls@540303d:J/configuration/security/WebSecurityConfig.java:58-60 sets only
    methods and credentials; refusal inferred).
  * 2024-05-06 to 2024-05-07: the only origin was the hard-coded S3 portal
    `http://sanchez-logistica-fe.s3-website.eu-north-1.amazonaws.com` (ls@09c3dc5).
  * From 2024-05-08: the property (ls@0dc9a44).

### 2.11 CSRF, sessions, cookies, headers

* CSRF protection is disabled (WebSecurityConfig.java:44). Sessions are stateless (:45).
  No cookie is set or read anywhere (`grep` for `Cookie`/`HttpSession` finds nothing). That
  is acceptable for a bearer-token API. But `GET /mono/email-confirm` changes state and sends
  an e-mail, so link scanners can trigger it (inferred).
* Spring Security's default response headers stay on, e.g. `X-Content-Type-Options`,
  `X-Frame-Options: DENY` and `Cache-Control: no-store` (inferred: not disabled). On HTTPS,
  nginx adds `Strict-Transport-Security`, `X-Frame-Options: SAMEORIGIN`,
  `X-Content-Type-Options` and `X-XSS-Protection` (nginx/conf.d/app-https.conf:44-47).
* No `@CrossOrigin`, no `@Secured` and no `@RolesAllowed` annotations (grep).

### 2.12 Actuator and management

* `spring-boot-starter-actuator` is **not a dependency** (pom.xml:22-49), so **no actuator
  endpoint exists**. `/mono/actuator/**` falls through to the static handler and answers 500
  (inferred). `scripts/fix-actuator.sh` prints "The actuator is broken, but your app works!"
  (scripts/fix-actuator.sh:210), and the Docker health check that used it is commented out
  (Dockerfile:72-73).
* The configuration is nevertheless dangerous if the dependency is ever added:
  * The base config exposes `'*'` (R/application.yml:59-66), and in the Docker deployment
    the base config is the one in force (§2.14).
  * The `prod` template exposes `health,info,metrics,env,configprops` with
    `show-details: always` (R/application.prod.yml:79-93).
  * Every path is `permitAll`, so `env` and `configprops` would publish the secrets.

### 2.13 Logging of request and response bodies

* `RequestResponseLoggingFilter` is a `@Component`, which Spring Boot registers for `/*`. It
  logs the full request body and the full response body at INFO
  (J/configuration/RequestResponseLoggingFilter.java:15-45). That includes login passwords,
  JWTs, refresh tokens and customer personal data.
* A second registration of the same filter (J/configuration/LogConfig.java:11-19) uses the
  servlet pattern `/mono/**`. Servlet patterns do not support `**`, so that registration
  never fires (inferred).
* The log appender writes `./logs`, which `.gitignore` does not exclude
  (R/logback.xml:4, 15, 32-35; .gitignore). The repository contains **43 log lines with a
  non-empty `"password"` value and 18 lines with JWTs** (log; counted by regex over
  `logs/**/*.log`, values never printed).
* In addition, `DispatcherServlet` logs at DEBUG (R/logback.xml:38-39), and Hibernate SQL
  logging is on in the base config (R/application.yml:36-39).

### 2.14 Configuration, profiles and committed secrets

**Profiles in practice:**
* Docker runs profile `prod` (docker-compose.yml:54; Dockerfile:62).
* **`application.prod.yml` and `application.local.yml` are not loaded.** Spring Boot loads
  `application-{profile}.yml` with a hyphen. Both files use a dot; `application.local.yml`
  was renamed from `application-local.yml` on 2025-10-20 (ls@285d2b1).
* So production runs on:
  1. `R/application.yml` (the base config);
  2. a server-side `config/application-prod.yml` mounted into the container (not in git,
     content unknown) (docker-compose.yml:99);
  3. environment variables from compose and the server's `.env` (inferred).
* The `dev` profile file is loaded where `dev` is active, presumably on Elastic Beanstalk
  (inferred).

**Error details.** `app.error.hideDetail`, `HIDE_ERROR_MESSAGES`: the default is true in
base, dev and compose (R/application.yml:82; docker-compose.yml:90; .env:28), so `detail`
reads "No detail". It is false in the local and test profiles.

**Secrets.** Only key names and their status are given; no value is reproduced.

| Key | File | Status |
|---|---|---|
| `app.aws.accessKey`, `app.aws.secretKey` (SES) | R/application-dev.yml:75-76; R/application.local.yml:75-76 | committed defaults, **in the format of a real AWS key pair** (an access-key-id-shaped value and a 40-character secret) |
| same | R/application.yml:75-76 | committed defaults, short placeholders |
| same | ls@main(1c65922):src/main/resources/application.yml:28-29; ls@dev_thymeleaf:…/application.yml:36-37 | literal values committed |
| same | ls@dev_API:…:28-29; ls@dev_API_fullSQL:…:28-29 | committed defaults |
| `spring.datasource.password` | R/application-dev.yml:19 (with the RDS host and user as defaults at :17-18); R/application.local.yml:19; R/application.yml:19; T/resources/application-h2.yml:19; T/resources/application-test.yml:19; the same key on `main`, `dev_API`, `dev_API_fullSQL`, `dev_thymeleaf` | committed defaults |
| `app.jwt.jwt-key` | R/application-dev.yml:71 (30 characters); R/application.local.yml:71; T/resources/application-*.yml; R/application.yml:71 (6 characters, too short for HS256) | committed defaults |
| `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD`, `JWT_KEY`, `AWS_ACCESS_KEY`, `AWS_SECRET_KEY` | ls@migration:.env:2, 5, 11, 16-17 (placeholder-like); .env.local:4, 7, 14, 19-20 | literal values committed |
| login password and session UUIDs | ls@migration:api/sanchez-logistica.postman_collection.json, 3 password values and 2 refresh tokens, e.g. lines 13-46, 146, 236 | committed. The collection also holds two expired example JWTs and at least one e-mail address that looks like a real customer's (line 2322). |
| test credentials | T/java/it/logisticasanchez/mono/util/client/CmsTestClient.java:31; T/resources/init.sql:3, 7 | committed |
| prod template | R/application.prod.yml:9, 56, 60-61 | environment variables only, no defaults (and the file is inert) |

### 2.15 Rate limiting and proxy

* The application has **no rate limit, lockout or CAPTCHA** (grep).
* nginx limits exist only in `nginx/conf.d/logistica.conf`: 10 req/s on `/mono/api/` and
  5 req/min on `/mono/auth/` (logistica.conf:8-9, 22-23, 36-37). `scripts/ssl-setup.sh`
  **deletes that file** and writes `app-https.conf` (ssl-setup.sh:85-88), whose single
  `location /mono/` has no limit (app-https.conf:54-74). So the HTTPS production setup has
  no rate limit (inferred).
* The application ignores `X-Forwarded-*`; no forward-headers strategy is configured (grep;
  inferred).
* MySQL (3306) and the debug port (5005) are bound to 127.0.0.1 (docker-compose.yml:16, 93-94).

### 2.16 Behaviour changes by date (to identify the live build)

| From | Commit | Change |
|---|---|---|
| 2024-02-29 | 6187a37 | `/api/order/pending` created without protection |
| 2024-03-03 | 30ea1ba | chain becomes `/mono/cms/** → ADMIN` (dead) + `anyRequest().permitAll()` |
| 2024-03-26 | ebcc438 | `@PreAuthorize ADMIN` on the customer lists, order registered and order detail |
| 2024-04-22 | 540303d | EP-13 added (returns an enum object); `@PreAuthorize` removed from EP-18 |
| 2024-05-06 | 09c3dc5, 40891b1 | enumeration ids 0-based (were ordinal + 1); enums written as label strings; EP-13 answers `{"status": label}` |
| 2024-05-08 | 0dc9a44 | CORS origins from `app.allowed-origins` |
| 2024-05-25 | bb43bbc | `GET /download-report` enabled (public) |
| 2024-07-16 | d7732eb | `/api/order/active` and `/delivered` added (public) |
| 2024-07-18 | d7b2c56 | EP-11 requires `req.importo` (before: `importoAssicurato`) |
| 2024-07-22 | 5debd84 | decline sets NEW instead of DECLINED (customers and orders) |
| 2024-07-27 | 0f1a1fe | custom ObjectMapper: timestamps as decimal epoch seconds; unknown JSON fields rejected (inferred) |
| 2024-08-25 | 96a8b25 | EP-18 needs `onlyDelivered` |
| 2025-11-16 | ca1b0d0 | e-mail links `https://`; portal and CMS links point to `ordini.` and `dashboard.` |

Read-only fingerprint for P-02, not written to chapter 02 yet (inferred):
* `POST /mono/api/order/trace/state` with `{"codice":"NONESISTE"}` answers 200
  `{"status":"Non gestito"}` on every build since 2024-05-06.
* The same call with an extra field, `{"codice":"NONESISTE","x":1}`, answers **500** on builds
  from 2024-07-27 and 200 on older ones.

## 3. Error model

### 3.1 Standard error body

`ErrorRepr` (ls@migration:J/api/error/model/ErrorRepr.java:17-22), built by
`ErrorFactory.errorRepr` (J/api/error/factory/ErrorFactory.java:8-13):
```json
{ "reason": "Richiesta non valida. Controlla nuovamente i dati inseriti", "detail": "No detail" }
```
* `reason`: an Italian sentence from `ErrorEnum`, or the reason of a `ResponseStatusException`.
* `detail`: `"No detail"` when `app.error.hideDetail` is true, the default in deployed
  profiles. Otherwise the exception message, e.g. Spring's validation text or
  `500 INTERNAL_SERVER_ERROR "Qualcosa è andato storto…"`.

There is no error code, no status field and no field list.

### 3.2 ErrorEnum

Source: ls@migration:J/api/error/ErrorEnum.java:18-38. The enum names are never sent; only
`reason` and the HTTP status are.

| Name | HTTP | `reason` (verbatim) | Raised where |
|---|---|---|---|
| `GENERIC_500` | 500 | "Qualcosa è andato storto. Si prega di riprovare oppure contattare il centro logistico." | catch-all, `ExceptionFactory.internalServerError()` (J/business/util/ExceptionFactory.java:21-25), `IllegalArgumentException` |
| `SES_500` | 500 | same text | `AmazonSimpleEmailServiceException` |
| `SES_INVALID_RECIPEE_500` | 500 | "Indirizzo email di destinazione non valido." | `MessageRejectedException` |
| `PERSISTENCE_500` | 500 | same as `GENERIC_500` | `SQLException`, rarely reached because Spring wraps JDBC errors (inferred) |
| `INVALID_ENUM_500` | 500 | same as `GENERIC_500` | customer not PENDING at approval; customer not REGISTERED at order |
| `GENERIC_503` | 503 | "Servizio non disponibile. Si prega di riprovare più tardi oppure contattare il centro logistico." | never raised |
| `GENERIC_400` | 400 | "Richiesta non valida. Controlla nuovamente i dati inseriti." | never raised by the controllers |
| `CONSTRAINT_VIOLATION_400` | 400 | "Richiesta non valida. Controlla nuovamente i dati inseriti" (no final period) | bean validation |
| `GENERIC_401` | 401 | "Non disponi delle autorizzazioni necessarie per procedere." | never raised by the current code; the only reference is commented out (J/configuration/security/SecurityContextUtils.java:41). Log: 2, from older builds. |
| `INVALID_CREDENTIALS_401` | 401 | "Username o password errate." | login |
| `GENERIC_403` | 403 | "Non disponi dei permessi necessari per questo tipo di operazione." | refresh with an unknown or expired session |
| `GENERIC_404` | 404 | "Impossibile recuperare i dati richiesti." | any `NoSuchElementException` (`Optional.orElseThrow()`) |
| `GENERIC_409` | 409 | "E' già stata effettuata una registrazione con questi dati. Si prega di riprovare con dati diversi." | registering a REGISTERED customer |
| `CODE_409` | 409 | "E' già stata effettuata una registrazione con questo codice. Si prega di riprovare con un nuovo codice." | approval or update with an existing code |

### 3.3 Exception to status mapping

Source: ls@migration:J/api/error/ErrorController.java:30-111.

| Cause | Status | Body |
|---|---|---|
| `@Valid` body violation (`MethodArgumentNotValidException`) | 400 | `CONSTRAINT_VIOLATION_400` |
| Service constraint violation (`ConstraintViolationException`) | 400 | same; `detail` reads `Optional[<messages>]` |
| Controller parameter constraint, e.g. a future `from` on `/download-report` | 400 | `ErrorRepr` (inferred) |
| `ResponseStatusException` from `ExceptionFactory` | its status | its reason |
| `NoSuchElementException` | 404 | `GENERIC_404` |
| Method-security denial (no token, or not ADMIN) | **500** | `GENERIC_500`, detail "Access Denied" (log) |
| Malformed JSON, wrong type, comma decimal, unknown property on builds from 2024-07-27 | **500** | `GENERIC_500` (inferred: `HttpMessageNotReadableException` hits the catch-all) |
| Wrong HTTP method | **500** | `GENERIC_500` (log: `GET /mono/auth/login` and `POST /mono/email-confirm` answered 500) |
| Unknown path, trailing slash, `/mono/actuator/**` | **500** | `GENERIC_500` (inferred: Spring 6.1 raises "No static resource", which the catch-all handles; log: one such marker with 500) |
| Missing query parameter or type mismatch in a query parameter | 500 | `GENERIC_500` (inferred) |
| `NullPointerException`, duplicate-row results, other runtime errors | 500 | `GENERIC_500` |
| SES failures | 500 | `SES_500` / `SES_INVALID_RECIPEE_500` |
| Invalid, expired or unknown-user Bearer token | 401 | **not** `ErrorRepr`: raw exception text (§2.9) |
| Disallowed CORS origin | 403 | plain `Invalid CORS request` (inferred) |

Log totals over all paths: 284 × 200, 36 × 500, 25 × 401, 20 × 400, 8 × 404, 1 × 409 (log).

### 3.4 Answers that are not JSON errors

* The 401 from the JWT filter: plain text with a JSON content type (JwtAuthFilter.java:42-44).
* The 403 CORS rejection: plain text.
* `GET /mono/email-confirm`: HTML, **200 for both success and failure**.
* `GET /mono/report`: HTML. `GET /mono/download-report`: PDF.
* Empty 200 bodies: EP-07, EP-12, EP-18 and `/mono/sendEmail`.

### 3.5 Mapping to the frontends' "only `response.ok`"

Chapter 02 §6 still holds: only the 2xx / non-2xx split reaches users. The backend adds
these cases, where a failure in business terms is a 2xx and the clients show it as a
success:

| Call | 2xx answer to a "failure" | What the client does |
|---|---|---|
| EP-13 with an unknown, TEMP or declined code | 200 `{"status":"Non gestito"}` | v1 shows "Non gestito". The new static site shows its state-0 view, not "not found". |
| EP-15 with an unknown code | 200 `{"notes":"Nessuna nota presente"}` | the CMS shows the literal and may save it (CMS-11) |
| EP-02 with an unknown e-mail | 200 `NEW` | registration form (by design) |
| EP-03 again for a NEW customer | 200, the same code re-sent | "Ci siamo quasi…" again |
| `/mono/email-confirm` with a wrong code | 200 HTML error page | — |
| EP-18 for a non-customer e-mail | 200 (dead links in the e-mail) | "Report inviato correttamente" |

Conversely, the protected reads called without a token, and every unexpected condition,
answer 500, which the CMS shows as "Ops, si è verificato un errore". An expired token
answers 401, shown the same way.

## 4. Reconciliation with chapter 02

### 4.1 Endpoints EP-01 to EP-20

"Confirmed" means chapter 02 is right. "Corrected" gives the right fact. Section numbers
point to §1.4.

| ID | Status | Fact from the source |
|---|---|---|
| EP-01 | corrected | The response has five keys: `token`, `refreshToken`, `expTokenSec`, `expRefreshTokenSec` and `userData{id, email(=username), role, operations}`. A wrong password gives 401 `ErrorRepr`. Login upserts the user's `user_session` row. The token is a JWT HS256 valid 6 h (A1, §2.4). |
| EP-02 | corrected | `NEW` also covers unverified customers and customers declined since 2024-07-22. `PENDING` means "e-mail verified, waiting for the office". `DECLINED` can appear for rows declined before 2024-07-22, and the portal then shows its error dialog. 400 for a malformed e-mail (B1). |
| EP-03 | corrected | It answers 200 with `CustomerRepr`. The customer is created **NEW**, not pending; PENDING comes only after the e-mail link. Duplicates: NEW gives 200 and the e-mail is re-sent, PENDING gives 500, REGISTERED gives 409. `telefono` is required. Size limits: 50/50/50/50/9/20/50. The e-mail is "Sanchez Logistica - Conferma indirizzo email" with the link `/mono/email-confirm` (B2). |
| EP-04, EP-05 | corrected | **Protected (ADMIN); 500 without a token.** Items also have `customer` (boolean). `emailConfirmed` is always true in both lists. No time window and no `ORDER BY` (B3/B4). |
| EP-06 | corrected | Approval requires state PENDING, i.e. a verified e-mail; otherwise, or for an unknown e-mail, 500. It returns `CustomerRepr`. E-mail "Sanchez Logistica - Conferma nuovo cliente" (B5). |
| EP-07 | corrected | It sets the customer back to **NEW** without deleting it (DECLINED before 2024-07-22), from any state. E-mail "Sanchez Logistica - Aggiornamento indirizzo email". The customer can re-verify with the old link (B6). |
| EP-08 | corrected | It answers 200 with `Spedizione` (`codice` = `TEMP`+UUID, `stato` PENDING). **An unknown e-mail or a non-REGISTERED customer gives 500** (CU-05). A `null` service gives 500. A `0` dimension gives 400. Each line is expanded per unit and totals are computed. Only the office is e-mailed (C1). |
| EP-09 | corrected | **Open, no token needed (Q-21 incident).** Only orders created in the last 6 months. Items are the full `Spedizione` with 20 keys (C2). |
| EP-10 | corrected | Protected (ADMIN); last 6 months only (C3). |
| EP-11 | corrected | It needs `req.codice`, **`req.importo`** and `req.tracking`. The `ls-cms@dev` body (`importoAssicurato`) answers **500** since 2024-07-18, and `"180,50"` always 500. A duplicate code gives 409. An order that is not PENDING gives 500; an unknown id gives 404. Tracking is forced to PRESA. `importoContrassegno` is overwritten with `importoAssicurato`. It returns `Spedizione` and e-mails "Sanchez Logistica - Conferma ordine N. {codice}" (C6). |
| EP-12 | corrected | The order becomes `stato` NEW and `tracking` NON_GESTITO and is kept (DECLINED before 2024-07-22). Ownership and state are not checked. The e-mail "Sanchez Logistica - Aggiornamento ordine rifiutato" goes to the e-mail in the request and shows the order id (C7). |
| EP-13 | corrected | `status` = label (Q-03: label). **An unknown or TEMP code gives 200 `{"status":"Non gestito"}`**. `""` or `{}` gives 400. Matching is SQL equality, so the collation decides (D1). |
| EP-14 | corrected | It returns the updated `Spedizione`. An unknown code gives 404; an order that is not REGISTERED gives 500; an id outside 0–5 gives 500. It sets `shippingTs`/`deliveryTs` and e-mails the customer on CONSEGNATO only. Any transition is allowed; no history (D2). |
| EP-15, EP-16 | corrected | The note is the order's `note` column, i.e. the customer's EP-08 note (CU-10). The server itself answers `"Nessuna nota presente"` for a null note and for an unknown code. Update: an unknown code gives 404, a null note 500, more than 255 characters 400 (E1/E2). |
| EP-17 | corrected | There are **four** keys: it adds `mittente`. All inner keys are known (§1.3). `palletList` has one entry per physical unit. An unknown code gives 404 (F1). |
| EP-18 | corrected | **Open (no auth).** The request also has `sendToCustomer` (default false) and `onlyDelivered` (no default: **500 when absent**, as in the CMS body, since 2024-08-25). A link is e-mailed (not an attachment) to the **office** always, and to `email` only when `sendToCustomer` is true. `email` filters the customer. `to` is inclusive and the period applies to the creation date (G1). |
| EP-19 | confirmed | Exactly §9.1 (H2). |
| EP-20 | corrected (values now known) | Six items, ids 0–5 as numbers, with `name`. **Id 0 = "Nessun servizio accessorio"** (H1). |

### 4.2 Contract unknowns CU-01 to CU-24

| ID | Status | Answer |
|---|---|---|
| CU-01 | answered | The label (`"Presa in carico"`), a string (OrderController.java:36-38). The 2024-04-22…05-05 builds returned `{"label": …}`. |
| CU-02 | mostly answered | Unknown code: 200 `"Non gestito"`. TEMP code: 200 `"Non gestito"`. `""`/`{}`: 400 `ErrorRepr`. Padded or lower-case codes: SQL equality, so the collation decides. The repository's MySQL runs `utf8mb4_unicode_ci` (case-insensitive; trailing spaces ignored) (docker-compose.yml:22-23). The production collation is still unknown. |
| CU-03 | answered | The only field is `status`. Values: NEW, PENDING, REGISTERED, DECLINED (legacy rows). A malformed or blank e-mail gives 400. Case and spaces: collation (inferred case-insensitive). |
| CU-04 | answered | EP-03: 200 `CustomerRepr`; 409 or 500 on duplicates (B2); limits in B2. EP-08: 200 `Spedizione`; 400 on validation; 500 for an unknown or non-REGISTERED customer and for invalid enums (C1). |
| CU-05 | answered | **Yes.** A customer that is not REGISTERED gets 500 (SpedizioneService.java:78-79). OP-S1 remains for any REGISTERED customer's e-mail. |
| CU-06 | answered | §1.5. EP-08 e-mails only the office; EP-11 e-mails the customer; EP-14 e-mails only on CONSEGNATO. |
| CU-07 | answered from source | H1/H2. `statoTracking` equals §9.1. P-01 still confirms the live build. |
| CU-08 | answered | `servizioAccessorio`, `stato` and `tracking` are label strings. `importo` is its own column: written at approval since 2024-07-18, null on orders approved earlier, which stored `importoAssicurato` instead. The extra fields are `importoContrassegno`, `importoAssicurato`, `confirmationTs`, `shippingTs` and `deliveryTs`. There is no creation date and no customer id. |
| CU-09 | answered | §1.3 `OrderDetailRepr`. |
| CU-10 | answered | It is the same field: staff edits overwrite the customer's note. |
| CU-11 | answered | 200 `Spedizione`; 404 for an unknown code. |
| CU-12 | answered | The PDF is generated on demand at `/mono/download-report`, with the columns in G2. The e-mail carries links, not an attachment. `email` is the customer filter and, when `sendToCustomer` is true, also the recipient. The office always receives it. `to` is inclusive. |
| CU-13 | answered | §2.4–2.9: JWT HS256, 6 h; refresh endpoint; roles ADMIN and CUSTOMER; enforcement per endpoint. The number of staff accounts is still unknown. |
| CU-14 | partly answered | The mechanism and the committed lists are in §2.10. The server's `ALLOWED_ORIGINS` is not in git, so P-03 is still needed. |
| CU-15 | inferred | There is no `ORDER BY`, so the database order applies (likely ascending id). |
| CU-16 | answered | Decline keeps the row and sets NEW (DECLINED before 2024-07-22). A declined customer can verify again with the old link and can register again (SoggettoService.java:53-61, 99-115). A DECLINED legacy customer gets 200 from register but can never verify (inferred). |
| CU-17 | answered | TEMP = `"TEMP"` + UUID v4. Definitive codes are free text; the Postman samples look like `1-RKSCODE`, so they may be the partner's numbers (inferred; postman:1924, 2195, 2284). Uniqueness is checked at approval and update (409), and the table has a unique constraint on `codice` (J/persistence/model/SpedizioneEntity.java:17-18). Case: collation. |
| CU-18 | answered | `updateShippingTs`/`updateDeliveryTs` stamp `shippingTs`/`deliveryTs` with now (SpedizioneFactory.java:104-106). The stored timestamps are `confirmationTs`, `shippingTs`, `deliveryTs` and the audit `createTms`/`updateTms`. No tracking history. |
| CU-19 | answered | `type` 0/1 is stored as the label `"PALLET"`/`"EPAL"`; `null` gives 400. Numeric strings are coerced. Totals are computed (DettaglioColli in §1.3). The backend serves the `tipoPallet` enumeration (H3). |
| CU-20 | answered | `destinatario.email` is stored as the order's `email` and listed as "Cliente mittente". The backend writes to it only through EP-12, when the CMS passes it. |
| CU-21 | still unknown, with new evidence | The CMS that works with this backend cannot be `ls-cms@dev` (§0 point 6). The backend links to `https://ordini.logisticasanchez.com/` and `https://dashboard.logisticasanchez.com/` (LinkUtil.java:24-30). |
| CU-22 | answered | Yes: approval needs PENDING, i.e. a verified e-mail (500 otherwise). NEW customers never appear in the pending list. |
| CU-23 | answered | No rate limits in the application, and none in the HTTPS nginx (§2.15). Validation is listed per endpoint. |
| CU-24 | answered | The link is `https://{origin}/mono/email-confirm?email=…&verificationCode=<UUID>` (`http://` before 2025-11-16). It never expires. Its host defaults to the apex in Docker (§1.5). The other endpoints no frontend calls are listed in §4.3. |

### 4.3 Backend endpoints chapter 02 does not know

There are 16: `POST /mono/auth/refresh-token`, `POST /mono/api/order/state`,
`POST /mono/api/order/active`, `POST /mono/api/order/delivered`,
`GET /mono/api/enumeration/tipoPallet`, `GET /mono/api/enumeration/statoConferma`,
`POST /mono/sendEmail`, `POST /mono/cms/order?email=`, `POST /mono/cms/order/update`,
`POST /mono/utils/order/delete`, `POST /mono/utils/order/deleteAll`,
`POST /mono/utils/order/deleteBeforeDate`, `POST /mono/utils/customer/delete`,
`GET /mono/email-confirm`, `GET /mono/download-report` and `GET /mono/report`. Static and
view pages come on top (§1.2).

For the cut-over (chapter 02 §10.3), the `GET /mono/**` links that already-sent e-mails
contain are `/mono/email-confirm`, `/mono/report` and `/mono/download-report`. Because of
the `EMAIL_ORIGIN` default (§1.5), they may point to the **apex** as well as to `app.`
(inferred). The proposed 410 page must therefore answer on both hosts, and must never serve
report data without authentication.

### 4.4 Other statements in the brief to update

1. **02 §1/§3/§4.3**: replace "which endpoints the server protects is (unknown)" with §2.7.
   The `/mono/cms` prefix is not "inferred: protected": EP-18 is open.
2. **02 §4.3**: missing token on a protected read gives **500**; an expired or invalid
   token gives **401** with a plain-text body.
3. **02 §5.2**: the legacy sends `Access-Control-Allow-Credentials: true` and allows `GET,
   POST, PUT, DELETE, OPTIONS` with any header.
4. **02 §8 (wire variants)**:
   * Line dimensions of `"0"` are rejected with 400.
   * A recipient `telefono: ""` (build `1c84556`) is rejected with 400.
   * `servizioAccessorio: null` (portal builds before `e183526`) is rejected: 400 while the
     field was `@NotNull` (ls@40891b1:J/api/req/CreateOrderReq.java:36), 500 in the
     `migration` build.
   * The number `1` sent as "no service" (portal builds `e183526`…`6176e85`, May 2024) is
     stored as "Prenotazione telefonica" on every backend from 2024-05-06, which maps the
     integer by 0-based ordinal (ls@bb43bbc:J/business/service/OrderService.java:59;
     OrderService.java:65; H1). This settles DQ-07 for those builds.
   * Timestamps travel as ISO strings before 2024-07-27 and as decimal epoch seconds after.
5. **02 §10.2 / §10.4**:
   * FX-03's "legacy evidence: non-2xx" is wrong: the legacy answers 200 "Non gestito". The
     target 404 is a deliberate change of behaviour; record it in the ADR (Q-03, Q-38).
   * FX-05: the legacy answers 400.
   * FX-06: the legacy answers 200 "Non gestito".
   * FX-07: the legacy preflight answers 200 with credentials true (inferred).
6. **02 §11.2 P-04**: read 500 as "protected" and add `order/active` and `order/delivered`
   (§2.8).
7. **08 §4.3 (API export)**:
   * Calls 6–7 return only the **last 6 months**. Add `POST /mono/cms/order?email=` for each
     customer of calls 4–5, which returns all orders of any state without a time window.
   * Customers in state NEW cannot be listed by any endpoint.
   * Timestamps arrive as numbers (or ISO strings on old builds); `createTms` is never
     exposed.
   * Treat `"Nessuna nota presente"` from EP-15 as "no note".
   * EP-13 always answers 2xx, so it cannot detect unknown codes.
   * The token lasts 6 h, and a refresh endpoint exists.
8. **11 Q-25**: answered from source (H1). Keep P-01 only to confirm the live build.
9. **11 Q-28**: the server refuses to approve unverified customers. The recommended default
   "No (legacy behaviour)" describes the CMS, not the server.
10. **11 Q-07 / Q-08**: answered by §1.5 and G1. Copy review is still the owner's.
11. **11 Q-22 (CMS stored XSS)**: confirmed on the server side. No input is sanitised; only
    sizes (50/255) and the phone pattern are checked, so a payload such as
    `<img src=x onerror=…>` (28 characters) fits `ragioneSociale` or `note`.
12. **11 Q-43**: the office mailbox is hard-coded in `EmailReqFactory.java:159, 185`.

## 5. Security findings

Severity uses the impact on the live system. Every fix is phrased for the new app.

| ID | Severity | Finding | Evidence | One-line fix for the new app |
|---|---|---|---|---|
| SEC-01 | **Critical** | Pending orders, with personal data, are readable without authentication | ls@migration:J/api/controller/OrderController.java:55-59; SpedizioneRepository.java:39-45 | Deny by default: every back-office route requires a staff session and a role check, covered by a test per route. |
| SEC-02 | **Critical** | `order/active` and `order/delivered` return every approved order when `email` is omitted, or any customer's orders when it is given, without authentication | OrderController.java:68-78; SpedizioneService.java:268-292; J/api/req/OrderFiltersReq.java:16-18 | Never key data access on a client-supplied e-mail; derive the customer from the session. |
| SEC-03 | **Critical** | `GET /mono/download-report` and `/mono/report` serve any customer's shipment PDF by e-mail address. The same links were e-mailed over `http://` until 2025-11-16. | J/web/WebController.java:39-68; LinkUtil.java:14-21; ls@285d2b1:J/business/util/LinkUtil.java:15 | Authenticated download, or short-lived signed single-use URLs; HTTPS only. |
| SEC-04 | **High** | The URL rule `/mono/cms/**` is dead because of the context path, so protection depends on per-method annotations alone; EP-18 has none | WebSecurityConfig.java:41-42; PatternConstants.java:6; R/application.yml:4; CmsController.java:79 | Central deny-by-default authorisation written against real request paths, plus a route-by-route test matrix. |
| SEC-05 | **High** | EP-18 is open and sends e-mail from the company's SES identity to the office, and to any address with `sendToCustomer: true` (spam relay, SES reputation, office flooding) | CmsController.java:79-109; EmailReqFactory.java:107-131 | Staff-only; recipients from an allow-list (Q-08 default); rate limit and audit. |
| SEC-06 | **High** | Secrets are committed: AWS SES key pairs in the format of real keys, DB passwords with the RDS host, JWT keys, `.env` files, a login password and session ids in the Postman collection. All remain in git history across branches. | §2.14 table | Rotate everything now; secrets only from a secret store; secret scanning in CI; never ship `.env`. |
| SEC-07 | **High** | Every request and response body is logged, including passwords, JWTs and customer data, and the logs were committed: 43 password lines and 18 JWT lines | J/configuration/RequestResponseLoggingFilter.java:15-45; R/logback.xml:4, 32-35; `logs/` | No body logging; structured logs with redaction; purge the repository and change the staff passwords. |
| SEC-08 | **High** | The refresh token is a permanent per-user session id: reused at every login, sliding, never rotated, no revocation and no logout. Access tokens cannot be revoked (6 h). | AuthenticationService.java:45-77; JwtAuthFilter.java:48-56 | Server-side sessions with idle and absolute timeouts and logout, or rotated one-time refresh tokens. |
| SEC-09 | **Medium** | Authorisation failures and framework errors answer 500. Invalid tokens answer 401 with raw library messages under a JSON content type. Attacks are hidden in error noise and internals leak. | ErrorController.java:30-33; JwtAuthFilter.java:41-45 | Explicit 401/403/404/405/415 mapping; one JSON error schema; no exception text to clients. |
| SEC-10 | **Medium** | No brute-force protection on login or on the public endpoints. The nginx limits are deleted by the SSL setup. Unknown usernames answer faster (timing). | ssl-setup.sh:85-88; app-https.conf:54-74; DatabaseAuthenticationConfig.java:34 | Per-IP and per-account rate limits, lockout or backoff, constant-time failure path. |
| SEC-11 | **Medium** | Unauthenticated e-mail triggers: registration sends to any address; `email-confirm` notifies the office on every hit, before checking the code; `GET` has side effects | CustomerService.java:38-41, 47-55; WebController.java:34-37 | Rate limit plus a bot check on public forms; send after validation; confirm with `POST`. |
| SEC-12 | **Medium** | E-mail-only identity: anyone who knows a REGISTERED customer's e-mail can order in its name (OP-S1). `customer/state` is an enumeration oracle (OP-S2). | OrderService.java:59; SpedizioneService.java:78; CustomerController.java:27-33 | Authenticated customer area; one uniform answer for every e-mail. |
| SEC-13 | **Medium** | Stored-XSS source: free-text fields accept HTML that the legacy CMS renders unescaped (Q-22) | CreateSoggettoReq.java:21-49; CreateOrderReq.java:22-39 | Escape on output everywhere; validate charsets and lengths on input. |
| SEC-14 | **Medium** | Admin decline and update skip state and ownership checks: a REGISTERED order can be reset to NEW, and decline e-mails go to the address supplied | OrderService.java:135-152, 205-211; CustomerService.java:70-77 | An explicit state machine with allowed transitions, and recipients taken from the record. |
| SEC-15 | **Low** | `/mono/sendEmail` lets any ADMIN token send arbitrary e-mail through the company's SES identity (phishing if a token leaks) | CmsController.java:44-49; AwsEmailService.java:36-50 | Do not port; templated notifications only. |
| SEC-16 | **Low** | Hard-delete admin utilities without audit | AdminToolsController.java:31-57; AdminToolsService.java:46-135 | Soft delete with an audit log and retention rules. |
| SEC-17 | **Low** (latent) | The actuator config exposes `*` or `env`/`configprops` with all paths public. It is harmless only because the dependency is missing. | R/application.yml:59-66; R/application.prod.yml:79-93; pom.xml:22-49 | Expose only `health` on an internal port; never `env`/`configprops`. |
| SEC-18 | **Low** | CORS allows credentials without needing them, and some environments list `http://` origins | WebSecurityConfig.java:58-68; R/application-dev.yml:69 | Exact HTTPS origins only, no credentials for the public API (chapter 02 §10.2 rule 5). |
| SEC-19 | **Low** | The verification link carries the raw e-mail (not URL-encoded) and a code that never expires; customer data is also passed to Google through "safe link" wrappers | LinkUtil.java:32-35; EmailReqFactory.java:27, 111-112 | Signed, expiring, opaque tokens; no third-party redirect wrappers. |
| SEC-20 | **Info** | The profile file `application.prod.yml` is inert (dot instead of hyphen), so production runs the base config (Hibernate SQL logging, actuator `*`) plus an untracked server file | R/application.prod.yml; docker-compose.yml:99; ls@285d2b1 | One versioned config schema validated at start-up; fail fast on missing secrets. |
| SEC-21 | **Info** | The tests do not protect the contract: the CMS test client swaps the URI and token arguments, the fixture passwords are not BCrypt, and the integration test is `@Disabled` | T/java/it/logisticasanchez/mono/util/client/CmsTestClient.java:34; T/resources/init.sql:3; T/java/it/logisticasanchez/mono/business/service/crud/IntegrationTests.java:109-111 | Contract tests per route, including auth-negative cases (no token, wrong role, expired token). |

## 6. Still unknown after reading the source

* **Which commit runs in production**, and on which host: the Docker/VPS behind
  `app.logisticasanchez.com`, or Elastic Beanstalk. The committed deploy script runs
  `git pull origin main` (scripts/deploy.sh:55), but `main` holds only the 2024 skeleton.
  So the live build was not produced by this script as committed (inferred). Use the
  fingerprints of §2.16 and probes P-01, P-02 and P-04.
* The server-side `.env` and the mounted `config/application-prod.yml`. They hold the real
  `ALLOWED_ORIGINS`, `EMAIL_ORIGIN`, the token lifetimes and `HIDE_ERROR_MESSAGES` (P-03 for
  CORS).
* The production database collation, which decides case and space matching for codes and
  e-mails (CU-02, CU-03, CU-17).
* The number of staff accounts, their roles and their `policy` values (Q-17).
* Which CMS build staff use: `dashboard.logisticasanchez.com` is likely, and it is not the
  `ls-cms` repository (§0 point 6).
* Whether the live data contains `DECLINED` rows, duplicate customer e-mails or orders
  approved before 2024-07-18 with `importo` null (chapter 08 data quality).
