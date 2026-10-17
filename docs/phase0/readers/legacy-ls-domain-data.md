# Phase 0 legacy reader — `ls@migration` domain model and data

Read on 2026-10-17 against the local clone `legacy/ls` (HEAD `ca1b0d0`, branch `migration`), with
chapter 12b as the map. Citations: `ls@migration:<path>:<line>`; `M/` = `src/main/java/it/logisticasanchez/mono/`,
`R/` = `src/main/resources/`. Verdicts: **VERIFIED** (code says so), **DIFFERS** (code says otherwise),
**NOT FOUND** (not verifiable from the clone: logs, YAML values and older commits are off limits; only commit
metadata was read). No personal datum, secret or price is reproduced here.

## 1. What was read (paths)

* Entities, listeners, converters: `M/persistence/model/*.java`, `M/persistence/model/security/*.java`,
  `M/persistence/audit/*.java`, `M/persistence/model/converter/*.java`, `M/persistence/repository/*.java`.
* Enumerations and constants: `M/business/model/enumeration/**`, `M/business/model/constant/*.java`.
* Services: `M/business/service/{Order,Customer,AdminTools,Authentication,Report,AwsEmail}Service.java`,
  `M/business/service/crud/*.java`. Factories and models: `M/business/factory/*.java`, `M/business/model/*.java`.
* DTOs: `M/api/req/**`, `M/api/repr/**`. Controllers and errors: `M/api/controller/*.java`, `M/web/WebController.java`,
  `M/api/error/*.java`. Utilities: `M/business/util/{MathUtil,TextCodeFactory,TemporalUtil,LinkUtil,ExceptionFactory,EmailReqFactory}.java`.
* Configuration: `M/configuration/JacksonConfig.java`, `M/configuration/security/*.java`, `M/configuration/constant/*.java`,
  `M/configuration/property/{Jwt,Email}Properties.java`; `pom.xml`; key names only of `R/application*.yml` and
  `src/test/resources/application-*.yml`; `src/test/resources/init.sql` (statement shapes only); `src/test/**/*.java`
  (test names); `R/templates/{order-detail,tracking-state}.html` (field references only).
* Commit metadata only (`git log`, `git diff --stat`); cross-checks in `legacy/ls-order@dbe7292` (`js/function/data-handler.js`,
  `js/constant/costant.js`, `js/mapper/data-mapper.js`) and `legacy/ls-cms@f0c881c` (`js/function/component-handler.js`).
* Brief: 12b in full; 08 §2.3–2.5, §3, §4.5–4.6, §5; 09 and 11 ID lists; the Phase 0 readers for 08 and 12b.

## 2. Verified facts (claim → verdict → evidence)

