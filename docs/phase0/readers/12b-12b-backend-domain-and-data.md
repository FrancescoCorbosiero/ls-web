# Phase 0 reader — chapter 12b · Legacy backend: domain model, persistence, state machines and data migration

Source: `docs/brief/12b-backend-domain-and-data.md` (1126 lines, read in full on 2026-10-16). Cross-checked against README, 09, 10 §2, 11 (Q-02/03/05/06/09/13/24/25/26/27/28/30/35/36/38), 08 §2.3–2.5, §3.2, §3.7–3.8, §4.2, §4.5–4.6, 02 §10.2, §11, 04 (CMS-04/11/20), 03 (OP-U4/U7), 12a §1.4 D1, 12c §4.2, 12d §1.7 and PROMPT.md. `M/` = `src/main/java/it/logisticasanchez/mono/` in `ls@migration` (= `ca1b0d0`).

## 1. Scope

The chapter reads the seven MySQL tables of `lsdb` as Hibernate generates them (§1, with the drift `ddl-auto: update` may have left, §1.8), the five persisted enumerations stored as Italian labels (§2), the customer, order, code, tracking-timestamp, amount and note state machines as coded (§3), the request/response DTOs and the per-unit `pallet` storage (§4), and it rewrites chapter 08's reconciliation: verdicts on §1, DQ-01…24, new DQ-25…37, the legacy → target column mapping, the importer steps and the reconciliation report (§5), the data risks (§6) and the answers to Q-03/05/06/09/25/26/28/38 (§7).
It is the primary input of the migration module and the authority on how customers and orders behaved; it corrects chapter 08 §1–§4 and chapter 02 §11, and it settles CU-01…CU-24 (minus CU-06, CU-12…14, CU-21, CU-23).
Reading map: migration reads §1, §2, §5, §6 (with 12d §1.7, §1.11, §6.4–6.5 and 08 §1, §3–§5); customers + sign-in reads §3.1; orders reads §3.2–3.6 and §4 (with 12c §4.2); tracking + compatibility is not listed for 12b but §2.3, §3.4 and §3.7 settle its contract (API-01/02, TRK-02, ADM-10); back office needs §3.2 O2–O6, §3.6, §4.2; Phase 1 foundations need §5.4 (target-model changes) before writing the schema migrations.

## 2. Requirement IDs touched

