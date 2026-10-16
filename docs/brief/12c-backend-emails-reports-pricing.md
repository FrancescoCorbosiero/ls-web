# 12c · E-mails, backend pages, report, pricing and admin tools (legacy backend)

> **Status.** Written from the legacy backend source (`ls`, branch `migration`, commit
> `ca1b0d0`) on 2026-10-16. Its corrections to chapters 01–11 have been applied there.
> Where this chapter and an older chapter still disagree about how the legacy system
> works, **this chapter wins**: it reads the server code, while 02–08 inferred the server
> from its clients. Sections titled "Reconciliation" or "Corrections to the brief" record
> what changed.

Notes on the legacy Spring Boot backend for the rebuild. They cover what the backend
sends or renders for people (e-mails, HTML pages, the PDF report), its pricing code, its
admin tools and its SES integration, and they reconcile all of it with chapters 07, 09
and 11 of the brief. Other notes cover the API contract, the data model and security;
this file repeats only what it needs.

## 0. Sources, conventions, findings in brief

| Alias | What | Ref / remark |
|---|---|---|
| `ls@migration` | Backend repository `ls` (`legacy/ls`), branch `origin/migration`, head `ca1b0d0` "http ses fix" (2025-11-16). Believed to be production. | The extracted copy that was read is byte-identical to this tree (all 230 files compared). |
| `ls@dev_API_v2` | Head `91cf025` (2025-01-07) | Differs from `migration` only in `LinkUtil.java` and the yml profiles (`git diff --stat origin/dev_API_v2 origin/migration -- src/main`). Every e-mail, report and pricing behaviour below is therefore unchanged since 2025-01-07, except the link scheme and hosts (§1.4). |
| `ls@main` | Head `7dbb2d4` (2026-07-29) | Initial skeleton plus `IMPROVEMENTS.md`. It has no e-mail, report or pricing code and is not production. |
| `aws-ses-ms` | `https://github.com/FrancescoCorbosiero/aws-ses-ms` | Public and **empty**: no branch, no tag, no commit (§5.1). |
| `ls-cms@dev` | CMS frontend, head `f0c881c` (2024-06-06) | Used only to see what the CMS sends to the backend. |

Conventions
* `J/` = `src/main/java/it/logisticasanchez/mono/`, `R/` = `src/main/resources/`,
  `T/` = `src/main/resources/templates/`. Citations: `ls@migration:<path>:<line>`.
* "(inferred)" = deduced from the code, not observed at runtime. "(unknown)" = neither
  the code nor the repository says.
* Italian strings are verbatim, with HTML entities decoded (`pi&ugrave;` → "più").
* Secrets: key names only. Personal data: none. `logisticasanchezmb@gmail.com` is the
  company's public contact address (chapter 01) and is quoted because it is hard-coded as
  the office recipient. `servizioclienti@logisticasanchez.com` is a role address.
* Runtime evidence, used sparingly: development logs committed under `logs/` (profiles
  `local`, `dev` and `test`, June 2024 – January 2025, older builds), and the developer's
  Postman collection `api/sanchez-logistica.postman_collection.json`.

Findings in brief
1. The backend sends **ten automatic e-mails** (L-E1…L-E10) plus one free-text admin
   tool (L-E11). All automatic e-mails are HTML-only and go through the **AWS SDK v1
   SES client, called directly** (SendEmail API). No HTTP mail service is involved, and
   the `aws-ses-ms` repository is empty.