| 12b claim | Verdict | Evidence |
|---|---|---|
| §1.1 Spring Boot 3.2.2, Java 17, MySQL Connector/J, H2 for tests; no Flyway/Liquibase | VERIFIED | `pom.xml:7-8,17,83,180`; no migration tool in the tree |
| §1.1 `application.prod.yml` has a dot and is never loaded as a profile | VERIFIED (name) / NOT FOUND (values) | files `R/application.prod.yml`, `R/application.local.yml` (dots) vs `R/application-dev.yml`; `ddl-auto` key present in all, values redacted |
| §1.1 default naming (snake_case), no `hibernate.jdbc.time_zone` | VERIFIED | no `spring.jpa.hibernate.naming*` or time-zone key in any YAML (key list) |
| §1.1 `Instant` columns; `user_session` uses `LocalDateTime` (JVM local) | VERIFIED | `M/persistence/model/SpedizioneEntity.java:67-72,75-80`; `M/persistence/model/security/UserSessionEntity.java:21`; `M/business/service/AuthenticationService.java:75` |
| §1.3 `soggetti` columns, nullability, UK `(ragione_sociale, email, codiceVerifica)`, index `ragione_sociale_email` | VERIFIED | `M/persistence/model/SoggettoEntity.java:15-17,25-57`; `codiceVerifica` is written camelCase in the annotation (:16); the snake_case DDL depends on the naming strategy (log claim NOT FOUND) |
| §1.3 `spedizioni` columns: `email` NOT NULL "Email mittente", recipient embedded, `telefono` nullable, three DOUBLE amounts, `note`, DDT nullable, enums NOT NULL, three timestamps, UK `codice`, FK `mittente_id` NOT NULL | VERIFIED | `SpedizioneEntity.java:17-18,30-72,85-90` |
| §1.3 `dettaglio_colli`: `colli/pallet/epal` nullable int, `peso/volume` NOT NULL double, id `GenerationType.AUTO`, `spedizione_id` NOT NULL one-to-one | VERIFIED | `M/persistence/model/DettaglioColliEntity.java:22-35,48-53` (`dettaglio_colli_seq` table: log claim NOT FOUND, consistent with Hibernate 6 on MySQL) |
| §1.3 `pallet`: one row per unit, dims/peso NOT NULL double, `type` NOT NULL label, FK NOT NULL, no quantity | VERIFIED | `M/persistence/model/PalletEntity.java:22-35,48-50` |
| §1.3 `users` (`username` unique NOT NULL, `psw`, `role`, `policy` nullable, no audit), `user_session` (`session_id` PK string, `user_id` no FK) | VERIFIED | `M/persistence/model/security/UserEntity.java:14-25`; `UserSessionEntity.java:16-21` |
| §1.5 cascades: customer→orders `ALL`+orphanRemoval; order→aggregate `ALL`; aggregate→units `ALL`+orphanRemoval; admin tools delete bottom-up | VERIFIED | `SoggettoEntity.java:60`; `SpedizioneEntity.java:85`; `DettaglioColliEntity.java:52`; `M/business/service/AdminToolsService.java:50-58,70-78,93-101,124-132` |
| §1.6 listeners: `create_tms` if null, `create_user` always, `update_*` on update; user = first authority (`ROLE_ADMIN`) else `CUSTOMER` | VERIFIED | `M/persistence/audit/SoggettoAuditListener.java:13-25` (the other three identical); `M/configuration/security/SecurityContextUtils.java:26-37`; `UserPrincipalDetails.java:22-24` |
| §1.6 submission inserts `NEW` then updates to `PENDING` in one transaction | VERIFIED | `M/business/service/OrderService.java:53-68`; `M/business/service/crud/SpedizioneService.java:75-88,107-114,132-143` |
| §2.1 converters store `getLabel()`, read `fromString` (equalsIgnoreCase, else 500); JSON out = label; ids resolved by ordinal | VERIFIED | `M/persistence/model/converter/*.java:7-19`; `M/business/model/enumeration/state/TrackingState.java:24-46` (same shape in the other enums) |
| §2.2 values and labels of TrackingState, ConfirmationState, PalletTypeEnum, ServizioAccessorioEnum, UserRoleEnum; NotificationEnum/AdminNotificationEnum texts; `AdminNotificationEnum.fromString` returns `EvasioneEnum`; ErrorEnum texts; PalletEnum/EvasioneEnum/TariffarioConst values | VERIFIED | `TrackingState.java:15-20`; `ConfirmationState.java:15-18`; `PalletTypeEnum.java:16-17`; `ServizioAccessorioEnum.java:16-21`; `UserRoleEnum.java:15-16`; `NotificationEnum.java:16-23`; `AdminNotificationEnum.java:15-17,27`; `M/api/error/ErrorEnum.java:18-38`; `PalletEnum.java:15-21`; `EvasioneEnum.java:15-20`; `TariffarioConst.java:9-22` |
| §2.3 four enumeration endpoints `{id: ordinal, name, label}`, `id` is `int` | VERIFIED | `M/api/controller/EnumController.java:25-67`; `M/api/repr/EnumerationRepr.java:7-12` |
| §3.1 C1–C8: register matches (email, ragione sociale), NEW row reused, PENDING→500, REGISTERED→409; link requires NEW and equal code; admin mail sent before the check; approve requires PENDING; decline → NEW from any state | VERIFIED | `M/business/service/crud/SoggettoService.java:53-61,63-70,99-121`; `M/business/service/CustomerService.java:38-41,47-55,57-62,70-77` |
| §3.1 `CustomerRepr.customer` = REGISTERED, `emailConfirmed` = PENDING or REGISTERED; EP-04/05 list PENDING/REGISTERED, no window | VERIFIED | `M/business/factory/CustomerFactory.java:26-27`; `M/api/controller/CustomerController.java:41-53`; `SoggettoRepository.java:19` |
| §3.1 link without URL-encoding; lookups by e-mail through `Optional<…> findByEmail` (non-unique → 500) | VERIFIED | `M/business/util/LinkUtil.java:32-34`; `M/persistence/repository/SoggettoRepository.java:15,21-22` |
| §3.2 O1: customer must be REGISTERED; `NEW`, `NON_GESTITO`, `"TEMP"+UUID`; admin e-mail; no customer e-mail | VERIFIED | `SpedizioneService.java:76-84`; `OrderService.java:110`; `M/business/util/TextCodeFactory.java:9` |
| §3.2 O2: `requireNonNull(codice, importo, tracking)` → 409 if the code exists → 500 unless PENDING; `importo_contrassegno := importo_assicurato`; state REGISTERED, tracking PRESA forced, `confirmation_ts` set, shipping/delivery flags false; e-mail "Conferma ordine N. <codice>" | VERIFIED | `SpedizioneService.java:145-175`; `M/business/factory/SpedizioneFactory.java:83-109`; `OrderService.java:114-121`; `M/business/util/EmailReqFactory.java:85` |
| §3.2 O2b: EP-11 without `req` promotes by ordinal + 1 keeping the TEMP code | VERIFIED (see §3 item 6) | `SpedizioneService.java:107-114,150` |
| §3.2 O3: decline checks only that the e-mail exists; writes `NEW` + `NON_GESTITO`, code unchanged; e-mail quotes the numeric id | VERIFIED | `OrderService.java:135-152`; `EmailReqFactory.java:90-102` |
| §3.2 O4: trace requires REGISTERED; any state 0–5; delivery e-mail when CONSEGNATO | VERIFIED | `SpedizioneService.java:236-259`; `M/api/controller/CmsController.java:111-123` |
| §3.2 O5/O6/O7: notes read/overwrite; `/cms/order/update` patches any field and rejects the order's own code; admin deletes, NPE on null `confirmation_ts` | VERIFIED | `OrderService.java:170-211`; `AdminToolsService.java:45-135` (NPE: :68) |
| §3.3 definitive code: no trim, no length check, `""` accepted (`@NotEmpty` commented out) | VERIFIED | `M/api/req/crud/PatchSpedizioneReq.java:18-19` |
| §3.4 timestamp rules per new state; approval forces shipping/delivery flags false | VERIFIED | `SpedizioneService.java:169-173,245-256`; `SpedizioneFactory.java:104-106` |
| §3.4 report: "data spedizione" = `confirmation_ts`, "data consegna" = `shipping_ts` replaced by `update_tms` when `delivery_ts` is null; range on `create_tms` with UTC day boundaries, `to` inclusive | VERIFIED (+ see §4) | `M/business/factory/ReportFactory.java:37-38`; `SpedizioneService.java:206-234` |
| §3.5 amounts are `Double`, `@Positive` (0 and negatives → 400); lists return `importo` as is (dead "Temp code" block); detail falls back to `importo_assicurato` when `importo` is null and tracking ≠ PRESA; approval e-mail prints all three | VERIFIED | `SpedizioneEntity.java:50-55`; `PatchSpedizioneReq.java:21-28`; `SpedizioneFactory.java:26-38`; `SpedizioneService.java:194`; `R/templates/order-detail.html:86,90,94` |
| §3.5 history of the approval contract (dates 2024-03-26, 06-04, 07-16, 07-18) | NOT FOUND (content) / VERIFIED (dates) | commits `ebcc438`, `2949426`, `d7732eb`, `d7b2c56` exist on those dates and touch `SpedizioneService.java` / `SpedizioneEntity.java` (`git log` metadata); `git show` not used |
| §3.5 `ls-cms@dev` sends `{id, req:{codice, importoAssicurato, tracking: 1}}`, never `importo` | VERIFIED | `ls-cms@f0c881c:js/function/component-handler.js:407-426` |
| §3.6 one `note` column; EP-15 answers "Nessuna nota presente" for NULL or unknown code; EP-16 overwrites when different, NPE on null `notes` | VERIFIED | `OrderService.java:170-203` |
| §3.7 public lookup returns the label; unknown or TEMP code → 200 "Non gestito"; empty → 400; no trim | VERIFIED | `M/api/controller/OrderController.java:33-39`; `SpedizioneRepository.java:30-31`; `SpedizioneService.java:116-119`; `M/api/req/OrderDetailReq.java:16-18` |
| §3.8 one `user_session` row per user, UUID = refresh token, expiry now + configured seconds; refresh endpoint | VERIFIED | `AuthenticationService.java:35-77`; `M/configuration/constant/PathConstants.java:16-17` |
| §4.1 request DTOs, limits (1–50, cap 1–9, phone pattern ≤ 20, e-mail ≤ 50, DDT ≥ 1, note ≤ 255, dims `@Positive`, `quantity ≥ 1`, `type @NotNull`) | VERIFIED | `M/business/model/constant/SoggettoConst.java:5-25`; `M/api/req/crud/CreateSoggettoReq.java`; `M/api/req/CreateOrderReq.java:22-39`; `CreateDestinatarioReq.java:20-46`; `OrderPalletReq.java:16-37` |
| §4.1 `servizioAccessorio` null → 500 (unboxing); `DettaglioColliService`/`PalletService` not `@Validated` | VERIFIED | `CreateOrderReq.java:36` (no `@NotNull`) + `OrderService.java:65`; `DettaglioColliService.java:21-23`, `PalletService.java:23-25` (no `@Validated`); proof: `CreateDettaglioColliReq.palletList` is `@NotNull` (:45-46) but never set (`OrderService.java:96-103`) |
| §4.2 `Spedizione` fields; `CustomerRepr` fields; `OrderDetailRepr {spedizione, mittente, dettaglioColli, palletList}`; `codiceVerifica` ignored | VERIFIED | `M/business/model/Spedizione.java:23-73`; `M/api/repr/CustomerRepr.java`; `OrderDetailRepr.java`; `M/business/model/Soggetto.java:53-54` |
| §4.2 lists EP-09/EP-10 (and `/api/order/active`, `/delivered`) limited to the last 6 months by `create_tms`; `/cms/order?email=` unlimited; no ORDER BY | VERIFIED | `SpedizioneRepository.java:39-80`; `SpedizioneService.java:177-181,261-292`; `OrderController.java:55-78` |
| §4.3 expansion by `quantity` in line order; counts per type; Σ unit `peso`; Σ unit volumes `h×w×l/1 000 000` then `Math.round`; `colli` from the request | VERIFIED | `OrderService.java:71-108`; `M/business/util/MathUtil.java:6` |
| §4.5 plain `ObjectMapper` + `JavaTimeModule` → epoch-decimal dates, unknown properties rejected; error mapping 404/400/500 | VERIFIED | `M/configuration/JacksonConfig.java:15-16`; `M/api/error/ErrorController.java:30-33,45-61,71-77` |
| §6 portal default type `1` (= EPAL) when none chosen; `0` = "none" service at `dev` | VERIFIED | `ls-order@dbe7292:js/function/data-handler.js:23-26,58-61`; `js/constant/costant.js:87-88` (`"0"` generic, `"1"` EPAL) |
| §0 "domain code frozen since 2025-01-07" | VERIFIED | `git diff --stat 91cf025 ca1b0d0 -- src/main/java`: only `LinkUtil.java` (6 lines) |
| §1.1/§1.4/§1.7 logged DDL, constraint names, `dettaglio_colli_seq`, collation, Docker MySQL version | NOT FOUND | logs and YAML values are off limits; the entity mappings are consistent with the quoted DDL |

