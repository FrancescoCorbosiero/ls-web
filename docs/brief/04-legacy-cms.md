# 04 · Legacy CMS (`ls-cms`)

The CMS is the office's back office. Staff log in, approve or decline the customers who
registered through the order portal (chapter 03), approve or decline their orders
(assigning the shipment code and typing the amount for the customer), set tracking
states, keep one note per order, open an order's detail and have a report e-mailed for a
date range. The new back office (chapter 09, ADM-01…ADM-10) replaces it.

This chapter documents branch `dev` of `ls-cms` (commit `f0c881c`, 2024-06-06). Branch
`main` (`4709770`, 2024-05-09) is an ancestor of `dev`; section 2.4 lists what `dev`
added. Request and response shapes come from the CMS code.

> **Read with chapter 12** (the backend source, `ls@migration`; it wins where this chapter
> guesses what the backend does).
> * **This CMS build is not the one used in production.** Since 2024-07-16/18 the backend
>   requires `req.importo` (a number > 0) to approve an order, and since 2024-08-25 it
>   requires `onlyDelivered` for the report; `ls-cms@dev` sends `importoAssicurato` as
>   text and no `onlyDelivered`, so both actions would fail with 500. The backend links to
>   a CMS at `https://dashboard.logisticasanchez.com/`, and the committed logs show calls
>   to `order/active`, `order/delivered` and `cms/order/update`, which no repository
>   frontend makes. The production CMS source is unknown (Q-47; 12a §0 point 6).
> * Use this chapter for the screens, the copy and the workflow; take the backend rules
>   from chapter 12: approval needs a verified customer; declines reset to NEW and keep the
>   row; the note is the customer's order note, overwritten by staff; tracking may move
>   between any two states and e-mails the customer on CONSEGNATO only; the report is a PDF
>   reached through links in an e-mail (12a §4.1, 12b §3, 12c §1–§3).
> * Staff accounts live in table `users` (role `ADMIN` or `CUSTOMER`, a `policy` column);
>   the token is an HS256 JWT valid 6 hours (12a §2.2–2.4).

How to read this chapter:

* Sources are cited as `repo@ref:path:line` (`ls-cms@dev` unless another ref is given).
* **(verified)** = observed on 2026-10-16 by running `ls-cms@dev`, unchanged, in headless
  Chromium (Playwright) with every backend call answered by a local mock. It proves what
  the frontend shows and sends, never what the real backend answers.
* "(inferred)" and "(unknown)" follow the brief conventions (README).
* Quirk IDs `CMS-nn` point to section 8. Question IDs `Q-nn` point to chapter 11.

## 1. Purpose, stack, boot, base URL, authentication

### 1.1 Purpose and users

| Job | How the CMS does it | Section |
|---|---|---|
| Customer onboarding | List of customers waiting for approval; approve or decline by e-mail | 3.2, 4.2, 4.3 |
| Order intake | List of pending orders; approve (shipment code + amount) or decline | 3.4, 4.4, 4.5 |
| Tracking | Set one of six tracking states on an order, by its code | 4.6 |
| Notes | Read and replace one free-text note per registered order | 4.7 |
| Order detail | Shipment, packages and pallet lines of one order | 4.8 |
| Reporting | Ask the backend to e-mail a report for a date range | 4.9 |

Users: office staff. There is one login and no notion of roles, user management or
password change in the UI. How staff accounts are created, how many exist and what the
username looks like are (unknown) (Q-17).

### 1.2 Stack and files

| Concern | What | Source |
|---|---|---|
| Page | One static `index.html`, title "Logistica Sanchez - CMS"; every view and dialog is built at runtime as HTML strings and inserted with `innerHTML` / `insertAdjacentHTML` | ls-cms@dev:index.html:5, 21-46 |
| Code | Vanilla ES modules, entry `main.js` (`type="module"`). No build step, no `package.json`, no tests | ls-cms@dev:index.html:17 |
| UI kit | Material Components for the Web (MDC, minified bundle, version not stated) for dialogs, tabs, text fields, menus, snackbar; Bootstrap 5.3.3 CSS for layout | ls-cms@dev:index.html:8-9, 14; ls-cms@dev:js/external/bootstrap.css:3 |
| Unused libraries | Bootstrap 5.3.3 JS and jQuery 3.6.0 are loaded and never referenced | ls-cms@dev:index.html:15-16 |
| Icons | Inline SVG constants; the Google Fonts "Material Icons" stylesheet is loaded from `fonts.googleapis.com` but only dead code uses it | ls-cms@dev:index.html:10; ls-cms@dev:js/constant/svg.js:1-90 |
| Theme | `--mdc-theme-primary: #171720`, `--mdc-theme-secondary: #ecece7` | ls-cms@dev:style.css:3-8 |
| Local run | `python3 local-server.py` serves the folder on `http://localhost:8000` (`start-server.bat`) | ls-cms@dev:local-server.py:4, 21-25 |
| Code layout | `js/constant` (ids, URLs, texts), `js/rest` (fetch wrappers, DTOs), `js/function` (state machine, handlers, validators, UI helpers), `js/components/types` (HTML builders), `js/components/data` (in-memory store `userData`, MDC registry) | — |

Part of the code was copied from the order portal (pallet dialog and cards, registration
and order validators, form texts) and never runs in the CMS (CMS-29).

### 1.3 Boot sequence

1. The browser loads MDC, Bootstrap and jQuery as classic scripts in `<head>`, then
   `main.js` as a deferred module (ls-cms@dev:index.html:14-17). While importing,
   `ui-handler.js` caches the three navigation buttons (ls-cms@dev:js/function/ui-handler.js:14-16).
2. `main.js` attaches the click handlers of the three floating buttons
   (ls-cms@dev:main.js:19-21) and, on `DOMContentLoaded` (ls-cms@dev:main.js:10-16):
   1. `updateNavigationButtonsUI()` enables the three buttons (ls-cms@dev:js/function/ui-handler.js:19-23);
   2. `initCmsUI()` draws the button icons and injects the snackbar and nine dialogs (error,
      login, approve customer, approve order, decline customer, decline order, notes,
      tracking, detail) into `<main id="app-id">` (ls-cms@dev:js/function/ui-handler.js:64-85).
      The dialogs are built **once per page load**; their content is fixed at that moment
      (this matters for the tracking menu, section 5.1);
   3. `initMdComponents()` instantiates the MDC components registered so far and attaches
      the queued click listeners (ls-cms@dev:js/components/data/component-data.js:13-16);
   4. `openLoginDialog()` opens the login dialog with Escape and scrim click disabled
      (ls-cms@dev:js/function/component-handler.js:71-78).
3. No API call happens before login (only static files and the Google Fonts stylesheet).
4. After a successful login, `initCms()` loads the customer lists and renders the
   customers view (ls-cms@dev:js/function/component-handler.js:193-211). Section 2.2 lists
   every later load.

### 1.4 Base URL and environments

* `HOST` is hard-coded to `http://uat-sanchez-logistica.eu-north-1.elasticbeanstalk.com`
  (plain HTTP); `https://api.logisticasanchez.com` is commented out next to it
  (ls-cms@dev:js/constant/rest-constant.js:2-3). Every URL is `HOST + "/mono/..."`
  (ls-cms@dev:js/constant/rest-constant.js:6-36).
* The production backend is `https://app.logisticasanchez.com/mono/...` (owner). The code
  has no environment switch, so a production build must have had `HOST` edited by hand
  (inferred). The production URL of the CMS, its hosting and the `HOST` it uses are
  (unknown).
* The order portal hard-codes the same UAT host (ls-order@dev:js/constant/rest-constant.js:2-3).
* Every call sends `Content-Type: application/json`, the two GETs included
  (ls-cms@dev:js/rest/rest-caller.js:136-142, 154-159, 171-178). Since `HOST` is absolute,
  the CMS calls the API cross-origin from wherever it is served: the browser sends a CORS
  preflight for each call and the backend must allow the CMS origin (inferred).

### 1.5 Authentication and session

**Login request** (verified) — `POST {HOST}/mono/auth/login`, no `Authorization` header
(ls-cms@dev:js/rest/rest-caller.js:7-16; ls-cms@dev:js/rest/dto/login-request.js:3-8):

```json
{
  "username": "ufficio",
  "password": "<password>"
}
```

The username format (e-mail or name) is (unknown): the placeholder says "username"; the
DOM id contains "email" (ls-cms@dev:js/constant/costant.js:189).

**Login response** — the CMS reads only `token` and `refreshToken`
(ls-cms@dev:js/rest/rest-caller.js:12-15):

```json
{
  "token": "<access token>",
  "refreshToken": "<refresh token>"
}
```

Other fields (expiry, role, user name) are (unknown). The CMS never decodes the token;
that it is a JWT is only (inferred).

