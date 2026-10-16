# 03 · Legacy order portal (`ls-order`)

The order portal is the login-less page where a customer types an e-mail, registers if
the backend does not know it, and sends transport orders (pallet/parcel lines, transport
document, accessory service, notes, recipient). This chapter documents it from its source
so the customer area of the new app (chapter 09, CUS-01…04) keeps every behaviour that
matters and fixes the rest.

* Source: `ls-order` branch `dev` @ `dbe7292` (2024-06-30), the most recent code (owner).
  `ls-order@dev:` citations point to that commit; `ls-order@main:` = `origin/main` @
  `e9d2072` (2024-05-09). Section 9 lists what `dev` changed.
* The contract in section 7 is what the portal sends and reads. Chapter 02 consolidates
  all endpoints; chapter 04 covers the CMS that approves what the portal creates.

> **What the backend does with it** (chapter 12, from the source `ls@migration`; it wins
> where this chapter guesses):
> * Registration (EP-03) creates the customer as **NEW** and e-mails a verification link
>   (`GET /mono/email-confirm`, never expires); the link makes it **PENDING**, the office
>   approves it (**REGISTERED**). Approval needs a verified e-mail. A decline resets the
>   customer to NEW and keeps the row (12b §3.1).
> * EP-02 answers NEW also for unverified and declined customers (12a §4.1).
> * Ordering (EP-08) is refused with 500 unless the e-mail belongs to a **REGISTERED**
>   customer, so OP-S1 applies to any registered customer's e-mail. Each line is stored as
>   one row per physical unit, and the backend computes the totals (12b §4.3).
> * The backend sends the customer **no** "registration received" or "order received"
>   e-mail; it e-mails the office (12c §1).
> * Size limits on the server: 50 characters for most text fields, CAP 1–9 characters,
>   phone exactly 10 digits until 2025-01-07, then a looser pattern of at most 20
>   characters (12a §1.4 B2, C1).

## 1. At a glance

| | |
|---|---|
| Purpose | Customer self-service: identify by e-mail → register (new) or wait (pending) → place an order (registered). |
| Users | Customers: companies and private persons (label "Ragione Sociale/Nominativo", ls-order@dev:js/constant/language-messages.js:39). |
| URL today | https://ordini.logisticasanchez.com (owner). Older: http://sanchez-logistica-fe.s3-website.eu-north-1.amazonaws.com/index.html (owner; linked by the old site, ls-home@v1:index.html:138, 251, 291). The new static site links "Portale ordini" to https://ordini.logisticasanchez.com (ls-home@fe4b056:partials/header.html:11, 88; partials/footer.html:21). |
| Hosting | Static files with no build step (no package manager, bundler or CI in the repo). The older address is an S3 static-website endpoint in eu-north-1 (owner), so the files were uploaded as they are (inferred). What serves https://ordini.logisticasanchez.com today (the same bucket behind CloudFront, or other) is (unknown). Local development: `python3 local-server.py` → http://localhost:8000 (ls-order@dev:local-server.py:4, 21). |
| Secrets | None: no API key, token or password appears in the repo. |
| Stack | One HTML page + native ES modules, no framework. Material Components for the Web (vendored minified bundle, global `mdc`, version not stated), Bootstrap 5.3.3 CSS, Google "Material Icons" font. Bootstrap JS and jQuery 3.6.0 are loaded but never used by the app code (ls-order@dev:index.html:8-17). |
| Size | 2,122 lines of application JavaScript in 30 files under `js/` (excluding the vendored `js/external/` and the 1,555-line SVG constants file `js/constant/svg.js`), plus `main.js` (16 lines). |
| Backend | 4 REST calls under `{HOST}/mono/api/…`, JSON, **no authentication** (section 7). |
| Identity | Only the e-mail typed on the first screen. No password, no token, no session. |
| Not in the portal | Tracking, order history, order edit/cancel, prices, pick-up date or address, goods description or value, saved recipients, uploads, logout, other languages (all absent from the code). |

## 2. Boot and runtime model