## 3. Discrepancies and corrections to the brief

1. **08 §3.2 and §5 DQ-13 ("both present and different → use `importoAssicurato`, what staff typed") are wrong.** Since `d7732eb`
   (2024-07-16) the typed amount is `importo` (`SpedizioneService.java:153,168`); `importo_assicurato` is optional and
   `importo_contrassegno` is its copy (:166-167). Rule: `COALESCE(importo, importo_assicurato)` as 12b §3.5 says; flag differences.
2. **08 §3.4 (`type` null/missing → GENERIC + DQ-15) cannot occur in the dump.** `type` is `@NotNull` on the wire
   (`OrderPalletReq.java:36-37`) and NOT NULL as a label (`PalletEntity.java:34-35`). The only ambiguity is EPAL written by the
   portal fallback `palletTypeId = 1` whose comment says "Generic pallet" (`ls-order@dbe7292:js/function/data-handler.js:24-25`
   vs `costant.js:87-88`): over-represented EPAL, undetectable (the wire type is not stored).
3. **08 §3.4 / DQ-16 "line values 0" cannot occur** (`@Positive`, `OrderPalletReq.java:16-29`); what can occur is a non-integer
   centimetre or kilogram (DOUBLE columns; the portal sends text-field values). DQ-16 = non-integer dimensions, as 12b §5.2.