| Aspect | Behaviour | Source |
|---|---|---|
| Token storage | `userData.token` and `userData.refreshToken` in a JavaScript module object: page memory only, not in cookies or Web Storage | ls-cms@dev:js/components/data/user-data.js:9-10; ls-cms@dev:js/rest/rest-caller.js:14-15 |
| Credentials kept | `userData.username` and `userData.password` keep the typed values for the whole session; the password also stays in the hidden login input (verified) | ls-cms@dev:js/function/component-handler.js:148-152; ls-cms@dev:js/components/data/user-data.js:5-6 |
| Authenticated calls | Every POST except login sends `Authorization: Bearer <token>` (verified) | ls-cms@dev:js/rest/rest-caller.js:170-187 |
| Unauthenticated calls | Login and the two enumeration GETs | ls-cms@dev:js/rest/rest-caller.js:119-168 |
| Refresh token | Stored, never sent; no refresh endpoint exists in the code | ls-cms@dev:js/rest/rest-caller.js:15 |
| 401 / expiry | No status-specific handling: any non-2xx answer throws `Error("Rest call failed")`; the caller shows a generic snackbar or nothing. No re-login prompt | ls-cms@dev:js/rest/rest-caller.js:180-184 |
| Page reload | The token is lost; the login dialog opens again (verified) | — |
| Logout | None: no button, no endpoint. Closing the tab ends the session | — |
| Wrong credentials | Same snackbar as any failure, "Ops, si è verificato un errore"; the dialog stays open (verified with a 401) | ls-cms@dev:js/function/component-handler.js:162-164 |
| Failure right after login | If loading the customers fails, the login dialog is already closed: blank page, snackbar "Ops, si è verificato un errore", only a reload helps (verified) | ls-cms@dev:js/function/component-handler.js:158-161, 207-210 |

Whether the backend really requires the token on `/mono/api/customer/*` and
`/mono/api/order/pending|registered|detail` is (unknown): the same `/mono/api` prefix
serves the unauthenticated order portal and the public tracking call (CMS-05).

## 2. Navigation and views

### 2.1 Layout

```
+------------------------ page body (Bootstrap container, centred) ------------------------+
| CUSTOMERS view (default after login)      | ORDERS view                                   |
| [In Approvazione] [Registrati]            | [In Approvazione] [Registrati] [Report]       |
|  pending customers (approve, decline)     |  pending orders (approve, decline, detail)    |
|  registered customers (no actions)        |  registered orders (tracking, notes, detail)  |
|                                           |  report form                                  |
+-------------------------------------------+-----------------------------------------------+
            fixed, bottom centre:   [customers icon] [truck icon]  [refresh icon]
```

* Three icon-only floating buttons, fixed at the bottom centre: Customers, Orders, Refresh
  (ls-cms@dev:index.html:29-44; icons ls-cms@dev:js/function/ui-handler.js:66-73). They have no
  text, tooltip or `aria-label` (CMS-24). There is no header, logo, user name or logout.
* A view is a `div` inside `#form-id`; switching views fades the old one out and the new one
  in (500 ms each) (ls-cms@dev:js/function/ui-handler.js:109-138, 171-190; ls-cms@dev:style.css:42-48).
* Tabs are MDC tab bars; `switchTab` hides the current panel and shows the chosen one
  (ls-cms@dev:js/function/component-handler.js:170-177).

| View | Tab (label) | Content | Source |
|---|---|---|---|
| Customers (shown after login) | "In Approvazione" (default) | Pending customers (3.2) | ls-cms@dev:js/components/types/form-components.js:18-22; ls-cms@dev:js/components/types/tab-component.js:20-28 |
| | "Registrati" | Registered customers (3.3) | ls-cms@dev:js/components/types/form-components.js:24-28; ls-cms@dev:js/components/types/tab-component.js:29-37 |
| Orders (truck button) | "In Approvazione" (default) | Pending orders (3.4) | ls-cms@dev:js/components/types/form-components.js:41-45; ls-cms@dev:js/components/types/tab-component.js:52-60 |
| | "Registrati" | Registered orders (3.5) | ls-cms@dev:js/components/types/form-components.js:47-51; ls-cms@dev:js/components/types/tab-component.js:61-69 |
| | "Report" | E-mail and date range (4.9) | ls-cms@dev:js/components/types/form-components.js:53-92; ls-cms@dev:js/components/types/tab-component.js:70-78 |
| Dialogs | — | Login, approve/decline customer, "Approva ordine", decline order, "Imposta tracking", "Aggiorna note", "Dettaglio", error (never opened) | ls-cms@dev:js/components/types/dialog-components.js:26-424 |

On `dev`, tracking is not a tab: it is a row action of registered orders (4.6).

### 2.2 What loads when

| Trigger | Calls (sequential, in this order) | Result | Source |
|---|---|---|---|
| Page load | none | Login dialog | ls-cms@dev:main.js:10-16 |
| Login succeeds | `POST /mono/auth/login` → `POST /mono/api/customer/pending` → `POST /mono/api/customer/registered` | Customers view, tab "In Approvazione" | ls-cms@dev:js/function/component-handler.js:156-161, 193-211 |
| First click on Orders | `POST /mono/api/order/pending` → `POST /mono/api/order/registered` → `GET /mono/api/enumeration/statoTracking` → `GET /mono/api/enumeration/servizioAccessorio` (verified) | Orders view, tab "In Approvazione" | ls-cms@dev:js/function/state-machine.js:13-26 |
| Later clicks on Orders or Customers | none: the view built earlier is shown again with the data of its last load | View switch | ls-cms@dev:js/function/state-machine.js:16-23, 28-32; ls-cms@dev:js/function/ui-handler.js:171-190 |
| Refresh button | Orders view: `order/pending` → `order/registered`. Customers view: `customer/pending` → `customer/registered`. Enumerations are never reloaded | The whole current view is rebuilt and opens on its first tab | ls-cms@dev:js/function/state-machine.js:34-50; ls-cms@dev:js/function/ui-handler.js:140-169, 192-204 |
| Any successful action | Same reload as the Refresh button, for the current view | The dialog closes after the reload | ls-cms@dev:js/function/component-handler.js:230, 310, 343, 378, 437, 471, 527 |

There is no polling and no counter or notification of new registrations or orders: the
office must press Refresh.

### 2.3 Refresh behaviour and side effects

* Refresh disables the three buttons and re-enables them at the end
  (ls-cms@dev:js/function/state-machine.js:35, 41, 47). There is no `try/catch`: when a call
  fails the buttons stay disabled and no message appears until the page is reloaded
  (verified) (CMS-15). The first load of the orders view behaves the same way
  (ls-cms@dev:js/function/state-machine.js:13-26).
* A rebuilt view always opens on its first tab: after setting a tracking state from
  "Registrati" or sending a report, the office lands on "In Approvazione" (verified) (CMS-18).
* Rebuilding re-creates rows and buttons, but MDC components stay cached by id and click
  listeners pile up at every rebuild. Consequences (verified): the report e-mail field is
  read from a stale copy, requests are duplicated, and stale row data can be used
  (CMS-06, CMS-07).
* Switching views never reloads data: the customer lists date from login (or the last
  refresh in that view), the order lists from the first visit (or the last refresh there).

### 2.4 What `dev` added over `main`

| Area | `main` (2024-05-09) | `dev` (2024-06-06) |
|---|---|---|
| Navigation | Customers and Orders buttons | + Refresh button (ls-cms@dev:index.html:40-43; ls-cms@dev:main.js:21) |
| Customer tables | Raw backend keys as headers (ls-cms@main:js/components/types/data-tables-component.js:53-55); text buttons "Approva" / "Rimuovi" called the API at once | Italian headers, icon buttons, confirmation dialog |
| Orders | Pending table only; its buttons did nothing (ls-cms@main:js/components/types/data-tables-component.js:146-161); tab "Ricerca" with code field, state menu and a table of the trace responses (ls-cms@main:js/components/types/form-components.js:46-84) | Pending and registered tables, approve/decline, tracking dialog, notes, detail, "Report" tab |
| Endpoints | login; customer pending/registered/confirm/decline; order pending; `cms/order/trace`; `statoTracking` | + `api/order/registered`, `cms/order/confirm`, `cms/order/decline`, `cms/notes`, `cms/notes/update`, `api/order/detail`, `cms/order/report`, `servizioAccessorio` |
| Other | — | TEMP codes shown as "-", hard-coded tracking states, page title |

All seven `dev` commits after `main` carry the same message, "v 0.0.1 … Problems: - temp
codes - buttons and dialog - admin utils" (`git log origin/dev`): the author considered
those areas unfinished.

## 3. Data tables

### 3.1 Rendering rules common to all tables

* Each list is mapped to objects whose keys are the Italian headers; header cells are the
  keys and body cells the values, in insertion order
  (ls-cms@dev:js/components/types/data-tables-component.js:10-53, 89-127).
* Values are inserted as raw HTML, without escaping (verified: a script in a company name
  ran) (CMS-04).
* No sorting, filtering, search or pagination: every row is rendered at once (CMS-26).
* An empty list shows one line of text instead of the table.
* Tables with actions have a first column without header holding icon buttons
  (ls-cms@dev:js/components/types/data-tables-component.js:92, 174, 265).
* The rows array is interpolated into the template, so literal commas appear above the
  table, one fewer than the rows (verified: ",," above three rows)
  (ls-cms@dev:js/components/types/data-tables-component.js:48, 140, 230, 321) (CMS-23).
* `null` and `undefined` print as "null" / "undefined" unless a "-" fallback is coded
  (verified: "Telefono" `null` → "null").
* Row button ids are positional counters ("1-customer-approve-button-id", …) reset after
  each table (ls-cms@dev:js/components/data/counter.js:1-10;
  ls-cms@dev:js/components/types/data-tables-component.js:106, 129) (CMS-07).