**Entry points.** `index.html` is the only page (title "Sanchez Logistica - Effettua un
ordine", header "Sanchez Logistica - Portale ordini", ls-order@dev:index.html:5, 26). It
contains a top bar with an empty logo slot `#logo-id`, `<main id="app-id">` with an empty
`<div id="form-id">`, and two fixed round buttons: back `#prev-step-button-id` (bottom
left) and forward `#next-step-button-id` (bottom right) (ls-order@dev:index.html:22-51).
The buttons are icon-only arrows: the words "Avanti"/"Indietro" exist in the copy file but
are never rendered (ls-order@dev:js/constant/language-messages.js:67-68).

`main.js` (module, deferred) wires the two buttons to `nextStep` / `prevStep` and, on
`DOMContentLoaded`, runs (ls-order@dev:main.js:9-17):

1. `updateNavigationButtonsUI()` — START state: back disabled, forward enabled.
2. `initPageUI()` — renders the start form into `#form-id`, appends the error dialog to
   `#app-id`, puts SVG arrows in the buttons and the logo SVG in the bar
   (ls-order@dev:js/function/ui-handler.js:64-84).
3. `initMdComponents()` — instantiates the MDC widgets registered so far and attaches the
   click handlers registered by `createFunctionButton`
   (ls-order@dev:js/components/data/component-data.js:13-16, 86-96).

**Rendering.** Every screen is an HTML template string (`js/components/types/*`). A
screen is built the first time its state is entered, appended to `#form-id`, and from then
on only hidden/shown (ls-order@dev:js/function/ui-handler.js:117-136). Typed values
therefore survive Back/Forward, and a page reload loses everything: there is no
`localStorage`, cookie or URL routing (no match for them in the code). MDC instances are
kept in a map keyed by DOM id; an id already in the map is never instantiated again
(ls-order@dev:js/components/data/component-data.js:20-28). All data lives in one
in-memory object `userData` (ls-order@dev:js/components/data/user-data.js:3-21).

**Feedback.** There are no error messages next to fields: an invalid field "shakes"
(CSS `.shake`, 0.5 s) and gets MDC's invalid outline
(ls-order@dev:js/function/ui-handler.js:141-169; ls-order@dev:style.css:122-132). A
failed call opens one generic dialog "Errore" (section 8), except a failure of the
accessory-service call, which shows nothing (OP-03).

## 3. Talking to the backend

| Aspect | Behaviour | Source |
|---|---|---|
| Base URL | `HOST = http://uat-sanchez-logistica.eu-north-1.elasticbeanstalk.com` (UAT, plain HTTP); `https://api.logisticasanchez.com` is commented out. Production is `https://app.logisticasanchez.com/mono/…` (owner), so the deployed bundle must carry a different `HOST` than the repo (inferred); its exact value is (unknown). | ls-order@dev:js/constant/rest-constant.js:2-3 |
| Paths | `/mono/api/customer/state`, `/mono/api/customer/register`, `/mono/api/order/register`, `/mono/api/enumeration/servizioAccessorio`. The tracking endpoint `/mono/api/order/trace/state` is **not** used by the portal. | ls-order@dev:js/constant/rest-constant.js:6-11 |
| POST | `fetch(url, {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(request)})`. No auth header, no credentials, no custom headers, no timeout, no retry. | ls-order@dev:js/rest/rest-caller.js:44-51 |
| GET | `fetch(url, {method: "GET"})`, no headers; the `{}` passed as request is ignored. | ls-order@dev:js/rest/rest-caller.js:34-36, 62-65 |
| Errors | `response.ok == false` → `throw new Error("Rest call failed")`; error bodies are never read. The state machine catches it, logs it and opens the error dialog; the screen does not change. Exception: the enumeration call is outside the `try` (OP-03). | ls-order@dev:js/rest/rest-caller.js:53-57, 67-71; ls-order@dev:js/function/state-machine.js:140-143 |
| CORS | The portal origin differs from the API origin, and a JSON POST triggers a preflight, so the backend must allow the portal origin(s) (inferred). | — |

## 4. State machine

`currentState` starts at `START_STEP` (ls-order@dev:js/function/state-machine.js:8).
State constants: ls-order@dev:js/constant/costant.js:2-13. Screen per state:
ls-order@dev:js/mapper/state-form-mapper.js:4-27. Forward logic:
ls-order@dev:js/function/state-machine.js:60-168. Back logic: :25-58. Button states:
ls-order@dev:js/function/ui-handler.js:15-33.

| State | Screen element id | Title shown (verbatim) | "Avanti" (forward) | "Indietro" (back) | Buttons |
|---|---|---|---|---|---|
| `START_STEP` | `start-form-id` | "Inserisci email" | validate e-mail → `POST customer/state` → branch on `status` | — | back off, forward on |
| `PENDING_STEP` | `pending-form-id` | "La tua richiesta di registrazione è andata a buon fine, a breve verrà approvata" | — | `START_STEP` | back on, forward off |
| `REGISTRATION_STEP` | `registration-form-id` | "Inserisci i dati dell'azienda" | validate → save → `CONFIRM_REGISTRATION_STEP` | `START_STEP` | both on |
| `CONFIRM_REGISTRATION_STEP` | `confirm-data-form-id` | "Per confermare i dati inseriti clicca nuovamente il pulsante 'Avanti' " | `POST customer/register` → `REGISTRATION_EMAIL_SENT_STEP` | `REGISTRATION_STEP` | both on |
| `REGISTRATION_EMAIL_SENT_STEP` | `registration-email-sent-form-id` | "Ci siamo quasi, per completare la registrazione segui le istruzioni contenute nella mail che ti abbiamo mandato" | — | `CONFIRM_REGISTRATION_STEP` (OP-02) | back on, forward off |
| `ORDER_STEP` | `order-form-id` | "Inserisci i dati dell'ordine" | validate → save → `RECIPIENT_STEP` | `START_STEP` | both on |
| `RECIPIENT_STEP` | `recipient-form-id` | "Inserisci i dati del destinatario" | validate → save → `CONFIRM_ORDER_STEP` | `ORDER_STEP` | both on |
| `CONFIRM_ORDER_STEP` | `confirm-data-form-id` (same element as the registration confirm) | same sentence as above | `POST order/register` → `ORDER_EMAIL_SENT_STEP` | `RECIPIENT_STEP` | both on |
| `ORDER_EMAIL_SENT_STEP` | `order-email-sent-form-id` | "Il tuo ordine è stato inoltrato correttamente! Ti preghiamo di attendere la conferma via email dal nostro centro logistico. Grazie per aver scelto Sanchez Logistica!" | — | `CONFIRM_ORDER_STEP` (OP-02) | back on, forward off |

```
START_STEP "Inserisci email"
  │ Avanti: e-mail valid → saveEmail → POST /mono/api/customer/state → status
  ├── "NEW" ──────────▶ REGISTRATION_STEP ──Avanti(valid)──▶ CONFIRM_REGISTRATION_STEP
  │                                                            │ Avanti: POST /mono/api/customer/register
  │                                                            ▼
  │                                                     REGISTRATION_EMAIL_SENT_STEP (forward off)
  ├── "PENDING" ──────▶ PENDING_STEP (forward off)
  ├── "REGISTERED" ───▶ GET /mono/api/enumeration/servizioAccessorio (failure: no dialog, buttons stay off — OP-03)
  │                     ▼
  │                   ORDER_STEP ──Avanti(valid)──▶ RECIPIENT_STEP ──Avanti(valid)──▶ CONFIRM_ORDER_STEP
  │                                                                                   │ Avanti: POST /mono/api/order/register
  │                                                                                   ▼
  │                                                                     ORDER_EMAIL_SENT_STEP (forward off)
  └── other value / failed status call ──▶ error dialog "Errore", stay on START_STEP

Indietro: PENDING, REGISTRATION, ORDER → START · RECIPIENT → ORDER · CONFIRM_ORDER → RECIPIENT
          ORDER_EMAIL_SENT → CONFIRM_ORDER · CONFIRM_REGISTRATION → REGISTRATION
          REGISTRATION_EMAIL_SENT → CONFIRM_REGISTRATION
```

What the user sees when something fails: on invalid input the screen stays and the
invalid fields (or the add/type button) shake, with no text; on a failed call the dialog
"Errore" / "Ops, si è verificato un errore riprova più tardi. Se il problema persiste
contattaci via email o telefonicamente" / "Chiudi" opens and the screen stays (except
OP-03). On success the next screen fades in; the two final screens and the pending screen
have no way forward.

**How a transition runs.** `updateState(next)` awaits the screen swap (fade-out 500 ms,
hide current / show or build next, fade-in 500 ms), then sets `currentState`, refreshes
the buttons and instantiates the new MDC widgets
(ls-order@dev:js/function/state-machine.js:170-177;
ls-order@dev:js/function/ui-handler.js:86-115). `nextStep()` shows a spinner in the
forward button and disables both buttons, calls `evaluateNextStep()` **without awaiting
it**, then restores the arrow at once, so the spinner is practically invisible during calls
(inferred from ls-order@dev:js/function/state-machine.js:10-17). The buttons come back
when the transition ends, when validation fails (:136-138) or in `showError()`
(ls-order@dev:js/function/ui-handler.js:35-38). "Disabled" is only two CSS classes
(`mdc-text-field--disabled`, which the vendored MDC stylesheet styles with
`pointer-events:none`, and an opacity class), not the `disabled` attribute
(ls-order@dev:js/utility/component-util.js:3-11;
ls-order@dev:js/constant/costant.js:40-41; ls-order@dev:style.css:38-40).

## 5. Customer identification and registration

### 5.1 E-mail first

| Element | Value | Source |
|---|---|---|
| Title | "Inserisci email" | ls-order@dev:js/components/types/form-components.js:17 |
| Field | `#text-field-email-id`, placeholder "Email", `<input type="text">` (not `type="email"`), 75 % width | ls-order@dev:js/components/types/form-components.js:22; ls-order@dev:js/components/types/text-field-component.js:8-22 |
| Hint under the field | "Assicurati di aver inserito la mail corretta. Se sei già registrato NON è necessario ripetere la registrazione." (inside an `<h1>` styled 15 px) | ls-order@dev:js/components/types/form-components.js:28 |
| Validation | `isEmailFormValid()`: not empty and `EMAIL_REGEX`; no trimming, no lower-casing | ls-order@dev:js/function/form-validator.js:7-22 |
| On "Avanti" | `saveEmail()` stores the raw value in `userData.email`; `POST /mono/api/customer/state` `{"email": …}`; `evaluateRegistrationState(status)` (called without `await`, see OP-03) | ls-order@dev:js/function/data-handler.js:8-10; ls-order@dev:js/function/state-machine.js:65-79 |

### 5.2 The three statuses

The switch compares exact, case-sensitive strings
(ls-order@dev:js/function/state-machine.js:146-168; ls-order@dev:js/constant/costant.js:17-19).