4. **08 §2.3 `order_line.line_volume_m3` (per-line `round₂`, the portal formula) is not what the backend stored.** The backend sums
   unrounded unit volumes and rounds the order total to an integer (`OrderService.java:89-94`; portal: per-line `toFixed(2)`,
   `data-handler.js:102-108`). The 08 §4.6 check "Σ m³ (OP-R5) equal" must compare against values recomputed from `pallet` rows,
   never against `dettaglio_colli.volume`.
5. **08 §3.2 "`email` → `customer_id` by normalised e-mail; no match → DQ-10 blocking" must not be used for the dump:** join on
   `spedizioni.mittente_id` (NOT NULL FK, `SpedizioneEntity.java:88-90`); DQ-10 becomes an assertion (12b §5.2 already says so).
6. **12b §3.2 O2b is imprecise:** `promote` adds 1 to the ordinal for any non-REGISTERED state (`SpedizioneService.java:109-113`), so
   EP-11 without `req` on a declined-since-2024-07-22 order (`NEW`) re-pends it (`NEW → PENDING`), and on a `DECLINED` row it
   fails with 500 (`fromId(4)`). Same for customers (`SoggettoService.java:84-91`, unreachable because the callers check the state).
7. **12b §3.6 "more than 255 chars from EP-16 fail in MySQL strict mode with 500" DIFFERS:** EP-16 patches through
   `PatchSpedizioneReq.note @Size(max = 255)` (`PatchSpedizioneReq.java:35-36`) validated by the `@Validated` service
   (`SpedizioneService.java:41,90`) → 400; the database never receives a longer note through the API. A code longer than 255 chars,
   by contrast, has no request-side limit and would fail at the column (500).
