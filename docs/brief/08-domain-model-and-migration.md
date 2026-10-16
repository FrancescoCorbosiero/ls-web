# 08 · Domain model and data migration

This chapter has four parts:

1. The legacy entities as the clients see them: fields, inferred types, identifiers,
   states and transitions (§1).
2. The target model of the monolith for the core logistics data: customers and their
   logins, orders with lines and address snapshots, accessory services, tracking events,
   notes, leads, staff users and roles, audit log (§2). The pricing tables are in chapter 07
   §6.2.
3. The field-by-field mapping from legacy to target, quirks included (§3).
4. The migration plan: from a database dump (preferred) or through the legacy API
   (fallback), with an idempotent importer, a dry-run and a reconciliation report; then
   cut-over, rollback (§4) and the data-quality issues to expect (§5).

Read chapter 02 first: it defines the endpoints `EP-01…EP-20` and the contract unknowns
`CU-01…` used here.

How to read it:

* §1, §3 and §5 were written before the backend source was available, from the client
  code. **Chapter 12b reads the source (`ls@migration`) and wins where they disagree**: its
  §1 gives the real schema (seven MySQL tables), §5.1 corrects §1 claim by claim, §5.2–5.3
  settle the DQ list and add DQ-25…DQ-37, §5.5 maps every column, §5.6 replaces the
  migration steps. Applied here: the customer state machine (§2.4), the migration source
  (§4.2–4.3) and the cut-over on the legacy VPS (§4.7–4.9; 12d §6.5). Where the brief and
  the code disagree, the code wins.
* Refs as in chapter 02: `ls-order@dev` = `dbe7292`, `ls-cms@dev` = `f0c881c`,
  `ls-home@v1` = `8201716`, `ls-home@fe4b056` = the new static site, `owner`.
* Table and column names are proposals in `snake_case` for PostgreSQL, the default stack of
  chapter 10. Status enums are stored as `varchar` with a `CHECK`, mapped with JPA
  `@Enumerated(STRING)`.
* Data-quality issues are `DQ-01…` (§5). Sample values are illustrative (codes like
  `LS-2024-0118` are not the real format, Q-06).

## 1. Legacy entities as observed

> The clients' view. What the backend really stores is in 12b §1 (schema) and §3 (state
> machines); 12b §5.1 says which statements below are confirmed or corrected. The main
> corrections: customer `NEW` = registered but unverified, or declined; `PENDING` =
> verified, waiting for staff; approval needs a verified e-mail; declines keep the row and
> reset it to `NEW` (`DECLINED` only before 2024-07-22); lines are stored one row per unit
> with an aggregate row; amounts are `DOUBLE` columns; dates exist on every table; the note
> is one shared column (12b §0).

### 1.1 Overview

```
Customer  (business key: e-mail)
   │ 1
   │ the order names its customer by e-mail (clienteEmail / email)
   ▼ n
Order  (keys: id for approve/decline; codice for tracking, notes, detail)
   ├── embeds the Recipient ("destinatario"), no address book
   ├── has n Pallet/colli lines ("palletList"), no line id
   ├── refers to one Accessory service (enumeration id) or "none"
   ├── has one current Tracking state (enumeration id 0–5), no history
   └── has one free-text Note (read and replaced by code)

Staff user (username) ── login ──▶ token, refreshToken
```

Whether the backend stores the order→customer link as a foreign key or as the e-mail
string is (unknown). No client ever sends a customer `id`.

### 1.2 Customer