| `status` | What the portal does | What it means (as used) |
|---|---|---|
| `"NEW"` | Shows the registration form. | E-mail unknown to the backend: the customer must register (inferred). |
| `"PENDING"` | Shows "La tua richiesta di registrazione è andata a buon fine, a breve verrà approvata" with an hourglass icon; forward is off, back returns to the start. | Registered but not yet approved by the office (inferred from the copy and from the CMS approve/decline actions for pending customers, ls-cms@dev:js/constant/rest-constant.js:9, 12-13). The customer cannot order. |
| `"REGISTERED"` | Loads the accessory services, then shows the order form. | Approved customer: may order. This is the **only** gate before ordering. |
| anything else | `console.error("registration state not valid: …")` + error dialog; stays on the start screen. | Other values (for example a declined customer) are (unknown) — see chapter 11 Q-09. |

### 5.3 Registration form

Screen: title "Inserisci i dati dell'azienda", then a stray heading "B", then the same
hint as the start screen, then the fields
(ls-order@dev:js/components/types/form-components.js:84-94, field builder :105-142).

| DOM id | Placeholder (verbatim) | Input | Required | Rule | JSON key |
|---|---|---|---|---|---|
| `text-field-company-id` | "Ragione Sociale/Nominativo" | text | yes | not empty | `ragioneSociale` |
| `text-field-address-id` | "Via" | text | yes | not empty | `indirizzo` |
| `text-field-province-id` | "Provincia" | text | yes | not empty, free text (no 2-letter code check) | `provincia` |
| `text-field-city-id` | "Città" | text | yes | not empty | `citta` |
| `text-field-postal-code-id` | "CAP" | text, `inputmode="numeric"` | yes | `POSTALCODE_REGEX` | `cap` |
| `text-field-phone-id` | "Telefono(Facoltativo)" | text, `inputmode="numeric"` | **yes, despite the label** (OP-08) | `MOBILE_PHONE_REGEX` | `telefono` |

There is no e-mail field: the e-mail typed on the start screen is sent.

Validation `isRegistrationFormValid()` (ls-order@dev:js/function/form-validator.js:24-45):
(1) every field of the form must be non-empty — all empty fields shake at once
(`isFormNullOrEmpty`, :138-154); (2) CAP regex (:31); (3) phone regex, always (:37).
Saving: `saveRegistrationData()` → `saveUserInfo()` copies the six values into
`userData` (empty phone → `null`) (ls-order@dev:js/function/data-handler.js:12-14, 77-86).

The regular expressions (ls-order@dev:js/constant/costant.js:26-29):

```text
EMAIL_REGEX        = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
MOBILE_PHONE_REGEX = /^(\+\d{1,3}\s?)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$/
POSTALCODE_REGEX   = /^\d{5}$|^\d{5}-\d{4}$/
ONLY_NUMBERS_REGEX = /^\d+$/
```

Tested against the legacy patterns: the phone regex accepts "3292396962",
"+39 329 239 6962", "0362123456", "0212345678" and "+1 (555) 123-4567"; it rejects
"0362 123456", "02 12345678", "036212345", "03621234567", "+39 0362 123456",
"0039 329 239 6962" and any value with a trailing space. The CAP regex also accepts
"20831-1234" (US ZIP+4). See OP-09.

### 5.4 Confirmation step

Only the sentence "Per confermare i dati inseriti clicca nuovamente il pulsante 'Avanti' "
— no summary of the typed data (ls-order@dev:js/components/types/form-components.js:45-49).
"Avanti" sends `POST /mono/api/customer/register` (section 7.2); any 2xx is success, the
body is ignored (ls-order@dev:js/function/state-machine.js:125-129;
ls-order@dev:js/rest/rest-caller.js:17-23).

### 5.5 "E-mail sent" step

Text "Ci siamo quasi, per completare la registrazione segui le istruzioni contenute nella
mail che ti abbiamo mandato", an animated check icon and a link "Effettua un nuovo
ordine" to `index.html` (a reload) — the same template as the order's final screen, hence
the order wording (ls-order@dev:js/components/types/form-components.js:51-71).

### 5.6 What the code implies about verification and approval

* Fact: the portal never verifies anything itself. Any e-mail for which the backend
  answers `REGISTERED` gets the order form (ls-order@dev:js/function/state-machine.js:148-151).
* Inferred: after registering, the backend e-mails "istruzioni" to complete the
  registration — most likely a confirmation link (copy at
  ls-order@dev:js/constant/language-messages.js:9; the owner confirms a registration
  confirmation e-mail exists). No page or call in this repo handles a token, so the link
  target lives in the backend or another app (unknown).
* Inferred: e-mail confirmation and approval are separate. The CMS shows a column
  "Email confermata" SI/NO for both pending and registered customers
  (ls-cms@dev:js/components/types/data-tables-component.js:65, 80) and approves or declines
  pending customers (ls-cms@dev:js/constant/rest-constant.js:12-13). An approved customer
  may therefore have an unconfirmed e-mail.
* Unknown: whether `NEW` or `PENDING` is answered before the e-mail is confirmed, whether
  approval requires confirmation, and what a declined customer receives.
* Business intent: the old site sold the portal as "Effettua subito un ordine sul nostro
  portale dedicato. Basta solo la tua email." (ls-home@v1:index.html:249). Low friction is
  deliberate; the new app keeps it with a sign-in link instead of no check (CUS-01).

## 6. Order flow

### 6.1 Order screen

Top to bottom (ls-order@dev:js/components/types/form-components.js:144-224):

1. Title "Inserisci i dati dell'ordine".
2. Totals: "Totale", weight `#pallet-overall-weigth-id` (starts "0") + "kg", volume
   `#pallet-overall-volume-id` (starts "0") + "m3" (:150-161).
3. Card list `#pallet-list-id` (:164-168).
4. Rounded button "Aggiungi Pallet/Colli" `#pallet-open-dialog-button-id` (class `w-100`
   in a narrow column), opens the dialog (:171-183).
5. The fields below.

| DOM id | Text (verbatim) | Input | Required | Rule and function | Saved to → JSON |
|---|---|---|---|---|---|
| `text-field-delivery-document-id` | "Documento di trasporto" | text | **yes** | not empty, any text (`isOrderFormValid`, ls-order@dev:js/function/form-validator.js:103-108) | `userData.deliveryDocument` → `codiceDocumentoTrasporto` (an empty value would become `null`, ls-order@dev:js/function/data-handler.js:54-57, but validation makes that unreachable) |
| `text-field-package-quantity-id` | "Numero Colli (facoltativo)" | text (no numeric keypad) | no | if not empty: `ONLY_NUMBERS_REGEX` (ls-order@dev:js/function/form-validator.js:92-100) | `userData.packageQuantity` → **never sent** (OP-07) |
| `text-area-order-notes-id` | "Note (facoltativo)" | single-line text, despite the id | no | none | `userData.orderNotes` → `note` (`""` when empty) |
| `service-open-dropdown-type-button-id` (attribute `serviceTypeId`) | "Seleziona servizio" | button + menu `#service-dropdown-id` | no | none: the "not selected" flag is computed but unused (ls-order@dev:js/function/form-validator.js:110-111) | `userData.serviceType` → `servizioAccessorio` (section 6.5) |
| card list | via "Aggiungi Pallet/Colli" | dialog | at least one line | `userData.palletList.length > 0`, else the add button shakes (ls-order@dev:js/function/form-validator.js:113-117) | `dettaglioColli.palletList` |

"Avanti" on this screen (`isOrderFormValid`, ls-order@dev:js/function/form-validator.js:88-126):
(1) checks Numero colli if present (continues on failure); (2) DDT empty → shake twice and
stop (the line check does not run); (3) no line → shake the add button; (4) blocks if (1)
or (3) failed. Then `saveOrderData()` (ls-order@dev:js/function/data-handler.js:53-66) and
the recipient screen.

### 6.2 Pallet / parcel dialog

Built once with the order screen and appended to `<main id="app-id">`
(ls-order@dev:js/components/types/form-components.js:145;
ls-order@dev:js/components/types/dialog-components.js:11-81).