8. **12b §3.3 omits `SpedizioneConst.CODICE_CHAR_MAX = 50`** (`M/business/model/constant/SpedizioneConst.java:5-6`): it is used only
   on the output model `Spedizione` (`Spedizione.java:27-29`), which is never validated, so it limits nothing. Codes up to 255 chars
   are possible; 08 §2.3 `code varchar(64)` needs an importer assertion or a wider column.
9. **12b §3.4 report fallback is incomplete:** besides `shipping_ts := update_tms` when `delivery_ts` is null, the report also sets
   `shipping_ts := confirmation_ts` when still null (`SpedizioneService.java:224-229`), in memory only.
10. **08 §2.3 `shipment_order.amount_raw varchar(50)` "legacy text as typed":** the source holds three doubles, never text
    (`SpedizioneEntity.java:50-55`); store the three values' text as 12b §5.4 proposes (fits in 50 chars only if abbreviated).
11. **08 §3.1 "`emailConfirmed` true → the dump's date if any":** there is no verification timestamp. For `PENDING` rows `update_tms`
    is the link time (nothing else updates a PENDING customer: `CustomerService.java:57-77`); for `REGISTERED` rows it is the approval
    time and the link time is lost (12b §5.5 "estimated" is right).
12. **08 §5 DQ-11 origin "builds e183526 → 1c84556":** the backend has always accepted a missing DDT (`CreateOrderReq.java:33-34`
    `@Size(min = 1)` only, column nullable), so NULL DDT may come from any period; the detection stays `IS NULL`.
13. **12b §2.1 "requests carry ids":** true for the `Integer` fields; enum-typed request fields also accept the label through
    `@JsonCreator fromString` (e.g. `DeleteAllOrdersReq.state`, `DeleteAllOrdersReq.java`). No data effect.

## 4. Facts the brief does not mention

* **E-mail failures roll back writes:** `CustomerService.register`, `confirm`, `decline` and `OrderService.register`, `confirm`,
  `decline` are `@Transactional` and send the e-mail inside the transaction (`CustomerService.java:47-77`;
  `OrderService.java:53-152`); an SES exception (a `RuntimeException`) rolls the row back. So the dump holds no "registered but
  never e-mailed" rows from SES outages. Exception: `CmsController.trace` commits the tracking change before sending the delivery
  e-mail (`CmsController.java:113-121`): a failed EML-09 leaves `CONSEGNATO` stored.