### 3.2 Customers · "In Approvazione"

Data: `POST /mono/api/customer/pending` → `userData.pendingCustomers`
(ls-cms@dev:js/rest/rest-caller.js:19-27). Builder:
ls-cms@dev:js/components/types/data-tables-component.js:70-145. Row order: as returned by
the backend (not reversed). Empty list: "Nessun utente da approvare".

| # | Header | Source field | Formatting |
|---|---|---|---|
| 0 | (empty) | — | Green check → approve (4.2); circled cross → decline (4.3) |
| 1 | ID | `id` | raw |
| 2 | Ragione sociale | `ragioneSociale` | raw HTML |
| 3 | Email | `email` | raw |
| 4 | Indirizzo | `indirizzo` | raw |
| 5 | Provincia | `provincia` | raw |
| 6 | Città | `citta` | raw |
| 7 | Cap | `cap` | raw |
| 8 | Telefono | `telefono` | raw ("null" when null) |
| 9 | Email confermata | `emailConfirmed` | "SI" if truthy, otherwise "NO" |

Mapping: ls-cms@dev:js/components/types/data-tables-component.js:71-81.

### 3.3 Customers · "Registrati"

Data: `POST /mono/api/customer/registered` (ls-cms@dev:js/rest/rest-caller.js:29-37).
Builder: ls-cms@dev:js/components/types/data-tables-component.js:55-68. Same nine columns
and formats as 3.2, **no action column**. Rows are **reversed**
(ls-cms@dev:js/components/types/data-tables-component.js:67): newest first if the backend
returns ascending ids (inferred). Empty list: "Nessun utente registrato". A registered
customer cannot be opened, edited, suspended or linked to its orders.

### 3.4 Orders · "In Approvazione"

Data: `POST /mono/api/order/pending` (ls-cms@dev:js/rest/rest-caller.js:56-64). Builder:
ls-cms@dev:js/components/types/data-tables-component.js:238-326. Row order: as returned
(not reversed). Empty list: "Nessun ordine da approvare".

| # | Header | Source field | Formatting |
|---|---|---|---|
| 0 | (empty) | — | Green check → "Approva ordine" (4.4); circled cross → decline (4.5); magnifier → detail (4.8) |
| 1 | ID | `id` | raw |
| 2 | Codice | `codice` | "-" when the code contains "TEMP" (case-sensitive substring) |
| 3 | Cliente mittente | `email` | raw |
| 4 | Codice Doc. Trasporto | `codiceDocumentoTrasporto` | "-" when null/undefined |
| 5 | Destinatario | `ragioneSociale` | raw |
| 6 | Indirizzo | `indirizzo` | raw |
| 7 | Città | `citta` + "(" + `provincia` + ")" | e.g. "Monza(MB)", no space |
| 8 | Cap | `cap` | raw |
| 9 | Telefono | `telefono` | raw |
| 10 | Importo | `importo` | "-" when null/undefined |
| 11 | Ser. Accessorio | `servizioAccessorio` | raw value, not mapped to the enumeration label (format unknown) |
| 12 | Stato | `stato` | raw (values unknown) |
| 13 | Tracking | `tracking` | raw (id, name or label: unknown) |

Mapping: ls-cms@dev:js/components/types/data-tables-component.js:240-254. `codice` must be
a string: a `null` code would make the whole orders view fail (inferred,
ls-cms@dev:js/components/types/data-tables-component.js:242). The detail button sends the
real code, TEMP codes included (verified) (ls-cms@dev:js/components/types/data-tables-component.js:298).

Columns 5–9 are the recipient's data (inferred from the header "Destinatario" and from the
portal, which sends them inside `destinatario`, ls-order@dev:js/rest/dto/order-request.js:8-16).
"Cliente mittente" shows `email`. The portal fills `destinatario.email` with the customer's
own e-mail (ls-order@dev:js/rest/dto/order-request.js:15; chapter 03, OP-14), so the
frontends cannot tell whether the backend returns the customer's or the recipient's
e-mail here (unknown); in practice both are the customer's.

### 3.5 Orders · "Registrati"

Data: `POST /mono/api/order/registered` (ls-cms@dev:js/rest/rest-caller.js:66-74). Builder:
ls-cms@dev:js/components/types/data-tables-component.js:147-236. Same 13 columns and
formats as 3.4 except **Codice is shown raw** (no TEMP masking)
(ls-cms@dev:js/components/types/data-tables-component.js:151). Rows are **reversed**
(ls-cms@dev:js/components/types/data-tables-component.js:165). Empty list: "Nessun ordine
registrato". Actions: truck → "Imposta tracking" (4.6); notepad → "Aggiorna note" (4.7);
magnifier → detail (4.8) (ls-cms@dev:js/components/types/data-tables-component.js:186-209).

### 3.6 Row actions

| Table | Icon | Opens | Payload | Source (data-tables-component.js) |
|---|---|---|---|---|
| Customers · In Approvazione | green check | "Sei sicuro di voler procedere?" (approve) | mapped row | 104-111 |
| | circled cross | "Sei sicuro di voler procedere?" (decline) | mapped row | 112-119 |
| Orders · In Approvazione | green check | "Approva ordine" | mapped row | 277-284 |
| | circled cross | "Sei sicuro di voler procedere?" (decline) | mapped row | 285-292 |
| | magnifier | "Dettaglio" | real `codice` of the order | 293-300 |
| Orders · Registrati | truck | "Imposta tracking" | mapped row | 186-193 |
| | notepad | "Aggiorna note" | mapped row | 194-201 |
| | magnifier | "Dettaglio" | real `codice` of the order | 202-209 |

"Mapped row" is the object with Italian keys stored in `userData.rowPayload`
(ls-cms@dev:js/function/component-handler.js:29-36); actions read `row['Email']`,
`row['ID']`, `row['Cliente mittente']` and `row['Codice']`. No action on pending orders
reads `row['Codice']` (which is "-" for TEMP codes).

## 4. Actions and dialogs

Common pattern:

* The confirm button shows a spinner and gets two CSS classes; `mdc-text-field--disabled`
  sets `pointer-events: none`, which blocks mouse clicks
  (ls-cms@dev:js/function/ui-handler.js:36-44; ls-cms@dev:js/utility/component-util.js:3-6).
* During approve/decline the affected table is replaced by a spinner.
* Success: the current view is reloaded (2.3), then the dialog closes.
* Failure: the dialog closes, the table content is restored, a snackbar shows the message.
* Dialogs other than login and error also close with Escape, a click on the scrim and the
  "Annulla" / "Chiudi" button. MDC renders button labels in capitals ("CONFERMA").
* Inputs have no visible labels, only placeholders, and every input has
  `aria-label="Label"` (ls-cms@dev:js/components/types/text-field-component.js:14-19, 30-35).

### 4.1 Login

| Field | Placeholder | Type | Validation |
|---|---|---|---|
| username | "username" | text | non-empty |
| password | "password" | **text: the password is visible** (verified) | non-empty |

Button "Conferma" (ls-cms@dev:js/components/types/dialog-components.js:56-95). An empty
field shakes and nothing is sent (verified)
(ls-cms@dev:js/function/component-handler.js:138-145; ls-cms@dev:js/function/form-validator.js:120-128, 140-156).
Success closes the dialog and runs `initCms()`; failure shows "Ops, si è verificato un
errore". There is no `<form>`, so Enter does not submit (inferred).

### 4.2 Approve customer

Dialog title "Sei sicuro di voler procedere?", empty body, buttons "Annulla" / "Conferma"
(ls-cms@dev:js/components/types/dialog-components.js:145-179). The dialog does not say
which customer will be approved (verified) (CMS-19).

`POST /mono/cms/customer/confirm`, Bearer (verified)
(ls-cms@dev:js/function/component-handler.js:339-341; ls-cms@dev:js/rest/rest-caller.js:39-41):

```json
{
  "email": "ufficio@mobilificiorossi.example"
}
```

The response body is ignored. Success reloads the customer lists; failure shows "Ops, si è
verificato un errore". The CMS lets staff approve a customer whose "Email confermata" is
"NO" (verified) (CMS-20). Server-side checks and the e-mails sent are (unknown) (Q-07).
After approval the customer should move to "Registrati" and the portal should answer
`REGISTERED` for that e-mail (inferred; ls-order@dev:js/function/state-machine.js:146-153).

### 4.3 Decline customer

Same dialog as 4.2 (ls-cms@dev:js/components/types/dialog-components.js:218-253).
`POST /mono/cms/customer/decline`, Bearer, same body `{"email": "…"}`
(ls-cms@dev:js/function/component-handler.js:374-376; ls-cms@dev:js/rest/rest-caller.js:43-45).
What the backend does (delete or flag the record, send an e-mail) and whether the e-mail
can register again are (unknown) (Q-09). Declined customers are not listed anywhere.

### 4.4 Approve order ("Approva ordine")

Dialog: ls-cms@dev:js/components/types/dialog-components.js:97-143.

| Field | Placeholder | Type | Validation | Sent as |
|---|---|---|---|---|
| Code | "Codice" | text | non-empty (spaces only pass) | `req.codice` |
| Amount | "Importo Totale (per cliente)" | text with `inputmode="numeric"` | non-empty only: "180,50" and "abc" pass | `req.importoAssicurato`, the string as typed |