2. Recipients are either the customer (the order's sender, *mittente*) or a
   **hard-coded office Gmail address**. The consignee never receives e-mail. No e-mail
   sets a Reply-To.
3. Legacy sends a **delivery e-mail** ("Consegna effettuata per ordine N. …") when
   staff set tracking to *Consegnato*. Chapter 09 has this only in M2 (ENH-05).
4. Legacy sends **no** "registration received" or "order received" e-mail to the
   customer; it only sends the verification e-mail and the office notifications.
5. The **report** is a PDF (A4 landscape, a customer block and a 13-column table). It
   is generated when a link is clicked: the e-mails carry links to `/mono/report` and
   `/mono/download-report`, which are **public, unauthenticated and never expire**. The
   PDF is never attached. The office always receives the e-mail; the customer receives it
   only when `sendToCustomer=true`, which the CMS in the repository never sends.
6. The backend **computes no price**. `TariffarioConst`, the two tariff traits,
   `EvasioneEnum` and `PalletEnum` are referenced by no other class. Their numbers equal
   the MOSCA notes, except `PalletEnum`, which contradicts the MOSCA class image for HP,
   ULP, LP and FP.
7. In `ls@migration` the amount staff type at approval is stored in **`importo`**, which
   is required and must be > 0. The CMS in the repository still sends `importoAssicurato`.
8. **Admin tools**: four hard-delete endpoints, a free-text e-mail endpoint and an
   order-update endpoint. All require the ADMIN role, have no UI and are used from Postman.
9. **Urgent**: the unauthenticated report download (§3.5), and a literal AWS key pair
   committed in two yml profiles (§5.5).

---

## 1. E-mail catalogue

### 1.1 Mechanics shared by all e-mails

| Aspect | Legacy behaviour | Evidence |
|---|---|---|
| Transport | `AmazonSimpleEmailService.sendEmail(SendEmailRequest)`: the SES v1 *SendEmail* API, not *SendRawEmail*, so there are no attachments and no custom headers. | ls@migration:J/business/service/AwsEmailService.java:49, 66 |
| Format | Automatic e-mails: HTML body only (`Body.withHtml`), no text alternative. The admin tool `/sendEmail` sends text only. Charset `UTF-8` for subject and body. | ls@migration:J/business/service/AwsEmailService.java:31, 36-50, 52-67 |
| Rendering | Thymeleaf templates in `T/`, rendered by the Spring `TemplateEngine` from a `Context` map. Values are inserted with `th:text`, which HTML-escapes them. | ls@migration:J/business/util/EmailReqFactory.java:21, 29-34 |
| Sender (From) | `app.email.author`, a bare address with no display name. Default `servizioclienti@logisticasanchez.com`; `.env` sets another `@logisticasanchez.com` address. | ls@migration:J/configuration/property/EmailProperties.java:8-13; ls@migration:R/application.yml:78; ls@migration:R/application.prod.yml:63; ls@migration:docker-compose.yml:82 |
| Office recipient | Hard-coded `logisticasanchezmb@gmail.com`, not configurable. | ls@migration:J/business/util/EmailReqFactory.java:159, 185 |
| Addressing | Exactly one To address; no CC, BCC or Reply-To. | ls@migration:J/business/service/AwsEmailService.java:58-64 |
| Kill switch | When `app.email.enableAutomaticEmails` is false, every send method returns silently, including office notifications and `/sendEmail`. Tests and local setups set it to false. | ls@migration:J/business/service/AwsEmailService.java:37, 53, 71; ls@migration:R/application.yml:80; ls@migration:src/test/resources/application-test.yml:80 |
| Threading | Sent synchronously in the HTTP request thread. There is no `@Async`, queue or outbox (no `@Async`/`@EnableAsync` anywhere in `J/`). | ls@migration:J/business/service/AwsEmailService.java:99-135 |
| Subjects | All start with "Sanchez Logistica - ". | ls@migration:J/business/util/EmailReqFactory.java:39, 55, 69, 85, 102, 128, 144, 160, 186 |
| Footer | Customer e-mails: "Questa email viene generata automaticamente dal servizio online di Logistica Sanchez. Si prega di non rispondere." Error template and office notification: "Questa risposta viene generata automaticamente dal servizio online di Logistica Sanchez. Si prega di non rispondere." | ls@migration:T/email-confirmation.html:73; ls@migration:T/error.html:52; ls@migration:T/admin-notification.html:62 |
| Brand name | Subjects say "Sanchez Logistica"; bodies mix "Sanchez Logistica" and "Logistica Sanchez" (Q-37). | ls@migration:T/email-confirmation.html:61, 71 |
| Visuals | Inline `<style>` in `<head>`. Check/cross icons come from Font Awesome 5.15.4 CSS on cdnjs, which most mail clients do not load (inferred). The logo `<img>` is commented out with "TODO: carica da FE static". | ls@migration:T/customer-success.html:5, 59; ls@migration:T/email-confirmation.html:54-57 |
| Value output | `th:text` prints enums by their Java name (`PRESA`, `NESSUN_SERVIZIO`, `REGISTERED`): the enums declare no `toString`, and their labels are used only for JSON. Doubles print as Java doubles (`120.0`, `51.75`), with no € sign and no Italian decimal comma (inferred). | ls@migration:J/business/model/enumeration/ServizioAccessorioEnum.java:14-27; ls@migration:J/business/model/Spedizione.java:55-69 |
| "Safe" links | Buttons and links carry `data-saferedirecturl="https://www.google.com/url?q=<link>"`, imitating Gmail's own attribute. No functional effect (inferred). | ls@migration:J/business/util/EmailReqFactory.java:27, 111-112 |
| URL building | Query values (e-mail, code, dates) are inserted with `String.format` and **no URL-encoding**. An address containing `+` or `&` gives a broken link: `+` reaches the server as a space (inferred). | ls@migration:J/business/util/LinkUtil.java:14-35 |

### 1.2 Catalogue

The IDs L-E1… are local to this file; §1.5 maps them to chapter 09. Endpoints carry the
`/mono` context path (ls@migration:R/application.yml:4). EP-xx are the endpoint IDs of
chapter 02.

| ID | Enum → factory method | Trigger (endpoint → service) | To | Subject (verbatim) | Template |
|---|---|---|---|---|---|
| L-E1 | `NotificationEnum.EMAIL_REGISTRATION` → `emailConfirmation` | `POST /mono/api/customer/register` (EP-03) → `CustomerService.register` (ls@migration:J/api/controller/CustomerController.java:35-39; ls@migration:J/business/service/CustomerService.java:47-55) | the registering customer | "Sanchez Logistica - Conferma indirizzo email" (ls@migration:J/business/util/EmailReqFactory.java:39) | `email-confirmation` |
| L-E2 | `AdminNotificationEnum.ADMIN_CUSTOMER_REQUEST` → `adminNotification` | `GET /mono/email-confirm` (the verification link) → `CustomerService.emailConfirm`, sent **before** the code is checked (ls@migration:J/business/service/CustomerService.java:38-41) | office Gmail | "Sanchez Logistica - Nuovo cliente in attesa di conferma" (ls@migration:J/business/util/EmailReqFactory.java:160; ls@migration:J/business/model/enumeration/AdminNotificationEnum.java:15) | `admin-notification` |
| L-E3 | `CUSTOMER_REGISTRATION` → `customerRegistration` | `POST /mono/cms/customer/confirm` (EP-06) → `CustomerService.confirm` (ls@migration:J/api/controller/CmsController.java:51-56; ls@migration:J/business/service/CustomerService.java:57-62) | customer | "Sanchez Logistica - Conferma nuovo cliente" (ls@migration:J/business/util/EmailReqFactory.java:55) | `customer-success` |
| L-E4 | `CUSTOMER_DECLINATION` → `customerDeclination` | `POST /mono/cms/customer/decline` (EP-07) → `CustomerService.decline` (ls@migration:J/api/controller/CmsController.java:65-70; ls@migration:J/business/service/CustomerService.java:70-77) | customer | "Sanchez Logistica - Aggiornamento indirizzo email" (ls@migration:J/business/util/EmailReqFactory.java:69) | `error` |
| L-E5 | `AdminNotificationEnum.ADMIN_ORDER_REQUEST` → `adminNotification` | `POST /mono/api/order/register` (EP-08) → `OrderService.register`, after the order is saved (ls@migration:J/api/controller/OrderController.java:49-53; ls@migration:J/business/service/OrderService.java:110) | office Gmail | "Sanchez Logistica - Nuova richiesta di spedizione in attesa di conferma" (ls@migration:J/business/util/EmailReqFactory.java:160; ls@migration:J/business/model/enumeration/AdminNotificationEnum.java:16) | `admin-notification` |
| L-E6 | `ORDER_DETAIL` → `orderDetail` | `POST /mono/cms/order/confirm` (EP-11) → `OrderService.confirm` (ls@migration:J/api/controller/CmsController.java:58-63; ls@migration:J/business/service/OrderService.java:114-121) | customer (the order's *mittente*) | "Sanchez Logistica - Conferma ordine N. {codice}" (ls@migration:J/business/util/EmailReqFactory.java:85) | `order-detail` |
| L-E7 | `ORDER_DECLINATION` → `orderDeclination` | `POST /mono/cms/order/decline` (EP-12) → `OrderService.decline` (ls@migration:J/api/controller/CmsController.java:72-77; ls@migration:J/business/service/OrderService.java:135-152) | the `email` in the request body; the CMS sends the row's customer (chapter 02 EP-12) | "Sanchez Logistica - Aggiornamento ordine rifiutato" (ls@migration:J/business/util/EmailReqFactory.java:102) | `error` |
| L-E8 | `TRACKING_STATE` → `trackingState` | `POST /mono/cms/order/trace` (EP-14), only when the new state is `CONSEGNATO` (ls@migration:J/api/controller/CmsController.java:111-123) | customer (*mittente*) | "Sanchez Logistica - Consegna effettuata per ordine N. {codice}" (ls@migration:J/business/util/EmailReqFactory.java:144) | `tracking-state` |
| L-E9 | `REPORT` → `emailReport` | `POST /mono/cms/order/report` (EP-18), only when `sendToCustomer` is true (ls@migration:J/api/controller/CmsController.java:104-105) | the `email` in the request, i.e. the customer whose orders are reported | "Sanchez Logistica - Report ordini" (ls@migration:J/business/util/EmailReqFactory.java:128) | `email-report` |
| L-E10 | `ADMIN_REPORT` → `adminEmailReport` | same endpoint, on every call (ls@migration:J/api/controller/CmsController.java:106) | office Gmail | "Sanchez Logistica - Report ordini" (ls@migration:J/business/util/EmailReqFactory.java:186) | `email-report` |
| L-E11 | `AwsEmailService.send` | `POST /mono/sendEmail`, an admin tool (§6) | any | any | none (plain text) |

Declared but never used:
* `AdminNotificationEnum.ADMIN_REPORT("Report cliente")`
  (ls@migration:J/business/model/enumeration/AdminNotificationEnum.java:17).
* The attachment variant `sendHtml(req, byte[] pdfBytes)`, which would embed the PDF as
  a base64 `<embed>` under "Attached PDF:", with the comment "//TODO: find a way to attach
  files". Nothing calls it (ls@migration:J/business/service/AwsEmailService.java:69-97).
  `ReportPayload.report` is always `null` (ls@migration:J/api/controller/CmsController.java:101).
* `CustomerEmailNotificationReq`, `OrderEmailNotificationReq` and `EmailFilters`: no
  usage outside their own files (grep).
* `LinkUtil.buildCmsLink`: its call is commented out
  (ls@migration:J/business/util/EmailReqFactory.java:150).
* The `javaMailSender` bean: nothing injects it
  (ls@migration:J/configuration/AwsMailConfig.java:28-31).
* The `NotificationEnum` labels are not used as subjects; two of them appear inside the
  decline e-mails (ls@migration:J/business/model/enumeration/NotificationEnum.java:16-23).

### 1.3 Content of each e-mail

**L-E1 · E-mail verification** (`email-confirmation`)
* Context: `link`, `safeLink`, `imgLink` (not rendered) and `email`
  (ls@migration:J/business/util/EmailReqFactory.java:25-34).
* Body (ls@migration:T/email-confirmation.html):
  * h1 "Conferma indirizzo mail" (:58)
  * h2 the customer's e-mail address (:60)
  * "Clicca per confermare questo indirizzo email presso Logistica Sanchez:" (:61)
  * button "Conferma" → link (:64)
  * "Oppure clicca sul link seguente:" (:68), then a link with text "Conferma Email" (:69)
  * "Grazie per aver scelto Sanchez Logistica!" (:71), then the footer (:73).
* Link: `https://{origin}/mono/email-confirm?email={e-mail}&verificationCode={codiceVerifica}`
  (ls@migration:J/business/util/LinkUtil.java:32-35;
  ls@migration:J/configuration/constant/PathConstants.java:59). For `{origin}` see §1.4.
* Token:
  * `codiceVerifica` is a random UUID v4 (36 characters), generated once when the
    customer row is created (ls@migration:J/business/service/crud/SoggettoService.java:63-70;
    ls@migration:J/business/util/TextCodeFactory.java:8-10) and stored in
    `SoggettoEntity.codiceVerifica` (ls@migration:J/persistence/model/SoggettoEntity.java:47).
  * **No expiry**: no timestamp field and no check anywhere (grep).
  * Effectively single use. The link succeeds only while the customer is NEW, and success
    moves them to PENDING (ls@migration:J/business/service/crud/SoggettoService.java:53-61).
    The code itself is never cleared.
* Re-sending: when the same e-mail and *ragione sociale* register again while the
  customer is still NEW, the existing row is reused and L-E1 is sent again with the same
  code (ls@migration:J/business/service/crud/SoggettoService.java:99-115;
  ls@migration:J/business/service/CustomerService.java:51-53). Registering again while
  PENDING answers 500; registering again when REGISTERED answers 409, and neither sends an
  e-mail (ls@migration:J/business/service/crud/SoggettoService.java:108-111).
* After a decline (L-E4) the customer is NEW again, so the original link works again
  (inferred from ls@migration:J/business/service/CustomerService.java:73-75).
* The test suite exercises this link with GET and expects 200
  (ls@migration:src/test/java/it/logisticasanchez/mono/NonRegressionTests.java:72-79;
  ls@migration:src/test/java/it/logisticasanchez/mono/util/client/WebTestClient.java:17-18).

**L-E2 · Office: new customer** (`admin-notification`)
* Body (ls@migration:T/admin-notification.html):
  * title "Admin Notification" (:5)
  * h1 "Sanchez Logistica - Notifica" (:53)
  * "Hai una nuova notifica: " (:56)
  * "Azione: Nuovo cliente in attesa di conferma" (:57, label from
    ls@migration:J/business/model/enumeration/AdminNotificationEnum.java:15)
  * "Cliente: {customer e-mail}" (:58), then the footer (:62).
  * There is no link to the CMS.
* It is sent on **every GET** of the verification link, before the result is known
  (ls@migration:J/business/service/CustomerService.java:39-40). Repeated clicks, wrong
  codes and mail scanners that pre-fetch links each produce one office e-mail (inferred).

**L-E3 · Customer approved** (`customer-success`)
* Body (ls@migration:T/customer-success.html):
  * green check icon (:59)
  * h1 "Benvenuto in Sanchez Logistica!" (:60)
  * "Da questo momento in poi fai parte dei clienti del nostro centro logistico." (:61)
  * h2 "Effettua subito un nuovo ordine:" (:62)
  * button "Portale ordini" → `orderLink` (:66)
  * "Oppure clicca sul link seguente:" (:70), then a link "Effettua nuovo ordine" (:71)
  * "Grazie per aver scelto Sanchez Logistica!" (:74), then the footer (:76).
* Link: `https://ordini.logisticasanchez.com/` (ls@migration:J/business/util/LinkUtil.java:24-26).
  E-mails sent before 2025-11-16 link to
  `http://sanchez-logistica-fe.s3-website.eu-north-1.amazonaws.com/` (`git show ca1b0d0`).
* The template reads `${safeLink}`, which the context does not set
  (ls@migration:J/business/util/EmailReqFactory.java:47-49), so the attribute is left out
  (inferred).

**L-E4 · Customer declined** (generic `error` template)
* Body (ls@migration:T/error.html):
  * red cross icon (:45)
  * h1 "Abbiamo riscontrato un errore nella tua richiesta." (:46)
  * h2 "Richiesta: Indirizzo email e/o dati cliente rifiutati dall'amministratore" (:47;
    text from ls@migration:J/business/util/EmailReqFactory.java:63 and
    ls@migration:J/business/model/enumeration/NotificationEnum.java:17)
  * "Si prega di riprovare più tardi o contattare il supporto se ritieni ci sia stato un errore." (:48)
  * "Grazie per la comprensione!" (:49)
  * "Grazie per aver scelto Sanchez Logistica!" (:50), then the footer "Questa risposta
    viene generata automaticamente…" (:52).
* Effect on data: the customer goes back to **NEW**, not DECLINED
  (ls@migration:J/business/service/CustomerService.java:73-75). No code path writes
  `ConfirmationState.DECLINED` (grep).

**L-E5 · Office: new order** (`admin-notification`)
* Same template as L-E2, with "Azione: Nuova richiesta di spedizione in attesa di
  conferma" and "Cliente: {customer e-mail}"
  (ls@migration:J/business/model/enumeration/AdminNotificationEnum.java:16;
  ls@migration:J/business/service/OrderService.java:110).
* No order code: at this point the order only has a `TEMP…` code
  (ls@migration:J/business/service/crud/SpedizioneService.java:84).

**L-E6 · Order approved** (`order-detail`)
* Context: the whole `OrderDetailRepr` (shipment, customer, packages, pallets)
  (ls@migration:J/business/util/EmailReqFactory.java:74-80;
  ls@migration:J/api/repr/OrderDetailRepr.java:21-27).
* Body (ls@migration:T/order-detail.html):
  * h2 "Ti confermiamo la presa in carico dell'ordine N. {codice}" (:46)
  * h1 " Grazie per aver scelto Sanchez Logistica!" (:47)
  * h1 "Dettaglio Spedizione" (:50), with rows "Codice:", "Ragione Sociale:",
    "Indirizzo:", "Provincia:", "Città:", "CAP:", "Telefono:", "Email:" (these are the
    consignee's data), "Importo Contrassegno:", "Importo Assicurato:", "Importo:",
    "Codice Documento Trasporto:", "Servizio Accessorio:", "Note:", "Tracking:" (:53-110)
  * h1 "Dettaglio Ordine" (:116), with "Colli:", "Pallet:", "EPAL:", "Peso:", "Volume:" (:119-136)
  * h1 "Pallets" (:142): one block per physical pallet, with "ID:" (1-based index),
    "Altezza:", "Peso:", "Larghezza:", "Lunghezza:" (:144-165). A line with quantity *n*
    was expanded into *n* pallets at registration
    (ls@migration:J/business/service/OrderService.java:71-79), so it shows *n* identical
    blocks.
  * h1 "I tuoi dati" (:171), with "Ragione Sociale:", "Indirizzo:", "Provincia:",
    "Città:", "CAP:", "Telefono:", "Email:", "Stato:" — the customer's own data (:173-204)
  * "Grazie per aver scelto Sanchez Logistica!" (:206), then the footer (:208).
* No tracking link, and no link at all.
* The customer **sees the amount(s) typed by staff** (§4.2). "Tracking:" prints `PRESA`,
  "Servizio Accessorio:" prints the enum name and "Stato:" prints `REGISTERED`
  (inferred, §1.1).

**L-E7 · Order declined** (generic `error` template)
* Body: the L-E4 template with h2 "Richiesta: Ordine invalido rifiutato
  dall'amministratore" (ls@migration:J/business/util/EmailReqFactory.java:96;
  ls@migration:J/business/model/enumeration/NotificationEnum.java:20).
* The context also carries `codice` set to the order **id**, but the template prints only
  `${request}` (ls@migration:T/error.html:47). **The e-mail never says which order was
  declined.**
* Effect on data: the order is set to state NEW and tracking NON_GESTITO and is kept
  (ls@migration:J/business/service/OrderService.java:140-146).

**L-E8 · Order delivered** (`tracking-state`)
* Trigger: only when EP-14 sets `CONSEGNATO`
  (ls@migration:J/api/controller/CmsController.java:120-121). Setting it again sends the
  e-mail again; there is no guard (inferred). Other tracking states send nothing.
* Body (ls@migration:T/tracking-state.html): the L-E6 template with a different head and
  these changes:
  * h1 "Ti confermiamo l'avvenuta consegna dell'ordine N. {codice}" (:46)
  * h2 "Ricordiamo che è sempre possibile verificare lo stato di qualsiasi spedizione effettuata presso il nostro sito web" (:47)
  * link `https://www.logisticasanchez.com/#tracking`, with text "www.logisticasanchez.com" (:48)
  * h1 " Grazie per aver scelto Sanchez Logistica!" (:49)
  * The row labelled "Importo Assicurato:" prints `importo` (:91-92), and there is no
    "Importo:" row (diff against `order-detail.html`).
  * No closing "Grazie…" paragraph; the footer is centred (:205-206).

**L-E9 / L-E10 · Report** (`email-report`)
* Body (ls@migration:T/email-report.html):
  * icon (:59): a Font Awesome 4 class with Font Awesome 5 CSS, so it does not render (inferred)
  * h1 "Sanchez Logistica - Report ordini PDF" (:60)
  * "Date di riferimento: {from} / {to}" (:61), with ISO dates `yyyy-MM-dd`
  * h2 "Puoi scaricare il report qui: " (:62)
  * button "Scarica PDF" → `reportLink` (:64)
  * "Oppure clicca sul link seguente se sei su dispositivo mobile:" (:68), then a link
    "Scarica PDF" → `reportLinkMobile` (:69)
  * "Grazie per aver scelto Sanchez Logistica!" (:72), then the footer (:74).
* Links (ls@migration:J/business/util/LinkUtil.java:14-22;
  ls@migration:J/business/util/EmailReqFactory.java:109-110, 167-168):
  * button: `https://{origin}/mono/report?email={e}&from={from}&to={to}&onlyDelivered={true|false}`
  * mobile link: `https://{origin}/mono/download-report?…` with the same parameters.
  * No token and no expiry. The PDF is built when the link is clicked, from the data at
    that moment (§3).
* The template reads `${safeLinkMobile}` but the factory sets `safeMobileLink`
  (ls@migration:J/business/util/EmailReqFactory.java:121, 179), so the attribute is left
  out (inferred).
* L-E9 and L-E10 are identical apart from the recipient.

### 1.4 Links in legacy e-mails, and the host they use

| Link | In | Format | Token / expiry | Remarks |
|---|---|---|---|---|
| Verification | L-E1 | `https://{origin}/mono/email-confirm?email=…&verificationCode=…` | UUID v4, no expiry; the GET changes state | `http://` before 2025-11-16 |
| Report landing | L-E9, L-E10 | `https://{origin}/mono/report?email=&from=&to=&onlyDelivered=` | none, no expiry | public (§3.5) |
| Report PDF | L-E9, L-E10 | `https://{origin}/mono/download-report?…` | none, no expiry | public (§3.5) |
| Order portal | L-E3 | `https://ordini.logisticasanchez.com/` | — | S3 website URL before 2025-11-16 |
| Public tracking | L-E8 | `https://www.logisticasanchez.com/#tracking` | — | legacy anchor; covered by PUB-04 |
| Logo | none (the `<img>` is commented out) | `https://{origin}/mono/img/logistica-sanchez-logo.jpg` | — | ls@migration:J/business/util/LinkUtil.java:37-40 |

`{origin}` is `app.email.origin`:

| Profile | Environment key | Default in the repository |
|---|---|---|
| base `application.yml` | `EMAIL_ORIGIN` | `localhost:9050` (ls@migration:R/application.yml:79) |
| prod | `EMAIL_ORIGIN` | `logisticasanchez.com` (ls@migration:R/application.prod.yml:64; ls@migration:docker-compose.yml:83) |
| dev (the former Elastic Beanstalk UAT) | `EMAIL_SENDER_ORIGIN` | `uat-sanchez-logistica.eu-north-1.elasticbeanstalk.com` (ls@migration:R/application-dev.yml:79) |
| local | `EMAIL_ORIGIN` | `localhost:8080` (ls@migration:R/application.local.yml:79) |

* `.env` sets `EMAIL_SENDER_ORIGIN=logisticasanchez.com` (ls@migration:.env:21), but
  docker-compose passes `EMAIL_ORIGIN`, not that key (ls@migration:docker-compose.yml:83)
  (inferred).
* Production runs with profile `prod` (ls@migration:docker-compose.yml:54;
  ls@migration:Dockerfile:62). The repository's file is named `application.prod.yml`
  (dot), which Spring Boot does not load as the profile file. Compose mounts an external
  `./config/application-prod.yml` (ls@migration:docker-compose.yml:99) that is not in
  the repository (inferred). **The production value of `app.email.origin` is (unknown).**
* nginx serves the backend only under `app.logisticasanchez.com`
  (ls@migration:nginx/conf.d/app-https.conf:11, 28, 54), and the public site on the apex
  has no `/mono` route (ls-home@fe4b056: `public/_redirects`, `netlify.toml`). With the
  repository default `logisticasanchez.com`, e-mail links in production would therefore
  answer 404 (inferred).
* The 2024 development logs show report links on the UAT host over plain HTTP
  (ls@migration:logs/archived/spring-boot-logger-2024-08-01.0.log:254).

### 1.5 Mapping to chapter 09 (EML-01…08)

| Legacy | Chapter 09 | Status |
|---|---|---|
| L-E1 verification | EML-01 ("e-mail verification") | **Different.** Legacy has only the verification e-mail: no sign-in link and no e-mail-change e-mail. Its link never expires and confirms on GET. It is re-sent with the same code when the customer registers again. |
| L-E2 office: new customer | EML-06 ("new registration") | **Different trigger.** Sent when the customer clicks the verification link, on every click, not at registration. |
| L-E3 approved | EML-03 | **Confirmed.** Welcome text and order-portal link. |
| L-E4 declined | EML-03 | **Confirmed**, but legacy uses the generic error template with a misleading subject ("Aggiornamento indirizzo email"). |
| L-E5 office: new order | EML-06 ("new order") | **Confirmed.** Carries the customer e-mail only, no code. |
| L-E6 order approved | EML-05 | **Different.** No tracking link. Contains the full order detail: amounts, consignee, packages, pallets and customer data. |
| L-E7 order declined | EML-05 | **Different.** No order code or id in the e-mail. |
| L-E8 order delivered | — (only ENH-05 `tracking.notifyCustomer`, M2) | **Missing from M1.** Parity requires it: add EML-09 "consegna effettuata" in M1, sent by ADM-06 when the state becomes Consegnato. |
| L-E9 report to customer | EML-07 | **Different.** Links instead of an attachment; sent only if `sendToCustomer`; recipient = the customer the report is about. |
| L-E10 report to office | EML-07 | **Different.** The office receives every report. |
| L-E11 free-text e-mail | — | Not needed (an admin test tool, §6). |
| (none) | EML-02 "registration received" | **New in chapter 09.** Legacy sends nothing; after verification the landing page says "Rimani in attesa della email di conferma diretta da parte del nostro centro logistico." (ls@migration:T/email-success.html:40). |
| (none) | EML-04 "order received" (customer) | **New in chapter 09.** Legacy sends nothing to the customer; only L-E5 to the office. |
| (none) | EML-08 quote and contact forms | Not in the backend: the Netlify function `form.mjs` sends them (chapter 06). |

### 1.6 Legacy defects: keep the content, do not reproduce these

* The verification GET changes state, so link scanners can verify an address
  (CUS-01 already fixes this).
* An office e-mail goes out on every verification click, valid or not
  (ls@migration:J/business/service/CustomerService.java:39).
* The order-decline e-mail has no order identifier (ls@migration:T/error.html:47).
* The delivery e-mail labels `importo` as "Importo Assicurato"
  (ls@migration:T/tracking-state.html:91-92).
* Enum names and Java doubles are printed raw (§1.1), and query values are not
  URL-encoded (ls@migration:J/business/util/LinkUtil.java:14-35).
* Template and context variables do not match (`safeLink` in `customer-success`,
  `safeLinkMobile` in `email-report`).
* A failed send aborts, and usually rolls back, the business action (§5.4).
* Approval stores `importoAssicurato` into `importoContrassegno`
  (ls@migration:J/business/service/crud/SpedizioneService.java:166-167, §4.2).

---

## 2. Pages served by the backend

* The context path is `/mono` (ls@migration:R/application.yml:4;
  ls@migration:R/application.prod.yml:43).
* nginx proxies `/mono/` for `app.logisticasanchez.com`
  (ls@migration:nginx/conf.d/app-https.conf:54-74; ls@migration:nginx/conf.d/logistica.conf:57-64)
  and redirects `/` to `/mono/` with a 301
  (ls@migration:nginx/conf.d/app-https.conf:77-79; ls@migration:nginx/conf.d/logistica.conf:74-76).
* Every URL below is public: `anyRequest().permitAll()`. The rule written for
  `/mono/cms/**` would not cover them, and in fact matches nothing, because the context
  path `/mono` is not part of the path Spring Security compares (12a §2.7)
  (ls@migration:J/configuration/security/WebSecurityConfig.java:41-42).
* The links reach these pages on the `{origin}` host of §1.4.

| URL (GET) | Handler | Response | Reached from | Cut-over (proposal) |
|---|---|---|---|---|
| `/mono/email-confirm?email=&verificationCode=` | ls@migration:J/web/WebController.java:34-37 → ls@migration:J/business/service/CustomerService.java:38-41 | HTML: Thymeleaf `email-success` if the code matches a NEW customer (who becomes PENDING), otherwise `error`. Side effect: L-E2 on every hit. | L-E1 | Answer on the apex and `app.` hosts with a page that changes nothing and points to `/area-clienti/` (chapter 02 §10.3). |
| `/mono/report?email=&from=&to=&onlyDelivered=` | ls@migration:J/web/WebController.java:56-68 | HTML: Thymeleaf `report`. `from` and `to` are required strings. | the button in L-E9/L-E10 | Page pointing to `/gestione/` (staff) or `/area-clienti/` (customer). |
| `/mono/download-report?email=&from=&to=&onlyDelivered=` | ls@migration:J/web/WebController.java:39-54 | `application/pdf`, `Content-Disposition: attachment` (§3.4). `from` defaults to `LocalDate.MIN`, `to` to today, `onlyDelivered` to false. | the `report` page; the "mobile" link in L-E9/L-E10 | **Never serve the PDF without authentication.** Show the same page. |
| `/mono/request-success.html` | static file `R/static/request-success.html` | HTML: green check, h1 "Abbiamo inoltrato la tua richiesta." (:39), "Controlla la tua casella postale in attesa di conferma." (:40), "Grazie per aver scelto Sanchez Logistica!" (:41) | no caller found in the backend, `ls-order` or `ls-cms` (grep) | Generic `/mono/**` page (API-03). |
| `/mono/img/logistica-sanchez-logo.jpg` | static file `R/static/img/` (27 808 bytes) | JPEG | no sent e-mail (the `<img>` is commented out) | 410 or the new logo. |
| `/mono/mono/request-success.html`, `/mono/mono/email-success.html`, `/mono/mono/report.html`, `/mono/mono/error.html` | ls@migration:J/configuration/MvcConfig.java:12-15 | The view controllers are registered with a `/mono` prefix *inside* the `/mono` context, so the prefix doubles (inferred). `request-success` has no template (it is static), so that one errors (inferred). The others render without model data. | none | Ignore (the generic page). |

Page content, verbatim:
* `email-success` (ls@migration:T/email-success.html):
  * green check (:38)
  * h1 "Abbiamo verificato il tuo indirizzo mail" (:39)
  * "Rimani in attesa della email di conferma diretta da parte del nostro centro logistico." (:40)
  * "Puoi chiudere questa scheda" (:41)
  * "Grazie per aver scelto Sanchez Logistica!" (:42)
* `error` as the verification failure page: the L-E4 texts
  (ls@migration:T/error.html:45-52). The h2 is empty because `WebController` sets no
  `request` attribute (ls@migration:J/web/WebController.java:36) (inferred). The page says
  nothing about why the link failed.
* `report` (ls@migration:T/report.html):
  * h1 "Sanchez Logistica - Report ordini PDF" (:60)
  * "Date di riferimento: {from} / {to}" (:61)
  * h2 "Scarica il report qui: " (:62)
  * button "Scarica" → the `/mono/download-report` link for the same parameters (:64)
  * "Grazie per aver scelto Sanchez Logistica!" (:69); the footer is commented out (:70-72).
  * Bug: the page's `safeLink` is built by concatenating the `LinkUtil` *object*
    (ls@migration:J/web/WebController.java:65). The 2024 development logs show
    `data-saferedirecturl="https://www.google.com/url?q=it.logisticasanchez.mono.business.util.LinkUtil@…"`
    (ls@migration:logs/archived/spring-boot-logger-2024-08-25.0.log:1351).
* `report-download.html` is a dead template: nothing references it (grep). It has
  * title "Download PDF" (:5), h1 "Logistica Sanchez - Report PDF" (:58)
  * h3 "Scarica il report degli ordini in formato PDF" (:59), legend "Report mensile" (:62)
  * fields "Indirizzo mail" (:64) and "Mese di riferimento" (:67-68), button "Download PDF" (:70)
  * a form that GETs `/download-pdf` (:60), a path no controller maps.

Error responses:
* Exceptions thrown in any controller, `WebController` included, become JSON
  `{reason, detail}` through the `@RestControllerAdvice`, not the HTML error page
  (ls@migration:J/api/error/ErrorController.java:22-33).
* Unknown paths under `/mono` answer **500 JSON**. Spring 6.1's
  `NoResourceFoundException` is caught by the generic handler (inferred). A 2024 log
  shows an unknown path answered 500 "No static resource …"
  (ls@migration:logs/archived/spring-boot-logger-2024-07-18.0.log:752-761).

Hosts that appear in backend code and must be listed for the cut-over:
* `app.logisticasanchez.com` (nginx)
* the `{origin}` host of §1.4
* `ordini.logisticasanchez.com` (portal, L-E3)
* `dashboard.logisticasanchez.com`: the CMS URL in
  ls@migration:J/business/util/LinkUtil.java:28-30. It appears in no e-mail but is new
  information for the cut-over.
* `www.logisticasanchez.com/#tracking` (L-E8)
* the S3 website endpoints of the portal and the CMS (in e-mails sent before 2025-11-16)
* `uat-sanchez-logistica.eu-north-1.elasticbeanstalk.com` (dev profile and old e-mails;
  §7, Q-30).

---

## 3. The report

### 3.1 Request

* Endpoint: `POST /mono/cms/order/report` with body `ReportReq`:
  * `email`: required, valid address, max 255
  * `from`: optional, `@PastOrPresent`
  * `to`: optional, unconstrained
  * `sendToCustomer`: optional Boolean
  * `onlyDelivered`: optional Boolean
  (ls@migration:J/api/req/ReportReq.java:20-31; ls@migration:J/api/controller/CmsController.java:79-80)
* Defaults: no `from` means `LocalDate.MIN` (printed "-999999999-01-01"); no `to` means
  today; no `sendToCustomer` means false (ls@migration:J/api/controller/CmsController.java:86-88).
  `onlyDelivered` gets **no** default (:84).
* Nothing checks that `from` ≤ `to`, or that `email` belongs to a customer.
* Access: **none**. The method has no `@PreAuthorize`, and the URL rule on `/mono/cms/**`
  (ls@migration:J/configuration/security/WebSecurityConfig.java:41) never matches because
  of the context path, so anyone can call it (12a §2.7–2.8, SEC-05). *Corrected after
  review: an earlier draft of this note read the URL rule as effective.*
* Answer: 200 with an empty body; the PDF is not returned
  (ls@migration:J/api/controller/CmsController.java:108). The code that returned it is
  commented out (:91-93).
* What the clients send:
  * `ls-cms@dev` sends `{email, from, to}` only (ls-cms@dev:js/rest/rest-caller.js:109-116).
  * The developer's Postman request sends `{email, from, to, sendToCustomer: true, onlyDelivered: true}`
    (ls@migration:api/sanchez-logistica.postman_collection.json:2166).
  * Both flags were added on 2024-08-25 (`git log -S`: commits `96a8b25` and `3bda6ad`),
    after the last commit of `ls-cms@dev` (2024-06-06).

### 3.2 What it selects

* Customer: the sender whose e-mail equals `email`
  (ls@migration:J/business/service/crud/SpedizioneService.java:210). **`email` is both
  the customer filter and, for L-E9, the recipient.**
* Orders: state **REGISTERED** (approved) only. Tracking is any state, or only
  `CONSEGNATO` when `onlyDelivered` is true
  (ls@migration:J/business/service/crud/SpedizioneService.java:216-223;
  ls@migration:J/persistence/repository/SpedizioneRepository.java:68).
* Period: by the order's **creation time** `createTms`, from `from` 00:00 UTC to `to`+1
  day 00:00 UTC, so `to` is inclusive
  (ls@migration:J/business/service/crud/SpedizioneService.java:212-215). UTC, not
  Europe/Rome: an order created between 00:00 and 01:00 (02:00 in summer) Italian time
  falls on the previous day (inferred).
* Order of rows: the repository's (no `ORDER BY`) (inferred).

### 3.3 Delivery

* The report is delivered **only by e-mail links**: L-E10 always goes to the office, and
  L-E9 to the customer when `sendToCustomer` (ls@migration:J/api/controller/CmsController.java:104-106).
* Never as an attachment: SendEmail cannot attach, and the attachment variant is unused
  (§1.2).
* The PDF is built when `GET /mono/download-report` is called
  (ls@migration:J/web/WebController.java:39-54), so its content reflects the data at
  click time, not at request time.

### 3.4 PDF format (`HorizontalPdfGenerator`, iText 2.1.7)

* Library: `com.lowagie:itext:2.1.7` (ls@migration:pom.xml:143-145). PDFBox and
  flying-saucer are declared but unused (ls@migration:pom.xml:132-141; grep).
* Page: **A4 landscape** (`PageSize.A4.rotate()`).
* Metadata: author and creator "Sanchez Logistica", title "Sanchez Logistica - Report",
  creation date (ls@migration:J/business/util/HorizontalPdfGenerator.java:26, 35-38).

Blocks, top to bottom (ls@migration:J/business/util/HorizontalPdfGenerator.java:41-44):

1. **Fixed text**, Helvetica 6 pt (:55-70), verbatim:
   ```
   c/a MB208 MONZABRIANZA1 Allegato fatturazione direzionale
   Spett.le
   LOGISTICA SANCHEZ   VIA DEI MEIANI, 90   20831 SEREGNO [MB]
   ```
   It reads like the header of a partner network's billing annex *addressed to*
   Logistica Sanchez, and it is printed even on reports meant for customers (inferred).
   "MB208 MONZABRIANZA1" looks like a network depot or account code: treat it as
   confidential (inferred).
2. **"I tuoi dati"**: a two-column table (30/70), full width, 10 pt spacing above,
   Helvetica Bold Oblique 8 pt. A light-grey header cell spans both columns. Rows
   "Ragione Sociale:", "Indirizzo:", "Provincia:", "Città:", "CAP:", "Email:" hold the
   **customer's** data; there is no phone (:72-119; ls@migration:J/business/factory/ReportFactory.java:56-65).
3. An empty paragraph.
4. **Order table**: 13 columns of equal width, full width, Helvetica Bold 8 pt
   (:121-222). The header row is dark grey for "Codice" and light grey for the others.
   The "Codice" cell of each row is light grey. The header is not repeated on new pages
   (no `setHeaderRows`, inferred).

| # | Header (verbatim) | Value | Source |
|---|---|---|---|
| 1 | Codice | shipment code | ls@migration:J/business/factory/ReportFactory.java:39 |
| 2 | Codice Tras. | transport document code, `-` if empty | :40 |
| 3 | Dt/Sped. | **approval time** (`confirmationTs`) as an ISO date `yyyy-MM-dd` in Europe/Rome | :37; ls@migration:J/business/util/TemporalUtil.java:12-18 |
| 4 | Dt/Cons. | `shippingTs` after adjustment. Not yet delivered: the last update time (`updateTms`). Delivered: the time the order first left PRESA, **not the delivery time**. Fallback: `confirmationTs`. ISO date. | ls@migration:J/business/service/crud/SpedizioneService.java:224-229; ls@migration:J/business/factory/ReportFactory.java:38 |
| 5 | Servizio | accessory-service **label**, e.g. "Nessun servizio accessorio" | :41 |
| 6 | Destinazione | `"{indirizzo}({città})"`, `-` for missing parts | :42-43 |
| 7 | Cliente | the **consignee's** name, not the customer's | :44 |
| 8 | Cap | consignee CAP | :45 |
| 9 | Città | `"{città}({provincia})"`; `.` if the city is missing, `-` if the province is missing | :46-47 |
| 10 | Colli | number of packages if stored, otherwise the number of `PALLET`-type pallets (EPAL not counted) | :48 |
| 11 | Peso | total kg (sum of the per-pallet weights), printed as a Java double (`120.0`) | :49; ls@migration:J/business/service/OrderService.java:86-88 |
| 12 | Volume | total m³ **rounded to a whole number** at order registration, printed as a double (`2.0`) | :50; ls@migration:J/business/service/OrderService.java:89-94 |
| 13 | Importo | `importo`; falls back to `importoAssicurato` when `importo` is null and tracking ≠ PRESA. Printed as a double (`51.75`), no €, dot decimal. | :51; ls@migration:J/business/service/crud/SpedizioneService.java:194 |

* No totals row, no page numbers, no logo, and **no date range in the PDF**: the range
  appears only in the e-mail and in the file name.
* File name: `{local part of the e-mail}-{dd_MM_yyyy}_{dd_MM_yyyy}.pdf`, e.g.
  `ufficio-01_06_2024_30_06_2024.pdf` (ls@migration:J/business/util/ReportUtil.java:113-127;
  ls@migration:J/web/WebController.java:49-51).
* Dead alternative: `ReportUtil.report(...)` (portrait-style blocks "Report Ordini -
  Logistica Sanchez", "Data inizio: …", "Data fine: …"). Nothing calls it
  (ls@migration:J/business/util/ReportUtil.java:30-111; ls@migration:J/business/service/ReportService.java:39).
* If iText throws a `DocumentException`, the error is only printed and the method returns
  whatever bytes were written (inferred, ls@migration:J/business/util/HorizontalPdfGenerator.java:48-50).

### 3.5 Access control and data protection

* **`GET /mono/download-report` and `GET /mono/report` need no token, carry no
  signature and never expire** (ls@migration:J/configuration/security/WebSecurityConfig.java:41-42;
  ls@migration:J/web/WebController.java:39-68). Anyone who knows or guesses a registered
  customer's e-mail address can download that customer's report for any period
  (inferred). The PDF holds the customer's details, every consignee's name, address and
  CAP, and the weights, volumes and amounts.
* The confirmation e-mails therefore leak a durable capability URL, and so does any
  forwarded or logged copy of them.
* `RequestResponseLoggingFilter` logs every request and response body at INFO
  (ls@migration:J/configuration/RequestResponseLoggingFilter.java:39, 44;
  ls@migration:J/configuration/LogConfig.java:12-16). Generated PDFs therefore end up in
  the application logs: the committed development logs contain 13 PDF bodies
  (`grep -a -c "%PDF-"`).

### 3.6 Failure modes

* **With the CMS of the repository the report cannot work against `ls@migration`.**
  `ls-cms@dev` omits `onlyDelivered`, the null `Boolean` is passed to `LinkUtil`'s
  primitive `boolean` parameter, and unboxing throws `NullPointerException`
  (ls@migration:J/business/util/EmailReqFactory.java:167;
  ls@migration:J/business/util/LinkUtil.java:19). The result is a 500 GENERIC_500, no
  e-mail, and the CMS shows "Dati non validi" (inferred). Whether the production CMS is a
  newer, unpublished build that sends the flag is (unknown) (CU-21).
* **Download validation.** `HorizontalPdfGenerator` is `@Validated` and its parameter is
  `@Valid` (ls@migration:J/business/util/HorizontalPdfGenerator.java:22, 25). Every row
  must therefore satisfy `ReportRepr.ReportRow`
  (ls@migration:J/api/repr/ReportRepr.java:42-75): non-null past dates, non-empty
  strings, `peso` > 0, non-null `volume` and `importo`. The customer block must have
  non-empty fields (:23-36). A single bad row — for example an order approved without an
  amount or a timestamp, or a customer without a province — makes the **whole download
  fail with 400** "Richiesta non valida…" (inferred;
  ls@migration:J/api/error/ErrorController.java:45-53).
  * 2024 development logs show download 400s ("Validation failure") in an older build
    (ls@migration:logs/archived/spring-boot-logger-2024-08-25.0.log:1364-1368).
* An e-mail that is not a customer's: the report e-mails still go out, and the download
  then answers 404 "Impossibile recuperare i dati richiesti." (inferred;
  ls@migration:J/business/service/crud/SpedizioneService.java:210;
  ls@migration:J/api/error/ErrorController.java:71-77).
* A future `from` answers 400, at the API (`ReportReq`) and at the download link alike.

### 3.7 Answer to Q-08 (and CU-12)

* **Content:** the approved orders of one customer, created between `from` and `to`
  (inclusive, UTC days), optionally only delivered ones. One row per order, 13 columns
  (§3.4), plus the customer's data. No internal notes and no margins.
* **Format:** PDF, A4 landscape, iText table. Not XLSX.
* **Delivery:** e-mail with links, never an attachment. The office always receives it;
  the customer receives it only when `sendToCustomer=true`.
* **"Email Cliente"** is **both** the customer filter and, only with `sendToCustomer`,
  the recipient.
* **Is `to` inclusive?** Yes.
* **Still unknown:** whether the production CMS sends `sendToCustomer` and `onlyDelivered`.
  If it does not, the feature fails (§3.6).

---

## 4. Pricing logic in the backend

### 4.1 Verdict

**The backend computes no price today.**
* `TariffarioConst`, `TariffarioClienteTrait`, `TariffarioRegionaleTrait`, `EvasioneEnum`
  and `PalletEnum` are declared, and no other class references them. Their only other
  appearance is a copy-paste return type in `AdminNotificationEnum.fromString`, which
  returns an `EvasioneEnum` (ls@migration:J/business/model/enumeration/AdminNotificationEnum.java:26-34).
* `EnumController` exposes none of them
  (ls@migration:J/api/controller/EnumController.java:25-67).
* There is no VAT, currency, rounding or discount code: no `iva`, `vat`, `imponibile`,
  `BigDecimal`, `RoundingMode` or number formatting anywhere in `src/main` (grep).
* The only computations close to pricing:
  * total weight, the sum of the expanded per-pallet weights
    (ls@migration:J/business/service/OrderService.java:86-88)
  * total volume, Σ h·l·w / 1 000 000 **rounded to a whole m³** with `Math.round`
    (ls@migration:J/business/service/OrderService.java:89-94;
    ls@migration:J/business/util/MathUtil.java:5-7)
  * counts of PALLET and EPAL (ls@migration:J/business/service/OrderService.java:80-85).
  * Stored on the order's package detail. Volumes under 0.5 m³ become 0 (inferred).

### 4.2 The manual amount (what M1 must keep)

* Columns: `importoContrassegno`, `importoAssicurato` and `importo` are nullable `Double`
  (floating point) (ls@migration:J/persistence/model/SpedizioneEntity.java:50-55).
* At approval (EP-11), `ls@migration` **requires `req.importo`**, together with `codice`
  and `tracking`, through `Objects.requireNonNull`
  (ls@migration:J/business/service/crud/SpedizioneService.java:152-154). This check was
  added on 2024-07-16 (`git log -S`: commit `d7732eb`).
* Validation: every amount is `@Positive` and validated, so **0 and negatives are
  refused** with 400 (ls@migration:J/api/req/crud/PatchSpedizioneReq.java:21-28;
  ls@migration:J/api/req/OrderConfirmationReq.java:22).
* Amounts are JSON numbers. A comma text such as `"180,50"` cannot be converted to
  `Double` and lands in the generic 500 handler (inferred).
* Further rules at approval:
  * The code must be unique: 409 CODE_409
    (ls@migration:J/business/service/crud/SpedizioneService.java:163;
    ls@migration:J/persistence/model/SpedizioneEntity.java:18).
  * The order must be PENDING (:164).
  * The order becomes REGISTERED with tracking PRESA, and the approval time is stamped (:168-173).
* Bug: `importoContrassegno` is overwritten with `importoAssicurato`
  (ls@migration:J/business/service/crud/SpedizioneService.java:166-167), so the cash on
  delivery value is never stored from the request.
* Fallback: when `importo` is null and tracking ≠ PRESA, the order detail (and thus
  L-E6, L-E8 and the report) shows `importoAssicurato` as `importo`
  (ls@migration:J/business/service/crud/SpedizioneService.java:194). The similar
  "temp code" in `SpedizioneFactory` is dead: it is overwritten two lines later
  (ls@migration:J/business/factory/SpedizioneFactory.java:29-38).
* Who sends what:
  * `ls-cms@dev` sends the typed text as `importoAssicurato` (chapter 07 §1.1). Against
    `ls@migration` that means approval fails with NPE → 500 (inferred), unless production
    runs a newer CMS (unknown, CU-21).
  * The developer's Postman request sends `"importo": 300.00` and comments out the other
    two (ls@migration:api/sanchez-logistica.postman_collection.json:1924).
* `POST /mono/cms/order/update` can later change any amount, code, state or tracking,
  with no e-mail and no audit (ls@migration:J/api/controller/CmsController.java:146-151;
  ls@migration:J/business/service/OrderService.java:205-211). It is Postman-only (§6).
* **Customers see the amount.** L-E6 shows "Importo Contrassegno", "Importo Assicurato"
  and "Importo" (ls@migration:T/order-detail.html:85-95). L-E8 shows `importo` under
  "Importo Assicurato" (ls@migration:T/tracking-state.html:91-92). The PDF has the
  "Importo" column.

### 4.3 `TariffarioConst` compared with the MOSCA list (chapter 07 §2.3, §4)

| Constant (ls@migration:J/business/model/constant/TariffarioConst.java) | Value | MOSCA cell | Same? |
|---|--:|---|---|
| `DEPOSITO_GIORNALIERO` (:9) | 7 | A28 "Deposito merce: €7 a bancale al giorno." | same number |
| `COLLI_BANCALE` (:10) | 22 | A41 "Ogni 22 colli per spedizone a singolo destinatario fare  bancale." | same |
| `PESO_MINIMO` (:12) / `PREZZO_MINIMO` (:15) | 10 / 6 | A43 "Presa in carico minimo 10kg = 6€" | same |
| `PESO_PREMIUM` (:13) / `PREZZO_PREMIUM` (:16) | 50 / 9 | A43 "ogni 50KG=9€" | same |
| `SOGLIA_SCONTO_MINIMO` (:18) / `PERCENTUALE_SCONTO_MINIMO` (:21) | 8000 / 3 | A55 (> €8.000 → 3 %) | same |
| `SOGLIA_SCONTO_PREMIUM` (:19) / `PERCENTUALE_SCONTO_PREMIUM` (:22) | 16000 / 5 | A56 (> €16.000 → 5 %) | same |

* Not among the constants: P/V 300 (A43), which is instead a per-customer value
  `rapportoPesoVolume()` (§4.4); the 3 kg minimum taxable weight (A43); the 25 kg
  maximum per parcel (A41); parcels only to Lombardia and Veneto (A45); payment terms (A58).
* The names (MINIMO and PREMIUM pairs) suggest the developer read A43 as two weight
  tiers, 10 kg → 6 € and 50 kg → 9 €, and A55/A56 as two tiers. That is consistent with
  the defaults of PR-07 and PR-09 but does not settle them (inferred). The values are
  `Integer`, with no unit, scope or rule attached.
* The constants are integers with no customer or validity attached. They look like
  MOSCA 2024 copied into code, not a generic configuration (inferred).

### 4.4 Tariff traits compared with chapter 07 §6.2

* `TariffarioRegionaleTrait`: `regione()`, `mqp()`, `qp()`, `elp()`, `hp()`, `ulp()`,
  `lp()`, `fp()` (all `Double`) and `evasione()` (`EvasioneEnum`)
  (ls@migration:J/business/model/trait/tariffario/TariffarioRegionaleTrait.java:8-16).
  This is exactly one MOSCA row: region × the seven classes + "TEMPO DI RESA".
* `TariffarioClienteTrait`: `tariffeRegionali()` (a list of the above),
  `servizioAccessorio()` and `rapportoPesoVolume()`
  (ls@migration:J/business/model/trait/tariffario/TariffarioClienteTrait.java:10-12).
  That means a per-customer list, a per-list P/V ratio (as chapter 07 §2.5 issue 12
  proposes) and one accessory service per list, whose meaning is (unknown); perhaps the
  included service.
* No implementation, persistence or table exists (no entity or repository).

### 4.5 `EvasioneEnum` compared with "TEMPO DI RESA"

Values (ls@migration:J/business/model/enumeration/EvasioneEnum.java:15-20):

| Constant | Label |
|---|---|
| `ESPRESSO_PLATINUM` | "24" |
| `ESPRESSO_GOLD` | "24/48" |
| `ESPRESSO_SILVER` | "48" |
| `ESPRESSO` | "48/72" |
| `STANDARD` | "72" |
| `HEAVY` | "72/96" |

* All five MOSCA values (24, 24/48, 48/72, 72, 72/96) are present. "48" is extra: it
  matches only the new site's "48 ore" zone.
* The tier names are new information: neither chapter 07 nor the site uses them.
* Unused, so they settle nothing for Q-14.

### 4.6 `PalletEnum` compared with the MOSCA class image (chapter 07 §3.1)

Values (ls@migration:J/business/model/enumeration/PalletEnum.java:15-21): label, height,
width, depth, weight.

| Code | Label | Backend height / footprint / weight | Image (chapter 07) | Match |
|---|---|---|---|---|
| MQP | Mini Quarter Pallet | 60 cm / 100×120 / 150 kg | 60 / 100×120 / 150 | yes |
| QP | Quarter Pallet | 100 / 100×120 / 300 | 100 / 300 | yes |
| ELP | Extra Light Pallet | 150 / 100×120 / 450 | 150 / 450 | yes |
| HP | Half Pallet | 150 / 100×120 / **150 kg** | 150 / **600 kg** | **weight differs** |
| ULP | Ultra Light Pallet | **150 cm** / 100×120 / 350 | **240 cm** / 350 | **height differs** |
| LP | Light Pallet | **150 cm** / 100×120 / 750 | **240 cm** / 750 | **height differs** |
| FP | Full Pallet | **150 cm** / 100×120 / 1200 | **240 cm** / 1200 | **height differs** |

* The backend values look like unfinished placeholders: from ELP onward every height is
  150, and HP repeats MQP's weight (inferred).
* Keep the image as the reference, but add the discrepancy to PR-02. It also changes the
  "covers" relation of chapter 07 §3.2 if anyone adopts the backend values.

### 4.7 RKS cost model

* Absent from the backend. None of the RKS constants (200 kg/m³, 1.10 €, 0.0135 €/kg,
  1.90 €, 0.0315 €/kg, the hub handling charges, the 1100 kg and 220/240 cm limits)
  appears in `src/main` (grep).
* Chapter 07 §5 stands unchanged: the cost model lives only in the Excel file.

---

## 5. The SES integration

### 5.1 `aws-ses-ms`

* `git ls-remote https://github.com/FrancescoCorbosiero/aws-ses-ms` returns **no refs**,
  and a clone reports "You appear to have
  cloned an empty repository" (aws-ses-ms@(no ref)). The session's repository service
  confirms that it is public. **It contains no code, API, templates or configuration to
  document.**
* No branch of `ls` calls an HTTP mail service. A search of every branch for `ses-ms`,
  `RestTemplate`, `WebClient`, `HttpClient` and `Feign` found only the Maven wrapper.
* The last commit, "http ses fix" (`ca1b0d0`), only changed e-mail **links** from
  `http://` to `https://` and replaced the S3 portal and CMS URLs with `ordini.` and
  `dashboard.`. It is not an HTTP integration (`git show ca1b0d0`).

### 5.2 The client

* Library: AWS SDK for Java **v1**, `com.amazonaws:aws-java-sdk-ses:1.12.341`
  (ls@migration:pom.xml:69-73).
* Bean: `AmazonSimpleEmailServiceClientBuilder.standard()` with
  `AWSStaticCredentialsProvider` built from `app.aws.accessKey` and `app.aws.secretKey`
  (long-lived IAM keys) (ls@migration:J/configuration/AwsMailConfig.java:20-26).
* Region: hard-coded `Regions.EU_NORTH_1` (ls@migration:J/configuration/AwsMailConfig.java:25).
  The `cloud.aws.region.static: eu-north-1` keys belong to `spring-cloud-starter-aws`
  2.2.6, a library built for Spring Boot 2, which is probably inert on Boot 3.2 (inferred;
  ls@migration:pom.xml:74-78; ls@migration:R/application.yml:48-57).
* `spring-boot-starter-mail` and `javax.mail-api` are on the classpath
  (ls@migration:pom.xml:26-29, 120-121), but there is no `spring.mail.*` configuration
  and nothing uses the `javaMailSender` bean.
* Not used: configuration sets, message tags, Reply-To, display names, the SNS
  bounce/complaint pipeline, templates stored in SES.

### 5.3 Configuration keys (names only)

| Property | Environment variable | Where |
|---|---|---|
| `app.aws.accessKey` | `AWS_ACCESS_KEY` | ls@migration:J/configuration/property/AwsProperties.java:8-12; ls@migration:R/application.yml:75; ls@migration:docker-compose.yml:78; ls@migration:.env:16 |
| `app.aws.secretKey` | `AWS_SECRET_KEY` | ls@migration:R/application.yml:76; ls@migration:docker-compose.yml:79; ls@migration:.env:17 |
| `app.email.author` | `EMAIL_AUTHOR` | ls@migration:J/configuration/property/EmailProperties.java:11; ls@migration:R/application.yml:78; ls@migration:.env:20 |
| `app.email.origin` | `EMAIL_ORIGIN` (`EMAIL_SENDER_ORIGIN` in the dev profile and in `.env`) | ls@migration:J/configuration/property/EmailProperties.java:12; ls@migration:R/application.yml:79; ls@migration:R/application-dev.yml:79; ls@migration:.env:21 |
| `app.email.enableAutomaticEmails` | `ENABLE_AUTOMATIC_EMAILS` | ls@migration:J/configuration/property/EmailProperties.java:13; ls@migration:R/application.yml:80; ls@migration:.env:22 |
| hard-coded | the office recipient (§1.1) and the SES region (§5.2) | — |

### 5.4 Error handling, transactions, retries

* `AwsEmailService` catches nothing. SES exceptions reach the
  `@RestControllerAdvice` (ls@migration:J/api/error/ErrorController.java:79-93):
  * `MessageRejectedException` → 500 "Indirizzo email di destinazione non valido.", which
    is misleading because SES also rejects unverified senders
    (ls@migration:J/api/error/ErrorEnum.java:20)
  * any other `AmazonSimpleEmailServiceException` → 500 "Qualcosa è andato storto. Si
    prega di riprovare oppure contattare il centro logistico." (ls@migration:J/api/error/ErrorEnum.java:19)
  * network errors (`SdkClientException`) → the generic 500.
* **The send happens inside the business transaction.** The methods are annotated with
  `jakarta.transaction.Transactional`, and Spring rolls back on a runtime exception, so an
  SES failure **rolls back** the business change and the caller gets a 500 (inferred).
  This applies to:
  * customer registration (ls@migration:J/business/service/CustomerService.java:47-55)
  * customer approval and decline (:57-62, :70-77)
  * order registration, where an office notification failure **loses the order**
    (ls@migration:J/business/service/OrderService.java:53-112)
  * order approval and decline (:114-121, :135-152).
  * Conversely, if the commit fails after the send, the e-mail has already gone out for a
    change that was not saved (inferred).
* Not transactional:
  * `emailConfirm`: L-E2 is sent first, and if it fails the verification never happens
    and the browser gets JSON instead of a page (inferred).
  * `trace`: the tracking change is committed before L-E8 is attempted.
  * `report`: nothing to roll back.
* Validation is bypassed on the automatic path. `EmailNotificationReq.content` has
  `@Size(max = 255)` (ls@migration:J/api/req/EmailNotificationReq.java:28-30), but
  automatic e-mails call `this.sendHtml(req)` from inside the bean, which skips the proxy
  and so skips method validation (ls@migration:J/business/service/AwsEmailService.java:129, 134).
  Only `/sendEmail` enforces the 255-character limit.
* Retries: none in the application code. The SDK v1 default retry policy (up to 3 retries
  with backoff on throttling, 5xx and I/O errors) applies, because no `ClientConfiguration`
  is set (inferred). There is no outbox, dead-letter or resend.
* The 2024 development logs show an SES failure "The security token included in the
  request is invalid" (403, `InvalidClientTokenId`) turned into a JSON error
  (ls@migration:logs/archived/spring-boot-logger-2024-07-18.0.log:198).

### 5.5 Secrets hygiene (urgent)

* `application-dev.yml` and `application.local.yml` hold **literal default values shaped
  like a real AWS key pair**: an access key id of 20 characters starting `AKIA` and a
  40-character secret (ls@migration:R/application-dev.yml:75-76;
  ls@migration:R/application.local.yml:75-76). The values are not reproduced here.
* The base `application.yml` holds placeholder-like defaults.
* Literal defaults also exist for the database password and the JWT key in every profile
  except prod. Only the key names were inspected.
* Treat the key pair as compromised: deactivate and rotate it in IAM, and check
  CloudTrail and the SES sending statistics for its use. This adds a concrete item to the
  credential rotation of MIG-03.
* The new app uses an instance role, as chapter 10 already requires.

---

## 6. Admin tools

All of them are `@PreAuthorize("hasRole('ADMIN')")`
(ls@migration:J/configuration/constant/MonoConstants.java:6). Method security (`@EnableMethodSecurity`)
is the only guard, here as everywhere: the `/mono/cms/**` URL rule matches nothing (12a §2.7) (ls@migration:J/configuration/security/WebSecurityConfig.java:27, 41-42). No
frontend calls these endpoints: they appear only in the Postman collection
(ls@migration:api/sanchez-logistica.postman_collection.json, folder "Admin Tools" and the
"CMS" items). No admin tool writes an audit record or sends an e-mail.
`AwsEmailService` is injected into `AdminToolsController` and `AdminToolsService` but
never used.

| Endpoint | Body | Effect | Risk |
|---|---|---|---|
| `POST /mono/utils/order/delete` (ls@migration:J/api/controller/AdminToolsController.java:31-36) | `{email, id}` | Finds the customer by e-mail (500 if unknown) and the order with that id **among that customer's orders** (500 if not found). Hard-deletes its pallets, package detail and the order; returns `[codice]` (ls@migration:J/business/service/AdminToolsService.java:83-109). | Irreversible, no soft delete, no audit. |
| `POST /mono/utils/order/deleteAll` (:38-43) | `{email, state?}` | Hard-deletes **all** the customer's orders, or only those whose `stato` equals `state`; returns the codes (ls@migration:J/business/service/AdminToolsService.java:111-135). | Mass irreversible deletion, no confirmation. |
| `POST /mono/utils/order/deleteBeforeDate` (:45-50) | `{email, before}` | Meant to hard-delete the customer's **delivered** orders (`deliveryTs` set) approved before `before` 00:00 Europe/Rome (ls@migration:J/business/service/AdminToolsService.java:62-81). Probably broken: `@PastOrPresent` sits on the request object (:63), so Hibernate Validator throws `UnexpectedTypeException` on every call and the answer is 500 (inferred). If that were fixed, any order without `confirmationTs` would still cause an NPE (:68), and a null `before` too. | Irreversible deletion of accounting data (NFR-03 requires anonymisation). |
| `POST /mono/utils/customer/delete` (:52-57) | `{email}` | Hard-deletes the customer and all their orders, pallets and package details; returns the deleted customer (ls@migration:J/business/service/AdminToolsService.java:45-60). | GDPR erasure done as a cascade delete; loses shipments that must be kept. |
| `POST /mono/sendEmail` (ls@migration:J/api/controller/CmsController.java:44-49) | `{fromAddress, toAddress, subject, content}`, each 1–255 characters (ls@migration:J/api/req/EmailNotificationReq.java:16-30) | Sends a **plain-text** e-mail through SES with any sender (it must be an SES-verified identity) and any recipient (ls@migration:J/business/service/AwsEmailService.java:36-50). | Free-form mail from the company's SES identity; a phishing vector if an admin token leaks. The Postman sample uses a `text` key instead of `content`, so it would answer 400 (inferred; ls@migration:api/sanchez-logistica.postman_collection.json:1537). |
| `POST /mono/cms/order/update` (ls@migration:J/api/controller/CmsController.java:146-151) | `{id, req: PatchSpedizioneReq}` | Patches any order field: code (409 if taken), the three amounts, accessory service, note, state, tracking, timestamp flags (ls@migration:J/business/service/OrderService.java:205-211; ls@migration:J/business/factory/SpedizioneFactory.java:83-109). | Bypasses the approval flow and its e-mails; edits amounts silently. Not called by `ls-cms@dev`. |
| `POST /mono/cms/order?email=` (ls@migration:J/api/controller/CmsController.java:139-144) | query `email` | Lists all orders of one customer (read-only). | Not called by `ls-cms@dev`. |

Recommendation for the new app: do not port these endpoints. ADM-01's roles, the audit
log, anonymisation (NFR-03) and the back office cover the legitimate needs; the TODO
"outdated orders and declined cleanup" (ls@migration:J/api/controller/AdminToolsController.java:59)
belongs to the retention jobs of Q-18.

---

## 7. Reconciliation with chapters 07, 09 and 11

### Chapter 07 (pricing)

| Item | Status | Detail |
|---|---|---|
| §1.1 "The raw text is sent as `importoAssicurato`" | **Corrected (backend side)** | `ls@migration` requires `req.importo` (> 0, a number) at approval and stores it in `importo` (§4.2). `importoAssicurato` is optional, and the backend copies it into `importoContrassegno`. The CMS in the repository predates this contract; the production CMS build is (unknown, CU-21). |
| §1.1 "Customers never see a price" | **Corrected** | They see the amount in the approval e-mail L-E6, in the delivery e-mail L-E8 and in the PDF report (§4.2). The order *portal* shows no price: that part stays true. |
| §1.1 M1 parity "required, free amount" | **Refined** | Legacy accepts only a positive JSON number (0 is refused) and stores a `Double`. Keep "required, > 0"; whether a 0 amount is allowed in the new app is an owner decision. |
| §1.3 "Price lists and costs live in Excel" | **Confirmed** | The backend computes no price (§4.1). |
| §2.3 notes A28, A41, A43, A55, A56 | **Confirmed** | `TariffarioConst` has the same numbers (§4.3). |
| §2.2 / §2.4 "TEMPO DI RESA" | **Confirmed + new** | `EvasioneEnum` contains all values plus "48" and names the tiers (Espresso Platinum/Gold/Silver, Espresso, Standard, Heavy) (§4.5). |
| §3.1 class limits (image) | **Conflict found** | The backend's `PalletEnum` says HP 150 kg and ULP/LP/FP 150 cm (§4.6). Keep the image and add the discrepancy to **PR-02**. |
| §5 RKS | **Confirmed** | Not in the backend (§4.7). |
| §6.2 data model | **Supported** | The backend traits sketch the same structure: region × 7 classes + transit time per row, a per-customer list with a P/V ratio and an accessory service (§4.4). |
| PR-07 parcels / PR-09 discounts / PR-14 storage | **Still open** | The constant names suggest weight tiers and separate discount tiers, but carry no rule (§4.3). |

### Chapter 09 (features)

| Item | Status | Detail |
|---|---|---|
| EML preamble: "Which ones the legacy backend sends … is unknown (Q-07)" | **Corrected** | Replace with §1.2–1.3. Legacy facts: HTML only, no Reply-To, sender `servizioclienti@…`, office recipient a hard-coded Gmail address, subjects prefixed "Sanchez Logistica - ". |
| EML-01 | **Different** | Verification only; permanent link; GET side effect (§1.5). |
| EML-02 | **No legacy e-mail** | New; the legacy page text is in §2. |
| EML-03 | **Confirmed** | With L-E3/L-E4 wording. |
| EML-04 | **No legacy e-mail** | New. |
| EML-05 | **Different** | The approval e-mail has full detail and amounts but no tracking link; the decline e-mail has no order id. |
| EML-06 | **Confirmed / different trigger** | The new-customer notice is sent at e-mail verification, not at registration. |
| EML-07 | **Different** | Links, not an attachment; the office always receives it; the customer only optionally. |
| EML-08 | Not a backend e-mail | — |
| **Missing** | **Add EML-09 (M1)** | "Consegna effettuata per ordine N. {codice}" to the customer when tracking becomes Consegnato (L-E8). Chapter 09 has it only as ENH-05 (M2). ADM-06 must trigger it. |
| ADM-02 / ADM-04 | **Confirmed** | They send EML-03 / EML-05. ADM-04's amount: legacy accepts only a positive number (§4.2). |
| ADM-09 | **Corrected** | The format is known: PDF, A4 landscape, customer block + 13 columns (§3.4). It filters by customer: confirmed. Recipients in legacy: office always, customer optionally, never a third party, so the allow-list is a new restriction and harmless if the office address is on it by default. Delivery: legacy uses public links; the new app must use authenticated downloads (§3.5). "XLSX if unknown" no longer applies: decide whether to keep the PDF for parity, perhaps with an XLSX option. |
| API-03 | **Refined** | The legacy GET paths to answer with the generic page are `/mono/email-confirm`, `/mono/report`, `/mono/download-report`, `/mono/request-success.html` and `/mono/img/logistica-sanchez-logo.jpg` (§2). Answer them on **both** the apex and `app.`, because e-mail links may use the apex (§1.4). |
| CUS-01 | **Confirmed need** | The legacy verification link is consumed by a GET and also e-mails the office on every hit. |
| PUB-04 | **Confirmed** | It must also keep `https://www.logisticasanchez.com/#tracking` working (L-E8) and `ordini.` (L-E3); consider `dashboard.` too (§2). |

### Chapter 11 (open questions)

| Question | Status | Answer / what remains |
|---|---|---|
| **Q-07** e-mails | **Answered** | §1: ten automatic e-mails, their triggers, recipients, subjects and verbatim bodies. Owner review of the copy is still useful (wording bugs in §1.6). |
| **Q-08** report | **Answered** | §3.7. Unknown: whether the production CMS sends `sendToCustomer` and `onlyDelivered` (without the latter the feature fails). |
| **Q-14** VAT, public estimate, delivery times | **Still open** | The backend has no VAT notion and no delivery-time logic. `EvasioneEnum` lists the MOSCA values plus "48" with tier names (§4.5). |
| **Q-30** links in already-sent e-mails | **Partly answered** | The legacy links never expire and contain the e-mail address in clear (§1.4). They point to `https://{EMAIL_ORIGIN}/mono/…`, whose production value is (unknown): the repository default is the apex, which the static site does not serve. Older e-mails point to the UAT Elastic Beanstalk host over HTTP, and before 2025-11-16 to the S3 portal URL. Recommendation: (1) answer the legacy GET paths on the apex and `app.` with the generic page, never confirming or serving PDFs; (2) after termination keep the Elastic Beanstalk environment name reserved (or accept that the links die), because a released `*.elasticbeanstalk.com` CNAME can be claimed by others (inferred); (3) keep the S3 bucket names (Q-29). The owner can read `EMAIL_ORIGIN` from one recent e-mail. |
| Q-05 meaning of the amount | **Partly answered** | The required field is `importo` (since 2024-07-16); `importoAssicurato` is the older CMS field and the fallback; `importoContrassegno` is a copy of `importoAssicurato` (bug). VAT: still (unknown). Migration (chapter 08 DQ-13): prefer `importo`, fall back to `importoAssicurato`, ignore `importoContrassegno` when it equals `importoAssicurato`. |
| Q-09 declined customers and orders | **Answered (legacy facts)** | A declined customer goes back to NEW (DECLINED is never written) and may register again; the same verification code is re-sent. A declined order is set to NEW / NON_GESTITO and kept with its TEMP code (§1.3). |
| Q-28 / CU-22 must the e-mail be verified before approval? | **Corrected** | Yes, in legacy. The pending list shows only PENDING customers (ls@migration:J/api/controller/CustomerController.java:41-46). Approval requires PENDING (ls@migration:J/business/service/crud/SoggettoService.java:117-121), and PENDING is reached only through the verification link. "Email confermata" is derived from that state (ls@migration:J/business/factory/CustomerFactory.java:27), so it is always true in that list. The default "staff may approve an unverified customer (legacy behaviour)" is wrong. |
| Q-32 / Q-34 pricing rules, other lists | **Partly** | The backend adds no rule. Its traits mirror the MOSCA layout, which suggests that layout was the intended generic format (inferred). Other customers' files are still needed. |
| Q-37 brand name | **Fact** | Every subject says "Sanchez Logistica"; bodies mix both names. |
| Q-43 office mailbox | **Confirmed** | Hard-coded Gmail address (ls@migration:J/business/util/EmailReqFactory.java:159, 185). The new app must make it configurable. |
| Chapter 02 CU-06, CU-12, CU-24 | **Answered** | §1, §3 and §1.4 / §2 respectively. |

New questions or risks proposed for chapter 11 §0 (urgent, about the system in
production). They were merged into chapter 11 with the IDs of 12d §6.7; the first column
gives the chapter 11 ID:

| Proposed ID | Risk | Recommended action |
|---|---|---|
| Q-21 (merged) | `GET /mono/download-report?email=<customer e-mail>` (and `/mono/report`) returns any customer's full shipment report with no authentication (§3.5). | Block both paths at nginx (`location = /mono/download-report { return 403; }`) or restrict them to the office IPs until the cut-over. Export the access logs for these paths (P-06 style, counts only) and assess a GDPR art. 33 notification together with Q-21. |
| Q-53 (merged) | A literal AWS key pair shaped like real credentials is committed in `application-dev.yml` and `application.local.yml` (§5.5). | Deactivate and rotate in IAM the same day; check CloudTrail and the SES sending statistics; remove the literal defaults. |
| Q-50 (merged) | Which host do production e-mail links use (`EMAIL_ORIGIN`)? Does `https://logisticasanchez.com/mono/email-confirm` reach the backend today? (§1.4) | The owner reads one recent verification or report e-mail. If the links point to the apex, they are broken today, and the new app should still answer them (API-03). |
| Q-47 (merged) | Which CMS build runs in production: does it send `importo` and the report flags? (§3.6, §4.2) | Owner: the URL or files of the deployed CMS. Without them, approval and report may be failing in production. |