| Field (wire) | Inferred type | Seen in | Notes | Source |
|---|---|---|---|---|
| `id` | integer (inferred) | EP-04, EP-05 | Shown as "ID"; never sent back | ls-cms@dev:js/components/types/data-tables-component.js:57, 72 |
| `email` | string, as typed (case kept; the portal's regex forbids whitespace) | EP-02, EP-03, EP-04/05, EP-06/07; orders: `clienteEmail` (EP-08), `email` (EP-09/10) | The business key of every write | ls-order@dev:js/function/data-handler.js:8-10; ls-cms@dev:js/function/component-handler.js:339-341, 374-376 |
| `ragioneSociale` | string | EP-03, EP-04/05 | "Ragione Sociale/Nominativo": a company or a person | ls-order@dev:js/constant/language-messages.js:39 |
| `indirizzo` | string | same | Street and number in one field ("Via") | ls-order@dev:js/rest/dto/customer-request.js:13 |
| `provincia` | string | same | Free text, no 2-letter check | ls-order@dev:js/function/form-validator.js:24-45 |
| `citta` | string | same | | |
| `cap` | string | same | US-style rule: 5 digits or ZIP+4 `20831-1234` (OP-09) | ls-order@dev:js/constant/costant.js:28 |
| `telefono` | string or `null` | same | Required at registration despite the label (OP-08); US-style pattern (OP-09) | ls-order@dev:js/constant/costant.js:27; ls-order@dev:js/function/data-handler.js:85 |
| `emailConfirmed` | boolean (truthy test) | EP-04/05 | "Email confermata" SI/NO; set by the link in the registration e-mail (inferred) | ls-cms@dev:js/components/types/data-tables-component.js:65, 80 |
| `status` | `"NEW"`, `"PENDING"`, `"REGISTERED"` | EP-02 only | `NEW` = no record for that e-mail (inferred) | ls-order@dev:js/constant/costant.js:17-19 |

No client reads or sends a registration date, VAT number, tax code, contact person,
pick-up address or price list. Each customer's price list lives in Excel (owner; chapter 07).

**Identifiers.** The e-mail is the key for the status check (EP-02), registration
(EP-03), approval and decline (EP-06, EP-07) and ordering (EP-08). `id` is display-only.
Case variants of one address may therefore be two customers (OP-11).

**States and transitions**

```
 (no record) = NEW ──EP-03 register──▶ PENDING [emailConfirmed = false]
                                          │ link in the e-mail (inferred) → emailConfirmed = true
                                          ├──EP-06 confirm──▶ REGISTERED   (the CMS UI would allow it, CMS-20; the server refuses an unverified customer, 12b §3.1)
                                          └──EP-07 decline──▶ ? deleted (back to NEW) or flagged (unknown, CU-16)
```

| From | Event | To | Evidence |
|---|---|---|---|
| no record (`NEW`) | EP-03 | `PENDING`, `emailConfirmed` false | portal copy "a breve verrà approvata" (ls-order@dev:js/constant/language-messages.js:3); CMS pending list (inferred) |
| `PENDING` or `REGISTERED` | click on the link in the registration e-mail | `emailConfirmed` true | (inferred) from the column and the copy "segui le istruzioni contenute nella mail" (ls-order@dev:js/constant/language-messages.js:9); mechanism (unknown, CU-24) |
| `PENDING` | EP-06 | `REGISTERED` | lists and portal statuses (inferred) |
| `PENDING` | EP-07 | (unknown) | CU-16, Q-09 |

No client offers a transition out of `REGISTERED`: no edit, suspension or deletion.

### 1.3 Order

Every field any client sends or reads:

| Field (wire) | Type as seen | Where | Meaning | Source |
|---|---|---|---|---|
| `id` | integer (inferred) | EP-09/10 read; EP-11, EP-12 write | Key of approve and decline | ls-cms@dev:js/function/component-handler.js:421, 466 |
| `codice` | string | EP-09/10 read; EP-11 `req.codice`; key of EP-13…EP-17 | Contains `TEMP` while pending (shown "-"); the staff-typed definitive code after approval (inferred) | ls-cms@dev:js/components/types/data-tables-component.js:242; ls-cms@dev:js/function/component-handler.js:423 |
| `clienteEmail` / `email` | string | EP-08 write / EP-09/10 read ("Cliente mittente") | The customer | ls-order@dev:js/rest/dto/order-request.js:7; ls-cms@dev:js/components/types/data-tables-component.js:152, 243 |
| `destinatario.{ragioneSociale, indirizzo, provincia, citta, cap, telefono}` / flat `ragioneSociale` … `telefono` | strings; `telefono` may be `null` | EP-08 write / EP-09/10 read | The recipient. The backend flattens it (inferred). | ls-order@dev:js/rest/dto/order-request.js:8-16; ls-cms@dev:js/components/types/data-tables-component.js:154-158 |
| `destinatario.email` | string | EP-08 write | Always the **customer's** e-mail (OP-14) | ls-order@dev:js/rest/dto/order-request.js:15 |
| `dettaglioColli.palletList[]` / `palletList[]` | array of line objects | EP-08 write (nested) / EP-17 read (top level) | Lines; the backend moves them (inferred) (OP-15) | ls-order@dev:js/rest/dto/order-request.js:17-19; ls-cms@dev:js/function/component-handler.js:115 |
| `dettaglioColli` | flat object, keys (unknown) | EP-17 read | "Dettaglio Colli" | ls-cms@dev:js/function/component-handler.js:104 |
| `spedizione` | object, keys (unknown) | EP-17 read | "Dettaglio Spedizione" | ls-cms@dev:js/function/component-handler.js:93 |
| `codiceDocumentoTrasporto` | string or `null` | EP-08 write; EP-09/10 read | Transport document number (DDT) | ls-order@dev:js/rest/dto/order-request.js:20 |
| `servizioAccessorio` | write: id string, or `0`, `1`, `null` for "none" by build; read: raw | EP-08; EP-09/10 | Accessory service (chapter 02 §8) | ls-order@dev:js/function/data-handler.js:58-61 |
| `note` | string (`""` when empty) | EP-08 write | The customer's note; where it is read back is (unknown) | ls-order@dev:js/rest/dto/order-request.js:22 |
| `notes` | string or `null` | EP-15 read, EP-16 write | One note per order; same field as `note` or not is (unknown) (CU-10) | ls-cms@dev:js/rest/rest-caller.js:88-96 |
| `importoAssicurato` | free text as typed | EP-11 write | "Importo Totale (per cliente)", the customer price (owner) | ls-cms@dev:js/function/component-handler.js:408, 424 |
| `importo` | number or string, `null` until set (inferred) | EP-09/10 read | "Importo"; the same stored value as `importoAssicurato` or not is (unknown) (Q-05, CU-08) | ls-cms@dev:js/components/types/data-tables-component.js:159, 250 |
| `tracking` | write: `1` (EP-11), `"0"`…`"5"` (EP-14); read: raw | EP-09/10, EP-11, EP-14 | Current tracking state | ls-cms@dev:js/function/component-handler.js:425, 514 |
| `stato` | raw, values (unknown) | EP-09/10 read; commented in EP-11 | An order status of some kind (inferred, weak) | ls-cms@dev:js/components/types/data-tables-component.js:161, 252 |
| `updateShippingTs`, `updateDeliveryTs` | booleans, commented out | EP-11 | Orders probably store shipping and delivery timestamps (inferred) | ls-cms@dev:js/function/component-handler.js:431-432 |
| a date | never read | EP-18 filters by date range | Orders carry a date (inferred) | ls-cms@dev:js/rest/rest-caller.js:109-116 |
| "Numero Colli" | collected by the portal, **never sent** | — | Lost (OP-07) | ls-order@dev:js/function/data-handler.js:64 |
| weight and volume totals | computed by the portal, never sent | — | Lost; recomputable from the lines (OP-R5) | ls-order@dev:js/function/data-handler.js:88-115 |

**Identifiers.** `id` is used only by approve and decline. `codice` is used by everything
else, including public tracking. A pending order's code contains `TEMP`. The customer never
sees any code from the portal, because EP-08's answer is ignored; whether the e-mails show
one is (unknown).

**States and transitions**

```
 EP-08 ──▶ pending (codice contains "TEMP", in EP-09)
             ├──EP-11 confirm {codice, importoAssicurato, tracking: 1}──▶ registered = approved (in EP-10)
             └──EP-12 decline──▶ ? (gone from the lists; deleted or flagged: unknown, CU-16)
 (from the source, 12b §3.2: approval requires `importo`, not `importoAssicurato`;
  a decline resets the order to NEW with tracking NON_GESTITO and keeps it)
 registered ──EP-14 {codice, tracking}──▶ registered, tracking = any of 0–5
 registered ──EP-16 {codice, notes}────▶ registered, note replaced
```

* No client moves an order back to pending, cancels it, or edits its code, amount,
  recipient or lines after submission.
* Approval through the CMS exists only from `ls-cms@e5a72fc` (2024-05-25) (chapter 02,
  EP-11). Orders approved earlier may lack an amount (inferred).

### 1.4 Recipient

Embedded in each order, with no id and no address book: name (`ragioneSociale`), street
(`indirizzo`), `provincia`, `citta`, `cap`, optional `telefono`. The portal collects no
recipient e-mail and sends the customer's instead (OP-14). There is no pick-up address
anywhere; the pick-up is presumably the customer's registered address (inferred; Q-13).

### 1.5 Pallet/colli line

| Field | Type as sent | Unit and rule | Source |
|---|---|---|---|
| `altezza`, `lunghezza`, `larghezza` | string of digits | cm; integers ≥ 0 (`0` passes) | ls-order@dev:js/rest/dto/order-request.js:32-35; ls-order@dev:js/function/form-validator.js:128-136 |
| `peso` | string of digits | kg **per unit** (inferred from the totals) | ls-order@dev:js/function/data-handler.js:91 |
| `quantity` | string of digits | number of identical units | ls-order@dev:js/rest/dto/order-request.js:36 |
| `type` | `"0"`, `"1"`, number `1`, or `null` (chapter 02 §8) | `"0"` = "Pallet Generico/Colli", `"1"` = "Epal" | ls-order@dev:js/constant/costant.js:86-90 |

No line id, position, goods description or stackability. Totals are computed by the portal
only: weight = Σ peso × quantità; volume = Σ round₂(h × l × w / 1 000 000 × quantità)
(OP-R5).

**Pallet "type" means three different things in the system:**

| Where | Values | Source |
|---|---|---|
| Order line `type` (portal) | `"0"` "Pallet Generico/Colli" (pallets **and** parcels), `"1"` "Epal"; the fallback is the number `1` with the comment "Generic pallet", although `1` is EPAL (OP-04) | ls-order@dev:js/constant/costant.js:86-90; ls-order@dev:js/function/data-handler.js:23-26 |
| Public quote form `tipo_pallet` (lead) | `eur`, `industriale`, `mezzo`, `quarto`, `altro` | ls-home@fe4b056:netlify/functions/form.mjs:26 |
| Price-list classes (chapter 07) | `MQP`, `QP`, `ELP`, `HP`, `ULP`, `LP`, `FP`, chosen by size and weight | chapter 07 §3 |

None maps one-to-one onto another. The target keeps the two legacy kinds on order lines,
lets pricing compute the class from the dimensions (chapter 07 §3.3), and keeps the quote
format as a lead attribute.

### 1.6 Accessory service

Backend enumeration `{id, label}` (`name` unknown), values unknown (chapter 02 §9.2). An
order refers to one item by id, or to "none", sent as `null`, `1` or `0` depending on the
portal build (chapter 02 §8).

### 1.7 Tracking state

Enumeration ids 0–5 (`NON_GESTITO`, `PRESA`, `TRANSITO`, `TERMINAL_DEST`, `ASSEGNATO`,
`CONSEGNATO`; chapter 02 §9.1). Each order has one current value. EP-11 sets 1, and EP-14
sets any value by code, backwards included. No client shows a history or a timestamp of
tracking changes; whether the backend keeps them is (unknown) (CU-18). EP-13 tells the
public the current state.

### 1.8 Notes

One free-text note per order, read (EP-15) and replaced (EP-16) by code, with no author,
date or history. Legacy values may hold the literal "Nessuna nota presente" and have lost
their line breaks (CMS-11). Stale row data in the CMS can save one order's note onto
another (CMS-07).

### 1.9 Staff user

`username` and `password` → `token`, `refreshToken` (EP-01). No roles, user management,
password change or logout in any client. The number of accounts and the username format
are (unknown) (Q-17).

### 1.10 What the legacy model does not have

* No date visible to any client: registration, order, approval and tracking changes. Only
  the dump can provide them (§4.2).
* No history: tracking, notes and amounts are overwritten in place; no record of who did
  what.
* No price data except the typed amount, and no VAT flag (Q-05).
* No pick-up address, goods description, package count or totals.
* No declined records in any list (CU-16).
* No leads: the public forms are e-mailed by a Netlify Function that stores nothing
  (ls-home@fe4b056:netlify/functions/form.mjs:17, 244-288).

## 2. Target model

### 2.1 Principles

1. **Own ids, legacy ids kept.** Every table has a `bigint` identity key. Legacy ids and
   codes live in their own columns (`legacy_id`, `code`, `temp_code`) so old links,
   documents and e-mails keep working (chapter 10 §2).
2. **Typed values.** Integers, `numeric`, enums and `timestamptz`; no numbers in strings.
3. **Snapshots on orders.** An order copies the sender's and the recipient's data at
   submission. Editing a profile or an address book never rewrites history.
4. **Explicit state machines** (§2.4): transition methods refuse illegal moves. Every staff
   action writes the audit log. Every tracking change is an event with a time.
5. **Strict input, lenient storage.** The application validates new input with the rules
   of chapters 03, 04 and 09 (5-digit CAP, Italian/E.164 phone, trimmed and lower-cased
   e-mail…). Column types are wide enough to hold legacy values. An imported anomaly is
   kept and flagged in `data_quality`; it is never rejected silently or "fixed" by guess.
6. **Money and time.** `numeric(10,2)` in EUR plus a VAT flag; `timestamptz`, shown in
   Europe/Rome (chapter 10 §2).
7. **E-mail addresses** are stored trimmed and lower-case, with uniqueness on that value.
8. **Pricing** tables (`price_list`, `price_list_version`, rates, rules, `order_price`, …)
   are defined in chapter 07 §6.2 and reference the tables below (§2.6).

### 2.2 Overview

```
customer 1──n customer_user 1──n login_token
   │ 1
   │                 accessory_service
   │                      ▲ 0..1
   └──n shipment_order ───┘
          │ 1   ├──n order_line
          │     ├──n tracking_event ──▶ tracking_state (0–5)
          │     └──0..1 order_internal_note
staff_user ──▶ approvals, tracking events, notes, audit_log entries
customer_address (M2 address book)     lead (public forms) ──0..1──▶ customer
audit_log (every staff, customer and system action)
legacy_key_map, import_run, import_issue (migration bookkeeping)
```

### 2.3 Tables

#### `customer`

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | bigint identity | no | PK |
| `legacy_id` | bigint | yes | unique; legacy `id` |
| `email` | varchar(254) | no | unique; `CHECK (email = lower(email))`; the M1 login identity |
| `company_name` | varchar(200) | no | "Ragione Sociale/Nominativo" |
| `address_line` | varchar(200) | no | street and number ("Via") |
| `city` | varchar(100) | no | |
| `province` | varchar(60) | no | as typed, trimmed (legacy free text) |
| `province_code` | char(2) | yes | official two-letter code, derived when `province` matches one |
| `postal_code` | varchar(10) | no | new input: exactly 5 digits (CUS-02); legacy values kept and flagged |
| `phone` | varchar(30) | yes | normalised (E.164) when possible; optional unless the owner decides otherwise (OP-08) |
| `vat_number`, `tax_code` | varchar(16) | yes | new and optional (Q-10) |
| `status` | varchar(24) | no | `PENDING_VERIFICATION`, `PENDING_APPROVAL`, `ACTIVE`, `DECLINED` (§2.4) |
| `email_verified_at` | timestamptz | yes | set by the first link used |
| `registered_at` | timestamptz | yes | null only for legacy rows without a known date |
| `approved_at`, `approved_by` | timestamptz, FK `staff_user` | yes | |
| `declined_at`, `declined_by`, `decline_reason` | timestamptz, FK, text | yes | |
| `source` | varchar(16) | no | `CUSTOMER_AREA`, `BACKOFFICE`, `LEGACY` |
| `legacy_email_raw` | varchar(254) | yes | the e-mail exactly as legacy stored it |
| `data_quality` | jsonb | no | default `[]`; DQ codes with details |
| `created_at`, `updated_at` | timestamptz | no | |
| `version` | integer | no | optimistic locking |

#### `customer_user` (login identity)

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | bigint identity | no | PK |
| `customer_id` | FK `customer` | no | |
| `email` | varchar(254) | no | unique, lower-case |
| `role` | varchar(16) | no | `OWNER` in M1; `MEMBER` later (Q-11) |
| `last_login_at`, `disabled_at` | timestamptz | yes | |
| `created_at` | timestamptz | no | |

M1 rule: exactly one `OWNER` per customer, with the customer's e-mail. Imported customers
need no password: they sign in with a link to the address they already use.

#### `login_token`

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | bigint identity | no | PK |
| `customer_user_id` | FK | yes | null only for `REGISTER` (unknown e-mail: the row stores `email_hash`); the page answers the same way for every e-mail (CUS-01) |
| `email_hash` | char(64) | yes | for `REGISTER` only: HMAC-SHA-256 of the normalised e-mail (key from the secret store) |
| `purpose` | varchar(16) | no | `SIGN_IN`, `VERIFY_EMAIL`, `REGISTER` (unknown e-mail: a 24-hour link to the registration form with the address pre-filled and already verified), `CHANGE_EMAIL` (CUS-06) |
| `token_hash` | char(64) | no | unique; SHA-256 of a random token of at least 128 bits (chapter 10 §2) |
| `expires_at` | timestamptz | no | 15 minutes after creation (`REGISTER`: 24 hours) |
| `used_at` | timestamptz | yes | single use |
| `requested_ip_hash` | char(64) | yes | rate limits; HMAC-SHA-256 with a secret key, kept at most 30 days (chapter 10 §2) |
| `created_at` | timestamptz | no | |

#### `staff_user`

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | bigint identity | no | PK |
| `username` | varchar(100) | no | unique, lower-case |
| `email` | varchar(254) | yes | unique |
| `display_name` | varchar(100) | no | |
| `password_hash` | varchar(255) | no | bcrypt or argon2 (chapter 10 §4) |
| `role` | varchar(16) | no | `ADMIN`, `OPERATOR` (ADM-01, Q-17) |
| `active` | boolean | no | |
| `failed_logins` | integer | no | lock-out (ADM-01) |
| `locked_until`, `last_login_at` | timestamptz | yes | |
| `totp_secret` | bytea, encrypted | yes | M2 (ENH-09) |
| `created_at`, `updated_at` | timestamptz | no | |

Proposed permissions (owner confirms, Q-17): `OPERATOR` can do everything the legacy CMS
does (approve or decline customers and orders, tracking, notes, detail, reports).
`ADMIN` can also manage staff users, feature flags, enumerations (ENH-14), price lists
(M2) and personal-data export or deletion (NFR-03).

#### `shipment_order`

The name avoids the SQL keyword `ORDER`; chapter 07's `order_price.order_id` references it.

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | bigint identity | no | PK |
| `legacy_id` | bigint | yes | unique |
| `customer_id` | FK `customer` | no | the sender |
| `status` | varchar(16) | no | `PENDING`, `APPROVED`, `DECLINED` (§2.4) |
| `temp_code` | varchar(64) | yes | unique; assigned at submission; legacy `TEMP` codes kept verbatim |
| `code` | varchar(64) | yes | unique on `upper(code)`; `CHECK (status <> 'APPROVED' OR code IS NOT NULL)` |
| `transport_document_code` | varchar(100) | yes | DDT; required for new orders (Q-24 default, as `dev`); null only on legacy rows (DQ-11) |
| `package_count` | integer | yes | "Numero Colli", ≥ 0; always null for legacy orders (never sent) |
| `accessory_service_id` | FK `accessory_service` | yes | null = none |
| `customer_notes` | text | yes | the customer's "Note" |
| `amount` | numeric(10,2) | yes | "Importo Totale (per cliente)", ≥ 0; `CHECK (status <> 'APPROVED' OR amount IS NOT NULL OR source = 'LEGACY')` |
| `amount_vat_included` | boolean | yes | per Q-05; null for legacy amounts until Q-05 is answered |
| `amount_raw` | varchar(50) | yes | legacy text as typed |
| `total_weight_kg` | numeric(12,2) | no | Σ `line_weight_kg` |
| `total_volume_m3` | numeric(10,2) | no | Σ `line_volume_m3` |
| `tracking_state_id` | smallint FK `tracking_state` | no | default 0; always equals the latest event's state once one exists |
| `tracking_updated_at` | timestamptz | yes | |
| `sender_name`, `sender_address_line`, `sender_city`, `sender_province`, `sender_postal_code`, `sender_email` | as `customer` | no | snapshot of the customer at submission (Q-13) |
| `sender_phone` | as `customer` | yes | |
| `recipient_name`, `recipient_address_line`, `recipient_city`, `recipient_province`, `recipient_postal_code` | as `customer` | no | |
| `recipient_phone`, `recipient_email` | | yes | `recipient_email` is new (CUS-04) |
| `submitted_at` | timestamptz | yes | `CHECK (submitted_at IS NOT NULL OR source = 'LEGACY')` |
| `approved_at`, `approved_by`, `declined_at`, `declined_by`, `decline_reason` | | yes | |
| `idempotency_key` | uuid | yes | unique; one per wizard session (CUS-04, OP-02) |
| `source` | varchar(16) | no | `CUSTOMER_AREA`, `BACKOFFICE`, `LEGACY` |
| `legacy_stato`, `legacy_tracking_raw`, `legacy_service_raw` | varchar(100) | yes | raw legacy values |
| `legacy_payload` | jsonb | yes | the legacy list item, detail and notes answers, verbatim |
| `data_quality` | jsonb | no | default `[]` |
| `created_at`, `updated_at`, `version` | | no | |

#### `order_line`

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | bigint identity | no | PK |
| `order_id` | FK `shipment_order` | no | |
| `line_no` | smallint | no | from 1; unique (`order_id`, `line_no`) |
| `kind` | varchar(8) | no | `GENERIC` ("Pallet Generico/Colli") or `EPAL` ("Epal") |
| `height_cm`, `length_cm`, `width_cm` | integer | no | new input ≥ 1 with an owner-approved maximum (OP-12); DB `CHECK (≥ 0)` so legacy zeros import |
| `weight_kg` | numeric(9,2) | no | per unit; new input > 0; decimals only if the owner agrees (OP-12) |
| `quantity` | integer | no | new input ≥ 1; DB ≥ 0 |
| `line_weight_kg` | numeric(12,2) | no | `weight_kg × quantity` |
| `line_volume_m3` | numeric(10,2) | no | `round_half_up(h × l × w / 1 000 000 × quantity, 2)`, the legacy per-line formula (OP-R5), computed in decimal arithmetic |
| `legacy_type_raw` | varchar(10) | yes | `"0"`, `"1"`, `1`, `null` as received |
| `data_quality` | jsonb | no | default `[]` |

#### `customer_address` (M2, ENH-07)

Saved recipients: `id`, `customer_id`, `label`, the recipient columns of
`shipment_order`, `last_used_at`, `created_at`. Choosing an entry copies it into the order
snapshot. It may be seeded from the distinct legacy recipients per customer once the flag
is on (optional).

#### `accessory_service`

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | bigint identity | no | PK |
| `legacy_id` | varchar(50) | yes | unique; the legacy `id` as text |
| `legacy_json` | jsonb | yes | the legacy item exactly as EP-20 returned it; API-02 serves these items verbatim |
| `name` | varchar(60) | yes | legacy `name`, if present |
| `label` | varchar(120) | no | Italian label |
| `is_none` | boolean | no | true for legacy item 0 "Nessun servizio accessorio" (12b §2.2); never offered as a choice; orders that point to it get `accessory_service_id = null` |
| `active` | boolean | no | |
| `sort_order` | integer | no | position in the legacy answer |

Services added later (ENH-14) get a `legacy_id` that follows the legacy id scheme, so EP-20
stays consistent (proposal).

#### `tracking_state`

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | smallint | no | PK, 0–5, the legacy ids |
| `name` | varchar(20) | no | unique: `NON_GESTITO` … `CONSEGNATO` |
| `label` | varchar(40) | no | legacy CMS label ("Presa in carico" …) |
| `public_title` | varchar(60) | no | the site's status line ("In attesa di presa in carico" …, chapter 06 §5.7) |
| `step_label` | varchar(40) | yes | progress-bar step; null for `NON_GESTITO` |
| `sort_order` | smallint | no | |

Seeded exactly from chapter 02 §9.1 and chapter 06 §5.7 (ADM-10); read-only in the UI.

#### `tracking_event`

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | bigint identity | no | PK |
| `order_id` | FK `shipment_order` | no | |
| `state_id` | smallint FK `tracking_state` | no | |
| `occurred_at` | timestamptz | no | when the state was reached |
| `recorded_at` | timestamptz | no | default `now()` |
| `source` | varchar(16) | no | `APPROVAL`, `STAFF`, `BULK` (ENH-15), `LEGACY_IMPORT` |
| `staff_user_id` | FK `staff_user` | yes | |
| `time_is_estimated` | boolean | no | true when the legacy date is unknown |
| `location`, `public_note` | varchar(120), varchar(40) | yes | M2 timeline (ENH-05). `location` is a city and province or a hub name; `public_note` is a key from a fixed list of phrases (no free text), so no personal data can reach public tracking (TRK-03) |

Append-only: a correction is a new event. Index (`order_id`, `occurred_at` desc, `id` desc).
The order's `tracking_state_id` and `tracking_updated_at` change in the same transaction.

#### `order_internal_note`

| Column | Type | Null | Rules |
|---|---|---|---|
| `order_id` | FK `shipment_order` | no | PK: one note per order, as in the legacy (ADM-07) |
| `body` | text | no | up to 5,000 characters; line breaks kept |
| `updated_at` | timestamptz | no | |
| `updated_by` | FK `staff_user` | yes | null when imported |
| `imported_from_legacy` | boolean | no | |

Each edit writes the audit log; the before and after text go to `audit_log_detail`: the history the legacy lacked.
A thread of notes can come later without changing M1.

#### `lead` (public quote and contact forms)

Stored in M1 when the form is sent; the inbox UI is M2 (ENH-06). The fields mirror the
server-side validation of `form.mjs` (ls-home@fe4b056:netlify/functions/form.mjs:52-152;
chapter 06 §5.5–5.6).

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | bigint identity | no | PK |
| `form` | varchar(12) | no | `PREVENTIVO`, `CONTATTI` (`form-name`) |
| `status` | varchar(12) | no | `NEW`, `CONTACTED`, `WON`, `LOST`, `SPAM` (§2.4) |
| `name` | varchar(100) | no | `nome` |
| `company` | varchar(120) | yes | `azienda` |
| `email` | varchar(254) | no | `email` |
| `phone` | varchar(30) | yes | `telefono`; required for `PREVENTIVO` |
| `preferred_contact` | varchar(10) | yes | `email`, `telefono`, `whatsapp` |
| `subject` | varchar(100) | yes | `oggetto` (contact) |
| `message` | text | yes | `messaggio` (≤ 5,000) or `note` (≤ 3,000) |
| `service` | varchar(12) | yes | `pallet`, `groupage`, `completo`, `magazzino` |
| `pickup_date` | date | yes | `data_ritiro` |
| `pickup_postal_code`, `pickup_city`, `delivery_postal_code`, `delivery_city` | varchar | yes | `ritiro_cap`, `ritiro_localita`, `consegna_cap`, `consegna_localita` |
| `recipient_type` | varchar(8) | yes | `azienda`, `privato` |
| `pallet_format` | varchar(12) | yes | `eur`, `industriale`, `mezzo`, `quarto`, `altro` |
| `quantity` | numeric(5,2) | yes | `numero` (the server accepts "2,5" today) |
| `total_weight_kg`, `max_height_cm` | numeric | yes | `peso`, `altezza` |
| `goods_description` | varchar(200) | yes | `descrizione_merce` |
| `extras` | text[] | no | `servizi_extra[]`, known values only |
| `payload` | jsonb | no | every validated field with its form name |
| `privacy_accepted_at` | timestamptz | no | `privacy = accettata` |
| `customer_id` | FK `customer` | yes | set when the e-mail matches a customer, or on conversion |
| `converted_order_id` | FK `shipment_order` | yes | M2 |
| `ip_hash`, `user_agent` | | yes | abuse control |
| `created_at` | timestamptz | no | |
| `retain_until` | date | no | from the retention policy (Q-18) |

Honeypot submissions are not stored. Nothing to migrate: the legacy stores no leads (§1.10).

#### `audit_log`

| Column | Type | Null | Rules |
|---|---|---|---|
| `id` | bigint identity | no | PK |
| `occurred_at` | timestamptz | no | default `now()` |
| `actor_type` | varchar(10) | no | `STAFF`, `CUSTOMER`, `SYSTEM`, `IMPORT` |
| `actor_id` | bigint | yes | |
| `action` | varchar(60) | no | e.g. `customer.approve`, `order.approve`, `order.tracking.set`, `order.note.update`, `report.send`, `staff.login.failed`, `flag.toggle`, `import.run` |
| `entity_type`, `entity_id`, `entity_ref` | varchar, bigint, varchar(100) | yes | `entity_ref` holds an order code or an internal id, never an e-mail |
| `before`, `after` | jsonb | yes | changed field names and non-personal values only; for personal fields (names, e-mails, phones, addresses, notes, messages) only the field name and the fact that it changed |
| `ip_hash`, `user_agent`, `request_id` | | yes | |

Append-only: the application's database role has no `UPDATE` or `DELETE` on this table.
Entries older than the audit retention agreed with the owner (Q-18; default 24 months) are
purged by a scheduled job that uses a separate maintenance role. The viewer is M2 (ENH-13);
the log is written from M1 (NFR-02).

#### `audit_log_detail`

| Column | Type | Null | Rules |
|---|---|---|---|
| `audit_log_id` | FK `audit_log` | no | PK |
| `before`, `after` | jsonb | yes | personal values of the change (e.g. note text, address) |

Purged by the erasure tool (NFR-03, chapter 10 §5) and by the retention job (Q-18); the
application role may delete from this table, unlike `audit_log`.

#### Supporting tables (owned by other modules)

| Table | Purpose | Where defined |
|---|---|---|
| `email_outbox` | e-mails queued in the business transaction, sent by a worker | chapter 10 §2 |
| `report_run` | who requested which report, range, recipient, file, status | ADM-09 |
| `feature_flag` | M2 flags | chapter 10 §2 |
| `legacy_key_map`, `import_run`, `import_issue` | migration bookkeeping | §4.5 |

### 2.4 State machines

**Customer** (states of chapter 10 §2; as in the legacy, only a customer whose e-mail is
verified can be approved, 12b §3.1, Q-28):

```
               register                        link used
  (none) ─────────────────▶ PENDING_VERIFICATION ──────────▶ PENDING_APPROVAL
                                           │                     │          │
                                   decline │             approve │  decline │
                                           ▼                     ▼          ▼
                                        DECLINED               ACTIVE    DECLINED
  DECLINED ── register again (Q-09 default: allowed, same record) ──▶ PENDING_VERIFICATION
```

| From | Event | To | Guard | Side effects |
|---|---|---|---|---|
| none | registration in the customer area | `PENDING_VERIFICATION` | e-mail not used by a non-declined customer; otherwise nothing is created, a sign-in link is sent and the same page is shown (CUS-02) | `customer_user` + `VERIFY_EMAIL` token; EML-01 (skipped when the form was opened from a `REGISTER` link: the e-mail is already verified, go straight to `PENDING_APPROVAL`) |
| `PENDING_VERIFICATION` | verification or sign-in link used | `PENDING_APPROVAL` | valid token | `email_verified_at`; EML-02 to the customer, EML-06 to the office |
| `PENDING_APPROVAL` | staff approve | `ACTIVE` | — | EML-03; audit |
| `PENDING_*` | staff decline | `DECLINED` | optional reason | EML-03 (wording: owner, Q-07); audit |
| `DECLINED` | registers again | `PENDING_VERIFICATION` | Q-09; the new data are stored apart and applied only when the verification link is used | EML-01; audit |
| any | sign-in link used | unchanged | — | sets `email_verified_at` if null |

`PENDING_*` and `DECLINED` customers can sign in, see their status and cannot order (CUS-03).
`ACTIVE` is final in M1: the legacy has no suspension. Add `DISABLED` only if the owner asks.

**Order**

```
 (none) ──submit──▶ PENDING ──approve(code, amount)──▶ APPROVED
                       └────────decline(reason)──────▶ DECLINED
```

| From | Event | To | Guard | Side effects |
|---|---|---|---|---|
| none | customer submits the wizard | `PENDING` | customer `ACTIVE`; ≥ 1 line; idempotency key unused; DDT non-empty after trim (Q-24 default) | `temp_code`; `tracking_state_id` 0; totals; snapshots; EML-06 to the office (legacy) and EML-04 to the customer (new; 12c §1.5) |
| `PENDING` | staff approve | `APPROVED` | code non-empty after trim and unique ignoring case; amount parses (`180,50` or `180.50`) and is > 0 (legacy rule; 0 only if the owner decides, 12c §4.2) | `tracking_event` `PRESA` (source `APPROVAL`); EML-05; audit |
| `PENDING` | staff decline | `DECLINED` | optional reason | EML-05 (declined); audit |

`APPROVED` and `DECLINED` are final in M1. The legacy CMS cannot correct a code, an amount
or a recipient after approval (chapter 04 §9.2); the backend's `POST /mono/cms/order/update`
can, but only from Postman (12c §6). If the owner wants corrections, allow them to
`ADMIN` only, with an audit entry.

**Tracking** (on `APPROVED` orders only, as the CMS offers it)

* Any state may follow any state, as in the legacy (chapter 04 §4.6). The UI asks for
  confirmation when the move goes backwards or to `NON_GESTITO` (proposal).
* Setting the current state again creates no event.
* Each change inserts a `tracking_event` (source `STAFF`, `occurred_at` = now), updates the
  order and writes the audit log. When the state becomes `CONSEGNATO`, the customer gets
  the delivery e-mail EML-09 (M1: the legacy sends it, 12c §1.3 L-E8). E-mails for the other
  states are M2 (ENH-05, `tracking.notifyCustomer`).

**Lead**: `NEW → CONTACTED → WON | LOST`; `NEW | CONTACTED → SPAM`. M1 only creates `NEW`.

**Login token**: created → used (once) or expired (15 minutes; `REGISTER` 24 hours). Only the
CSRF-protected `POST` from the confirmation page uses a token; a `GET` of the e-mailed link
(including mail scanners and link previews) never does (chapter 10 §2).

### 2.5 Codes and identifiers

| Identifier | Rule |
|---|---|
| `customer.email` | Unique, lower-case; how the customer signs in and how the importer matches orders. |
| `shipment_order.code` | The definitive shipment code. Staff type it at approval, as today. The app proposes the next code when the imported codes follow a recognisable sequence, and enforces uniqueness ignoring case (Q-06 default). Kept with its original case for display. |
| `shipment_order.temp_code` | Given at submission. Proposal for new orders: `TEMP-` + 8 random base-32 characters. It keeps the marker the legacy CMS looked for and cannot be guessed. Legacy temporary codes are kept verbatim. |
| Public tracking (EP-13, TRK-02) | Looks up `code` only, normalised (trimmed, spaces removed, case rule per TRK-02). Pending orders are not trackable (proposal; legacy behaviour CU-02). |
| Customer-area URL | `/area-clienti/ordini/{codice}/` (chapter 10) uses `code`, or `temp_code` while pending, and checks that the order belongs to the signed-in customer. |
| Legacy ids | `customer.legacy_id`, `shipment_order.legacy_id`, `accessory_service.legacy_id`: import keys, never shown. |

### 2.6 Links with chapter 07 (pricing)

* `customer_price_list.customer_id` → `customer.id`; `order_price.order_id` →
  `shipment_order.id`. The engine reads `order_line` (kind, dimensions, weight, quantity)
  and the recipient's postal code and province.
* Chapter 07's `order_price.final_amount` is the same value as `shipment_order.amount`.
  Keep **one authority**: `shipment_order.amount` exists from M1. When ENH-02 is built,
  either copy it into `order_price.final_amount` in the same transaction or drop that
  column. Record the choice in the pricing ADR.

## 3. Mapping legacy → target

> With the dump as the source (§4.2), **the authoritative mapping is 12b §5.5**, per table
> and column (`soggetti`, `spedizioni`, `dettaglio_colli`, `pallet`): the status table that
> tells unverified from declined `NEW` customers, the per-unit rows regrouped into lines,
> the amount rule (`importo`, else `importo_assicurato`) and the tracking events from
> `confirmation_ts`, `shipping_ts` and `delivery_ts`. The wire-field mapping below remains
> for the API-export fallback (§4.3) and for the names the clients used.

### 3.1 Customer

| Legacy field | Seen in | Target | Rule |
|---|---|---|---|
| `id` | EP-04/05 | `customer.legacy_id` | as is |
| `email` | EP-04/05 | `customer.email`, `customer_user.email`, `legacy_email_raw` | trim + lower-case (§3.8). Two legacy customers with the same normalised e-mail → DQ-01, blocking until the owner chooses the record to keep. |
| `ragioneSociale` | | `company_name` | trim, collapse spaces; empty → DQ-04 |
| `indirizzo` | | `address_line` | trim |
| `citta` | | `city` | trim |
| `provincia` | | `province`, `province_code` | trim; derive the code (§3.8); no match → DQ-05 |
| `cap` | | `postal_code` | §3.8; not 5 digits → DQ-03 |
| `telefono` | | `phone` | `null` or `""` → null; normalise (§3.8); unparseable → raw kept + DQ-06 |
| `emailConfirmed` | | `email_verified_at` | true → the dump's date if any, else the import time and DQ-24 note "date estimated"; false → null |
| list membership | EP-04 / EP-05 | `status` | pending + not confirmed → `PENDING_VERIFICATION`; pending + confirmed → `PENDING_APPROVAL`; registered → `ACTIVE`, whatever `emailConfirmed` says (DQ-24) |
| declined records | dump only | `status = DECLINED` | only if the dump keeps them (CU-16) |
| — | | `source = LEGACY`; `registered_at`, `approved_at` from the dump or null; one `customer_user` `OWNER` | |

Old registration links stop working at the switch (chapter 02 §10.3). A
`PENDING_VERIFICATION` customer verifies by signing in with a new link; until then the back
office lists them under "In Approvazione" with "Email confermata: NO", as today.

### 3.2 Order

| Legacy field | Seen in | Target | Rule |
|---|---|---|---|
| `id` | EP-09/10 | `legacy_id` | as is |
| list membership | EP-09 / EP-10 | `status` | pending → `PENDING`; registered → `APPROVED`; declined (dump only) → `DECLINED` |
| `codice` | EP-09/10 | pending → `temp_code`; registered → `code` | trim; registered code still containing `TEMP` or blank → DQ-09; duplicate ignoring case → DQ-08 (blocking) |
| `email` | EP-09/10 | `customer_id` | match the normalised e-mail; no match → DQ-10 (blocking) |
| `ragioneSociale`, `indirizzo`, `citta`, `provincia`, `cap`, `telefono` (flat) | EP-09/10 | `recipient_name`, `recipient_address_line`, `recipient_city`, `recipient_province`, `recipient_postal_code`, `recipient_phone` | as the customer fields; `""` → null |
| `destinatario.email` | EP-08 only | `recipient_email` = null | the legacy value is the customer's e-mail (OP-14); never copy it (DQ-23) |
| — | | `sender_*` | copied from the customer record at import time; the legacy stores no pick-up data (inferred) → DQ-14 note |
| `codiceDocumentoTrasporto` | EP-09/10 | `transport_document_code` | null stays null (DQ-11) |
| `importo`, and `importoAssicurato` if the dump has it | EP-09/10; dump | `amount`, `amount_raw` | parse (§3.8). Unparseable or ambiguous → null + DQ-12. Both present and different → DQ-13: use `importoAssicurato` (what staff typed) unless the owner says otherwise (Q-05). |
| — | | `amount_vat_included` | null until Q-05 is answered, then set for all legacy rows at once |
| `servizioAccessorio` | EP-09/10 | `accessory_service_id`, `legacy_service_raw` | §3.5 |
| `tracking` | EP-09/10 | `tracking_state_id`, one `tracking_event`, `legacy_tracking_raw` | §3.6 |
| `stato` | EP-09/10 | `legacy_stato` | kept raw; the status comes from the list; the value distribution goes in the report |
| `note` | dump; maybe EP-17 | `customer_notes` | `""` → null; §3.7 |
| `notes` | EP-15 | `order_internal_note` | §3.7 |
| `palletList[]` | EP-17 (or dump) | `order_line` rows | §3.4 |
| `dettaglioColli{…}` | EP-17 | `legacy_payload`; `package_count` only if a key clearly holds the number of packages | keys unknown (CU-09) |
| `spedizione{…}` | EP-17 | `legacy_payload`; recognised dates → `submitted_at`, `approved_at` | keys unknown (CU-09, CU-18) |
| dates | dump | `submitted_at`, `approved_at`, `tracking_updated_at`, event times | else null (`source = LEGACY`) → DQ-21 |
| — | | `total_weight_kg`, `total_volume_m3` | computed from the lines (OP-R5) |
| whole answers | | `legacy_payload` | verbatim (list item + detail + notes) |

### 3.3 Worked example

Legacy data for one approved order, as the API export would see it. The list fields are the
ones the CMS reads. The `tracking` format is unknown and the matcher (§3.6) accepts an id,
name or label. The detail assumes the portal's key names (CU-09).

```json
{
  "list": {
    "id": 118,
    "codice": "LS-2024-0118",
    "email": "Ufficio@MobilificioRossi.example",
    "codiceDocumentoTrasporto": "DDT 245/2024",
    "ragioneSociale": "Ferramenta Bianchi S.n.c.",
    "indirizzo": "Via Roma 5",
    "citta": "Bergamo",
    "provincia": "BG",
    "cap": "24121",
    "telefono": "035 7654321",
    "importo": "180,50",
    "servizioAccessorio": 0,
    "stato": "<valore sconosciuto>",
    "tracking": 1
  },
  "detail": {
    "spedizione": { "<chiave>": "<valore>" },
    "dettaglioColli": { "<chiave>": "<valore>" },
    "palletList": [
      { "altezza": "150", "peso": "420", "larghezza": "80", "lunghezza": "120", "quantity": "2", "type": "1" },
      { "altezza": "40", "peso": "12", "larghezza": "30", "lunghezza": "30", "quantity": "3", "type": "0" }
    ]
  },
  "notes": { "notes": "Nessuna nota presente" }
}
```

Target rows the importer writes (abridged):

```json
{
  "shipment_order": {
    "legacy_id": 118,
    "customer_id": "→ customer ufficio@mobilificiorossi.example",
    "status": "APPROVED",
    "code": "LS-2024-0118",
    "temp_code": null,
    "transport_document_code": "DDT 245/2024",
    "accessory_service_id": null,
    "amount": 180.50,
    "amount_raw": "180,50",
    "amount_vat_included": null,
    "tracking_state_id": 1,
    "recipient_name": "Ferramenta Bianchi S.n.c.",
    "recipient_address_line": "Via Roma 5",
    "recipient_city": "Bergamo",
    "recipient_province": "BG",
    "recipient_postal_code": "24121",
    "recipient_phone": "+390357654321",
    "recipient_email": null,
    "total_weight_kg": 876.00,
    "total_volume_m3": 2.99,
    "submitted_at": null,
    "source": "LEGACY",
    "data_quality": ["DQ-14", "DQ-21"]
  },
  "order_line": [
    { "line_no": 1, "kind": "EPAL", "height_cm": 150, "length_cm": 120, "width_cm": 80, "weight_kg": 420.00, "quantity": 2, "line_weight_kg": 840.00, "line_volume_m3": 2.88, "legacy_type_raw": "1" },
    { "line_no": 2, "kind": "GENERIC", "height_cm": 40, "length_cm": 30, "width_cm": 30, "weight_kg": 12.00, "quantity": 3, "line_weight_kg": 36.00, "line_volume_m3": 0.11, "legacy_type_raw": "0" }
  ],
  "tracking_event": [
    { "state_id": 1, "source": "LEGACY_IMPORT", "occurred_at": "<import time>", "time_is_estimated": true }
  ],
  "order_internal_note": null
}
```

The note is dropped because the legacy CMS wrote that literal into empty notes (CMS-11).
`servizioAccessorio: 0` becomes "none" if the enumeration has no item with id 0 (§3.5).

### 3.4 Lines

| Legacy | Target | Rule |
|---|---|---|
| position in `palletList` | `line_no` | 1-based, in the order received |
| `type` `"0"` or `0` | `kind = GENERIC` | |
| `type` `"1"` | `kind = EPAL` | |
| `type` `1` (number) | `kind = EPAL` + DQ-15 | may mean "no type chosen" (OP-04); detectable only if the backend kept the JSON type (unlikely, inferred) |
| `type` `null`, missing or other | `kind = GENERIC` + DQ-15 | builds before `e183526` could send `null` (chapter 02 §8) |
| `altezza`, `lunghezza`, `larghezza` | `height_cm`, `length_cm`, `width_cm` | integer parse; `0` or not a number → DQ-16 |
| `peso` | `weight_kg` | per unit (inferred); if the dump shows per-line totals instead, adapt and note it |
| `quantity` | `quantity` | integer parse |
| — | `line_weight_kg`, `line_volume_m3` | computed |
| raw `type` | `legacy_type_raw` | |

### 3.5 Accessory services

The enumeration is known (chapter 02 §9.2; 12b §2.2): six items, ids 0–5; item 0 "Nessun
servizio accessorio" is the `is_none` row; the database stores the **label**, so from the
dump map orders by label (12b §5.5). The rows below cover the API-export fallback.

| Legacy | Target |
|---|---|
| each EP-20 item, in order | one `accessory_service` row: `legacy_id` = the id as text, `legacy_json` = the item, `label`, `name`, `sort_order` |
| an item that means "none" (if any) | `is_none = true` |
| order value `null`, `""`, `0`, `"0"` | `accessory_service_id = null`, unless the enumeration has a real item with id 0 → DQ-07 |
| order value `1` (number) | ambiguous if the enumeration has an item with id 1: the `e183526` builds sent `1` for "none". Without a date or build marker, import as that service and flag DQ-07 for the owner. |
| order value matching an item's id (as text) | that row |
| order value equal to an item's `label` or `name` (if the lists return labels) | that row |
| anything else | null + DQ-07; raw kept in `legacy_service_raw` |

### 3.6 Tracking

| Legacy `tracking` value | Target |
|---|---|
| `0`–`5`, `"0"`–`"5"`, a name, a label, or an object `{id, name, label}` | matched like the new site does: by id, by name, or by label ignoring accents, case and spaces (ls-home@fe4b056:public/js/tracking.js:23-30, 81-91) → `tracking_state_id` |
| empty on a pending order | 0 (`NON_GESTITO`) |
| empty on an approved order | 1 (`PRESA`, the approval state) + DQ-17 |
| no match | 0 + DQ-17; raw kept |

Events: one `tracking_event` per approved order, with the mapped state and source
`LEGACY_IMPORT`. `occurred_at` is the best known date: the dump's tracking, shipping or
delivery timestamp for that state; else the approval date; else the import time. Set
`time_is_estimated = true` unless the dump gives the exact time. No intermediate events are
invented.

### 3.7 Notes

| Legacy | Target |
|---|---|
| EP-15 `notes` = `null`, `""`, only spaces, or exactly "Nessuna nota presente" (trimmed) | no note (CMS-11) |
| other text | `order_internal_note.body`, `imported_from_legacy = true` (DQ-18) |
| the portal `note` (dump or detail), if stored separately | `customer_notes` |
| the portal `note` and the CMS notes are one field (CU-10) | import into `order_internal_note` only, not shown to the customer: staff may have overwritten it with internal remarks (DQ-18) |

### 3.8 Normalisation rules

| Value | Rule |
|---|---|
| E-mail | Trim; lower-case; must contain no inner space. Normalised duplicates → DQ-01. |
| One-line text | Trim; collapse runs of whitespace to one space. |
| Phone | Remove spaces, dots, dashes and brackets. `+39…` or `0039…` → E.164. Italian numbers without a prefix (starting with `0` or `3`) → `+39…`. Anything else: raw kept + DQ-06. |
| CAP | `^\d{5}$` kept; `^\d{5}-\d{4}$` → first 5 digits + DQ-03; anything else kept + DQ-03. |
| Province | Match the official two-letter codes, or the province names (ISTAT list), ignoring case and accents → `province_code`; keep the raw text. |
| Amount | Remove `€`, `EUR` and spaces. `1.234,56` → 1234.56; `180,50` → 180.50; `180.50` → 180.50; integers as they are. Ambiguous `1.250` (a single dot followed by exactly 3 digits: Italian thousands or decimal?), more than 2 decimals, negative values, or text → null + DQ-12 with the raw value. |
| Codes | Trim. Compare ignoring case; display as typed. |
| HTML-like text (`<`, `>`, `javascript:`) | Imported as plain text (the target escapes all output) + DQ-19 for review (CMS-04). |

### 3.9 Staff users

Not migrated. Legacy passwords are unknown or hashed with an unknown scheme (CU-13). The
owner lists the people and their roles (Q-17); their accounts are created fresh with
`ADMIN` or `OPERATOR`, and legacy usernames may be reused.

Staff accounts are created with a command (`create-staff-user --username … --role
ADMIN|OPERATOR`) that prints a one-time set-password link valid for 24 hours. No migration,
seed or default configuration contains a staff user or password; a test asserts that no
migration inserts into `staff_user`. Only the local development profile may create demo
users, with random passwords printed at startup.

## 4. Migration plan

### 4.1 Principles

* **Read-only towards the legacy system.** The migration never calls a legacy write
  endpoint: EP-03, EP-06, EP-07, EP-08, EP-11, EP-12, EP-14 (it writes, despite its name),
  EP-16, and EP-18 (it sends e-mails).
* **Personal data.** Dumps and snapshots travel and rest encrypted, outside git, and are
  deleted when the retention agreed with the owner ends. Staging uses an anonymised copy
  (Q-02).
* **One input format.** Both options produce the same canonical snapshot (§4.4), so one
  importer serves both.
* **Idempotent and auditable.** Dry-run first. Every source record ends in exactly one
  outcome (inserted, updated, unchanged, rejected), and the report lists every outcome
  that is not clean.
* **Silent.** The importer never writes to the e-mail outbox. Its audit entries use actor
  `IMPORT`.
* **Signed off.** The owner approves the reconciliation report before go-live (MIG-02).
  Done means 100 % equal counts per state and an equal sum of amounts.

### 4.2 Option 1 · From a dump of the legacy database (preferred)

**This is the plan** (12b §5.6). Only the dump gives dates, declined records, both amount
columns and the unit rows. Without dates, the date-range report (ADM-09) cannot include
legacy orders.

1. The owner provides the dump: MySQL 8.0.35, database `lsdb`, taken inside the legacy
   container as `scripts/backup.sh` does (`mysqldump --single-transaction --routines
   --triggers --events --databases lsdb`; nightly dumps may already exist under
   `/opt/logistica-sanchez/backup/mysql/`; 12d §1.7, §1.11). Transfer it encrypted; restore
   it into an isolated MySQL 8.0 container with the same collation, on a private network,
   never into the new app's database. In every non-production copy drop `users` and
   `user_session` right after the restore (password hashes, live refresh tokens).
2. Discover the schema: tables, columns, types, constraints, enums, row counts. 12b §1
   documents it from the entities; `ddl-auto: update` may have left extra columns, so run
   the drift queries of 12b §1.8 and check the October 2025 time-zone break (12d §1.7).
   Write `docs/migration/legacy-schema.md`, mapping every column to the wire fields of chapter 02
   and to the target (§3). Settle CU-08, CU-09, CU-10, CU-16, CU-17, CU-18 and CU-19 there.
3. Cross-check (optional, owner's go-ahead): for a sample of orders compare the dump with
   the EP-09/10/15/17 answers, to confirm which column is behind each wire field (for
   example `importo` and `importoAssicurato`, or the representation of `tracking`).
4. Extract: SQL views that output the canonical snapshot (§4.4), plus the dump-only data
   (dates, declined records, history).
5. Run the importer (§4.5).

### 4.3 Option 2 · Export through the legacy API (fallback)

> **Much weaker than it looked** (12b §5.6): EP-09 and EP-10 return only orders created in
> the last 6 months; declined orders and unverified or declined customers are invisible;
> EP-17 gives units, not lines; dates come as epoch numbers. `POST /mono/cms/order?email=`
> (ADMIN) returns every order of one customer, in any state, and would have to be called
> per customer of EP-04/EP-05. Keep this option only as an emergency fallback and state its
> gaps in the reconciliation report.

Prerequisites: the owner's go-ahead; a staff account whose credentials come from a secret
store and are never committed; a machine the owner trusts; outside office hours; calls in
sequence with a small pause between them.

The exporter has an allow-list of exactly these calls and refuses any other path:

| # | Call | Auth | Body | Saves |
|---|---|---|---|---|
| 1 | `POST /mono/auth/login` (EP-01) | none | `{"username": "…", "password": "…"}` | the token, in memory only |
| 2 | `GET /mono/api/enumeration/statoTracking` (EP-19) | none | — | raw JSON |
| 3 | `GET /mono/api/enumeration/servizioAccessorio` (EP-20) | none | — | raw JSON |
| 4 | `POST /mono/api/customer/pending` (EP-04) | Bearer | `{}` | raw JSON array |
| 5 | `POST /mono/api/customer/registered` (EP-05) | Bearer | `{}` | raw JSON array |
| 6 | `POST /mono/api/order/pending` (EP-09) | Bearer | `{}` | raw JSON array |
| 7 | `POST /mono/api/order/registered` (EP-10) | Bearer | `{}` | raw JSON array |
| 8 | `POST /mono/api/order/detail` (EP-17), once per order code of 6 and 7 (pending orders: the `TEMP` code as listed) | Bearer | `{"codice": "…"}` | raw JSON per code |
| 9 | `POST /mono/cms/notes` (EP-15), once per order code (registered; pending ones are attempted and failures tolerated) | Bearer | `{"codice": "…"}` | raw JSON per code |
| 10 | `POST /mono/api/order/trace/state` (EP-13), once per registered code | none | `{"codice": "…"}` | HTTP status + raw JSON: settles Q-03 and gives the expected public answer per code |
| 11 | optional: `POST /mono/api/customer/state` (EP-02) for a few e-mails of each list | none | `{"email": "…"}` | the status values (CU-03) |

Rules:

* On any non-2xx from an authenticated call, log in again once and retry that call once
  (the token lasts 6 hours; `POST /mono/auth/refresh-token` exists, 12a §2.5).
* Save every response verbatim with its URL, request body, HTTP status, time and SHA-256,
  plus a run manifest (base URL, start and end time, counts, exporter version).
* A code that appears twice in the lists (DQ-08) can only be fetched once by code. Store
  that answer once and flag both orders.
* Call 4–7 again at the end. If the counts changed, repeat the export during the freeze.

Known gaps of this option, stated in the report: only orders created in the last 6
months; no declined customers or orders and no unverified customers; no creation dates
(`createTms` is never exposed); no history; no staff users; units instead of lines
(12b §5.6).

### 4.4 Canonical snapshot

A directory with a manifest, the enumerations, and one record per customer and per order.
Each record holds the raw answers it came from:

```json
{
  "manifest": {
    "source": "API",
    "baseUrl": "https://app.logisticasanchez.com",
    "takenAt": "2026-11-07T14:05:00+01:00",
    "exporterVersion": "1.0.0",
    "counts": { "customersPending": 2, "customersRegistered": 40, "ordersPending": 3, "ordersRegistered": 500 }
  },
  "orders": [
    {
      "legacyList": "registered",
      "list": { "id": 118, "codice": "LS-2024-0118", "email": "ufficio@mobilificiorossi.example" },
      "detail": { "httpStatus": 200, "body": { "spedizione": {}, "dettaglioColli": {}, "palletList": [] } },
      "notes": { "httpStatus": 200, "body": { "notes": null } },
      "traceState": { "httpStatus": 200, "body": { "status": "Presa in carico" } },
      "fetchedAt": "2026-11-07T14:12:31+01:00",
      "sha256": "<hash of the raw answers>"
    }
  ]
}
```

(Counts and values are illustrative; the list item is abridged.) A dump extract fills the
same structure, with `source: "DUMP"`, the extra columns under `list`, and `detail`, `notes`
and `traceState` built from the tables.

### 4.5 The importer

A command of the `migration` module (chapter 10 §2), for example
`import-legacy --snapshot <dir> --decisions <file> [--dry-run] --report <dir>`.

1. Check the snapshot: manifest, hashes, required parts.
2. Enumerations: the `statoTracking` answer must equal the seed (chapter 02 §9.1);
   otherwise stop and report. Upsert `accessory_service` from EP-20.
3. Customers: normalise, apply the merge decisions, upsert by `legacy_id` (or e-mail when
   there is no id).
4. Orders: resolve the customer, map the fields (§3.2), upsert by `legacy_id` (or code /
   temporary code when there is no id).
5. Lines: replace the order's lines when its checksum changed.
6. Notes, then one `LEGACY_IMPORT` tracking event per approved order (upsert per order and
   source).
7. Totals, `data_quality` flags, the report (§4.6).

**Idempotency.** `legacy_key_map(entity, legacy_key, target_id, checksum, last_run_id)`.
An unchanged checksum is skipped; a changed one is updated and audited. Running the same
snapshot twice changes nothing (MIG-01 acceptance test).

**Dry-run.** The same code path in one transaction that is rolled back at the end; it
produces the full report.

**Decisions file.** Blocking issues are resolved only through a file the owner reviews,
which the importer applies deterministically. No manual database edits:

```json
{
  "mergeCustomers": [
    { "keep": "ufficio@mobilificiorossi.example", "merge": ["Ufficio@MobilificioRossi.example"] }
  ],
  "renameCodes": [ { "legacyOrderId": 131, "code": "LS-2024-0118-B" } ],
  "orphanOrders": [ { "legacyOrderId": 77, "attachToCustomer": "ufficio@mobilificiorossi.example" } ],
  "amounts": [ { "legacyOrderId": 95, "amount": "1250.00" } ],
  "accessoryServiceNone": { "valueOneMeansNone": false }
}
```

**Blocking issues** stop a real run until the file resolves them: duplicate normalised
e-mails (DQ-01), duplicate codes (DQ-08), orphan orders (DQ-10), unparseable amounts on
approved orders (DQ-12). Every other issue is imported and flagged.

**Records that vanish** between two runs are reported, never deleted automatically. The
final run goes into an empty schema, so the question does not arise then (§4.7).

### 4.6 Reconciliation report

| Section | Content | Pass when |
|---|---|---|
| Run | source, snapshot hash, times, importer version, dry-run or real | — |
| Customers | source pending → `PENDING_VERIFICATION` + `PENDING_APPROVAL`; source registered → `ACTIVE`; declined (dump) → `DECLINED`; merges listed | equal, after the listed merges |
| Orders | source pending → `PENDING`; registered → `APPROVED`; declined (dump) → `DECLINED` | equal |
| Tracking | count per raw source value → per target state | every row accounted for |
| Accessory services | count per raw value → per target service or "none" | every row accounted for |
| Lines | number of lines, Σ quantity, Σ kg (peso × quantity), Σ m³ (OP-R5), per raw `type` | equal |
| Amounts | orders with an amount, Σ amounts in EUR, min, max; every unparseable or ambiguous value with its raw text and the decision applied | Σ equals the sum computed from the source with the same parser and decisions |
| Identity | merged e-mails, duplicate codes, orphan orders, approved orders with a `TEMP` code | all resolved |
| Data quality | count per DQ code, plus a CSV of every flagged record | owner has read it |
| Spot checks | (a) a seeded random sample of 20 orders or 5 % (the larger), compared field by field, raw source against target, automatically; the same orders opened by the owner in the new back office and in the old CMS (checklist); (b) at least one order per tracking state; (c) the 10 highest amounts; (d) the 10 newest orders (highest legacy id); (e) every merged customer; (f) for every registered code, the new EP-13 state equals the legacy answer saved by export call 10 | all pass |
| Sign-off | owner, date | signed |

The flagged-records CSV and the spot-check diffs contain personal data: the importer writes
them outside the repository, next to the snapshot (§4.1). Only aggregate counts and sums may
be committed or quoted in a gate summary.

### 4.7 Rehearsal and cut-over

**Before (Gate 1, weeks ahead)**

* Rehearse on staging with the anonymised copy of production (chapter 10 §3): export or
  dump, import, report, owner UAT, with outbound e-mail caught (never delivered to
  customers). The real snapshot is used only in an owner-approved, access-restricted
  rehearsal environment with the outbox disabled. Repeat until the report is clean, and time
  every step.
* Lower the DNS TTL to 300 s for `logisticasanchez.com`, `www`, `app.`, `ordini.` and
  `dashboard.` (the CMS host the backend links to), at least one old TTL and no less than
  48 hours before the switch. Certificates for all five names on the new app: Let's
  Encrypt, DNS-01 to issue them in advance or HTTP-01 right after the switch (12d §6.4).
  SES production access already exists (the legacy backend e-mails customers; inferred):
  only a new IAM key for the new app is needed.
* Agree the freeze window (Q-19 default: a Saturday afternoon, under one hour) and the
  rollback window and triggers (§4.8).

**Freeze and switch**

1. Staff stop using the old CMS. The owner tells customers if they wish (wording: owner).
2. Stop legacy writes:
   * Point `ordini.logisticasanchez.com` to the new app, which shows a maintenance page
     until go-live. This does not depend on the portal's unknown hosting (CU-21). Proposed
     text: "Portale in aggiornamento: riprova tra poco oppure scrivici a
     logisticasanchezmb@gmail.com o su WhatsApp al +39 329 239 6962."
   * Turn the old S3 portal bucket into a redirect, or a maintenance page (LPS-S4: never
     delete it).
   * Block the legacy write endpoints by path in the legacy VPS's nginx (a snippet in
     `nginx/conf.d`, then `docker exec logistica-nginx nginx -s reload`; 12d §6.4): EP-03,
     EP-06, EP-07, EP-08, EP-11, EP-12, EP-14, EP-16, EP-18, `/mono/cms/order/update`,
     `/mono/utils/**`, `/mono/sendEmail` and `GET /mono/email-confirm` (it writes). Blocking
     by method is not enough, because the reads are `POST`s too. Keep EP-13 and the
     enumerations open and, for the API export, the reads the exporter needs (EP-01,
     EP-04, EP-05, EP-09, EP-10, EP-15, EP-17), ideally only from the exporter's IP
     address. The app's ports are bound to 127.0.0.1, so this stops every caller whatever
     the host name (inferred, 12d §6.4). The alternative, `docker compose -p
     logisticasanchez stop app`, keeps MySQL running for the dump but takes public tracking
     down for the window. Also stop any AWS-era backend that could still accept orders
     (Q-46): a frontend still pointed at Elastic Beanstalk would write into the old RDS.
3. Take the final snapshot (dump or API export) **after** writes have stopped.
4. **Final delta.** Import into the production schema, freshly migrated and empty. The
   full import is deterministic, removes the problem of vanished records, and its
   duration is known from the rehearsals. Use the incremental mode (the idempotent upsert
   over an earlier bulk run) only if the full import does not fit the window. Keep the
   outbox paused.
5. Reconciliation report; the owner signs off.
6. DNS and redirects:
   * `logisticasanchez.com` → the new app;
   * `www.logisticasanchez.com` → 301 to the apex;
   * `app.logisticasanchez.com` → the new app (compatibility endpoints, `410` for retired
     paths; chapter 02 §10);
   * `ordini.logisticasanchez.com` → 301 to `https://logisticasanchez.com/area-clienti/`;
   * `dashboard.logisticasanchez.com` → 301 to `https://logisticasanchez.com/gestione/`;
   * the S3 tracking bucket → redirect to `/tracking/` (LPS-S4).
7. Production smoke tests:
   * home and main pages; `/tracking/` with three real codes in different states;
   * EP-13 on `app.` with `Origin: https://logisticasanchez.com` (CORS) for the same codes:
     the same states as the legacy answers saved in step 3;
   * EP-19 and EP-20 bodies equal to the legacy captures; one retired path answers `410`;
   * staff login; approve and decline on test orders of a test customer created by the
     owner (removed afterwards); a sign-in link for that customer; delivery of an e-mail to
     the owner; the quote form.
8. Resume the outbox and open. Watch logs, 5xx errors and SES bounces (NFR-04).

**After**

* Keep the old backend stopped for writes but not deleted until the rollback window ends.
* Keep the final snapshot or dump encrypted for the agreed retention.

### 4.8 Rollback

* Decide beforehand the window (proposal: until the end of the first working day after
  go-live) and the triggers. Proposed triggers: public tracking wrong for real codes;
  staff cannot approve orders; e-mails not delivered; a reconciliation error found after
  go-live.
* Steps:
  1. Put the customer area and back office in maintenance.
  2. Export everything created or changed in the new app since go-live: new customers,
     orders, approvals with codes and amounts, tracking events, notes. A built-in report
     provides it.
  3. Point the DNS back to the legacy VPS IP (the TTL is still low). Lift the legacy write
     block by removing the nginx snippet; restore the portal at `ordini.` and the CMS at
     `dashboard.`. Never run `docker compose down -v` on the legacy server (it deletes the
     `mysql_data` volume) and do not use its `scripts/deploy.sh` (12d §1.13).
  4. Staff re-enter the exported changes in the old system. New orders must be submitted
     again through the old portal; approvals, tracking changes and notes are redone in the
     CMS. This is manual, so keep the window short.
  5. Post-mortem, fix, rehearse again, new cut-over date.
* After the window: fixes go forward only. The owner can retire the old backend once a
  last dump is archived.

### 4.9 After the cut-over

* Before terminating or deleting any old resource (the legacy VPS and its IP, Elastic
  Beanstalk environments, S3 website buckets, CloudFront distributions, the Netlify site),
  export the DNS zone of
  `logisticasanchez.com` and delete or repoint every record that targets one of them:
  CNAME or ALIAS records to `*.elasticbeanstalk.com`, `*.s3-website*.amazonaws.com`,
  `*.cloudfront.net` or `*.netlify.app` (for example `api.` and the CMS host). Once an
  environment is terminated or a bucket deleted, another account can register its name and
  serve content on our sub-domain; once the provider reassigns the VPS IP, an A record left
  pointing at it does the same.
* Retire the old CMS hosting, the legacy VPS (after archiving a final encrypted dump), the
  AWS-era resources (the Elastic Beanstalk UAT environment `uat-sanchez-logistica`, the RDS
  instances and their snapshots: check them for personal data first, Q-46) and the Netlify
  site (Q-20). Securely delete the backups and logs on the VPS: they hold personal data and
  secrets (12d §6.5). Keep both S3 bucket names as redirects for good (LPS-S4).
* Rotate or delete the old system's credentials: database, the old backend's SES key (in the
  server's `.env`) and any key committed in the `ls` repository (Q-53), staff passwords,
  and the IAM user and access key of the Netlify form function (`SES_ACCESS_KEY_ID` in the
  ls-home README), deleted when the Netlify site is retired. The new app has its own
  dedicated IAM user (chapter 10 §3).
* Apply the retention policy (Q-18) to imported data. Delete migration snapshots on
  schedule.

## 5. Data-quality issues to expect

> 12b §5.2 gives the verdict on each item from the source: DQ-08, DQ-10, DQ-12, DQ-16,
> DQ-17 and DQ-24 cannot happen (constraints and validation prevent them; keep them as
> integrity assertions), others are sharpened. 12b §5.3 adds DQ-25…DQ-37 (ambiguous `NEW`,
> declined orders kept, per-unit lines, coarse totals, untrimmed codes, timestamp meaning,
> amount columns by period, schema drift, hard deletes, …). Use both tables.

"Blocking" issues must be resolved in the decisions file before a real run. "Flag" issues
are imported and recorded in `data_quality`.

| ID | Issue | Origin | Detection | Handling |
|---|---|---|---|---|
| DQ-01 | Same e-mail in different case → duplicate customers (whitespace only if the backend was written to by other means) | OP-10, OP-11 | normalised collisions | blocking: owner picks the record to keep; the others' orders move to it |
| DQ-02 | Registration data that are really a recipient's | OP-01 (reverse order) | not detectable | spot checks only |
| DQ-03 | CAP not 5 digits (ZIP+4, typos) | OP-09 | pattern | normalise when safe; flag |
| DQ-04 | Required text empty or only spaces | OP-10 | trim | flag |
| DQ-05 | Province as free text | portal | no code match | flag; raw kept |
| DQ-06 | Phone in odd formats | OP-09 | parse failure | raw kept; flag |
| DQ-07 | "No service" and a real service id collide (`null`/`0`/`1`) | chapter 02 §8 | values 0 or 1 when the enumeration has those ids | owner decides (`accessoryServiceNone`); flag |
| DQ-08 | Duplicate definitive codes (no uniqueness check; the dialog keeps the previous code) | CMS-12, CMS-14 | group by normalised code | blocking: owner renames one, or confirms a duplicate order |
| DQ-09 | Approved order with a `TEMP` or blank code (spaces pass the CMS check) | CMS-14 | pattern | blocking if blank; flag otherwise |
| DQ-10 | Orphan order: its e-mail matches no listed customer (declined or deleted customer, case variant) | CU-05, CU-16 | join | blocking: attach to a customer, or create a minimal `DECLINED` customer flagged as orphan |
| DQ-11 | Missing DDT | builds `e183526` up to, not including, `1c84556` (2024-05-19 → 2024-06-03) | null | flag |
| DQ-12 | Amount unparseable, ambiguous (`1.250`), negative, more than 2 decimals | CMS-13 | parser | blocking on approved orders; the owner supplies the value or confirms null. A **missing** amount is not DQ-12: legacy approved orders without one (possible before `e5a72fc`) import with null and are counted in the report |
| DQ-13 | `importo` ≠ `importoAssicurato` | Q-05 | compare (dump) | default `importoAssicurato`; owner confirms; flag |
| DQ-14 | Sender snapshot taken from today's customer data | no pick-up data in legacy | always for legacy orders | note |
| DQ-15 | Line type missing or possibly wrong | OP-04 | raw `null`, number `1`, other | flag |
| DQ-16 | Line values `0` or not numeric | OP-12 | parse | flag |
| DQ-17 | Tracking value missing or unmatched | CU-08 | matcher | flag |
| DQ-18 | Notes: placeholder literal removed, line breaks lost, possibly saved on the wrong order, unclear link with the customer's note | CMS-07, CMS-11, CU-10 | text rules | note |
| DQ-19 | Values containing HTML or script | CMS-04 | `<[a-zA-Z!/]`, `javascript:` | imported as text; owner told (possible attack attempts) |
| DQ-20 | Possible duplicates from double submission (orders, registrations) | OP-02, CMS-25 | same customer, recipient, lines and DDT on consecutive ids (or within minutes when dates exist) | listed; never merged automatically |
| DQ-21 | Unknown dates | API export | null dates | note; the date-range report cannot include these orders |
| DQ-22 | Amount copied from the previous approval | CMS-12 | not reliably detectable (heuristic: equal amounts on consecutive approvals with different lines) | spot checks |
| DQ-23 | Recipient e-mail equal to the customer's | OP-14 | always | not imported as recipient e-mail |
| DQ-24 | Approved customer with an unconfirmed e-mail | CMS-20 | — | cannot happen: the server approves only verified customers (12b §5.2); integrity assertion only |

## 6. Open questions

Existing questions this chapter depends on: Q-01 (backend source), Q-02 (dump), Q-05
(meaning of the amount, VAT), Q-06 (codes), Q-09 (declines), Q-11 (users per customer),
Q-13 (pick-up address), Q-17 (staff and roles), Q-18 (retention), Q-19 (cut-over window),
Q-20 (Netlify site). Contract unknowns: chapter 02 §11.

Merged into chapter 11: approving a customer whose e-mail is not confirmed → Q-28;
duplicate shipment codes and orphan orders in the legacy data → Q-42 (resolved case by case
in the decisions file, §4.5); trackable temporary codes → Q-38; rollback window and
triggers → Q-40; telling customers about the customer area at go-live → Q-41.