* Title "Pallet" (`#pallet-dialog-title-id`), replaced by the chosen type's text.
* Button "Tipo Pallet" (`#pallet-open-dropdown-type-button-id`) opens menu
  `#pallet-type-dropdown-id` (ls-order@dev:js/components/types/dropdown-component.js:9-26):

| Menu text | `palletTypeId` sent as `type` | Constant | Card title |
|---|---|---|---|
| "Pallet Generico/Colli" | `"0"` | `GENERIC_PALLET_TYPE_ID` / `GENERIC_PALLET_TYPE_VALUE = "PALLET"` | "PALLET" |
| "Epal" | `"1"` | `EPAL_TYPE_ID` / `EPAL_TYPE_VALUE = "EPAL"` | "EPAL" |

  (ls-order@dev:js/constant/costant.js:86-90; ls-order@dev:js/mapper/data-mapper.js:3-6).
  Choosing an item copies its text into the dialog title and the button, stores the id in
  the button's `palletTypeId` attribute and sets `typeSelected = true`
  (ls-order@dev:js/function/component-handler.js:84-112).
* Fields, in dialog order (ls-order@dev:js/components/types/dialog-components.js:42-56), all
  plain `<input type="text">` with no numeric keypad:

| DOM id | Placeholder | Line field → JSON key |
|---|---|---|
| `text-field-pallet-height-id` | "Altezza (cm)" | `height` → `altezza` |
| `text-field-pallet-lenght-id` | "Lunghezza (cm)" | `lenght` → `lunghezza` |
| `text-field-pallet-width-id` | "Larghezza (cm)" | `width` → `larghezza` |
| `text-field-pallet-weight-id` | "Peso (kg)" | `weight` → `peso` (per unit: the total multiplies it by the quantity, inferred) |
| `text-field-pallet-quantity-id` | "Quantità" | `quantity` → `quantity` |

* Validation `isPalletDialogBoxFormValid()`: every field not empty and `^\d+$` → integers
  ≥ 0 only; `0` and leading zeros pass; no decimals ("12,5" fails); no maximum. All invalid
  fields shake. The type is required through `typeSelected`, otherwise the type button
  shakes (ls-order@dev:js/function/form-validator.js:128-136, 156-172;
  ls-order@dev:js/function/component-handler.js:34-46). See OP-04 for the hole in that
  check.
* Defaults: none. No field is prefilled and choosing "Epal" sets no size or limit. After
  "Conferma" nothing clears the fields or the type, so the next line starts with the
  previous values (inferred: no reset code exists).
* Fallback: if the type button has no attribute, `palletTypeId = 1` (a number) with the
  comment "Generic pallet" (ls-order@dev:js/function/data-handler.js:23-26), although `1`
  is EPAL; the card title then shows "undefined" (the map keys are strings). Reachable only
  through OP-04.
* "Annulla" closes the dialog without saving (MDC `data-mdc-dialog-action="cancel"`,
  ls-order@dev:js/components/types/dialog-components.js:61-64). "Conferma"
  (`#pallet-confirm-dialog-button-id`) runs `savePalletData()`: validate →
  `getPalletData()` (also updates the totals) → push to `userData.palletList` → append a
  card → wire its delete button → close → scroll the card into view
  (ls-order@dev:js/function/component-handler.js:34-74;
  ls-order@dev:js/function/data-handler.js:16-51).

### 6.3 Totals

| Quantity | Formula (legacy) | Rounding | Source |
|---|---|---|---|
| Line weight, kg | `peso × quantità` | `parseFloat(x.toFixed(2))` (integers in practice) | ls-order@dev:js/function/data-handler.js:91-92 |
| Line volume, m³ | `altezza × lunghezza × larghezza / 1 000 000 × quantità` | 2 decimals **per line** (a 10×10×10 cm box counts 0) | ls-order@dev:js/function/data-handler.js:105-107 |
| Order totals | running sums `userData.orderWeight` / `orderVolume`: added on "Conferma", subtracted on delete | none: the raw JS number is written into the page ("." decimal, no thousands separator) | ls-order@dev:js/function/data-handler.js:94-97, 109-112, 117-133 |

Units shown: "kg" and "m3" (ls-order@dev:js/constant/language-messages.js:13, 15). The
totals are only displayed: they are not sent (ls-order@dev:js/rest/dto/order-request.js:3-24).

Worked example, computed with the legacy formulas:

| Line | Type | H × L × W (cm) | Peso (kg) | Quantità | Line kg | Line m³ |
|---|---|---|---|---|---|---|
| 1 | Epal | 180 × 120 × 80 | 450 | 2 | 900 | 3.46 |
| 2 | Pallet Generico/Colli | 100 × 120 × 80 | 300 | 1 | 300 | 0.96 |
| 3 | Pallet Generico/Colli | 40 × 30 × 30 | 12 | 3 | 36 | 0.11 |
| **Totale** | | | | | **1236** | **4.53** |

Float artefacts (OP-06): lines 50×40×30 (0.06) and 20×20×25 (0.01) display
"0.06999999999999999"; deleting both then displays "-5.204170427930421e-18".

### 6.4 Line cards and deletion

* Card `#pallet-card-id<N>` with delete button `#pallet-delete-card-button-id<N>`, where
  `N = palletList.length − 1` at creation (ls-order@dev:js/function/component-handler.js:55-58).
* Content: title = type name ("PALLET" / "EPAL"), then lines "Altezza (cm) :  180",
  "Peso (kg) :  450", "Lunghezza (cm) :  120", "Larghezza (cm) :  80", "Quantità :  2"
  (order differs from the dialog), then a Material icon button "delete"
  (ls-order@dev:js/components/types/card-component.js:5-24).
* Delete: no confirmation; subtracts the line from the totals, removes it from the list by
  object identity and removes the card (ls-order@dev:js/function/component-handler.js:76-82).
  There is no edit: delete and add again. Ids can collide after deleting a card other
  than the last (OP-05).

### 6.5 Accessory service

* Options: `GET /mono/api/enumeration/servizioAccessorio`, called when the status is
  `REGISTERED`, before the order screen (ls-order@dev:js/function/state-machine.js:148-151;
  ls-order@dev:js/rest/rest-caller.js:33-42). Each item becomes a menu entry with text
  `label` and attribute `serviceTypeId = id`
  (ls-order@dev:js/components/types/dropdown-component.js:28-53). The menu is built once per
  page load, with the data of the first fetch: the order screen is created only the first
  time it is shown (ls-order@dev:js/function/ui-handler.js:126-132).
* Choosing an entry writes its text into the button and its id into the attribute
  (ls-order@dev:js/function/component-handler.js:114-138).
* Default: nothing chosen → `0` (a number) with the comment "Nessun servizio accessorio"
  (ls-order@dev:js/function/data-handler.js:58-61). A choice cannot be undone unless the
  enumeration contains a "none" entry (unknown).
* Values: not in any repository. The CMS loads the same enumeration
  (ls-cms@dev:js/rest/rest-caller.js:127-133). The new site advertises "Sponda idraulica",
  "Consegna su appuntamento", "Preavviso al destinatario", "Ritiri programmati", "Prova di
  consegna digitale" and "Copertura assicurativa integrativa"
  (ls-home@fe4b056:public/servizi/index.html:242-262); whether these match the enumeration
  is (unknown).

### 6.6 Recipient screen

Title "Inserisci i dati del destinatario" and the **same field builder, with the same DOM
ids**, as the registration form (ls-order@dev:js/components/types/form-components.js:96-103,
105-142) — see OP-01.