Buttons "Annulla" / "Conferma". Validation: ls-cms@dev:js/function/form-validator.js:130-138.
The code field is **not prefilled** (the TEMP code is hidden): staff type the definitive
shipment code. The fields are never cleared: reopening the dialog for the next order
shows the previous code and amount (verified) (CMS-12).

`POST /mono/cms/order/confirm`, Bearer (verified)
(ls-cms@dev:js/function/component-handler.js:420-435; ls-cms@dev:js/rest/rest-caller.js:47-49):

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

* `id` comes from the row's "ID" (ls-cms@dev:js/function/component-handler.js:421). The code
  format is (unknown) (Q-06).
* `tracking: 1` is `PRESA`, "Presa in carico", in the CMS list
  (ls-cms@dev:js/components/data/user-data.js:28-32): approving puts the shipment in "Presa
  in carico" (inferred from the payload; the backend effect is unknown).
* The label says "total for the customer" and the owner confirms staff type the price for
  the customer here (owner), but the key means "insured amount" and the tables read
  `importo`, not `importoAssicurato` (ls-cms@dev:js/components/types/data-tables-component.js:159, 250).
  Whether the backend maps one to the other is (unknown) (Q-05, CMS-13).

Commented optional fields, never sent (ls-cms@dev:js/function/component-handler.js:427-432):

| Field | Commented value | Probable meaning (inferred) |
|---|---|---|
| `note` | `null` | Order note |
| `servizioAccessorio` | `null` | Change the accessory service |
| `stato` | `null` | Order status |
| `updateShippingTs` | `true` | Set the shipping timestamp |
| `updateDeliveryTs` | `false` | Set the delivery timestamp |

They suggest the backend's confirm request accepts these keys and that orders carry
shipping and delivery timestamps (inferred); the CMS never shows any date. Unused
constants also name an `importoContrassegno` (cash on delivery) and a
`codiceDocumentoTrasporto` field for this dialog
(ls-cms@dev:js/constant/costant.js:199, 201; no commit ever uses them): planned, not built
(inferred).

Success reloads the order lists; failure shows "Ops, si è verificato un errore". No
uniqueness or format check of the code on the client (server behaviour unknown).

### 4.5 Decline order

Generic dialog "Sei sicuro di voler procedere?"
(ls-cms@dev:js/components/types/dialog-components.js:181-216).
`POST /mono/cms/order/decline`, Bearer (verified)
(ls-cms@dev:js/function/component-handler.js:465-469; ls-cms@dev:js/rest/rest-caller.js:51-53):

```json
{
  "id": 118,
  "email": "ufficio@mobilificiorossi.example"
}
```

`email` is the row's "Cliente mittente"; it is probably used to notify the customer
(inferred). What happens to the record is (unknown) (Q-09). Declined orders are not
listed anywhere.

### 4.6 Set the tracking state by code ("Imposta tracking")

Opened from the truck icon of a registered order
(ls-cms@dev:js/components/types/data-tables-component.js:186-193;
ls-cms@dev:js/function/component-handler.js:50-61). Dialog:
ls-cms@dev:js/components/types/dialog-components.js:301-363.

| Field | Placeholder / label | Type | Prefill | Validation |
|---|---|---|---|---|
| Code | "Codice Ordine" | text, **editable** | the row's "Codice" | non-empty |
| Tracking state | button "Seleziona tracking" opening a menu | menu of six states | the last state chosen in the session | one chosen |

Buttons "Chiudi" / "Imposta stato".

* The menu always lists the six **hard-coded** states, labels "Non gestito" … "Consegnato",
  because the dialog is built at page load, before `statoTracking` is fetched (verified:
  a different API list was ignored) (ls-cms@dev:js/components/types/dialog-components.js:336;
  ls-cms@dev:js/function/ui-handler.js:83) (CMS-10).
* The order's current state is not preselected; the menu keeps the previous choice
  (verified) (CMS-12).

`POST /mono/cms/order/trace`, Bearer (verified)
(ls-cms@dev:js/function/component-handler.js:514, 526; ls-cms@dev:js/rest/dto/order-request.js:3-8):

```json
{
  "codice": "LS-2024-0118",
  "tracking": "2"
}
```

* `tracking` is the menu item's id as a **string** (an HTML attribute value), while
  approval sends the number `1` (CMS-21).
* The response is parsed as JSON and pushed to `userData.tracedOrders`, never shown
  (ls-cms@dev:js/rest/rest-caller.js:80-84). On `main` these responses were shown as a
  table (ls-cms@main:js/function/component-handler.js:202-204), so the response is an
  object (inferred); its fields are (unknown).
* The code is editable, so the dialog can set the state of **any** order by its code.
* Success reloads the order lists. Any failure (an error answer, presumably also for an
  unknown code; a network error; an expired session) shows "Codice non presente"
  (ls-cms@dev:js/function/component-handler.js:530-534).
* A validation failure (empty code, no state chosen) returns without restoring the button:
  "Imposta stato" keeps its spinner and blocks clicks until the page is reloaded (verified)
  (ls-cms@dev:js/function/component-handler.js:507-520) (CMS-08).
* No rule on transitions: any state, backwards and "Non gestito" included, no
  confirmation, no history in the UI.

### 4.7 Notes ("Aggiorna note")

Opened from the notepad icon of a registered order
(ls-cms@dev:js/components/types/data-tables-component.js:194-201;
ls-cms@dev:js/function/component-handler.js:38-48). Dialog:
ls-cms@dev:js/components/types/dialog-components.js:255-299. Pending orders have no notes
action.

Read — `POST /mono/cms/notes`, Bearer (verified)
(ls-cms@dev:js/function/component-handler.js:265-269; ls-cms@dev:js/rest/rest-caller.js:88-92):

```json
{
  "codice": "LS-2024-0118"
}
```

Field read from the answer: `notes` (a string or `null`):

```json
{
  "notes": "Consegna solo al mattino, chiamare il destinatario prima"
}
```

Display: a read-only `<pre>` with the note, inserted as HTML (XSS, verified), and a
single-line text field prefilled with the same text
(ls-cms@dev:js/function/component-handler.js:271-272). When the note is `null` both show
"Nessuna nota presente" — written into the field's **value**, not only its placeholder
(verified) (CMS-11).

Update — button "Imposta" → `POST /mono/cms/notes/update`, Bearer (verified)
(ls-cms@dev:js/function/component-handler.js:297-308; ls-cms@dev:js/rest/rest-caller.js:94-96):

```json
{
  "codice": "LS-2024-0118",
  "notes": "Consegna solo al mattino, chiamare il destinatario prima"
}
```

* No validation (it is commented out, ls-cms@dev:js/function/component-handler.js:289-295).
  Success reloads the order lists and closes the dialog.
* Pressing "Imposta" without typing, on an order that has no note, stores the literal
  "Nessuna nota presente" (verified).
* The field is single-line: line breaks are dropped on display and lost when re-saved
  (verified: "riga1\nriga2" became "riga1riga2").
* On failure the handler throws `ReferenceError: pendingDataTable is not defined`: the
  dialog closes, no message appears and "Imposta" stays blocked (verified)
  (ls-cms@dev:js/function/component-handler.js:315) (CMS-09).
* One note per order, replaced on save: no history, author or date.
* Whether this is the customer's order `note` sent by the portal
  (ls-order@dev:js/rest/dto/order-request.js:22) or a separate internal note is (unknown).

### 4.8 Order detail ("Dettaglio")

Opened from the magnifier of a pending or registered order
(ls-cms@dev:js/components/types/data-tables-component.js:202-209, 293-300). Dialog:
ls-cms@dev:js/components/types/dialog-components.js:365-424.

`POST /mono/api/order/detail`, Bearer (verified)
(ls-cms@dev:js/rest/rest-caller.js:99-106). The order's real code is sent: the definitive
code of a registered order, the TEMP code of a pending one (verified):

```json
{
  "codice": "LS-2024-0118"
}
```

Response structure (only the top-level keys are known,
ls-cms@dev:js/function/component-handler.js:93, 104, 115):

```json
{
  "spedizione": { "<chiave>": "<valore>" },
  "dettaglioColli": { "<chiave>": "<valore>" },
  "palletList": [ { "<chiave>": "<valore>" } ]
}
```

* Rendering: three sections headed "Dettaglio Spedizione", "Dettaglio Colli" and
  "Pallets"; each entry prints as "key: value" with the backend's raw key as label; one
  blank line between pallets (ls-cms@dev:js/function/component-handler.js:80-129).
* `null` prints "-" in "Dettaglio Spedizione" only and "null" in the other two sections
  (verified).
* The inner keys are (unknown). Plausible keys, from what the portal sends (inferred):
  recipient fields, `codiceDocumentoTrasporto`, `servizioAccessorio`, `note`; pallet lines
  `altezza`, `peso`, `larghezza`, `lunghezza`, `quantity`, `type` (`"0"` = PALLET,
  `"1"` = EPAL) (ls-order@dev:js/rest/dto/order-request.js:6-23, 32-37;
  ls-order@dev:js/constant/costant.js:87-90).
* The portal nests the lines as `dettaglioColli.palletList` (OP-15), while the CMS expects
  `palletList` at top level and prints `dettaglioColli` as flat pairs: the backend reshapes
  the data (inferred). The portal never sends "Numero Colli" (OP-07).