| ID | Where 12b specifies or constrains it |
|---|---|
| TRK-01, TRK-02, TRK-03 | §3.7 lookup = `SELECT tracking WHERE codice = :codice`, label out, unknown and TEMP codes → `Non gestito`, empty → 400, collation decides case and trailing spaces; §2.2 the six states; legacy answer carries only `status` (TRK-03 baseline) |
| API-01, API-02, API-03 | §3.7, §2.3 (exact JSON of the four enumeration endpoints, `id` is an int, `tipoPallet`/`statoConferma` missing from 02); §5.6 write paths to block at cut-over (`/mono/cms/order/update`, `/mono/utils/**`, `GET /mono/email-confirm`) |
| CUS-01, CUS-02, CUS-03 | §3.1 C1–C8 (NEW → link → PENDING → REGISTERED, decline → NEW), §1.3 `soggetti` limits (texts 1–50, `cap` 1–9, phone required), §3.1 `+` in e-mails never confirmable (DQ-35), duplicate e-mails lock customers out (§6); §4.1 limits |
| CUS-04, CUS-05 | §3.2 O1 (only REGISTERED customers; `TEMP`+UUID; PENDING), §4.3 unit expansion, totals (volume rounded to whole m³), `colli` never sent, §4.1 wire variants and limits, §4.4 `spedizioni.email` = customer's e-mail; §4.2 list fields and the 6-month window; §3.5 which amount a customer sees |
| ADM-02, ADM-03 | §3.1 C7 (approval needs PENDING), C8, EP-04/05 lists (`CustomerRepr`, no dates, no `stato`) |
| ADM-04, ADM-05, ADM-06, ADM-07, ADM-08 | §3.2 O2–O6 (code 409, `importo` > 0, tracking forced PRESA, decline → NEW keeping the TEMP code, O2b promote path, O6 free edits), §3.3 codes, §3.4 timestamps, §3.5 amounts, §3.6 one shared note, §4.2 EP-17 detail with units (`palletList`), §4.2 lists limited to 6 months and unordered (CU-15) |
| ADM-09 | §3.4 report labels (`confirmation_ts` = "data spedizione", `shipping_ts` = "data consegna" with `update_tms` fallback) and `create_tms` range with UTC day boundaries |
| ADM-10 | §2.2 the exact labels and ids to seed; §2.2 unused pricing scaffolding never to seed; §5.4 `accessory_service` seed (`is_none` for id 0) |
| EML-01, EML-03, EML-05, EML-06, EML-09 | §3.1 C6 (office e-mail on every click, before the check; link never expires, no URL-encoding), C7/C8 subjects, §3.2 O1–O4 e-mails (decline quotes the numeric id; approval e-mail prints all three amounts and the note), §2.2 NotificationEnum subjects |
| MIG-01, MIG-02, MIG-03 | §1 (schema, DDL, drift queries), §2 (labels to assert), §3.4–3.6 (timestamp, amount, note semantics), §5.2–5.7 (DQ list, mapping, importer steps, blocking issues, reconciliation report, worked example), §5.6 cut-over block list and secrets in the dump, §5.9 what the dump must still tell |
| NFR-02, NFR-03, NFR-04 | §1.3/§5.6 `users` and `user_session` hold hashes and live refresh tokens (treat the dump as secret); §1.5 hard deletes leave no trace; §6 open endpoints exposing personal data; §1.6 audit columns hold a role, never a person |
| ENH-01/02/11 (pricing), ENH-05 (history), ENH-13 (audit) | §2.2 `PalletEnum`/`EvasioneEnum`/`TariffarioConst` are dead scaffolding (PR-02: keep chapter 07); §3.4 no tracking history; §1.6 no history table or version column |

## 3. Rules to keep (M1 parity)

* TRK-02, API-01, Q-03/Q-38: label out; unknown or TEMP code → `200 {"status":"Non gestito"}`; empty → 400 (ls@migration:M/business/service/crud/SpedizioneService.java:116-119; M/persistence/repository/SpedizioneRepository.java:30-31; M/api/req/OrderDetailReq.java:16-18; §3.7).
* API-02, ADM-10, Q-25: tracking ids 0–5 `Non gestito`…`Consegnato`; services ids 0–5 with 0 = `Nessun servizio accessorio` as a real item; JSON `{id, name, label}` with `id` a number, declaration order (M/business/model/enumeration/state/TrackingState.java:15-20; M/business/model/enumeration/ServizioAccessorioEnum.java:16-21; M/api/controller/EnumController.java:25-64; §2.2–2.3).
* CUS-02, ADM-02, Q-28: approval needs `stato = PENDING`, reached only through the verification link; unconfirmed customers never reach the staff list (M/business/service/crud/SoggettoService.java:117-121; M/api/controller/CustomerController.java:41-46; §3.1 C6–C7).
* CUS-02, Q-36: registration requires ragione sociale, address, province, city, CAP, phone and e-mail (`soggetti` columns NOT NULL; M/api/req/crud/CreateSoggettoReq.java:21-49; §1.3).
* CUS-02/03, Q-09: a declined customer keeps the record and may register again (M/business/service/CustomerService.java:70-77; §3.1 C8, C2).
* CUS-04: only a REGISTERED customer may order; a new order is PENDING with a temporary code and `Non gestito`; weight = Σ unit `peso`, volume from h × w × l / 1 000 000 (M/business/service/OrderService.java:53-112; M/business/util/MathUtil.java:5-7; M/business/service/crud/SpedizioneService.java:75-88; §3.2 O1, §4.3). Recipient phone optional, DDT non-empty when present (§1.3, §4.1).
* CUS-04, Q-13: no pick-up address exists; the sender is the customer record (§4.4, §5.1).
* ADM-04, Q-05/Q-06: approval = staff-typed code (unique → 409 `CODE_409`), `importo` > 0 (`@Positive`), state REGISTERED, tracking forced to PRESA, `confirmation_ts` = now; decline → the order is not deleted, TEMP code kept (SpedizioneService.java:152-173; M/api/req/crud/PatchSpedizioneReq.java:21-28; M/business/service/OrderService.java:135-152; §3.2 O2–O3).
* ADM-06, EML-09: tracking changes only on REGISTERED orders, any state after any state; `CONSEGNATO` triggers the delivery e-mail (SpedizioneService.java:236-259; §3.4). Only the timestamp semantics (§3.4) matter for the importer, not for new behaviour.
* ADM-07, Q-26: the customer's note is shown to staff; "Nessuna nota presente" when empty (OrderService.java:170-203; §3.6) — content kept, overwriting not (§4 fix 2).
* ADM-09: report range on the submission date, inclusive; approval date shown as "data spedizione" (SpedizioneService.java:212-229; M/business/factory/ReportFactory.java:37-38; §3.4).
* CUS-05/ADM-08: the detail shows sender, recipient, aggregate and units (M/api/repr/OrderDetailRepr.java:18-27; §4.2); the customer sees the amount only once approved (§3.5).
* MIG-01/02: the dump is the only complete source (lists see 6 months: SpedizioneRepository.java:41-45; §5.6); enumerations are labels in `varchar` and every stored label must belong to §2.2 (M/persistence/model/converter/TrackingStateConverter.java:7-18; §2.1); `datetime(6)` read as UTC (M/persistence/model/SpedizioneEntity.java:67-72; §1.1); FK `mittente_id` NOT NULL and UNIQUE `codice` are integrity assertions (SpedizioneEntity.java:17-18, 88-90; §1.4).
* MIG-03: also block `/mono/cms/order/update`, `/mono/utils/**` and `GET /mono/email-confirm` at the freeze (§5.6).