| DOM id | Placeholder | Required | Rule | JSON key in `destinatario` |
|---|---|---|---|---|
| `text-field-company-id` | "Ragione Sociale/Nominativo" | yes | not empty | `ragioneSociale` |
| `text-field-address-id` | "Via" | yes | not empty | `indirizzo` |
| `text-field-province-id` | "Provincia" | yes | not empty | `provincia` |
| `text-field-city-id` | "Città" | yes | not empty | `citta` |
| `text-field-postal-code-id` | "CAP" | yes | `POSTALCODE_REGEX` | `cap` |
| `text-field-phone-id` | "Telefono(Facoltativo)" | no | `MOBILE_PHONE_REGEX` only if not empty | `telefono` (`null` when empty) |

`isRecipientFormValid()` checks the fields one at a time and stops at the first failure
(ls-order@dev:js/function/form-validator.js:47-86). `saveRecipientData()` writes the values
into the same `userData` fields used for registration
(ls-order@dev:js/function/data-handler.js:73-86). There is no recipient e-mail field:
`destinatario.email` is filled with the customer's own e-mail
(ls-order@dev:js/rest/dto/order-request.js:15). There is no pick-up address anywhere: the
pick-up is presumably the customer's registered address (inferred; chapter 11 Q-13).

### 6.7 Confirm and final screens

* Confirm: the shared sentence "Per confermare i dati inseriti clicca nuovamente il pulsante
  'Avanti' ", no summary. "Avanti" sends `POST /mono/api/order/register`
  (ls-order@dev:js/function/state-machine.js:106-111).
* Final: "Il tuo ordine è stato inoltrato correttamente! Ti preghiamo di attendere la
  conferma via email dal nostro centro logistico. Grazie per aver scelto Sanchez
  Logistica!", animated check icon, link "Effettua un nuovo ordine" (reload)
  (ls-order@dev:js/constant/language-messages.js:10;
  ls-order@dev:js/components/types/form-components.js:55-71). No order code is shown (the
  response body is ignored). "Indietro" stays enabled (OP-02).
* What happens next (inferred): the order waits in the CMS as pending with a temporary
  code shown as "-" (ls-cms@dev:js/components/types/data-tables-component.js:242); the
  office confirms or declines it (ls-cms@dev:js/constant/rest-constant.js:14-15) and the
  customer is told by e-mail (owner: the backend sends order confirmation e-mails).

## 7. REST calls as used by the portal

All four calls: `{HOST}` + path, no authentication, JSON (section 3). Field types below
are what the code really sends: values read from inputs are **strings**.

### 7.1 `POST /mono/api/customer/state`

Caller `getCustomerStatus()` on the start screen (ls-order@dev:js/rest/rest-caller.js:6-15;
ls-order@dev:js/function/state-machine.js:75). Request built by `getCustomerStatusRequest()`
(ls-order@dev:js/rest/dto/customer-request.js:3-7):

```json
{ "email": "ufficio.spedizioni@example.com" }
```

Response: the portal reads only `status` (ls-order@dev:js/rest/rest-caller.js:11-12).

```json
{ "status": "REGISTERED" }
```

Values used: `"NEW"`, `"PENDING"`, `"REGISTERED"` (section 5.2). Other fields and the
answer for malformed input are (unknown). Failure → error dialog, stay on the start screen.

### 7.2 `POST /mono/api/customer/register`

Caller `sendCustomer()` on the registration confirm step
(ls-order@dev:js/rest/rest-caller.js:17-23). Request `getRegisterCustomerRequest()`
(ls-order@dev:js/rest/dto/customer-request.js:9-19):

| Key | Source | Type |
|---|---|---|
| `email` | start screen | string |
| `ragioneSociale` | "Ragione Sociale/Nominativo" | string |
| `indirizzo` | "Via" | string |
| `provincia` | "Provincia" | string |
| `citta` | "Città" | string |
| `cap` | "CAP" | string |
| `telefono` | "Telefono(Facoltativo)" | string (`null` if empty, unreachable here because the field is required) |

```json
{
  "email": "ufficio.spedizioni@example.com",
  "ragioneSociale": "Mobilificio Brianza S.r.l.",
  "indirizzo": "Via Milano 45",
  "provincia": "MB",
  "citta": "Lissone",
  "cap": "20851",
  "telefono": "0391234567"
}
```

Response: not read; any 2xx = success. Duplicate e-mail and validation errors are
(unknown); any non-2xx shows the generic error dialog.

### 7.3 `GET /mono/api/enumeration/servizioAccessorio`

Caller `getOrderDropdownData()` when the status is `REGISTERED`
(ls-order@dev:js/rest/rest-caller.js:33-42). No body, no headers. Response: a JSON array
stored as is; the portal reads `id` and `label` of each item
(ls-order@dev:js/components/types/dropdown-component.js:36-41). The sister enumeration
`statoTracking` has items `{id, name, label}` (ls-cms@dev:js/components/data/user-data.js:22-53),
so the same shape is likely (inferred). Shape only — the values are (unknown):

```json
[
  { "id": 0, "label": "…" }
]
```

Failure: not caught (OP-03).

### 7.4 `POST /mono/api/order/register`

Caller `sendOrder()` on the order confirm step (ls-order@dev:js/rest/rest-caller.js:25-31).
Request built by `getRegisterOrderRequest()` and `mapPalletData()`
(ls-order@dev:js/rest/dto/order-request.js:3-43):

| JSON path | Type as sent | Source | Note |
|---|---|---|---|
| `clienteEmail` | string | start screen | The only link to the ordering customer. |
| `destinatario.ragioneSociale` | string | recipient | |
| `destinatario.indirizzo` | string | recipient "Via" | Street and number in one field. |
| `destinatario.provincia` | string | recipient | Free text. |
| `destinatario.citta` | string | recipient | |
| `destinatario.cap` | string | recipient | |
| `destinatario.telefono` | string or `null` | recipient | `null` when empty. |
| `destinatario.email` | string | **start screen** | The customer's e-mail, not the recipient's (OP-14). |
| `dettaglioColli` | object | — | An object with the single key `palletList` (shorthand `{ palletList }`), not an array (OP-15). |
| `dettaglioColli.palletList[].altezza` | string of digits | dialog | cm |
| `dettaglioColli.palletList[].peso` | string of digits | dialog | kg per unit (inferred) |
| `dettaglioColli.palletList[].larghezza` | string of digits | dialog | cm |
| `dettaglioColli.palletList[].lunghezza` | string of digits | dialog | cm |
| `dettaglioColli.palletList[].quantity` | string of digits | dialog | English key. |
| `dettaglioColli.palletList[].type` | `"0"` / `"1"` (number `1` in the fallback) | dialog | English key; `"0"` = Pallet Generico/Colli, `"1"` = Epal. |
| `codiceDocumentoTrasporto` | string | order screen | Required in `dev`. |
| `servizioAccessorio` | enumeration `id` as a string, or number `0` | order screen | `0` = nothing chosen. |
| `note` | string | order screen | `""` when empty. |

Not sent: "Numero Colli" (`packageQuantity`), line and order totals, the type name, any
sender or pick-up data, any price.

Full request, exactly as the code builds it (no service chosen, no recipient phone):

```json
{
  "clienteEmail": "ufficio.spedizioni@example.com",
  "destinatario": {
    "ragioneSociale": "Ferramenta Rossi S.n.c.",
    "indirizzo": "Via Roma 12",
    "provincia": "BS",
    "citta": "Brescia",
    "cap": "25121",
    "telefono": null,
    "email": "ufficio.spedizioni@example.com"
  },
  "dettaglioColli": {
    "palletList": [
      { "altezza": "180", "peso": "450", "larghezza": "80", "lunghezza": "120", "quantity": "2", "type": "1" },
      { "altezza": "40", "peso": "12", "larghezza": "30", "lunghezza": "30", "quantity": "3", "type": "0" }
    ]
  },
  "codiceDocumentoTrasporto": "DDT 457/2024",
  "servizioAccessorio": 0,
  "note": "Consegna solo al mattino, chiamare il destinatario prima"
}
```