* The dialog opens before the answer arrives and shows the previously opened order until
  then; on failure the sections are emptied and "Ops, si è verificato un errore" appears
  (ls-cms@dev:js/function/component-handler.js:63-69, 80-89, 240-251) (CMS-16).

### 4.9 Report

Tab "Report" of the orders view (ls-cms@dev:js/components/types/form-components.js:53-92).

| Field | Label / placeholder | Type | Default | Validation |
|---|---|---|---|---|
| E-mail | placeholder "Email Cliente" | text | empty | `/^[^\s@]+@[^\s@]+\.[^\s@]+$/` (ls-cms@dev:js/constant/costant.js:28) |
| From | "Data inizio:" | native date | today (UTC) | `min="2024-01-01"`, `max` = today (UTC); nothing else |
| To | "Data fine:" | native date | today (UTC) | same; "from" after "to" is accepted (verified) |

Button "Invia Report", with no loading state (ls-cms@dev:js/components/types/form-components.js:82-90).

`POST /mono/cms/order/report`, Bearer (verified)
(ls-cms@dev:js/function/component-handler.js:213-238; ls-cms@dev:js/rest/rest-caller.js:109-116):

```json
{
  "email": "amministrazione@mobilificiorossi.example",
  "from": "2024-06-01",
  "to": "2024-06-30"
}
```

* Dates are sent as `YYYY-MM-DD` (verified). The response is ignored.
* Success: snackbar "Report inviato correttamente", then the orders view reloads and opens
  on "In Approvazione". Invalid e-mail **or** server failure: "Dati non validi" (verified).
* What the backend sends is (unknown): content, format, attachment, sender (owner: the
  backend sends reports by e-mail through AWS SES). Whether `email` is only the recipient
  or also selects that customer's orders is (unknown): the placeholder "Email Cliente"
  points to a customer, the success text only says the report was sent. Whether `to` is
  inclusive is (unknown) (Q-08).
* "Today" is computed in UTC (ls-cms@dev:js/components/types/form-components.js:68, 74):
  between midnight and 01:00 (02:00 in summer) Italian time the default and the maximum
  are yesterday (inferred).