## 4. Deliberate fixes (legacy NOT reproduced) — proposed ADR titles

1. **Explicit `DECLINED` for customers and orders; no reset to `NEW`** — 12b §3.1 C8, §3.2 O3, §5.4; Q-09; CUS-03, ADM-02/04, MIG-02 (importer classifies legacy `NEW` rows with DQ-25).
2. **Customer identity = one lower-cased, trimmed, unique e-mail; registration keyed on the e-mail alone** — legacy keys on (e-mail, ragione sociale) with a useless unique key (§1.3, §6, DQ-01); CUS-01/02; duplicates resolved only through the decisions file.
3. **One-time, expiring, URL-encoded verification tokens consumed by `POST`; `codice_verifica` not migrated** — §3.1 C6, DQ-35; Q-30; CUS-01, EML-01, EML-06 (office notified once, after the check).
4. **Order lines with a quantity and two-decimal volumes; aggregate recomputed from lines; `package_count` stored** — §4.3, DQ-27/28; CUS-04, ADM-08, MIG-02 (units regrouped per §5.5).
5. **Money as `numeric(10,2)` with a VAT flag; approval accepts the Italian comma; importer takes `COALESCE(importo, importo_assicurato)` rounded half-up and keeps both raw** — §3.5, DQ-13/31; Q-05; ADM-04, MIG-02.
6. **Shipment codes trimmed, non-empty, unique ignoring case, raw value kept; no "promote without a code" path** — §3.3, O2b, DQ-29/32; Q-06; ADM-04, TRK-02.
7. **Enumerations as seeded reference rows (FK by id) instead of free `varchar` labels; `is_none` row never offered** — §2.1, §5.4; ADM-10, API-02, CUS-04.
8. **Internal notes separate from the customer's note; import rule for the shared legacy column** — §3.6, §5.5; Q-26; ADM-07.
9. **Tracking history as events with time and user; `shipping_ts`/`delivery_ts` mapped to events once** — §3.4, DQ-30; ADM-06, ENH-05.
10. **Back-office lists show full history, newest first, paginated** — §4.2 (6-month window, no ORDER BY, CU-15); ADM-03/05.
11. **Decline validated against the order's own customer; decline e-mail quotes the code** — §3.2 O3 (any e-mail, numeric id); ADM-04, EML-05.
12. **No post-approval free edit endpoint in M1 (`/cms/order/update`); corrections, if wanted, ADMIN-only with audit** — §3.2 O6; ADM-04, ENH-13 (Gate 0 question).
13. **Italian formats: 5-digit CAP, E.164 phone, province code; recipient e-mail optional and never the sender's** — §1.3, §4.4, DQ-03/06/23; CUS-02/04.
14. **Flyway migrations and `ddl-auto: validate`; drift checked on the restored dump before import** — §1.1, §1.8, DQ-33; MIG-01, ADR 0001.
15. **Staff accounts and sessions not migrated; `users`/`user_session` dropped from every non-production copy** — §3.8, §5.6; ADM-01, NFR-02.
16. **No hard deletes: admin tools not ported, erasure = anonymisation** — §1.5 C9/O7, DQ-34; NFR-03.
17. **Dates as `timestamptz`, legacy `datetime(6)` read as UTC and verified on one known order** — §1.1, §5.6; MIG-02, ADM-09.