With a service chosen, `servizioAccessorio` is the item's `id` as a string (an `id` of 3
is sent as `"3"`). Response: not read; any 2xx = success. Whether the backend rejects an
e-mail that is not `REGISTERED` is (unknown).

How the data comes back elsewhere: the CMS lists orders flat (`email`, `ragioneSociale`,
`indirizzo`, `citta`, `provincia`, `cap`, `telefono`, `codiceDocumentoTrasporto`,
`servizioAccessorio`, `importo`, `stato`, `tracking`, `codice`;
ls-cms@dev:js/components/types/data-tables-component.js:149-163) and shows the detail as
separate `spedizione`, `dettaglioColli` and `palletList` objects
(ls-cms@dev:js/function/component-handler.js:93, 104, 115). The backend therefore
reshapes what the portal sends (inferred; chapters 02 and 04).

## 8. Copy deck

All user-facing text lives in `jsonMessagesItalian`
(ls-order@dev:js/constant/language-messages.js:1-74); `getLanguageMessages()` exists but
only Italian is defined (:76-82).

| Key | Text (verbatim) | Used at (ls-order@dev:) |
|---|---|---|
| `pendingFormText` | "La tua richiesta di registrazione è andata a buon fine, a breve verrà approvata" | js/components/types/form-components.js:36 |
| `registrationFormText` | "Inserisci i dati dell'azienda" | form-components.js:86 |
| `recipientFormText` | "Inserisci i dati del destinatario" | form-components.js:98 |
| `orderFormText` | "Inserisci i dati dell'ordine" | form-components.js:148 |
| `emailFormText` | "Inserisci email" | form-components.js:17 |
| `confirmDataFormText` | "Per confermare i dati inseriti clicca nuovamente il pulsante 'Avanti' " (trailing space in the source) | form-components.js:47 |
| `registrationEmailSentFormText` | "Ci siamo quasi, per completare la registrazione segui le istruzioni contenute nella mail che ti abbiamo mandato" | form-components.js:52 |
| `orderEmailSentFormText` | "Il tuo ordine è stato inoltrato correttamente! Ti preghiamo di attendere la conferma via email dal nostro centro logistico. Grazie per aver scelto Sanchez Logistica!" | form-components.js:56 |
| `weightUnit` | "kg" | form-components.js:155 |
| `lenghtUnit` | "cm" | unused |
| `volumeUnit` | "m3" | form-components.js:159 |
| `overall` | "Totale" | form-components.js:152 |
| `packageQuantityPlaceHolder` | "Numero Colli (facoltativo)" | form-components.js:193 |
| `packageWeightPlaceHolder` | "Peso (kg)" | unused |
| `packageVolumePlaceHolder` | "Volume (m3)" | unused |
| `epalQuantityPlaceHolder` | "Numero Epal" | unused |
| `palletHeightPlaceHolder` | "Altezza (cm)" | dialog-components.js:43; card-component.js:9 |
| `palletWeightPlaceHolder` | "Peso (kg)" | dialog-components.js:52; card-component.js:10 |
| `palletLenghtPlaceHolder` | "Lunghezza (cm)" | dialog-components.js:46; card-component.js:11 |
| `palletWidhtPlaceHolder` | "Larghezza (cm)" | dialog-components.js:49; card-component.js:12 |
| `palletQuantityPlaceHolder` | "Quantità" | dialog-components.js:55; card-component.js:13 |
| `deliveryDocumentPlaceHolder` | "Documento di trasporto" | form-components.js:187 |
| `orderNotesPlaceHolder` | "Note (facoltativo)" | form-components.js:199 |
| `emailPlaceHolder` | "Email" | form-components.js:22 |
| `companyPlaceHolder` | "Ragione Sociale/Nominativo" | form-components.js:109 |
| `addressPlaceHolder` | "Via" | form-components.js:115 |
| `provincePlaceHolder` | "Provincia" | form-components.js:121 |
| `cityPlaceHolder` | "Città" | form-components.js:127 |
| `postalCodePlaceHolder` | "CAP" | form-components.js:133 |
| `phoneNumberPlaceHolder` | "Telefono(Facoltativo)" | form-components.js:139 |
| `dialogConfirm` | "Conferma" | dialog-components.js:68 |
| `dialogCancel` | "Annulla" | dialog-components.js:63 |
| `dialogClose` | "Chiudi" | dialog-components.js:101 |
| `errorDialogTitle` | "Errore" | dialog-components.js:93 |
| `errorDialogText` | "Ops, si è verificato un errore riprova più tardi. Se il problema persiste contattaci via email o telefonicamente" (a line break after "tardi. " in the source, collapsed when rendered) | dialog-components.js:96 |
| `palletDialogDataTitle` | "Pallet" | form-components.js:145 |
| `genericPallet` | "Pallet Generico/Colli" | dropdown-component.js:17 |
| `epal` | "Epal" | dropdown-component.js:21 |
| `nextButtonText` | "Avanti" | unused (buttons are icon-only) |
| `previousButtonText` | "Indietro" | unused |
| `serviceOpenDropdownText` | "Seleziona servizio" | form-components.js:209 |
| `palletOpenDialogButtonText` | "Aggiungi Pallet/Colli" | form-components.js:176 |
| `palletOpenDropdownTypeText` | "Tipo Pallet" | dialog-components.js:35 |

Hard-coded text outside the copy file:

| Text (verbatim) | Where (ls-order@dev:) |
|---|---|
| "Sanchez Logistica - Effettua un ordine" (page title) | index.html:5 |
| "Sanchez Logistica - Portale ordini" (header) | index.html:26 |
| "Assicurati di aver inserito la mail corretta. Se sei già registrato NON è necessario ripetere la registrazione." | js/components/types/form-components.js:28 (start), :90 (registration) |
| "B" (stray heading, 20 px) | js/components/types/form-components.js:89 |
| "Effettua un nuovo ordine" (link on both final screens) | js/components/types/form-components.js:68 |
| Card line format `"<label> :  <value>"`, card titles "PALLET" / "EPAL" | js/components/types/card-component.js:22-24; js/constant/costant.js:89-90 |
| Material icon ligatures "arrow_back_ios", "arrow_forward_ios", "hourglass_empty", "delete" | index.html:42, 49; js/function/ui-handler.js:58; js/constant/costant.js:22-23 |
| `aria-label="Label"` on every input | js/components/types/text-field-component.js:19, 35 |
| Console only, not user-facing: "Impossibile rimuove l'elemento ${elementToRemove} nell'array ${array}" | js/utility/array-util.js:9 |

Unused English placeholders ("Single-line item", "Radio 1") sit in components that are never
rendered (ls-order@dev:js/components/types/list-components.js:1-17;
ls-order@dev:js/components/types/radiobutton-component.js:8-24).

## 9. What `dev` changed compared with `main`

`git diff origin/main origin/dev`: 10 files, +139/−62. `state-machine.js`, `rest-caller.js`
and both request builders are identical on the two branches.