* **Serious bug (verified):** once the orders view has been rebuilt (Refresh, any action,
  or a first report), the address typed on screen is ignored: the CMS reads a stale copy
  of the field and sends the address typed before the rebuild (or nothing → "Dati non
  validi"), and it sends one request per rebuild (two requests after one rebuild)
  (CMS-06, CMS-07).

### 4.10 Messages after actions

| Situation | Snackbar text | Source (component-handler.js unless stated) |
|---|---|---|
| Login failed | "Ops, si è verificato un errore" | 163 |
| Loading customers after login failed | "Ops, si è verificato un errore" | 209 |
| Approve/decline customer or order failed, or the reload after it failed | "Ops, si è verificato un errore" | 351, 384, 443, 477 |
| Detail failed | "Ops, si è verificato un errore" | 250 |
| Reading notes failed (the dialog closes) | "Ops, si è verificato un errore" | 279 |
| Saving notes failed | none (ReferenceError) | 315-316 |
| Setting tracking failed | "Codice non presente" | 533 |
| Report accepted | "Report inviato correttamente" | 229 |
| Report input invalid, or report failed | "Dati non validi" | 233, 236 |
| Refresh or first orders load failed | none; the three buttons stay disabled | ls-cms@dev:js/function/state-machine.js:13-50 |

The error dialog ("Errore" and its text) is created but never opened: `showError` has no
caller (ls-cms@dev:js/function/ui-handler.js:25-28). When an action succeeds but the
reload after it fails, the CMS reports a failure (inferred from the shared `try` block,
e.g. ls-cms@dev:js/function/component-handler.js:336-352).

## 5. Enumerations

### 5.1 `statoTracking`

Hard-coded default, exactly as in ls-cms@dev:js/components/data/user-data.js:22-53:

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

* Fetched with `GET /mono/api/enumeration/statoTracking`, no auth, at the first visit of the
  orders view; the answer replaces `userData.trackingStateEnum`
  (ls-cms@dev:js/rest/rest-caller.js:119-125; ls-cms@dev:js/function/state-machine.js:19).
* Its only consumer, the tracking menu, was built at page load from the default
  (ls-cms@dev:js/components/types/dialog-components.js:336), so the office always sees the
  list above (verified). The API answer presumably has the same shape (inferred: it is
  meant as a drop-in replacement and items are read as `obj.id` / `obj.label`,
  ls-cms@dev:js/components/types/dropdown-component.js:55-77).
* Used values: the menu shows `label`; `/cms/order/trace` receives `id` as a string;
  approval sends `1`. `name` is not used by the CMS.
* The public site shows the `status` field of `POST /mono/api/order/trace/state` as it is
  (ls-home@v1:js/custom.js:55-57, 67-73); whether `status` is the label or the name is
  Q-03.

### 5.2 `servizioAccessorio`

* Fetched with `GET /mono/api/enumeration/servizioAccessorio`, no auth, at the first visit
  of the orders view, into `userData.accessoryServiceEnum`
  (ls-cms@dev:js/rest/rest-caller.js:127-133; ls-cms@dev:js/function/state-machine.js:20).
* **Never used by the CMS**: no menu, and the "Ser. Accessorio" column shows the order's raw
  value (CMS-10). `createServiceDropdownMenu` exists but is never called and reads a store
  the CMS never fills (ls-cms@dev:js/components/types/dropdown-component.js:28-53;
  ls-cms@dev:js/components/data/component-data.js:120-126).
* Shape as read by the portal: items with `id` and `label`
  (ls-order@dev:js/components/types/dropdown-component.js:38-40); a `name` key and the JSON
  type of `id` are (unknown) for this enumeration. Values are (unknown):

```json
[
  { "id": "<id>", "label": "<etichetta del servizio>" }
]
```

* The portal sends the chosen `id` (a string) or the number `0` for "Nessun servizio
  accessorio" (ls-order@dev:js/function/data-handler.js:58-61); legacy orders may hold
  `null`, `0` or `1` for "no service" (chapter 03, section 9).

## 6. REST calls and objects

### 6.1 Every call

| # | Method and path | Auth | Request body | Response fields used | Caller |
|---|---|---|---|---|---|
| 1 | `POST /mono/auth/login` | none | `{"username","password"}` | `token`, `refreshToken` | ls-cms@dev:js/rest/rest-caller.js:7-16 |
| 2 | `POST /mono/api/customer/pending` | Bearer | `{}` | array of Customer (6.2) | ls-cms@dev:js/rest/rest-caller.js:19-27 |
| 3 | `POST /mono/api/customer/registered` | Bearer | `{}` | array of Customer | ls-cms@dev:js/rest/rest-caller.js:29-37 |
| 4 | `POST /mono/cms/customer/confirm` | Bearer | `{"email"}` | HTTP status only | ls-cms@dev:js/rest/rest-caller.js:39-41 |
| 5 | `POST /mono/cms/customer/decline` | Bearer | `{"email"}` | HTTP status only | ls-cms@dev:js/rest/rest-caller.js:43-45 |
| 6 | `POST /mono/api/order/pending` | Bearer | `{}` | array of Order (6.2) | ls-cms@dev:js/rest/rest-caller.js:56-64 |
| 7 | `POST /mono/api/order/registered` | Bearer | `{}` | array of Order | ls-cms@dev:js/rest/rest-caller.js:66-74 |
| 8 | `POST /mono/cms/order/confirm` | Bearer | `{"id", "req": {"codice", "importoAssicurato", "tracking": 1}}` | HTTP status only | ls-cms@dev:js/rest/rest-caller.js:47-49 |
| 9 | `POST /mono/cms/order/decline` | Bearer | `{"id", "email"}` | HTTP status only | ls-cms@dev:js/rest/rest-caller.js:51-53 |
| 10 | `POST /mono/cms/order/trace` | Bearer | `{"codice", "tracking": "<id>"}` | body must be JSON; stored, not shown | ls-cms@dev:js/rest/rest-caller.js:77-85 |
| 11 | `POST /mono/cms/notes` | Bearer | `{"codice"}` | `notes` | ls-cms@dev:js/rest/rest-caller.js:88-92 |
| 12 | `POST /mono/cms/notes/update` | Bearer | `{"codice", "notes"}` | HTTP status only | ls-cms@dev:js/rest/rest-caller.js:94-96 |
| 13 | `POST /mono/api/order/detail` | Bearer | `{"codice"}` | `spedizione`, `dettaglioColli`, `palletList` | ls-cms@dev:js/rest/rest-caller.js:99-106 |
| 14 | `POST /mono/cms/order/report` | Bearer | `{"email", "from", "to"}` | HTTP status only | ls-cms@dev:js/rest/rest-caller.js:109-116 |
| 15 | `GET /mono/api/enumeration/statoTracking` | none | — | array of `{id, name, label}` (stored, not shown) | ls-cms@dev:js/rest/rest-caller.js:119-125 |
| 16 | `GET /mono/api/enumeration/servizioAccessorio` | none | — | stored, unused | ls-cms@dev:js/rest/rest-caller.js:127-133 |

Behaviour common to every call (ls-cms@dev:js/rest/rest-caller.js:135-187):

* `fetch` with `Content-Type: application/json`, also on GET; success means `response.ok`;
  anything else throws `Error("Rest call failed")`. Error bodies are never read; there is
  no timeout, retry or cancellation; calls are chained with `await`, one at a time.
* Lists are read with POST and an empty JSON object as body.
* Where the body is parsed (calls 1, 2, 3, 6, 7, 10, 11, 13, 15, 16), a 2xx answer with an
  empty or non-JSON body counts as a failure (inferred).
* Lists, detail and enumerations live under `/mono/api/`, like the public and portal calls;
  approvals, declines, tracking, notes (read and write) and the report under `/mono/cms/`;
  login under `/mono/auth/`. Chapter 09 (API-03) retires these endpoints at cut-over (the
  enumerations stay, API-02); their behaviour moves into the new back office. Chapter 02
  consolidates all legacy endpoints.

### 6.2 Objects as seen by the CMS

**Customer** (items of calls 2 and 3; ls-cms@dev:js/components/types/data-tables-component.js:57-65, 72-80):

| Field | Type (observed use) | Notes |
|---|---|---|
| `id` | number (inferred) | Shown as "ID" |
| `ragioneSociale` | string | Company or personal name |
| `email` | string | Key used by approve/decline |
| `indirizzo` | string | |
| `provincia` | string | |
| `citta` | string | |
| `cap` | string (inferred) | |
| `telefono` | string or null | |
| `emailConfirmed` | boolean (truthy test) | "Email confermata" SI/NO |

The portal registers customers with `email`, `ragioneSociale`, `indirizzo`, `provincia`,
`citta`, `cap`, `telefono` (ls-order@dev:js/rest/dto/customer-request.js:9-19) and knows
three statuses, `NEW`, `PENDING`, `REGISTERED` (ls-order@dev:js/constant/costant.js:17-19);
the two CMS lists match `PENDING` and `REGISTERED` (inferred). The CMS reads no status,
date or price-list field; others may exist (unknown).

```json
{
  "id": 42,
  "ragioneSociale": "Mobilificio Rossi S.r.l.",
  "email": "ufficio@mobilificiorossi.example",
  "indirizzo": "Via Garibaldi 12",
  "provincia": "MB",
  "citta": "Lissone",
  "cap": "20851",
  "telefono": "039 2456789",
  "emailConfirmed": true
}
```

**Order** (items of calls 6 and 7; ls-cms@dev:js/components/types/data-tables-component.js:149-163, 240-254):

| Field | Type (observed use) | Notes |
|---|---|---|
| `id` | number (inferred) | Key of approve/decline |
| `codice` | string, never null in pending lists (inferred) | Contains "TEMP" while pending; definitive code after approval; key of tracking, notes, detail |
| `email` | string | "Cliente mittente" |
| `codiceDocumentoTrasporto` | string or null | Transport document number (DDT) |
| `ragioneSociale` | string | Recipient (inferred) |
| `indirizzo`, `citta`, `provincia`, `cap`, `telefono` | string | Recipient address (inferred) |
| `importo` | number or string, null until set (inferred) | "Importo" |
| `servizioAccessorio` | (unknown format) | Raw |
| `stato` | (unknown values) | Raw |
| `tracking` | (unknown format) | Raw |

```json
{
  "id": 118,
  "codice": "<temporary code, contains TEMP>",
  "email": "ufficio@mobilificiorossi.example",
  "codiceDocumentoTrasporto": "DDT 245/2024",
  "ragioneSociale": "Ferramenta Bianchi S.n.c.",
  "indirizzo": "Via Roma 5",
  "citta": "Bergamo",
  "provincia": "BG",
  "cap": "24121",
  "telefono": "035 765432",
  "importo": null,
  "servizioAccessorio": "<unknown format>",
  "stato": "<unknown value>",
  "tracking": "<unknown format>"
}
```

Other order data seen by the CMS: on approval `codice`, `importoAssicurato`, `tracking`
(optional, commented: `note`, `servizioAccessorio`, `stato`, `updateShippingTs`,
`updateDeliveryTs`) (4.4); the note `notes` (4.7); the detail `spedizione`,
`dettaglioColli`, `palletList` (4.8).

**Other shapes:** login answer `{token, refreshToken}` (1.5); tracking state
`{id, name, label}` (5.1); accessory service `{id, label}` (5.2).

## 7. Copy deck

### 7.1 `js/constant/language-messages.js`

All texts live in one Italian object; `getLanguageMessages` (a language switch) is never
called (ls-cms@dev:js/constant/language-messages.js:101-107). Line numbers refer to that
file.

Shown in the CMS:

| Key | Italian text (verbatim) | Where | Line |
|---|---|---|---|
| `username` | "username" | Login placeholder | 22 |
| `password` | "password" | Login placeholder | 23 |
| `orderCode` | "Codice Ordine" | "Imposta tracking" code placeholder | 26 |
| `emailCliente` | "Email Cliente" | Report e-mail placeholder | 27 |
| `dialogConfirm` | "Conferma" | Login, approve/decline dialogs | 30 |
| `dialogSet` | "Imposta" | Notes dialog | 31 |
| `dialogCancel` | "Annulla" | Approve/decline dialogs | 32 |
| `dialogClose` | "Chiudi" | Notes, tracking, detail, error dialogs | 33 |
| `errorDialogTitle` | "Errore" | Error dialog (never opened) | 37 |
| `loginDialogTitle` | "Login" | Login dialog | 40 |
| `confirmationDialogTitle` | "Sei sicuro di voler procedere?" | Approve/decline customer, decline order | 41 |
| `approveOrderDialogTitle` | "Approva ordine" | Approve order | 42 |
| `traceOrderDialogTitle` | "Imposta tracking" | Tracking dialog | 43 |
| `updateNotesDialogTitle` | "Aggiorna note" | Notes dialog | 44 |
| `detailDialogTitle` | "Dettaglio" | Detail dialog | 45 |
| `errorDialogText` | "Ops, si è verificato un errore riprova più tardi. Se il problema persiste contattaci via email o telefonicamente" | Error dialog (never opened); written on two lines in the source | 47-49 |
| `pendingCustomersEmpty` | "Nessun utente da approvare" | Empty pending customers | 57 |
| `registredCustomersEmpty` | "Nessun utente registrato" | Empty registered customers | 58 |
| `pendingOrdersEmpty` | "Nessun ordine da approvare" | Empty pending orders | 61 |
| `registredOrdersEmpty` | "Nessun ordine registrato" | Empty registered orders | 62 |
| `sendReport` | "Invia Report" | Report button | 73 |
| `search` | "Imposta stato" | Tracking dialog submit | 75 |
| `pendingCustomers` | "In Approvazione" | Customers tab | 79 |
| `registredCustomers` | "Registrati" | Customers tab | 80 |
| `pendingOrders` | "In Approvazione" | Orders tab | 82 |
| `registredOrders` | "Registrati" | Orders tab | 83 |
| `report` | "Report" | Orders tab | 87 |
| `trackingStateOpenDropdownText` | "Seleziona tracking" | Tracking menu button | 89 |

Present but never shown (copied from the portal or left over):

| Key | Italian text (verbatim) | Line |
|---|---|---|
| `pendingFormText` | "La tua richiesta di registrazione è andata a buon fine, a breve verrà approvata" | 3 |
| `registrationFormText` | "Inserisci i dati dell'azienda" | 4 |
| `recipientFormText` | "Inserisci i dati del destinatario" | 5 |
| `orderFormText` | "Inserisci i dati dell'ordine" | 6 |
| `emailFormText` | "Inserisci email" | 7 |
| `confirmDataFormText` | "Per confermare i dati inseriti clicca nuovamente il pulsante 'Avanti' " | 8 |
| `registrationEmailSentFormText` | "Ci siamo quasi, per completare la registrazione segui le istruzioni contenute nella mail che ti abbiamo mandato" | 9 |
| `orderEmailSentFormText` | "Il tuo ordine è stato inoltrato presso il nostro centro logistico. Verrai notificato dell'avvenuta presa in carico dell'ordine. Grazie per aver scelto Sanchez Logistica!" | 10 |
| `weightUnit`, `lenghtUnit`, `volumeUnit` | "kg", "cm", "m3" | 13-15 |
| `overall` | "Totale" | 18 |
| `dialogModify` | "Modifica" | 34 |
| `confirmationDialogDataTitle` | "Vuoi procedere?" | 52 |
| `approveOrderDialogDataTitle` | "Approva Ordine" | 53 |
| `tracedOrdersEmpty` | "Nessun ordine da approvare" | 63 |
| `genericPallet`, `epal` | "Pallet Generico", "Epal" | 66-67 |
| `approve`, `decline` | "Approva", "Rimuovi" (the row-button labels of `main`) | 71-72 |
| `searchOrder` | "Tracking" (the old tracking tab: "Ricerca" on `main`, then "Gestione Tracking") | 85 |
| `nextButtonText`, `previousButtonText` | "Avanti", "Indietro" | 92-93 |
| `serviceOpenDropdownText` | "Seleziona servizio accessorio" | 95 |
| `palletOpenDialogButtonText`, `palletOpenDropdownTypeText` | "Aggiungi Pallet", "Tipo Pallet" | 97-98 |

### 7.2 Hard-coded texts

| Text (verbatim) | Where | Source |
|---|---|---|
| "Logistica Sanchez - CMS" | Page title | ls-cms@dev:index.html:5 |
| "Ops, si è verificato un errore" | Snackbar, most failures | ls-cms@dev:js/function/component-handler.js:163, 209, 250, 279, 351, 384, 443, 477 |
| "Report inviato correttamente" | Snackbar, report accepted | ls-cms@dev:js/function/component-handler.js:229 |
| "Dati non validi" | Snackbar, report invalid or failed | ls-cms@dev:js/function/component-handler.js:233, 236 |
| "Codice non presente" | Snackbar, tracking failed | ls-cms@dev:js/function/component-handler.js:533 |
| "Nessuna nota presente" | Notes: `<pre>`, field value and placeholder | ls-cms@dev:js/function/component-handler.js:261-262, 271-272; ls-cms@dev:js/components/types/dialog-components.js:269, 274 |
| "Codice" | Approve order, code placeholder | ls-cms@dev:js/components/types/dialog-components.js:113 |
| "Importo Totale (per cliente)" | Approve order, amount placeholder | ls-cms@dev:js/components/types/dialog-components.js:116 |
| "Dettaglio Spedizione", "Dettaglio Colli", "Pallets" | Detail section headings | ls-cms@dev:js/components/types/dialog-components.js:397, 403, 409 |
| "Data inizio:", "Data fine:" | Report date labels | ls-cms@dev:js/components/types/form-components.js:67, 73 |
| "ID", "Ragione sociale", "Email", "Indirizzo", "Provincia", "Città", "Cap", "Telefono", "Email confermata" | Customer table headers | ls-cms@dev:js/components/types/data-tables-component.js:57-65, 72-80 |
| "ID", "Codice", "Cliente mittente", "Codice Doc. Trasporto", "Destinatario", "Indirizzo", "Città", "Cap", "Telefono", "Importo", "Ser. Accessorio", "Stato", "Tracking" | Order table headers | ls-cms@dev:js/components/types/data-tables-component.js:150-162, 241-253 |
| "SI", "NO" | "Email confermata" values | ls-cms@dev:js/components/types/data-tables-component.js:65, 80 |
| "-" | Hidden TEMP code; missing DDT, amount, shipment value | ls-cms@dev:js/components/types/data-tables-component.js:153, 159, 242, 244, 250; ls-cms@dev:js/function/component-handler.js:97 |
| "Non gestito", "Presa in carico", "Transito", "Terminal destinatario", "Assegnato", "Consegnato" | Tracking menu | ls-cms@dev:js/components/data/user-data.js:26-51 |

## 8. Quirks, bugs and security issues

Last column: **Fix** = do it right in the new app (record in an ADR when it changes
visible behaviour); **Replicate** = keep the legacy behaviour; **Ask** = owner decision
(chapter 11).

| ID | Kind | Issue | Evidence | Replicate / fix |
|---|---|---|---|---|
| CMS-01 | security | The password is typed into a plain text field and shown on screen (verified). | ls-cms@dev:js/components/types/dialog-components.js:74; ls-cms@dev:js/components/types/text-field-component.js:19 | Fix: `type="password"`, `autocomplete="current-password"`. |
| CMS-02 | security | Username and password stay in page memory for the whole session, and the password stays in the hidden login input (verified). | ls-cms@dev:js/function/component-handler.js:148-152 | Fix: never keep credentials after login. |
| CMS-03 | security | Session = bearer token in page memory; refresh token stored and never used; no expiry or 401 handling; no logout; a reload forces a new login. | ls-cms@dev:js/rest/rest-caller.js:14-15, 170-187 | Fix: server session cookie (HttpOnly, Secure, SameSite), idle and absolute timeout, logout, redirect to login on expiry, CSRF protection (ADM-01). |
| CMS-04 | security | Stored XSS: table cells, detail entries and the notes `<pre>` insert backend data as HTML. Customer and order data come from the unauthenticated portal, so anyone can plant script that runs in the office's browser (verified with mock data) and could read the password left in the login input (CMS-02). | ls-cms@dev:js/components/types/data-tables-component.js:33, 123, 213, 304; ls-cms@dev:js/function/component-handler.js:93-126, 272 | Fix: auto-escaping templates, strict CSP; tests with hostile values in every field. |
| CMS-05 | security | Reads (`/mono/api/customer/*`, `/mono/api/order/pending`, `registered`, `detail`) sit under the same prefix as unauthenticated portal and tracking calls; whether the backend enforces the token there is unknown. If not, every customer's and order's personal data is public. | ls-cms@dev:js/constant/rest-constant.js:9-10, 18-19, 29 | Fix: every back-office read needs a staff session and role, with authorisation tests. Ask: have the legacy behaviour checked by the owner before cut-over. |
| CMS-06 | bug | MDC components are cached by id and never re-created after a rebuild: the report e-mail field read after a rebuild is the old, detached one, so the report goes to the address typed before (or fails with "Dati non validi"); rebuilt tab bars lose their MDC behaviour (verified). | ls-cms@dev:js/components/data/component-data.js:20-24; ls-cms@dev:js/function/ui-handler.js:192-204; ls-cms@dev:js/function/component-handler.js:214-216 | Fix (new UI). Test: the report goes to the typed address, also after other actions. |
| CMS-07 | bug | Click listeners pile up: each rebuild queues listeners for the same positional ids and re-attaches all older ones. Effects (verified): one report request per rebuild; detail and notes fetched several times; after a refresh that shifts rows, the notes dialog can show another order's note and "Imposta" then saves it onto the clicked order (reproduced with a delayed mock answer); the detail can show another order. | ls-cms@dev:js/components/data/component-data.js:96-116; ls-cms@dev:js/components/types/data-tables-component.js:106, 186-209 | Fix: actions addressed by order id, one request per click, idempotent server actions. |
| CMS-08 | bug | Tracking dialog: after a validation failure (no state chosen, empty code) "Imposta stato" keeps its spinner and blocks clicks until reload (verified); a later load state would save the spinner as the label (inferred). | ls-cms@dev:js/function/component-handler.js:507-520; ls-cms@dev:js/function/ui-handler.js:36-60 | Fix. |
| CMS-09 | bug | Notes save failure throws `ReferenceError` (`pendingDataTable`): no message, button blocked (verified). | ls-cms@dev:js/function/component-handler.js:312-320 | Fix. |
| CMS-10 | data | Enumerations fetched but unused: the tracking menu uses the hard-coded list built at page load; the accessory-service list is never used; "Stato", "Tracking", "Ser. Accessorio" show raw values. | ls-cms@dev:js/components/types/dialog-components.js:336; ls-cms@dev:js/rest/rest-caller.js:119-133; ls-cms@dev:js/components/types/data-tables-component.js:160-162 | Replicate the six states with ids 0–5 and their labels (ADM-10). Fix: show labels everywhere. |
| CMS-11 | data | Notes: "Nessuna nota presente" is written into the field value and saved as the note if staff press "Imposta" (verified); the single-line field drops line breaks (verified). | ls-cms@dev:js/function/component-handler.js:261-272; ls-cms@dev:js/components/types/dialog-components.js:272-276 | Fix: textarea, empty when there is no note. Migration: treat the literal "Nessuna nota presente" as empty (inferred: legacy data may contain it). |
| CMS-12 | data | Approve-order fields keep the previous order's code and amount; the tracking menu keeps the previous state (verified). | ls-cms@dev:js/function/component-handler.js:29-36, 50-61 | Fix: a fresh form per order; preselect the order's current tracking state. |
| CMS-13 | data | Amount: free text sent as a string ("180,50", "abc" pass); written as `importoAssicurato`, read as `importo`; the label and the key disagree on its meaning. | ls-cms@dev:js/components/types/dialog-components.js:116; ls-cms@dev:js/function/component-handler.js:408, 424; ls-cms@dev:js/components/types/data-tables-component.js:159, 250 | Fix: decimal EUR input accepting Italian format, server validation, one field. Ask: meaning and VAT (Q-05). |
| CMS-14 | data | Codes: no format or uniqueness check in the CMS; TEMP detection is a case-sensitive substring; a null code breaks the pending view (inferred). | ls-cms@dev:js/function/component-handler.js:407; ls-cms@dev:js/components/types/data-tables-component.js:242 | Fix: "pending" as a state, not a code pattern; unique codes. Ask: format (Q-06). |
| CMS-15 | bug | Refresh and the first orders load have no error handling: on failure the buttons stay disabled, no message (verified). | ls-cms@dev:js/function/state-machine.js:13-50 | Fix. |
| CMS-16 | UX | Detail: shows the previous order while loading; raw technical keys as labels; null as "-" in one section only. | ls-cms@dev:js/function/component-handler.js:63-69, 80-129 | Fix: a labelled detail page (ADM-08). |
| CMS-17 | UX | Generic or misleading messages: same text for wrong password and outage; "Codice non presente" for any tracking failure; "Dati non validi" for server errors; successes shown through the error snackbar; a successful action with a failed reload is reported as failed; the error dialog is dead code. | ls-cms@dev:js/function/component-handler.js:163, 229-236, 336-352, 533; ls-cms@dev:js/function/ui-handler.js:25-28 | Fix: specific messages; log server errors. |
| CMS-18 | UX | Every action or refresh rebuilds the view and returns to the first tab (verified). | ls-cms@dev:js/components/types/form-components.js:35; ls-cms@dev:js/function/ui-handler.js:192-204 | Fix: keep the current tab and filters. |
| CMS-19 | UX | Confirmation dialogs do not name the customer or order (verified). | ls-cms@dev:js/components/types/dialog-components.js:155-159, 191-195, 228-232 | Fix: show name, e-mail or code. |
| CMS-20 | rule | Staff can approve customers whose e-mail is not confirmed (verified); the server rule is unknown. | ls-cms@dev:js/function/component-handler.js:323-345 | Ask: allow or block approval before e-mail confirmation (ADM-02, CUS-02). |
| CMS-21 | data | Tracking id sent as a string ("2") on update and as a number (1) on approval (verified). | ls-cms@dev:js/function/component-handler.js:425, 514 | Fix: typed values. |
| CMS-22 | UX | Report: "from" after "to" accepted (verified); "today" in UTC; no loading state or double-click guard; e-mail only. | ls-cms@dev:js/components/types/form-components.js:68, 74; ls-cms@dev:js/function/component-handler.js:218-226 | Fix (ADM-09): validate the range, Europe/Rome dates, download as well. |
| CMS-23 | UX | Stray commas above every table; "null" printed for empty values; "Monza(MB)" without a space; table `aria-label` "Dessert calories" (verified). | ls-cms@dev:js/components/types/data-tables-component.js:41, 48, 140, 156, 230, 247, 321 | Fix. |
| CMS-24 | a11y | Icon-only buttons without text, `title` or `aria-label` (verified); placeholders as labels; `aria-label="Label"` on every input; no `lang` on `<html>`; no keyboard submit. | ls-cms@dev:index.html:3, 30-43; ls-cms@dev:js/components/types/button-component.js:49-55; ls-cms@dev:js/components/types/text-field-component.js:14-19 | Fix (NFR-01). |
| CMS-25 | bug | "Disabled" buttons are only CSS (`pointer-events: none`); keyboard activation still works (inferred), so double submissions are possible. | ls-cms@dev:js/utility/component-util.js:3-11 | Fix: real `disabled` and server idempotency. |
| CMS-26 | scale | Every list is loaded and rendered in full; no search, filter, sort or pagination; the registered-orders list grows without limit; 14 columns scroll sideways on a phone (inferred). | ls-cms@dev:js/rest/rest-caller.js:56-74; ls-cms@dev:js/components/types/data-tables-component.js:147-236 | Fix (ADM-03, ADM-05, NFR-01). |
| CMS-27 | config | Plain-HTTP UAT host hard-coded; no environment configuration; production uses another host. | ls-cms@dev:js/constant/rest-constant.js:2-3 | Fix: same origin, HTTPS only, configuration from the environment. |
| CMS-28 | privacy | The admin page loads a Google Fonts stylesheet; Bootstrap JS and jQuery are loaded and unused. | ls-cms@dev:index.html:10, 15-16 | Fix: self-host or drop. |
| CMS-29 | code | Dead code copied from the portal (pallet dialog and cards, registration and order validators, form texts) and broken unused helpers (`updateCmsFormUI` uses undefined names; `refreshDataTable`; `isValidDate` passes a string as regex; the MDC init list never shrinks because indices are compared with objects). | ls-cms@dev:js/function/ui-handler.js:95-107; ls-cms@dev:js/function/component-handler.js:484-495; ls-cms@dev:js/utility/string-util.js:27-29; ls-cms@dev:js/components/data/component-data.js:73 | Fix: do not port. |

## 9. Business rules and gaps

### 9.1 Rules the code shows (keep them)

These are observations from the CMS code; chapter 09 turns them into requirements.

1. **Customers wait for approval.** Customers registered through the portal are pending
   until staff approve or decline them; both actions identify the customer by e-mail
   (ls-cms@dev:js/function/component-handler.js:339-341, 374-376). The list shows whether
   the customer confirmed the e-mail, but approval does not depend on it (CMS-20).
2. **Orders wait for approval with a temporary code.** Pending orders carry a code
   containing "TEMP", hidden as "-" (ls-cms@dev:js/components/types/data-tables-component.js:242).
3. **Approval assigns the code and the amount.** Approving an order needs a shipment code
   and an amount, both mandatory, typed by staff ("Importo Totale (per cliente)"); the
   approval request sets tracking state 1, "Presa in carico"
   (ls-cms@dev:js/function/component-handler.js:397-426). Pricing is manual (owner).
4. **Declining an order passes the customer's e-mail** with the order id, presumably to
   notify the customer (inferred) (ls-cms@dev:js/function/component-handler.js:465-468).
5. **The shipment code is the key** for tracking updates, notes and detail
   (ls-cms@dev:js/function/component-handler.js:266, 305;
   ls-cms@dev:js/rest/rest-caller.js:99-102; ls-cms@dev:js/rest/dto/order-request.js:3-8)
   and for public tracking (ls-home@v1:js/custom.js:67-73).
6. **Six tracking states, ids 0–5**, with the labels of 5.1. Staff may set any state at any
   time, backwards included; the UI offers it on registered orders only, but the endpoint
   takes any typed code.
7. **One free-text note per registered order**, read and replaced by staff.
8. **Order detail** has three parts: shipment, packages, pallet lines.
9. **Reports** are produced by the backend and e-mailed for a date range from 2024-01-01
   to today, to an address typed by staff (whether it also filters by customer is unknown).
10. **List conventions:** registered customers and registered orders newest first (reversed
    backend order); pending lists in backend order; "-" for a missing DDT or amount;
    "SI"/"NO" for e-mail confirmation; city shown with the province in brackets.
11. **Approved orders leave the pending list** and appear among registered orders after the
    reload (inferred from the reload after approval).

### 9.2 Gaps an admin would expect (observations)

* No search, filter, sort, pagination or export in any list.
* No customer page (data plus orders), and no editing of customer or order data after
  approval: address corrections, amount changes and wrong codes cannot be fixed.
* Declined customers and orders disappear: no list, no undo, no reason recorded.
* No dates anywhere (registration, order, approval, shipping, delivery), no tracking
  history, no record of who changed what.
* No user management, roles, password change or logout; no session persistence.
* No counters or alerts for new registrations or orders: the office must press Refresh.
* No printing (labels, transport documents) and no attachments (proof of delivery).
* Confirmation dialogs without context; no bulk actions (for example, many tracking
  updates at once).
* Accessory service, order status and tracking state shown as raw values.
* No suggested amount from the customer's price list (pricing is manual today; each
  customer has an Excel price list — owner; chapter 07).