## 5. Unknowns by ID (recommended default)

* **Q-05** amount meaning and VAT: storage settled (DOUBLE `importo`, fallback `importo_assicurato`; no VAT anywhere) — default price charged, VAT excluded, flag stored null for legacy rows until answered.
* **Q-06** who assigns the definitive codes (mostly 12-digit numbers, maybe a carrier's): default staff type it, uniqueness enforced, nothing proposed.
* **Q-09** declined records: default explicit `DECLINED`, re-registration allowed. **Q-28** approval only after verification: yes, as today. **Q-26** notes: separate internal notes, customer note kept.
* **Q-03 / Q-38** tracking answers: keep 200 "Non gestito", 400 for empty, case-insensitive trimmed matching; P-02 confirms the live build (**Q-27**: only after the owner's go-ahead).
* **Q-25** services: seed §2.2 exactly (OP-U4 settled). **Q-13** pick-up address = customer's address. **Q-24 / OP-U7** DDT mandatory; "Numero Colli" optional count (never filled in legacy). **Q-35** no maximum in the legacy; warnings per chapter 11. **Q-36** phone required.
* **Q-30** old `/mono/email-confirm` links: never confirm after cut-over; a small page sends to `/area-clienti/`. **Q-02** dump/schema: optional; build against §1.7, run §1.8 drift queries at cut-over.
* **CU-01…CU-24** settled in §5.8 except CU-21 (production CMS build is later than `ls-cms@dev`, source unknown): default build from chapter 04 screens + 12b rules.
* **CMS-20** cannot happen server-side (close); **CMS-04/CMS-11** stored HTML and placeholder notes → DQ-19/DQ-18 flags, escaping everywhere.
* **DQ-01…DQ-24** re-verdicted in §5.2 (DQ-08/10/12/17/24 impossible; DQ-01 blocking); **DQ-25…DQ-37** proposed with detection queries — adopt all; blocking: DQ-01, empty codes (DQ-29), unknown labels, violated assertions. **DQ-07** needs the portal deployment dates (owner). **DQ-37** declined customers with orders: import with their orders, list for the owner.
* **PR-02** `PalletEnum` dims contradict chapter 07 §3.1: keep chapter 07; `TariffarioConst` meaning to the pricing owner (Gate 2).
* **SEC-** (via §6 last row, 12a SEC-01…05): open list/report endpoints — not reproduced. No LPS-*, F-* or OP-U* beyond U4/U7 raised here.
* Without an ID (§5.9): production collation (PAD SPACE or not), effective `ddl-auto`, time zone of stored timestamps, deployment dates of each build, presence of `DECLINED` rows — default: collation-insensitive matching, drift and `@@time_zone` queries on the restored dump, one known order to validate UTC.

## 6. Contradictions and spot check

* **12b §3.5/§5.5 vs 08 §3.2 and §5 DQ-13 (12 wins):** 12b migrates `COALESCE(importo, importo_assicurato)` (what EP-17 shows) and flags differences; 08 §3.2 says "both present and different → use `importoAssicurato`". 08 §3.2/DQ-13 must follow 12b; Gate 0 confirms.
* **12b §5.4 vs 08 §2.5 and 11 Q-06:** 08 §2.5 still says "the app proposes the next code when the imported codes follow a recognisable sequence"; 12b §3.3 and 11 Q-06 say propose nothing until the owner explains the codes. 08 §2.5 is stale.
* **12b §5.5 vs 08 §3.7 (target rule, needs ADR):** 08 imports the shared note into `order_internal_note` only; 12b proposes also `customer_notes` for PENDING orders with `update_user = 'CUSTOMER'`. Decide in ADR (fix 8).
* **12b §5.6 vs 08 §4.5 blocking list:** 08 blocks DQ-01/08/10/12; 12b blocks DQ-01, DQ-29 (empty codes), unknown labels, violated assertions, and demotes DQ-08/10/12. 08 §4.5 and its decisions-file keys (`orphanOrders`, `amounts`, `accessoryServiceNone`) to be trimmed; add a key for DQ-29 codes.
* **12b §1.1 (UTC storage, inferred) vs 12d §1.7 / 08 §4.2 step 2 (possible October 2025 time-zone break):** not exclusive (Hibernate binds a UTC calendar), but the importer must verify on one order the owner can date before mapping timestamps.
* **12b §3.7 last paragraph vs 02 §10.2 (stale remark):** 12b says chapter 02's default is 404 for unknown codes; 02 §10.2 rule 3 already says 200 "Non gestito" and that 404 would need an ADR. No conflict in the result.
* **12b §0/§3.1 vs 04 CMS-20:** the CMS UI lets staff approve an unverified customer, the server answers 500; 12 wins (CMS-20 closed, DQ-24 dropped).
* **12b §1.3 (`telefono` NOT NULL) vs 08 §2.3 `customer.phone` "optional unless the owner decides":** 09 CUS-02 and Q-36 say required; 08 §2.3 wording stale (column may stay nullable for data reasons).
* **12b §5.5 vs 08 §2.3 column sizes:** legacy `codice` is any string ≤ 255 (with whitespace), target `code varchar(64)`; `amount_raw varchar(50)` must hold "importo=…; importo_assicurato=…". Importer must flag codes > 64 chars or Phase 1 widens the columns.
* **Internal, harmless:** §0 item 6 "no duplicate codes" vs §1.8 possible leftover UNIQUE `(codice, codice_verifica)` (extra, not weaker); §3.1 C5 says DECLINED rows get the link again while §0 says such rows exist only from older builds — consistent.
* **PROMPT.md:** none; its "Legacy at a glance" cites the same lines (OrderController.java:33-38, SpedizioneService.java:152-173).

| Citation | Verdict | Evidence |
|---|---|---|
| ls@migration:M/business/service/crud/SpedizioneService.java:116-119 | VERIFIED | `trackingState(codice)` returns `repository.findTrackingByCodice(codice).orElse(TrackingState.NON_GESTITO)` |
| ls@migration:M/business/service/crud/SpedizioneService.java:152-173 | VERIFIED | `requireNonNull` on codice/importo/tracking (152-154); `findByCodice` → `CODE_409` (163); PENDING check (164); contrassegno := assicurato (166-167); REGISTERED, PRESA, confirmation ts true, shipping/delivery false (168-173) |
| ls@migration:M/business/service/OrderService.java:71-108 | VERIFIED | `IntStream.range(0, quantity)` expands units; counts per type; Σ `peso`; Σ `MathUtil.volume` then `.map(Math::round)`; `colli` from the request; one `palletService.create` per unit |
| ls@migration:M/business/service/CustomerService.java:70-77 | VERIFIED | `decline` patches `stato` to `ConfirmationState.NEW.ordinal()` and sends `CUSTOMER_DECLINATION` |
| ls@migration:M/persistence/repository/SpedizioneRepository.java:41-45 | VERIFIED | default `findAllByStatoAndCreateTmsBetween(stato)` uses `LocalDateTime.now().minusMonths(6).toInstant(ZoneOffset.UTC)` (local wall-clock read as UTC: a window skew of up to 2 h, harmless) |

## 7. Planned tests

* **TRK-02 / API-01** — unit `@Tag("TRK-02")`: matcher trims, removes inner spaces, ignores case; contract `@Tag("API-01")` with 02 §10.4 fixtures FX-01/03/05/06: known → label, unknown and `TEMP…` → `Non gestito`, `""`/`{}` → 400; Playwright `[TRK-01]` six states + unknown.
* **API-02 / ADM-10** — contract `@Tag("API-02")`: the two endpoints return byte-for-byte the arrays of 12b §2.3 (ids as numbers, order); integration `@Tag("ADM-10")`: seed rows equal §2.2 labels, `is_none` only for id 0, seed idempotent.
* **CUS-01 / CUS-02** — integration `@Tag("CUS-02")`: registration lower-cases and trims the e-mail; a second registration with a different company name creates no second customer; phone required; 5-digit CAP; `+` in the e-mail survives the link (URL-encoded); E2E `[CUS-02]` register → verify → pending → approve → order.
* **CUS-03 / ADM-02** — unit `@Tag("ADM-02")`: approve refused unless `PENDING_APPROVAL`; decline → `DECLINED`; `DECLINED` → register again → `PENDING_VERIFICATION`; E2E `[CUS-03]` declined customer sees status, cannot order.
* **CUS-04** — unit `@Tag("CUS-04")`: lines keep quantity; totals in decimal (2 EPAL 150×80×120 → 2.88 m³, not 3); weight Σ peso × qty; service "none" → null FK; `package_count` optional; integration: non-ACTIVE customer refused; temp code unique and ≥ 40 chars allowed; E2E `[CUS-04]` happy path, stored order equals input.
* **CUS-05 / ADM-08** — E2E `[CUS-05]`: customer sees only own orders; amount shown only when approved; `[ADM-08]` detail lists lines with quantities (legacy-imported order shows regrouped lines).
* **ADM-04** — unit `@Tag("ADM-04")`: code trimmed, non-empty, unique ignoring case (409-equivalent error), `180,50` and `180.50` parse, 0 and negatives refused, approval sets PRESA + `approved_at`; decline keeps temp code and checks the customer; E2E `[ADM-04]` with EML-05 in the outbox quoting the code.
* **ADM-05 / ADM-03** — integration `@Tag("ADM-05")`: lists include orders older than 6 months, newest first, stable ordering, pagination; E2E `[ADM-05]` > 1 page.
* **ADM-06 / EML-09** — unit `@Tag("ADM-06")`: any → any on APPROVED only; event per change; `CONSEGNATO` queues EML-09 once; integration: no event when the same state is set again.
* **ADM-07** — integration `@Tag("ADM-07")`: internal note edit never changes `customer_notes`; imported legacy note lands per the ADR rule; E2E `[ADM-07]`.
* **ADM-09** — integration `@Tag("ADM-09")`: inclusive range on `submitted_at` in Europe/Rome days (document the UTC-boundary change), legacy-imported orders included, "data spedizione" = `approved_at`.
* **EML-01 / EML-06** — integration `@Tag("EML-06")`: office notice queued once at verification, not on repeated link use; `@Tag("EML-01")`: token single-use, expiring, `GET` never confirms.
* **MIG-01** — integration `@Tag("MIG-01")` on a synthetic MySQL fixture built from the §1.7 DDL (Testcontainers `mysql:8.0.35`, `utf8mb4_unicode_ci`): run twice → identical `legacy_key_map` and zero changes; dry-run rolls back and still writes the report; drift query output recorded; unknown label → blocking.
* **MIG-02** — unit `@Tag("MIG-02")` per rule of §5.5: status table (NEW + `update_user` NULL → `PENDING_VERIFICATION`, NEW + `ROLE_ADMIN` → `DECLINED`, PENDING → `PENDING_APPROVAL`, REGISTERED → `ACTIVE`; orders NEW/DECLINED → `DECLINED` with `temp_code`), amount `COALESCE` + half-up (180.3 double → 180.30; both differing → DQ-13), units → lines regrouping (consecutive equal runs; adjacent identical lines merge; totals equal), tracking events (PRESA at `confirmation_ts`, CONSEGNATO at `delivery_ts`, estimated for the rest), notes (blank/"Nessuna nota presente" → none), codes trimmed with raw kept and `""` blocking, e-mail collisions blocking, `spedizioni.email` never a recipient e-mail; integration: the §5.7 worked example end to end; reconciliation report equals source counts per state × tracking, `COUNT(pallet)` = Σ quantity, Σ peso, Σ amounts; `users`/`user_session` never read.
* **MIG-03** — rehearsal record on staging with the anonymised copy: nginx block list of §5.6 answers 403/410 on `/mono/cms/order/update`, `/mono/utils/**`, `GET /mono/email-confirm`; quality: a CI check that no SQL under `legacy/` or dumps is tracked.
* **NFR-02 / NFR-03** — quality `@Tag("NFR-03")`: importer writes flagged-record CSVs outside the repository; logs of the importer contain ids only; `@Tag("NFR-02")`: restore script drops `users`/`user_session` in non-production profiles.

## 8. Notes for the planner

* **Ownership (10 §2):** `migration` — §1.7 DDL fixture, §1.8 drift script, §5.5 mappers, §5.6 importer and report, DQ-25…37 detectors; `tracking` — enumeration seeds (§2.2), compatibility endpoint rules (§3.7); `customers` — state machine (§3.1 rewritten per fix 1–3), e-mail normalisation; `orders` — lines/totals (§4.3), codes (§3.3), notes (§3.6), approval/decline (§3.2); `backoffice` — lists without the 6-month window, detail with regrouped lines; `notifications` — subjects of §2.2 and the e-mail triggers of §3.1–3.2; `reports` — §3.4 label semantics; `platform` — Flyway, `numeric(10,2)`, `timestamptz`.
* **Phase 1 foundations this chapter needs:** schema of 08 §2.3 plus 12b §5.4: `customer.legacy_payload`, `amount_raw` wide enough for both doubles, `code`/`temp_code` lengths vs legacy 255/40, `order_line` keeps legacy unit ids in payload, `accessory_service.is_none`, `legacy_key_map`/`import_run`/`import_issue`; seeds for the twelve enumeration rows; a Testcontainers MySQL 8.0.35 profile for the importer; money and decimal-volume helpers shared by orders and migration; the decisions-file schema (merge e-mails, rename/trim codes, declined-with-orders).
* **Risks:** schema drift and the unknown external `application-prod.yml` (§1.8); DQ-01 duplicates blocking the final run (prepare the merge list early from a schema-only or anonymised dump, Q-02); DQ-25 classification rests on `update_user` (inferred); regrouping may merge adjacent identical lines (reconcile on units, not lines); float amounts and > 2 decimals; codes with tabs/spaces and PAD SPACE differences between the RDS-era and Docker collations; O6 edits in production by an unknown CMS build (CU-21); `volume` rounded to whole m³ makes legacy totals unreconcilable (compare units only); time-zone verification; the dump holds hashes and live tokens.
* **For ADR 0001 / Gate 0 summary:** MySQL dump restore in isolation + JDBC read-only import as the migration path (fix 14, 17); money type and VAT flag (Q-05); explicit `DECLINED` (Q-09); note split and import rule (Q-26, 08 §3.7 vs 12b §5.5); code policy and whether codes come from a carrier (Q-06); keep 200 "Non gestito" (Q-03/Q-38); amount fallback `COALESCE(importo, importo_assicurato)` (DQ-13); whether to port post-approval corrections ADMIN-only (O6); DQ-32 orders approved without a code — does the owner want codes assigned; DQ-37 declined customers with orders; deployment dates for DQ-07; one dated order for the UTC check; PR-02 and `TariffarioConst` to the pricing owner at Gate 2.