| Commit (date) | Change |
|---|---|
| `49eae23` (2024-05-09) | Final order text: from "Ci siamo quasi, per completare l'ordine segui le istruzioni contenute nella mail che ti abbiamo mandato" to today's "Il tuo ordine è stato inoltrato correttamente! …" (the customer no longer completes the order from an e-mail; the office confirms it). |
| `1b5458f`, `f14a63c` (2024-05-09) | Check icon instead of mail icon on the final screens; link "Effettua un nuovo ordine" added (moved between the two final-screen templates by `e183526` and `6176e85`; net result: on both final screens); `start-server.bat` removed. |
| `e183526` (2024-05-19) | DDT made optional (placeholder "Documento di trasporto (facoltativo)", empty → `null`); no service → `1`; pallet type fallback `1`; the service shake disabled. |
| `6176e85` (2024-05-25) | Page `<title>`; placeholder back to "Documento di trasporto"; the empty-DDT check is restored but only shakes the field: an empty DDT is still sent as `null`; no service → `0` ("Nessun servizio accessorio"); "Pallet Generico" → "Pallet Generico/Colli"; "Aggiungi Pallet" → "Aggiungi Pallet/Colli". |
| `1c84556` (2024-06-03) | Header "Sanchez Logistica - Portale ordini"; e-mail hint on the start screen; order screen reordered (totals, cards and add button first); "Ragione Sociale" → "Ragione Sociale/Nominativo"; "Telefono" → "Telefono(Facoltativo)"; recipient phone optional and fields checked one by one; DDT required again: an empty DDT now stops validation; the service check removed. |
| `1cb5dd2` (2024-06-04) | Empty phone sent as `null`; commented `https://api.logisticasanchez.com` host. |
| `dbe7292` (2024-06-30) | E-mail hint and the stray "B" heading added to the registration screen. |

On `main`, a missing service was sent as `null` and only shook the button
(ls-order@main:js/function/data-handler.js:51; ls-order@main:js/function/form-validator.js:88-93, 101).
Legacy orders may therefore hold `servizioAccessorio` `null`, `1` or `0` for "no service",
depending on the build deployed at the time, and some may lack a DDT (inferred; which build
ran when is unknown).

## 10. Quirks, bugs and security issues

Decision column: **Fix** = do it right in the new app; **Keep** = preserve the behaviour;
**Compat** = accept the legacy shape when importing data, do not reproduce it; **Drop** =
incidental, do not carry over.

| ID | Kind | Issue | Evidence | Decision |
|---|---|---|---|---|
| OP-S1 | security | No authentication: whoever knows a registered customer's e-mail can order in that customer's name; the backend receives only `clienteEmail`. | ls-order@dev:js/rest/dto/order-request.js:7; ls-order@dev:js/function/state-machine.js:148-151 | Fix: sign-in link / login, sender taken from the session (CUS-01). |
| OP-S2 | security | Account enumeration: `customer/state` tells anyone, without limits, whether an e-mail is NEW, PENDING or REGISTERED. | ls-order@dev:js/rest/rest-caller.js:6-15 | Fix: same answer for every e-mail, rate limit. |
| OP-S3 | security | Personal data sent over plain HTTP to the UAT host configured in the repo. | ls-order@dev:js/constant/rest-constant.js:2 | Fix: HTTPS only, per-environment config, HSTS. |
| OP-S4 | security | No bot or abuse protection on registration and order submission (no captcha, honeypot or client-side throttle; server-side limits unknown). | ls-order@dev:js/rest/rest-caller.js:17-31 | Fix: rate limits, honeypot/challenge, server-side validation with maximum lengths. |
| OP-S5 | security | Backend `label` values injected into `innerHTML` unescaped. | ls-order@dev:js/components/types/dropdown-component.js:40 | Fix: escape all output. |
| OP-01 | bug | Registration and recipient forms share the same DOM ids; MDC instances are keyed by id and the first one wins. After a `NEW` e-mail (for example a typo) followed by a `REGISTERED` one in the same page session, the recipient inputs are ignored: validation reads the hidden registration fields, shakes invisible fields and "Avanti" seems dead until a reload. In the opposite order the registration posts the recipient's data. | ls-order@dev:js/components/types/form-components.js:91, 100, 105-142; ls-order@dev:js/components/data/component-data.js:24 | Fix: separate forms and state. |
| OP-02 | bug | Double submission: "Indietro" stays enabled on both final screens and leads to the confirm step, where "Avanti" sends the same order (or registration) again. | ls-order@dev:js/function/ui-handler.js:22-25; ls-order@dev:js/function/state-machine.js:40-41, 49-50, 106-111, 125-129 | Fix: final screens terminal, idempotency key per submission. |
| OP-03 | bug | `evaluateRegistrationState` is not awaited: if the enumeration call fails, no error dialog appears and both buttons stay disabled. | ls-order@dev:js/function/state-machine.js:77, 148-151 | Fix. |
| OP-04 | bug | One `typeSelected` flag serves both menus: choosing an accessory service before any pallet type lets a line be confirmed without a type; it is then sent as `1` (EPAL) although the comment says "Generic pallet", and the card shows "undefined". | ls-order@dev:js/function/component-handler.js:11, 107, 133; ls-order@dev:js/function/data-handler.js:23-29 | Fix: type required per line. Compat: legacy `type` 1 may mean "not chosen". |
| OP-05 | bug | Card ids come from `palletList.length − 1`. After deleting a card that is not the last one, the next card gets the id of the current last card: its own delete button is never wired, and the older card's delete button then removes both lines (`getElementById` returns the first match). | ls-order@dev:js/function/component-handler.js:55-65 | Fix: stable unique line ids. |
| OP-06 | bug | Totals are running float sums written raw: "0.06999999999999999", "-5.204170427930421e-18". | ls-order@dev:js/function/data-handler.js:94-97, 109-112, 117-133 | Fix: recompute from the lines, Italian formatting ("0,07 m³"). Keep the formulas (section 6.3). |
| OP-07 | data | "Numero Colli (facoltativo)" is validated and saved but never sent. | ls-order@dev:js/function/data-handler.js:64; ls-order@dev:js/rest/dto/order-request.js:3-24 | Fix: store it (meaning to confirm, OP-U7). |
| OP-08 | bug | Registration requires the phone although the label says "Telefono(Facoltativo)": every field of the form must be non-empty and the phone regex always runs. | ls-order@dev:js/function/form-validator.js:25, 37, 138-154 | Fix: optional as labelled, unless the owner wants it required. |
| OP-09 | bug | US-style phone and CAP patterns: valid Italian landlines are rejected ("0362 123456", "02 12345678", 9 or 11 digits, "+39 0362 123456"), "+1 (555) 123-4567" and "20831-1234" are accepted. | ls-order@dev:js/constant/costant.js:27-28 | Fix: CAP exactly 5 digits; Italian/E.164 phone, normalised. |
| OP-10 | bug | No trimming: required fields accept spaces only; a trailing space in the e-mail fails with just a shake. | ls-order@dev:js/utility/string-util.js:3-5; ls-order@dev:js/function/form-validator.js:13 | Fix: trim every input. |
| OP-11 | data | The e-mail is sent as typed: "Mario@Azienda.it" and "mario@azienda.it" may be two customers (backend behaviour unknown). | ls-order@dev:js/function/data-handler.js:9 | Fix: normalise to lower case; Compat: merge case-variants on import. |
| OP-12 | bug | Line fields accept `0`, have no maximum and no decimals (weight "12,5" is rejected). | ls-order@dev:js/function/form-validator.js:128-136; ls-order@dev:js/constant/costant.js:29 | Fix: ≥ 1 and owner-approved maximums; decimals for weight only if the owner agrees. |
| OP-13 | data | Mixed JSON types: numbers sent as strings; `type` `"0"`/`"1"` or `1`; `servizioAccessorio` string id or `0`; `note` `""` but `telefono` `null`. | ls-order@dev:js/rest/dto/order-request.js:20-37; ls-order@dev:js/function/data-handler.js:24-25, 58-61, 85 | Compat on import; typed values in the new model. |
| OP-14 | data | `destinatario.email` carries the customer's own e-mail; no recipient e-mail is collected. | ls-order@dev:js/rest/dto/order-request.js:15 | Fix: optional recipient e-mail; Compat: treat the legacy value as the sender's (inferred). |
| OP-15 | data | Odd nesting `"dettaglioColli": {"palletList": [...]}` and mixed-language line keys (`altezza`, `peso`, `larghezza`, `lunghezza`, `quantity`, `type`). | ls-order@dev:js/rest/dto/order-request.js:17-19, 32-37 | Compat in the importer only; new model: order → lines. |
| OP-16 | UX | Confirm screens show no summary and ask to click "Avanti", a word that appears on no button. | ls-order@dev:js/components/types/form-components.js:45-49; ls-order@dev:index.html:39-51 | Fix: summary page with labelled buttons. |
| OP-17 | bug | "Disabled" buttons are only CSS (`pointer-events:none`); keyboard activation still works (inferred). Back on the start screen would leave both buttons disabled (the default branch never re-enables them). | ls-order@dev:js/utility/component-util.js:3-11; ls-order@dev:js/function/state-machine.js:19-23, 53-55 | Fix: real `disabled` state. |
| OP-18 | UX | Generic errors only: one dialog for every failure, backend messages never shown, no phone or e-mail in the text. | ls-order@dev:js/rest/rest-caller.js:53-57; ls-order@dev:js/components/types/dialog-components.js:83-111 | Fix: specific messages with contacts. |
| OP-19 | bug | `removeArrayElement` splices index −1 when the element is missing, which would delete the last line. | ls-order@dev:js/utility/array-util.js:5-13 | Fix. |
| OP-20 | UX | The registration success screen offers "Effettua un nuovo ordine". | ls-order@dev:js/components/types/form-components.js:51-53, 68 | Fix the wording. |
| OP-21 | UX | Stray "B" heading on the registration screen. | ls-order@dev:js/components/types/form-components.js:89 | Drop. |
| OP-22 | copy | Brand written "Sanchez Logistica"; the company is "Logistica Sanchez S.r.l." (owner). | ls-order@dev:index.html:5, 26; ls-order@dev:js/constant/language-messages.js:10 | Fix: "Logistica Sanchez" (owner to confirm). |
| OP-23 | a11y | Placeholders as labels, `aria-label="Label"` on every input, no `lang` on `<html>`, `<h1>` for small print, no `<form>` (Enter does nothing), notes on a single line. | ls-order@dev:js/components/types/text-field-component.js:19, 35; ls-order@dev:index.html:3; ls-order@dev:js/components/types/form-components.js:197-201 | Fix. |
| OP-24 | code | Menu items get one more click listener at every opening; MDC init list never shrinks (indices compared with objects); unused jQuery/Bootstrap JS; Sass `@use` in plain CSS; typos (`lenght`, `Widht`, `weigth`, `calculat`, `scrool`, `BOUCE`, `costant.js`). | ls-order@dev:js/function/component-handler.js:91-111, 121-137; ls-order@dev:js/components/data/component-data.js:57-63; ls-order@dev:index.html:15-16; ls-order@dev:style.css:1 | Drop. Keep legacy key names only inside the import mapper. |
| OP-25 | UX | Dialog fields keep the previous line's values and type after "Conferma" (inferred: no reset). | ls-order@dev:js/function/component-handler.js:34-74 | Fix: empty form plus an explicit "duplica riga" action (owner/UX choice). |