* Not designed for a phone.

### 9.3 Where each CMS function lands in chapter 09

| Chapter 09 | CMS behaviour | Section here |
|---|---|---|
| ADM-01 | Login (fixes CMS-01…03) | 1.5, 4.1 |
| ADM-02 | Customers "In Approvazione", approve/decline | 3.2, 4.2, 4.3 |
| ADM-03 | Customers "Registrati" | 3.3 |
| ADM-04 | Orders "In Approvazione", approve/decline | 3.4, 4.4, 4.5 |
| ADM-05 | Orders "Registrati" | 3.5 |
| ADM-06 | "Imposta tracking" | 4.6 |
| ADM-07 | "Aggiorna note" | 4.7 |
| ADM-08 | "Dettaglio" | 4.8 |
| ADM-09 | "Report" | 4.9 |
| ADM-10 | `statoTracking`, `servizioAccessorio` | 5 |

## 10. Unknowns

Questions the CMS code cannot answer; the ones that need the owner are in chapter 11.

| Topic | Question | Related |
|---|---|---|
| E-mails | Which e-mails do approve/decline of customers and orders trigger, to whom, with which text? | Q-07 |
| Declines | Are declined customers and orders deleted or kept with a status? May a declined e-mail register again? | Q-09 |
| Amount | Is `importo` (read) the same value as `importoAssicurato` (written)? Price for the customer or insured value? VAT? Decimal format accepted? | Q-05 |
| Codes | Format of temporary and definitive codes; uniqueness; case rules. | Q-06 |
| Raw columns | Values of `stato`, `tracking` and `servizioAccessorio` in the order lists (ids, names or labels). | chapter 02 |
| Detail | Keys inside `spedizione`, `dettaglioColli` and `palletList`. | chapter 02, 08 |
| Notes | Are CMS notes the customer's order `note` or a separate internal note? | chapter 08 |
| Report | Content and format; is `email` the recipient, a customer filter or both; is `to` inclusive? | Q-08 |
| Security | Do `/mono/api/customer/*` and `/mono/api/order/pending`, `registered`, `detail` require the token? | CMS-05 |
| Session | Token format and lifetime, refresh endpoint, roles, number and kind of staff accounts. | Q-17 |
| Hosting | Production URL and hosting of the CMS; which `HOST` the deployed build uses. | Q-16 |
| Trace | Body of `/mono/cms/order/trace` answers; status code for an unknown code. | chapter 02 |
| Order of lists | Does the backend return lists in ascending id order (the CMS reverses the registered lists)? | — |
| Timestamps | What `updateShippingTs` / `updateDeliveryTs` set, and whether orders store these dates. | chapter 08 |
| Accessory services | The values of the enumeration. | chapter 03 |