* **With `app.email.enableAutomaticEmails=false` every send is silently skipped** (`AwsEmailService.java:36-37,52-53`): if that flag
  was ever off in production, `NEW` customers exist who never received a link (they look like DQ-25 "unconfirmed").
* **`GET /mono/email-confirm` is not transactional and sends the admin e-mail first** (`CustomerService.java:38-41`): with e-mail
  enabled and SES down nobody can confirm; the admin inbox receives one "Nuovo cliente in attesa di conferma" per click, valid or not.
* **`spedizioni.email` can never be NULL** (column NOT NULL, `SpedizioneEntity.java:30-31`) although `destinatario.email` is optional
  on the wire (`CreateDestinatarioReq.java:44-46`): an order without it fails with 500, so every stored order carries the value the
  portal sent (the customer's e-mail, OP-14).
* **Unit regrouping is safe by construction:** every `pallet` row carries `dettaglio_colli_id`, so units of different orders never
  mix even if IDENTITY ids interleave under concurrency; `pallet.id` order only decides the line order (`OrderService.java:106-108`).
* **The 6-month window start is JVM-local wall-clock read as UTC** (`SpedizioneRepository.java:43`): 1–2 h shorter than six
  months in Europe/Rome; harmless.
* **The delivery e-mail labels `importo` as "Importo Assicurato"** (`R/templates/tracking-state.html:91-92`), while the approval
  e-mail prints the three columns under their own names (`order-detail.html:85-94`).
* **The PDF report's "colli" column is `colli` or, when NULL, the `pallet` count, which excludes EPAL units**
  (`ReportFactory.java:48`; `OrderService.java:80-85`): EPAL-only orders show 0 packages.
* **Dead or broken repository methods:** `DettaglioColliRepository.findBySpedizioneIdAndCreateTmsBetween` and
  `PalletRepository.findByDettaglioColliIdAndCreateTmsBetween` declare `List<SpedizioneEntity>` with `LocalDate` parameters and
  are never called (`DettaglioColliRepository.java:14`; `PalletRepository.java:14`). `EnumerationFactory` is empty. `PatchOrderReq`,
  the `*Trait` interfaces, `MathUtil.intVolume` and `TextCodeFactory.shuffle/toHexString` are unused.
* **`POST /mono/api/order/state {id}` returns an order's `stato` label by numeric id without a token**
  (`OrderController.java:41-47`; `SpedizioneService.java:126-130`): an id enumeration oracle (states only). For 12a's list.
* **A `users` row with NULL `role` breaks login with 500** (`AuthenticationService.java:83`), relevant to the `users` count check only.
* **Hard-coded addresses:** the office recipient of admin notifications and admin reports is a literal string
  (`EmailReqFactory.java:159,185`), and a sample link with a real-looking address sits in a comment (`EmailReqFactory.java:24`).
  Values not reproduced; the new app takes the office address from configuration (ADM-09 allow-list).
* **`pom.xml` declares `jackson-datatype-jsr310` twice (2.15.2 and 2.16.1)** and pins `jackson-*` 2.15.2 under Spring Boot 3.2.2;
  only relevant if Path A were chosen.
* **Tests:** `NonRegressionTests.OK_CustomerRegistration` and `IntegrationTests.ok` (CRUD round trip) are the only tests
  (`src/test/java/.../NonRegressionTests.java:64-65`; `IntegrationTests.java:109-170`); `init.sql` inserts two `users` rows.

## 5. Rules and texts to port (with the chapter 09 IDs they serve)

| Rule / text (source) | Serves |
|---|---|
| Registration fields and limits: 1–50 chars for name, address, province, city; CAP 1–9; phone required, pattern `SoggettoConst.java:22`, ≤ 20; e-mail `@Email` ≤ 50 (`CreateSoggettoReq.java`); new app tightens to 5-digit CAP and E.164 (Fix) | CUS-02 |
| Re-registration with the same (e-mail, company) is idempotent and re-sends the link; a pending registration is refused; an approved one answers 409 "E' già stata effettuata una registrazione con questi dati…" (`SoggettoService.java:99-115`; `ErrorEnum.java:37`) — in the new app replaced by the uniform CUS-01/CUS-02 answer | CUS-01, CUS-02 |
| Approval only from PENDING (verified e-mail); decline from any state; declined customers may register again (`SoggettoService.java:117-121`; `CustomerService.java:70-77`) | ADM-02, Q-09, Q-28 |
| Order submission requires an approved sender; one pending order per submission with `TEMP` code and tracking `NON_GESTITO`; lines: dims and weight > 0, quantity ≥ 1, type required; service id 0–5 (0 = none); DDT `""` rejected; note ≤ 255 (`SpedizioneService.java:76-84`; `OrderPalletReq.java`; `CreateOrderReq.java`) | CUS-04 |
| Totals: weight = Σ peso × quantity (`OrderService.java:86-88`); volume per chapter 09 (portal formula), **not** the backend's integer m³ (`OrderService.java:89-94`) | CUS-04, ADM-08 |
| List item fields of `Spedizione` (code, recipient, amounts, service, note, state, tracking, three timestamps) and detail `{spedizione, mittente, dettaglioColli, palletList}` (`Spedizione.java:23-73`; `OrderDetailRepr.java`); no 6-month window in the new app (all orders, paginated) | CUS-05, ADM-05, ADM-08 |
| Approval: code required, unique ignoring case (409 "E' già stata effettuata una registrazione con questo codice…"), amount > 0, order must be pending; tracking becomes `PRESA`; approval time recorded (`SpedizioneService.java:145-175`; `ErrorEnum.java:38`) | ADM-04 |
| Decline of an order: state `DECLINED` (new app) with e-mail subject "Sanchez Logistica - Aggiornamento ordine rifiutato" quoting the **code**, not the numeric id (`EmailReqFactory.java:90-102`; wording Q-37) | ADM-04, EML-05 |
| Tracking: any state after any, six labels verbatim (`TrackingState.java:15-20`); first change after approval and `TRANSITO`/`CONSEGNATO` timestamps become `tracking_event` rows; delivery e-mail "Sanchez Logistica - Consegna effettuata per ordine N. <codice>" (`EmailReqFactory.java:144`) | ADM-06, EML-09, ENH-05 |
| Notes: keep the customer's note; internal note separate; never store the placeholder "Nessuna nota presente" (`OrderService.java:170-203`) | ADM-07, Q-26 |
| Report: approved orders of one customer with `create_tms` in [from, to] inclusive, optionally only delivered; columns "data spedizione" = approval time, "data consegna" = delivery time; day boundaries in Europe/Rome (Fix: legacy uses UTC, `SpedizioneService.java:212-215`) | ADM-09 |
| Enumeration seeds and endpoint shapes `{id, name, label}` exactly as `EnumController.java:25-67` | ADM-10, API-02 |
| Public lookup: 200 `{"status": "<label>"}`, unknown and TEMP codes → "Non gestito", empty → 400 (`OrderController.java:33-39`) | TRK-01, TRK-02, API-01 |
| E-mail subjects: "Sanchez Logistica - Conferma indirizzo email", "… - Conferma nuovo cliente", "… - Aggiornamento indirizzo email", "… - Conferma ordine N. <codice>", "… - Report ordini"; admin: "… - Nuovo cliente in attesa di conferma", "… - Nuova richiesta di spedizione in attesa di conferma" (`EmailReqFactory.java:39,55,69,85,128,160`; `AdminNotificationEnum.java:15-16`) | EML-01…07, Q-37 |
| Error texts of `ErrorEnum.java:18-38` (400/401/403/404/409/500/503) as the default Italian copy | CUS-*, ADM-* |
| Migration: status table of 12b §5.5 (DQ-25 rule verified: `NEW` + `update_user IS NULL` = unconfirmed, `= 'ROLE_ADMIN'` = declined); orders by `mittente_id`; lines regrouped from units; amount `COALESCE(importo, importo_assicurato)`; events from `confirmation_ts`/`shipping_ts`/`delivery_ts` | MIG-01, MIG-02 |

## 6. Risks and unknowns (by ID where one exists)

* **Q-02 / DQ-33** real schema, collation (PAD SPACE or not), `@@time_zone`, leftover columns (`users.user`, `spedizioni.codice_verifica`,
  `pallet.type = ''`): only the dump answers; run the 12b §1.8 queries first.
* **DQ-01** number of e-mail collisions (`GROUP BY LOWER(TRIM(email))`), blocking. **DQ-25** counts of `NEW` by `update_user`
  class; rows with `NEW` + `'CUSTOMER'` would contradict the code and need a look. **DQ-26/37** presence of `DECLINED` rows (builds
  2024-02-29 → 2024-07-22) and declined customers with orders.
* **DQ-29** codes with whitespace, tabs, empty strings, length > 64; **DQ-32** `REGISTERED` with `TEMP%` code or NULL
  `confirmation_ts` (O2b path, `SpedizioneService.java:150`). **DQ-34** id gaps from admin deletes.
* **DQ-31 / Q-05** share of `REGISTERED` orders with NULL `importo` (approvals before 2024-07-16); values with more than two decimals;
  `importo` ≠ `importo_assicurato` cases; VAT meaning still the owner's.
* **DQ-28** `dettaglio_colli` vs units (`pallet`/`epal` counts, Σ `peso`, integer `volume`); **DQ-16** non-integer dimensions;
  **DQ-15 / DQ-07** EPAL and "Prenotazione telefonica" shares by period (needs portal deployment dates, owner).
* **DQ-30** orders with `delivery_ts` and tracking ≠ `CONSEGNATO`, `shipping_ts` semantics; **DQ-18** notes equal to the
  placeholder or overwritten by staff (`update_user = 'ROLE_ADMIN'` on PENDING rows).
* **Q-17** `users` count and roles (never migrated; the dump holds bcrypt hashes and live refresh tokens: drop both tables after
  restore, 12b §5.6). **Q-06** whether the 12-digit codes come from a carrier (owner). **Q-47 / CU-21** the production CMS build.
* **Unknown without ID:** whether `enableAutomaticEmails` was ever off in production (unconfirmed `NEW` rows without e-mails);
  whether the external `config/application-prod.yml` overrides `ddl-auto` (values redacted); whether production ever ran with
  `ROLE_ADMIN` writes through `/cms/order/update` (amount or code edits after approval, visible as `update_user='ROLE_ADMIN'` +
  changed values only in the developer logs, which were not read).
* **Security lens (12a):** personal data readable without a token on `/api/order/pending`, `/active`, `/delivered`, `/order/state`;
  hard-coded office address; not reproduced in the new app.

## 7. Notes for the planner

* **ADR 0001 inputs (data side):** (a) amount = `COALESCE(importo, importo_assicurato)` rounded half-up to 2 decimals, both raw
  values kept, `importo_contrassegno` in `legacy_payload`; (b) decline → explicit `DECLINED` with `declined_at = update_tms`
  (estimated); (c) notes: legacy note → `order_internal_note`, plus `customer_notes` only for PENDING orders never touched by staff
  (12b §5.5 proposal; needs the ADR per 08 §3.7); (d) codes: unique ignoring case, trimmed for matching, raw kept, no proposed
  sequence until Q-06 is answered, column at least as wide as the longest legacy code; (e) tracking events from the three legacy
  timestamps only, no invented steps; (f) the 6-month list window is dropped (ADM-05 pagination); (g) timestamps read as UTC,
  verified on one dated order; (h) public lookup keeps 200 "Non gestito".
* **Module ownership (10 §2):** `customers` owns `customer`, `customer_user`, `login_token`; `orders` owns `shipment_order`,
  `order_line`, `order_internal_note`, `accessory_service`; `tracking` owns `tracking_state`, `tracking_event` and the public
  lookup; `migration` owns `legacy_key_map`, the restored-dump reader and the reconciliation report; `notifications` owns the outbox.
  Cross-module reads go through services (orders → customers for the sender snapshot; tracking → orders for the code lookup).
* **Phase 1 needs:** seeds for the six tracking states and six services with `legacy_id` and `is_none`; `shipment_order.code` and
  `temp_code` wide enough (legacy `TEMP`+UUID = 40 chars; codes up to 255); `amount numeric(10,2)`; `legacy_payload jsonb` on
  customer, order, line; `data_quality jsonb`; importer assertions (unique codes, FK orders, one aggregate per order, known labels)
  as blocking checks; synthetic fixtures that mirror the legacy shapes (labels stored, units per row, `NEW`/`PENDING`/`REGISTERED`
  customers, declined orders as `NEW`); tests tagged MIG-01/MIG-02, ADM-04, ADM-06, TRK-02, API-01/02.
* **Gate 0 items to show the owner (Italian summary):** amount rule and VAT (Q-05), code origin (Q-06), notes split (Q-26),
  declined handling (Q-09), the office address moved to configuration, and the dump request (Q-02) with the 12b §1.8 queries.