## 11. What the new app must preserve

Business rules (Keep):

| ID | Rule | Source |
|---|---|---|
| OP-R1 | The customer is identified by e-mail; a new e-mail leads to registration; the hint about typing the right e-mail and not registering twice stays. | sections 5.1–5.3 |
| OP-R2 | Three customer states with these outcomes: new → register; pending → "waiting for approval" page, no ordering; registered → may order. Only approved customers order. | ls-order@dev:js/function/state-machine.js:146-168 |
| OP-R3 | Registration data: company or personal name, street address, province, city, CAP, phone (labelled optional, see OP-08), e-mail; a confirmation step; an e-mail with instructions; then office approval. | sections 5.3–5.6 |
| OP-R4 | An order has one or more lines; each line has a type (Pallet Generico/Colli or Epal), height, length, width (cm), weight per unit (kg) and quantity; at least one line is required. | section 6.2 |
| OP-R5 | Live totals: weight = Σ peso × quantità (kg); volume = Σ round₂(altezza × lunghezza × larghezza / 1 000 000 × quantità) (m³). | ls-order@dev:js/function/data-handler.js:88-115 |
| OP-R6 | Transport document code required (as in `dev`; this is the default of chapter 09 CUS-04 and chapter 11 Q-24 — owner to confirm, OP-U7); number of packages optional, digits only; notes optional; one optional accessory service from the backend enumeration, default "none". | section 6.1 |
| OP-R7 | Recipient: name, address, province, city and CAP required; phone optional. | section 6.6 |
| OP-R8 | Submitting creates an order waiting for office approval; the customer sees no price and is told to wait for the confirmation e-mail from the logistics centre. The sender is the identified customer. | section 6.7; chapter 07 |
| OP-R9 | The Italian wording of section 8 is the baseline copy, corrected where section 10 says so. | section 8 |

Incidental (Drop or redesign freely): the MDC/Bootstrap look, round arrow buttons, fade,
shake and bounce animations, the spinner, the single-page state machine with hidden
forms, placeholder-only fields, the "click Avanti again" confirm screen, card layout and
upper-case type names, "m3" written without superscript, the logo SVG and colours
(`--mdc-theme-primary: #171720`, ls-order@dev:style.css:3-8) — the visual base is the new
site (chapter 06).

## 12. Open questions

| ID | Question | Why it matters |
|---|---|---|
| OP-U1 | Exact answers of `customer/state`: other fields, other status values (declined? e-mail not confirmed?), HTTP status for malformed e-mails, case and space handling. | Status pages, migration of customer states (Q-09). |
| OP-U2 | Status codes and bodies of `customer/register` and `order/register` (success and errors such as duplicate e-mail); does `order/register` refuse e-mails that are not `REGISTERED`? | Contract tests; whether OP-S1 was exploitable even beyond the portal. |
| OP-U3 | Content of the registration e-mail: link format, landing page, expiry. Must links already sent keep working after cut-over? Is an e-mail sent when an order is submitted, or only at approval? | EML-01/02/04, cut-over (Q-07). |
| OP-U4 | Values of the `servizioAccessorio` enumeration (ids, names, labels). Is id 0 "none"? Did the ids change in May 2024 (default 1 → 0)? | ADM-10 seed, migration of `servizioAccessorio`. |
| OP-U5 | How the backend reads `type` (`"0"`/`"1"`/`1`) and string numbers; does it compute weight/volume totals? | Import mapper, order detail parity. |
| OP-U6 | Which commit is deployed at ordini.logisticasanchez.com, with which `HOST`? Is the old S3 address still live? What serves HTTPS on the custom domain, and which origins does the backend allow (CORS)? | Redirects and cut-over (MIG-03). |
| OP-U7 | Is the transport document code mandatory for every order (`dev`, the default) or optional (as in builds `e183526` up to `1c84556`)? What does "Numero Colli" mean next to the lines, and should it be stored? | Order form rules, OP-07. |
| OP-U8 | Does the backend validate lengths and formats, and rate-limit these endpoints? | Server-side rules for the new app; data quality of the migration. |
| OP-U9 | Does the backend use `destinatario.email` (for example to e-mail the recipient)? | OP-14. |
