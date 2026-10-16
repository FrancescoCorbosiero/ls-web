# 12b · Legacy backend: domain model, persistence, state machines and data migration

> **Status.** Written from the legacy backend source (`ls`, branch `migration`, commit
> `ca1b0d0`) on 2026-10-16. Its corrections to chapters 01–11 have been applied there.
> Where this chapter and an older chapter still disagree about how the legacy system
> works, **this chapter wins**: it reads the server code, while 02–08 inferred the server
> from its clients. Sections titled "Reconciliation" or "Corrections to the brief" record
> what changed.

Lens: domain model, persistence, state machines and data migration of the legacy backend
(Spring Boot, package `it.logisticasanchez.mono`). This note corrects and completes brief
chapter 08 (legacy model inferred from the frontends) and answers part of chapter 11.

**Sources and references**

* `ls@migration` = branch `migration`, commit `ca1b0d0` (2025-11-16); the extracted copy
  that was read is byte-identical for `src/` and `pom.xml`. Between
  `dev_API_v2` (`91cf025`, 2025-01-07) and `migration`, only `LinkUtil` (http → https, new
  host names), the YAML files and Docker/nginx files changed
  (`git diff --stat origin/dev_API_v2 origin/migration -- src/`): **the domain code, entities and
  states have been frozen since 2025-01-07**.
* History: `ls@<sha>` (date) from the same repository. Clients as in the brief:
  `ls-cms@dev` = `f0c881c`, `ls-order@dev` = `dbe7292`.
* Citation shorthand: `M/` = `src/main/java/it/logisticasanchez/mono/`, `R/` =
  `src/main/resources/`. Every citation is `ls@migration:<path>:<line>` unless another ref is
  given.
* "(inferred)" = deduced from the code or from framework behaviour, not observed in the data.
  Anything about the production data must be checked on the dump (§5.9).
* Log evidence: `ls@migration:logs/archived/*.log` are logs of the developer's machine (local
  Tomcat and test runs, June 2024 to January 2025). Only DDL text, enum values and masked
  shapes/counts were extracted. **No personal data is reproduced here**; no secret value from
  the YAML or `.env` files is reproduced either.

## 0. What changes for the brief (summary)

1. **Declines neither delete nor flag DECLINED.** Since 2024-07-22 (`ls@5debd84`, "declined
   state ban") a declined customer or order is reset to `stato = 'NEW'` and stays in the
   database (§3.1, §3.2). Rows declined by older builds (2024-02-29 to 2024-07-22) may still
   hold `DECLINED`.
2. **Customer states mean something else.** `NEW` = registered but e-mail not confirmed (or
   declined); `PENDING` = e-mail confirmed, waiting for staff; `REGISTERED` = approved.
   Staff **cannot** approve an unconfirmed customer, and unconfirmed registrations never
   appear in the CMS lists (CMS-20 and DQ-24 cannot happen) (§3.1).
3. **Order lines are stored one row per physical unit** (`quantity` is expanded into n rows of
   table `pallet`), plus one aggregate row per order (`dettaglio_colli`: unit counts per type,
   total weight, total volume **rounded to whole m³**, a `colli` column that the portal never
   fills) (§1.3, §4.3).
4. **Enumerations are stored as their Italian labels** (`varchar`), and the JSON returns labels:
   `tracking: "Presa in carico"`, `servizioAccessorio: "Nessun servizio accessorio"`. The six
   accessory services are known; id 0 means "none" (§2).
5. **Amounts are `DOUBLE` columns, not free text**: `importo` (the amount typed at approval
   since 2024-07-16), `importo_assicurato` (the amount typed before, still accepted),
   `importo_contrassegno` (since 2024-07-18 a copy of `importo_assicurato`) (§3.5).
6. **Integrity is better than feared**: `spedizioni.mittente_id` is a NOT NULL foreign key (no
   orphan orders) and `spedizioni.codice` is UNIQUE (no duplicate codes; case-insensitively so
   under MySQL's default collations, inferred). The real identity problem is **duplicate
   customer e-mails**, which lock the customer out of the legacy system (every e-mail lookup
   fails) (§6).
7. **Dates exist**: `create_tms`/`update_tms` on every business table, plus `confirmation_ts`,
   `shipping_ts`, `delivery_ts` on orders, stored as UTC `datetime(6)` (inferred, §1.1) (§1.6,
   §3.4).
8. **The order lists return only the last 6 months** (by creation date). The API-export
   fallback of chapter 08 §4.3 would silently miss older orders; the MySQL dump `lsdb` is the
   only complete source (§5.6).
9. **The CMS note is the customer's order note** (one column, overwritten by staff) (§3.6).
10. **`ddl-auto: update` is (very probably) active in production**, so the real schema may
    carry columns and constraints from older entity versions (§1.8).
11. **The CMS source used by the brief (`ls-cms@dev`) cannot approve orders against this
    backend** (it sends `importoAssicurato`, the backend requires `importo` since 2024-07-16):
    production must run a later CMS build whose source is not in the repositories (inferred)
    (§3.5).

## 1. Database schema (MySQL `lsdb`)

### 1.1 Platform and schema management

| Item | Value | Source |
|---|---|---|
| Engine | MySQL 8. Docker deployment: `mysql:8.0.35`. Before October 2025: Amazon RDS MySQL (eu-north-1), moved with `mysqldump lsdb` + import into the Docker MySQL | ls@migration:docker-compose.yml:6; ls@migration:README.md:241-266; ls@migration:R/application-dev.yml:17 |
| Database name | `lsdb` | ls@migration:R/application.yml:17; ls@migration:docker-compose.yml:11; ls@migration:scripts/backup.sh:40-50 |
| ORM | Spring Boot 3.2.2 (unchanged since 2024-02-03, `ls@1c65922`) → Hibernate ORM 6.4.1.Final, `org.hibernate.dialect.MySQL8Dialect` | ls@migration:pom.xml:5-9; ls@migration:R/application.yml:35; ls@migration:logs/archived/spring-boot-logger-2024-07-24.0.log:15 |
| Naming | Spring Boot default physical naming: camelCase → snake_case, lower-case (`SOGGETTI` → `soggetti`, `codiceVerifica` → `codice_verifica`) | confirmed by the logged DDL, ls@migration:logs/archived/spring-boot-logger-2024-07-28.0.log:739-2575 |
| Schema source | Hibernate only. No Flyway/Liquibase, no DDL script in the repository | repository tree |
| `ddl-auto` | `update` in `application.yml` (also `-dev`, `.local`); `create-drop` in the test profiles | ls@migration:R/application.yml:32; ls@migration:src/test/resources/application-test.yml:32 |
| `ddl-auto` in production | Production runs profile `prod` (docker-compose.yml:54, 63; Dockerfile:62). `validate` appears only in `R/application.prod.yml:22`, whose name has a **dot**: Spring Boot loads `application-prod.yml`, so this file is never applied (inferred). Compose mounts an external `./config/application-prod.yml` into `/app/config/` (docker-compose.yml:99), which Spring Boot does load; its content is not in the repository. **Effective value: `update`, unless that external file overrides it (inferred).** | as cited |
| What `update` does | At each start: creates missing tables, adds missing columns, unique keys and foreign keys. Never drops, renames or changes the type or nullability of an existing column → schema drift (§1.8). If the DB user has DML-only rights (the README recommends it, README.md:336-340), the ALTERs fail and are only logged (inferred). | Hibernate behaviour |
| Charset / collation | Not set by Hibernate → database default. Docker server: `utf8mb4` / `utf8mb4_unicode_ci` (docker-compose.yml:21-24). Tables restored from the RDS dump keep the collation written in that dump (MySQL 8 default `utf8mb4_0900_ai_ci`) (inferred). Both are case- and accent-insensitive: **every lookup by `email` or `codice`, and every UNIQUE key, ignores case** (inferred). `utf8mb4_unicode_ci` also ignores trailing spaces (PAD SPACE); `utf8mb4_0900_ai_ci` does not. | as cited |
| Timestamps | `Instant` → `datetime(6)`, bound by Hibernate's `TimestampUtcAsJdbcTimestampJdbcType` with a UTC `Calendar` → **stored as UTC wall-clock time** (binder verified on the Hibernate 6.4.1 jar in a scratch probe; that Connector/J honours the calendar is (inferred)). `LocalDateTime` (only `user_session`) → JVM local time (Docker TZ `Europe/Rome`, Dockerfile:34-36). | ls@migration:M/persistence/model/SpedizioneEntity.java:67-72 |
| Backups | `mysqldump --single-transaction --routines --triggers --events --add-drop-database --databases lsdb` → `backup/mysql/lsdb_<YYYYmmdd_HHMMSS>.sql.gz` | ls@migration:scripts/backup.sh:40-56 |
| Seed data | None in production. `init.sql` (tests) inserts two `users` rows, an `ADMIN` and a `CUSTOMER`, bcrypt `$2a$12$` hashes, policy `none` (usernames and hashes not reproduced). It is referenced only by the H2 profile through `spring.datasource.data`, a property Spring Boot 3 no longer reads (inferred). | ls@migration:src/test/resources/init.sql:1-7; ls@migration:src/test/resources/application-h2.yml:21 |

### 1.2 Entity-relationship overview

```
soggetti (customer)  1 ───< n  spedizioni (order + embedded recipient)
  id PK                          id PK
  email  (NOT unique)            mittente_id  FK → soggetti.id  NOT NULL
  stato  NEW|PENDING|            codice       UNIQUE  ("TEMP…" until approval)
         REGISTERED|DECLINED     stato, tracking (labels), amounts, 3 timestamps
                                     │ 1
                                     │ 1   (UNIQUE spedizione_id)
                                 dettaglio_colli (per-order aggregate)
                                   id PK from table dettaglio_colli_seq
                                     │ 1
                                     └───< n  pallet  (ONE ROW PER PHYSICAL UNIT)

users (staff logins)          user_session (refresh token; user_id without FK)
```

There is no table for recipients (embedded in `spedizioni`), tracking history, notes,
accessory services or tracking states (Java enums), price lists, leads or e-mails.

### 1.3 Tables

All strings are `varchar(255)` (no `length` anywhere); length limits exist only in Bean
Validation on the request DTOs. `Double` → `float(53)`, which MySQL stores as `DOUBLE`.
Column order below is Hibernate's generated order (from the logged DDL, §1.7).

#### `soggetti` — customers (`SoggettoEntity`)

| Column | Type | Null | Keys | Java field | Written when | Notes |
|---|---|---|---|---|---|---|
| `create_tms` | datetime(6) | no | | `createTms` Instant | insert (listener) | registration time, UTC |
| `id` | bigint auto_increment | no | PK | `id` IDENTITY | insert | shown as "ID" in the CMS |
| `update_tms` | datetime(6) | yes | | `updateTms` | every update (listener) | last change only |
| `cap` | varchar(255) | no | | `cap` | registration | 1–9 chars, no pattern |
| `citta` | varchar(255) | no | | `citta` | registration | 1–50 chars |
| `codice_verifica` | varchar(255) | yes | UK1g4y… (3rd col) | `codiceVerifica` | insert | e-mail confirmation code, a random UUID; never rotated or cleared |
| `create_user` | varchar(255) | no | | `createUser` | insert | `CUSTOMER` (anonymous caller) or `ROLE_ADMIN` (§1.6) |
| `email` | varchar(255) | no | UK1g4y… (2nd), index (2nd) | `email` | registration | as typed (no trim, no lower-case); 1–50 chars; **not unique by itself** |
| `indirizzo` | varchar(255) | no | | `indirizzo` | registration | 1–50 chars |
| `provincia` | varchar(255) | no | | `provincia` | registration | 1–50 chars, free text |
| `ragione_sociale` | varchar(255) | no | UK1g4y… (1st), index (1st) | `ragioneSociale` | registration | 1–50 chars |
| `stato` | varchar(255) | no | | `stato` ConfirmationState | insert, link, approve, decline | label: `NEW`, `PENDING`, `REGISTERED`, `DECLINED` (§2, §3.1) |
| `telefono` | varchar(255) | no | | `telefono` | registration | required; pattern `\d{10}` until 2025-01-07, then a loose pattern, ≤ 20 chars (§6) |
| `update_user` | varchar(255) | yes | | `updateUser` | every update | `CUSTOMER` (e-mail link) or `ROLE_ADMIN` (approve, decline) |

Sources: ls@migration:M/persistence/model/SoggettoEntity.java:15-17 (table, unique key, index),
25-27 (id), 29-42 (data), 44-47 (`stato`, `codiceVerifica`), 49-57 (technical), 60-61
(relation); limits ls@migration:M/business/model/constant/SoggettoConst.java:5-25 applied by
ls@migration:M/api/req/crud/CreateSoggettoReq.java:21-49; phone pattern before 2025-01-07:
ls@91cf025 (diff of `SoggettoConst.java`).

The unique key `(ragione_sociale, email, codice_verifica)` contains a random UUID, so it never
prevents a duplicate (inferred).

#### `spedizioni` — orders (`SpedizioneEntity`)

| Column | Type | Null | Keys | Java field | Notes |
|---|---|---|---|---|---|
| `importo` | DOUBLE | yes | | `importo` | amount typed at approval; column added 2024-07-16 (`ls@d7732eb`) (§3.5) |
| `importo_assicurato` | DOUBLE | yes | | `importoAssicurato` | amount typed at approval by the CMS of the brief; required by approvals 2024-03-26 → 2024-07-15 |
| `importo_contrassegno` | DOUBLE | yes | | `importoContrassegno` | since 2024-07-18 overwritten with `importo_assicurato` at approval |
| `confirmation_ts` | datetime(6) | yes | | `confirmationTs` | approval time (§3.4) |
| `create_tms` | datetime(6) | no | | | submission time, UTC |
| `delivery_ts` | datetime(6) | yes | | `deliveryTs` | last time the tracking was set to `CONSEGNATO` |
| `id` | bigint auto_increment | no | PK | | the `id` of approve/decline |
| `mittente_id` | bigint | no | FK `FK3d08negpel3jqjeoivre9kk15` → `soggetti.id` | `mittente` | the customer, resolved from `clienteEmail` at submission |
| `shipping_ts` | datetime(6) | yes | | `shippingTs` | §3.4 |
| `update_tms` | datetime(6) | yes | | | last change of any kind (note edits included) |
| `cap` | varchar(255) | no | | | recipient, 1–9 chars |
| `citta` | varchar(255) | no | | | recipient, 1–50 |
| `codice` | varchar(255) | no | UK `UKe8bu1k4tsjy4mmer76n9tslnh` | `codice` | `TEMP`+UUID until approval, then the staff-typed code (§3.3) |
| `codice_documento_trasporto` | varchar(255) | yes | | | DDT; `""` is rejected (`@Size(min=1)`) |
| `create_user` | varchar(255) | no | | | §1.6 |
| `email` | varchar(255) | no | | `email` "Email mittente" | copy of `destinatario.email` as sent by the portal, which is the **customer's** e-mail (OP-14); not used for any lookup |
| `indirizzo` | varchar(255) | no | | | recipient, 1–50 |
| `note` | varchar(255) | yes | | `note` | the customer's note (portal sends `""` when empty) **and** the CMS "notes" (§3.6) |
| `provincia` | varchar(255) | no | | | recipient, 1–50 |
| `ragione_sociale` | varchar(255) | no | | | recipient name, 1–50 |
| `servizio_accessorio` | varchar(255) | no | | ServizioAccessorioEnum | label (§2) |
| `stato` | varchar(255) | no | | ConfirmationState | label (§3.2) |
| `telefono` | varchar(255) | yes | | | recipient phone (optional since 2024-05-25, `ls@bb43bbc`) |
| `tracking` | varchar(255) | no | | TrackingState | label (§2) |
| `update_user` | varchar(255) | yes | | | §1.6 |

Sources: ls@migration:M/persistence/model/SpedizioneEntity.java:17-18 (table, unique),
26-28, 30-31 (`email` "Email mittente"), 33-45 (recipient), 47-61 (shipment data), 63-72
(states and timestamps), 74-82 (technical), 85-86 and 88-90 (relations); `email` comes from
`destinatario.email`: ls@migration:M/business/factory/SpedizioneFactory.java:71.

#### `dettaglio_colli` — per-order aggregate (`DettaglioColliEntity`)

| Column | Type | Null | Keys | Notes |
|---|---|---|---|---|
| `colli` | int | yes | | "Numero Colli": taken from `dettaglioColli.colli`, which the portal never sends → NULL for portal orders |
| `epal` | int | yes | | number of units of type `EPAL` |
| `pallet` | int | yes | | number of units of type `PALLET` (EPAL units not included) |
| `peso` | DOUBLE | no | | Σ of the units' `peso` = Σ (peso × quantity) of the submitted lines, kg |
| `volume` | DOUBLE | no | | Σ of unit volumes h×w×l/1 000 000 (m³), **rounded to the nearest integer** (`Math.round`) |
| `create_tms` | datetime(6) | no | | |
| `id` | bigint | no | PK | `GenerationType.AUTO` → values from table `dettaglio_colli_seq`; not contiguous, not equal to the order id (inferred: pooled optimizer, increment 50) |
| `spedizione_id` | bigint | no | UK `UK_7pcdnw679py8uspp5v1xxbsgm`; FK `FKrs7rq9rve42avkv88cscb49gl` → `spedizioni.id` | one aggregate per order |
| `update_tms`, `create_user`, `update_user` | | | | audit |

Sources: ls@migration:M/persistence/model/DettaglioColliEntity.java:14, 22-24, 26-35, 48-53;
computation ls@migration:M/business/service/OrderService.java:80-104;
ls@migration:M/business/util/MathUtil.java:5-7.

#### `dettaglio_colli_seq` — id generator

`next_val bigint` (nullable), one row, initialised with `insert into dettaglio_colli_seq values ( 1 )`
(ls@migration:logs/archived/spring-boot-logger-2024-07-28.0.log:1006, 1240). Not business data.

#### `pallet` — one row per physical unit (`PalletEntity`)

| Column | Type | Null | Keys | Notes |
|---|---|---|---|---|
| `altezza` | DOUBLE | no | | cm (integers when sent by the portal) |
| `larghezza` | DOUBLE | no | | cm |
| `lunghezza` | DOUBLE | no | | cm |
| `peso` | DOUBLE | no | | kg **per unit** |
| `create_tms` | datetime(6) | no | | |
| `dettaglio_colli_id` | bigint | no | FK `FKq83g5iyh710kf1lp8tays8r3e` → `dettaglio_colli.id` | |
| `id` | bigint auto_increment | no | PK | consecutive within an order, in submission order (inferred) |
| `update_tms`, `create_user`, `update_user` | | | | audit |
| `type` | varchar(255) | no | | PalletTypeEnum label `PALLET` or `EPAL` |

No quantity, line number, description or stackability. Sources:
ls@migration:M/persistence/model/PalletEntity.java:14, 22-35, 48-50; expansion
ls@migration:M/business/service/OrderService.java:71-79, 106-108.

#### `users` — staff logins (`UserEntity`)

| Column | Type | Null | Keys | Notes |
|---|---|---|---|---|
| `id` | bigint auto_increment | no | PK | |
| `policy` | varchar(255) | yes | | free string (`none` in tests); returned as the `operations` list at login |
| `psw` | varchar(255) | yes | | bcrypt hash (`BCryptPasswordEncoder`) |
| `role` | varchar(255) | yes | | UserRoleEnum label `ADMIN` or `CUSTOMER` |
| `username` | varchar(255) | no | UNIQUE (`unique = true`) | was column `user` until 2024-08-25 (`ls@3bda6ad`) |

No audit columns. Sources: ls@migration:M/persistence/model/security/UserEntity.java:11-25;
ls@migration:M/configuration/security/DatabaseAuthenticationConfig.java:31-49.

#### `user_session` — refresh tokens (`UserSessionEntity`)

| Column | Type | Null | Keys | Notes |
|---|---|---|---|---|
| `session_expiration_date` | datetime(6) | yes | | `LocalDateTime`, JVM local time |
| `user_id` | bigint | yes | (no FK) | one row per user, reused at every login |
| `session_id` | varchar(255) | no | PK | a UUID; it **is** the refresh token |

Sources: ls@migration:M/persistence/model/security/UserSessionEntity.java:13-21;
ls@migration:M/business/service/AuthenticationService.java:35-77. A dump therefore contains
live refresh tokens and password hashes: treat both tables as secrets (§5.6).

### 1.4 Keys, constraints and indexes

| Table | Object | Columns | Kind | Source |
|---|---|---|---|---|
| all | PK | `id` (`session_id` for `user_session`) | primary key | entities |
| `soggetti` | `UK1g4ywjec6rn7c6ppoof4mbp37` | `ragione_sociale, email, codice_verifica` | composite UNIQUE (useless, see §1.3) | log 2024-07-28.0.log:3267; SoggettoEntity.java:16 |
| `soggetti` | `ragione_sociale_email` | `ragione_sociale, email` | composite non-unique index | log :3041; SoggettoEntity.java:17 |
| `spedizioni` | `UKe8bu1k4tsjy4mmer76n9tslnh` | `codice` | UNIQUE | log :3492; SpedizioneEntity.java:18 |
| `spedizioni` | `FK3d08negpel3jqjeoivre9kk15` | `mittente_id` → `soggetti(id)` | FK (+ InnoDB index) | log :4410; SpedizioneEntity.java:88-90 |
| `dettaglio_colli` | `UK_7pcdnw679py8uspp5v1xxbsgm` | `spedizione_id` | UNIQUE (from `@OneToOne`) | log :2817 |
| `dettaglio_colli` | `FKrs7rq9rve42avkv88cscb49gl` | `spedizione_id` → `spedizioni(id)` | FK | log :3950-3952 |
| `pallet` | `FKq83g5iyh710kf1lp8tays8r3e` | `dettaglio_colli_id` → `dettaglio_colli(id)` | FK (+ index) | log :4180-4182 |
| `users` | `UK_…` (hash for `username`) | `username` | UNIQUE | UserEntity.java:18; the log has `UK_dmw0k15utpdl196fcup0h4xao unique (user)` (:3717) for the old column |

(Log = ls@migration:logs/archived/spring-boot-logger-2024-07-28.0.log, a test run with the
MySQL dialect on Hibernate 6.4.1, the production version.) **No composite primary key exists.**
`soggetti` had an `@EmbeddedId CustomerKey` for three days (`ls@5c76ad2`, 2024-02-10 →
`ls@b2941cc`, 2024-02-13). No index on `soggetti.email` alone, nor on `spedizioni.stato` or
`create_tms` (full scans; harmless at this size).

### 1.5 Relations and cascade rules

| Relation | JPA mapping | JPA cascade | DB foreign key | Source |
|---|---|---|---|---|
| customer → orders | `soggetti 1–n spedizioni` (`mappedBy = "mittente"`, LAZY) | `ALL` + `orphanRemoval` | `spedizioni.mittente_id` NOT NULL, no ON DELETE | SoggettoEntity.java:60-61; SpedizioneEntity.java:88-90 |
| order → aggregate | `spedizioni 1–1 dettaglio_colli` (owner: `dettaglio_colli.spedizione_id`, LAZY) | `ALL` from the order side, no orphanRemoval | `dettaglio_colli.spedizione_id` NOT NULL UNIQUE, no ON DELETE | SpedizioneEntity.java:85-86; DettaglioColliEntity.java:48-50 |
| aggregate → units | `dettaglio_colli 1–n pallet` | `ALL` + `orphanRemoval` | `pallet.dettaglio_colli_id` NOT NULL, no ON DELETE | DettaglioColliEntity.java:52-53; PalletEntity.java:48-50 |
| session → user | plain `user_id` column | none | none | UserSessionEntity.java:19 |

The database restricts deletes (no `ON DELETE CASCADE` in the generated DDL); only JPA cascades.
The admin tools delete bottom-up explicitly: units, aggregate, order, then customer
(ls@migration:M/business/service/AdminToolsService.java:45-60, 83-109, 111-135). Deleted rows
leave no trace.

### 1.6 Technical (audit) columns

* Interface `TechnicalData` (`createTms`, `createUser`, `updateTms`, `updateUser`) is
  implemented by the four business entities (ls@migration:M/persistence/model/TechnicalData.java:5-10).
* JPA listeners: `@PrePersist` sets `create_tms = now()` if null and **always** sets
  `create_user`; `@PreUpdate` sets `update_tms = now()` and `update_user`
  (ls@migration:M/persistence/audit/SoggettoAuditListener.java:13-25; the three other listeners
  are identical).
* The user value is the first authority of the authenticated principal, i.e. `ROLE_ADMIN`
  for staff tokens, else the label `CUSTOMER` for anonymous calls (portal, e-mail link)
  (ls@migration:M/configuration/security/SecurityContextUtils.java:26-36;
  ls@migration:M/configuration/security/UserPrincipalDetails.java:22-24). It identifies a
  role, never a person.
* An order submission inserts the row as `NEW` and updates it to `PENDING` in the same
  transaction, so `update_tms ≈ create_tms` and `update_user = 'CUSTOMER'` until staff act
  (inferred from ls@migration:M/business/service/crud/SpedizioneService.java:132-143).
  Every later change, including a note edit, overwrites `update_tms`.
* No history table, no soft delete, no version column.

### 1.7 Reference DDL (as Hibernate 6.4.1 generates it for MySQL)

Verbatim from ls@migration:logs/archived/spring-boot-logger-2024-07-28.0.log:739-4415, with
`users.username` per the current mapping (the log predates the 2024-08-25 rename):

```sql
create table soggetti (create_tms datetime(6) not null, id bigint not null auto_increment,
  update_tms datetime(6), cap varchar(255) not null, citta varchar(255) not null,
  codice_verifica varchar(255), create_user varchar(255) not null, email varchar(255) not null,
  indirizzo varchar(255) not null, provincia varchar(255) not null,
  ragione_sociale varchar(255) not null, stato varchar(255) not null,
  telefono varchar(255) not null, update_user varchar(255), primary key (id)) engine=InnoDB;
create table spedizioni (importo float(53), importo_assicurato float(53),
  importo_contrassegno float(53), confirmation_ts datetime(6), create_tms datetime(6) not null,
  delivery_ts datetime(6), id bigint not null auto_increment, mittente_id bigint not null,
  shipping_ts datetime(6), update_tms datetime(6), cap varchar(255) not null,
  citta varchar(255) not null, codice varchar(255) not null,
  codice_documento_trasporto varchar(255), create_user varchar(255) not null,
  email varchar(255) not null, indirizzo varchar(255) not null, note varchar(255),
  provincia varchar(255) not null, ragione_sociale varchar(255) not null,
  servizio_accessorio varchar(255) not null, stato varchar(255) not null, telefono varchar(255),
  tracking varchar(255) not null, update_user varchar(255), primary key (id)) engine=InnoDB;
create table dettaglio_colli (colli integer, epal integer, pallet integer,
  peso float(53) not null, volume float(53) not null, create_tms datetime(6) not null,
  id bigint not null, spedizione_id bigint not null, update_tms datetime(6),
  create_user varchar(255) not null, update_user varchar(255), primary key (id)) engine=InnoDB;
create table dettaglio_colli_seq (next_val bigint) engine=InnoDB;
insert into dettaglio_colli_seq values ( 1 );
create table pallet (altezza float(53) not null, larghezza float(53) not null,
  lunghezza float(53) not null, peso float(53) not null, create_tms datetime(6) not null,
  dettaglio_colli_id bigint not null, id bigint not null auto_increment, update_tms datetime(6),
  create_user varchar(255) not null, type varchar(255) not null, update_user varchar(255),
  primary key (id)) engine=InnoDB;
create table users (id bigint not null auto_increment, policy varchar(255), psw varchar(255),
  role varchar(255), username varchar(255) not null, primary key (id)) engine=InnoDB;
create table user_session (session_expiration_date datetime(6), user_id bigint,
  session_id varchar(255) not null, primary key (session_id)) engine=InnoDB;
create index ragione_sociale_email on soggetti (ragione_sociale, email);
alter table dettaglio_colli add constraint UK_7pcdnw679py8uspp5v1xxbsgm unique (spedizione_id);
alter table soggetti add constraint UK1g4ywjec6rn7c6ppoof4mbp37 unique (ragione_sociale, email, codice_verifica);
alter table spedizioni add constraint UKe8bu1k4tsjy4mmer76n9tslnh unique (codice);
alter table users add constraint UK_<hash> unique (username);
alter table dettaglio_colli add constraint FKrs7rq9rve42avkv88cscb49gl foreign key (spedizione_id) references spedizioni (id);
alter table pallet add constraint FKq83g5iyh710kf1lp8tays8r3e foreign key (dettaglio_colli_id) references dettaglio_colli (id);
alter table spedizioni add constraint FK3d08negpel3jqjeoivre9kk15 foreign key (mittente_id) references soggetti (id);
```

### 1.8 Schema drift to expect in the dump

`update` never removes or relaxes. Production data were created on RDS and restored into Docker
from a dump (README.md:253-266), so leftovers of the RDS life travel with them (inferred). Which
ones exist depends on when each table was first created, which is unknown:

| Possible leftover | Present if the table predates | Effect | History |
|---|---|---|---|
| `users.user` NOT NULL + `UK_dmw0k15utpdl196fcup0h4xao`; `username` added later (filled with `''` for old rows) | 2024-08-25 | staff rows inserted by hand; `username` probably fixed manually (inferred) | ls@3bda6ad |
| `spedizioni.telefono` NOT NULL | 2024-05-30 | orders without recipient phone would fail with 500 | ls@abc32fb |
| `spedizioni.codice_verifica` + UNIQUE `(codice, codice_verifica)` | 2024-03-26 | none (extra column) | ls@741195e → ls@ebcc438 |
| `spedizioni.tracking` nullable | 2024-04-22 | none for new rows | ls@540303d |
| `spedizioni.importo_contrassegno`, `importo_assicurato` NOT NULL | 2024-03-08 | approvals without both amounts would fail | ls@6664aa3 |
| February 2024 objects: tables `colli`/`collo`, columns `spedizioni.destinatario_id`, `spedizioni.dettaglio_colli_id`, `pallet.colli_id`/`collo_id`/`spedizione_id`, `soggetti.phone`, UNIQUE `(ragione_sociale)` or `(ragione_sociale, email)` on `soggetti`; `pallet` rows older than 2024-03-08 with `type = ''` (column added NOT NULL by `ls@6664aa3`) | 2024-02-24 (2024-03-08 for `pallet.type`) | a leftover UNIQUE on `ragione_sociale` would block two customers with the same name; `type = ''` makes the order unreadable (500) | ls@2ce6b43 … ls@a38063b, ls@6664aa3 |

Pre-import checks on the restored dump (read-only):

```sql
SELECT table_name, table_collation, table_rows FROM information_schema.tables WHERE table_schema = 'lsdb';
SELECT table_name, column_name, column_type, is_nullable, collation_name
  FROM information_schema.columns WHERE table_schema = 'lsdb' ORDER BY table_name, ordinal_position;
SELECT table_name, constraint_name, constraint_type
  FROM information_schema.table_constraints WHERE table_schema = 'lsdb';
SELECT index_name, table_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index)
  FROM information_schema.statistics WHERE table_schema = 'lsdb' GROUP BY 1, 2, 3;
SELECT @@global.time_zone, @@session.time_zone, @@collation_server, @@version;
```

Any column or constraint not listed in §1.7 goes into `legacy-schema.md` (chapter 08 §4.2) with
its row values profiled before the importer is finalised.

## 2. Enumerations

### 2.1 How enumerations are stored and serialised

* **Database**: an auto-applied JPA converter stores `getLabel()` and reads back with
  `fromString` (label, `equalsIgnoreCase`; unknown label → HTTP 500)
  (ls@migration:M/persistence/model/converter/TrackingStateConverter.java:7-18; same pattern in
  `ConfirmationStateConverter.java:7-18`, `ServizioAccessorioEnumConverter.java:7-18`,
  `PalletTypeEnumConverter.java:8-19`, `UserRoleEnumConverter.java:8-19`). **No ordinal is
  stored anywhere.**
* **JSON out**: `@JsonValue getLabel()` → the label
  (ls@migration:M/business/model/enumeration/state/TrackingState.java:24-27).
* **JSON in**: requests carry **ids**, which are ordinals, resolved by `fromId(int)`; an
  unknown id → 500 (TrackingState.java:39-46; ServizioAccessorioEnum.java:40-47;
  PalletTypeEnum.java:36-43; ConfirmationState.java:37-44).
* **Enumeration endpoints**: `{ "id": ordinal, "name": constant, "label": label }`
  (ls@migration:M/api/controller/EnumController.java:25-64).

### 2.2 Values

**TrackingState** (`spedizioni.tracking`) — ls@migration:M/business/model/enumeration/state/TrackingState.java:15-20

| id | name | label (stored, returned) |
|---|---|---|
| 0 | `NON_GESTITO` | `Non gestito` |
| 1 | `PRESA` | `Presa in carico` |
| 2 | `TRANSITO` | `Transito` |
| 3 | `TERMINAL_DEST` | `Terminal destinatario` |
| 4 | `ASSEGNATO` | `Assegnato` |
| 5 | `CONSEGNATO` | `Consegnato` |

Identical to the CMS copy in chapter 02 §9.1: the seed is confirmed.

**ConfirmationState** (`soggetti.stato`, `spedizioni.stato`) — ConfirmationState.java:15-18

| id | name = label | Meaning: customer | Meaning: order |
|---|---|---|---|
| 0 | `NEW` | registered, e-mail not confirmed; or declined after 2024-07-22; also "no record" in EP-02 | transient during submission; declined after 2024-07-22 |
| 1 | `PENDING` | e-mail confirmed, waiting for staff | submitted, waiting for staff |
| 2 | `REGISTERED` | approved | approved |
| 3 | `DECLINED` | declined by builds 2024-02-29 → 2024-07-22 | same |

**PalletTypeEnum** (`pallet.type`) — PalletTypeEnum.java:16-17: id 0 `PALLET`/`PALLET`
(portal "Pallet Generico/Colli"), id 1 `EPAL`/`EPAL` (portal "Epal").

**ServizioAccessorioEnum** (`spedizioni.servizio_accessorio`) — ServizioAccessorioEnum.java:16-21 (**Q-25**)

| id | name | label (stored, returned) |
|---|---|---|
| 0 | `NESSUN_SERVIZIO` | `Nessun servizio accessorio` |
| 1 | `PRENOTAZIONE_TEL` | `Prenotazione telefonica` |
| 2 | `SPONDA_IDR` | `Sponda idraulica` |
| 3 | `MERCE_LUNGA` | `Segnalazione merce lunga` |
| 4 | `FACCHINAGGIO` | `Facchinaggio` |
| 5 | `GDO` | `GDO` |

Id 0 is "none" and is a real item of the list. `null` cannot be stored (NOT NULL column; a
`null` id fails with 500, §3.2).

**UserRoleEnum** (`users.role`) — UserRoleEnum.java:15-16: `ADMIN`, `CUSTOMER` (label = name);
authorities `ROLE_ADMIN`, `ROLE_CUSTOMER`.

**Not persisted** (e-mail and error texts; useful for parity, verbatim):

* NotificationEnum (NotificationEnum.java:16-23): `EMAIL_REGISTRATION` "Conferma indirizzo email";
  `CUSTOMER_DECLINATION` "Indirizzo email e/o dati cliente rifiutati dall'amministratore";
  `CUSTOMER_REGISTRATION` "Indirizzo email e dati clienti confermati"; `ORDER_DETAIL` "Richiesta
  Dettaglio Ordine"; `ORDER_DECLINATION` "Ordine invalido rifiutato dall'amministratore";
  `REPORT` "Report ordini"; `ADMIN_REPORT` "Report ordini per admin"; `TRACKING_STATE` "Stato
  ordine".
* AdminNotificationEnum (AdminNotificationEnum.java:15-17): `ADMIN_CUSTOMER_REQUEST` "Nuovo
  cliente in attesa di conferma"; `ADMIN_ORDER_REQUEST` "Nuova richiesta di spedizione in
  attesa di conferma"; `ADMIN_REPORT` "Report cliente". (Its `fromString` returns an
  `EvasioneEnum`: a dead bug, line 27.)
* ErrorEnum (ls@migration:M/api/error/ErrorEnum.java:18-38): 500 "Qualcosa è andato storto. Si
  prega di riprovare oppure contattare il centro logistico." (generic, SES, persistence, invalid
  state); 500 "Indirizzo email di destinazione non valido."; 503 "Servizio non disponibile. Si
  prega di riprovare più tardi oppure contattare il centro logistico."; 400 "Richiesta non
  valida. Controlla nuovamente i dati inseriti." (validation: same text without the final
  period); 401 "Non disponi delle autorizzazioni necessarie per procedere." / "Username o password
  errate."; 403 "Non disponi dei permessi necessari per questo tipo di operazione."; 404
  "Impossibile recuperare i dati richiesti."; 409 "E' già stata effettuata una registrazione con
  questi dati. Si prega di riprovare con dati diversi."; 409 `CODE_409` "E' già stata effettuata
  una registrazione con questo codice. Si prega di riprovare con un nuovo codice."

**Unused pricing scaffolding** (never referenced; do not seed from it):

* PalletEnum (PalletEnum.java:15-21), fields label, altezza, larghezza, profondità, peso:
  `MQP` "Mini Quarter Pallet" 60/100/120/150; `QP` "Quarter Pallet" 100/100/120/300; `ELP`
  "Extra Light Pallet" 150/100/120/450; `HP` "Half Pallet" 150/100/120/150; `ULP` "Ultra Light
  Pallet" 150/100/120/350; `LP` "Light Pallet" 150/100/120/750; `FP` "Full Pallet"
  150/100/120/1200. It **contradicts** chapter 07 §3.1 (HP 600 kg; ULP, LP, FP 240 cm): keep
  chapter 07.
* EvasioneEnum (EvasioneEnum.java:15-20): `ESPRESSO_PLATINUM` "24", `ESPRESSO_GOLD` "24/48",
  `ESPRESSO_SILVER` "48", `ESPRESSO` "48/72", `STANDARD` "72", `HEAVY` "72/96".
* TariffarioConst (TariffarioConst.java:9-22): `DEPOSITO_GIORNALIERO` 7, `COLLI_BANCALE` 22,
  `PESO_MINIMO` 10, `PESO_PREMIUM` 50, `PREZZO_MINIMO` 6, `PREZZO_PREMIUM` 9,
  `SOGLIA_SCONTO_MINIMO` 8000, `SOGLIA_SCONTO_PREMIUM` 16000, `PERCENTUALE_SCONTO_MINIMO` 3,
  `PERCENTUALE_SCONTO_PREMIUM` 5 (meaning undocumented; pass to the pricing owner of chapter 07).

### 2.3 Exact answers of the enumeration endpoints

```json
GET /mono/api/enumeration/servizioAccessorio  (EP-20)
[{"id":0,"name":"NESSUN_SERVIZIO","label":"Nessun servizio accessorio"},
 {"id":1,"name":"PRENOTAZIONE_TEL","label":"Prenotazione telefonica"},
 {"id":2,"name":"SPONDA_IDR","label":"Sponda idraulica"},
 {"id":3,"name":"MERCE_LUNGA","label":"Segnalazione merce lunga"},
 {"id":4,"name":"FACCHINAGGIO","label":"Facchinaggio"},
 {"id":5,"name":"GDO","label":"GDO"}]
GET /mono/api/enumeration/statoTracking  (EP-19): ids 0–5 of §2.2, same shape
GET /mono/api/enumeration/tipoPallet: [{"id":0,"name":"PALLET","label":"PALLET"},{"id":1,"name":"EPAL","label":"EPAL"}]
GET /mono/api/enumeration/statoConferma: ids 0–3 NEW, PENDING, REGISTERED, DECLINED (name = label)
```

`id` is a JSON number (`int`) (ls@migration:M/api/repr/EnumerationRepr.java:15-19). Property order
is declaration order (inferred, Jackson default). `tipoPallet` and `statoConferma` are missing
from chapter 02.

## 3. State machines as implemented

### 3.1 Customer (`soggetti.stato`)

| Stored | Meaning | EP-02 `status` | CMS list | `emailConfirmed` / `customer` in `CustomerRepr` |
|---|---|---|---|---|
| (no row) | never registered | `NEW` | — | — |
| `NEW` | registered, link not used yet; or declined since 2024-07-22 | `NEW` | none | false / false |
| `PENDING` | link used: e-mail confirmed | `PENDING` | EP-04 | true / false |
| `REGISTERED` | approved by staff | `REGISTERED` | EP-05 | true / true |
| `DECLINED` | declined 2024-02-29 → 2024-07-22 | `DECLINED` | none | false / false |

Flags: ls@migration:M/business/factory/CustomerFactory.java:26-27; EP-02:
ls@migration:M/business/service/crud/SoggettoService.java:93-97 (no row → `NEW`).

```
 (no row) ──EP-03──▶ NEW ──GET /mono/email-confirm (code ok)──▶ PENDING ──EP-06──▶ REGISTERED
                      ▲                                            │                 │
                      └──────────────── EP-07 decline (any state, no check) ◀───────┘
```

| # | From | Trigger | Guard | To | Side effects | Errors | Source |
|---|---|---|---|---|---|---|---|
| C1 | no row for (e-mail, ragione sociale) | EP-03 `register` | Bean Validation | `NEW`, new row, `codice_verifica` = UUID | e-mail "Conferma indirizzo email" with the link | 400 | SoggettoService.java:63-70, 99-115; CustomerService.java:47-55 |
| C2 | `NEW` (same e-mail and ragione sociale, any case) | EP-03 | — | `NEW`, **row unchanged** (new form data are discarded) | same link e-mailed again (same code) | — | SoggettoService.java:102-104, 108-114 |
| C3 | `PENDING` | EP-03 | — | unchanged | — | **500** | SoggettoService.java:109 |
| C4 | `REGISTERED` | EP-03 | — | unchanged | — | 409 `GENERIC_409` | SoggettoService.java:110 |
| C5 | `DECLINED` | EP-03 | — | unchanged | link e-mailed again; the link then fails (it requires `NEW`) | — | SoggettoService.java:108-112 (DECLINED case commented out), 55 |
| C6 | `NEW` | `GET /mono/email-confirm?email=…&verificationCode=…` | stored code equals | `PENDING` | admin e-mail "Nuovo cliente in attesa di conferma" — sent **before** the check, on every click | error page | WebController.java:34-37; CustomerService.java:38-41; SoggettoService.java:53-61 |
| C7 | `PENDING` | EP-06 `confirm` | `stato = PENDING` | `REGISTERED` | e-mail "Indirizzo email e dati clienti confermati" | 500 `INVALID_ENUM_500` otherwise | SoggettoService.java:117-121; CustomerService.java:57-62 |
| C8 | any | EP-07 `decline` | none | **`NEW`** | e-mail "Indirizzo email e/o dati cliente rifiutati dall'amministratore" | 500 unknown e-mail | CustomerService.java:70-77 |
| C9 | any | `POST /mono/utils/customer/delete` | ADMIN | row and all its orders hard-deleted | — | — | AdminToolsService.java:45-60 |

Consequences:

* **Q-28**: approval requires a confirmed e-mail (C7). EP-04 lists only `PENDING` customers
  (ls@migration:M/api/controller/CustomerController.java:41-46), so staff never see unconfirmed
  registrations and the CMS column "Email confermata" is always "SI". CMS-20 and DQ-24 cannot
  occur.
* **Q-09**: a decline keeps the row and resets it to `NEW`. The old link still works (the code is
  not rotated, C6), so a declined customer can re-confirm and re-enter the CMS queue.
  Registering again with the same e-mail and ragione sociale reuses the row unchanged (C2); with
  a **different** ragione sociale it creates a second row (C1) → two rows with the same e-mail
  → every lookup by e-mail (EP-02, link, EP-06, EP-07, EP-08, admin tools) fails with a
  non-unique-result error, i.e. HTTP 500, until the data are fixed by hand (inferred from
  `Optional<…> findByEmail`, ls@migration:M/persistence/repository/SoggettoRepository.java:15, 21-22).
* Before `ls@5debd84` (2024-07-22) declines wrote `DECLINED` (`ls@6187a37`, 2024-02-29) and
  EP-03 refused a DECLINED e-mail with 500. Such rows are stuck: C5 loops forever.
* A decline does not touch the customer's orders.
* Lookups are case-insensitive (collation), so `Mario@x.it` and `mario@x.it` find the same row;
  the stored case is the first one typed (inferred).
* The link puts the e-mail in the query string without URL-encoding
  (ls@migration:M/business/util/LinkUtil.java:32-34): an address containing `+` arrives as a space
  and can never be confirmed (inferred).

### 3.2 Order (`spedizioni.stato`)

| Stored `stato` | `tracking` | `codice` | Meaning | Listed in |
|---|---|---|---|---|
| `NEW` | `Non gestito` | `TEMP…` | only inside the submission transaction; persisted = declined since 2024-07-22 | none |
| `PENDING` | `Non gestito` | `TEMP…` | submitted | EP-09, created in the last 6 months |
| `REGISTERED` | any | staff code | approved | EP-10, created in the last 6 months |
| `DECLINED` | `Non gestito` | `TEMP…` | declined 2024-02-29 → 2024-07-22 | none |

```
 EP-08 ─▶ (NEW) ─▶ PENDING ──EP-11 {id, req:{codice, importo, tracking}}──▶ REGISTERED ──EP-14──▶ REGISTERED (tracking 0–5)
                      └──────EP-12 decline (no state check)──▶ NEW (tracking Non gestito, TEMP code kept)
```

| # | From | Trigger | Guard | To / writes | Side effects | Errors | Source |
|---|---|---|---|---|---|---|---|
| O1 | — | EP-08 `register` | customer found by `clienteEmail` and `REGISTERED`; Bean Validation | insert `NEW`, `tracking = Non gestito`, `codice = "TEMP"+UUID`; promote to `PENDING`; aggregate row; one `pallet` row per unit | admin e-mail "Nuova richiesta di spedizione in attesa di conferma" (no e-mail to the customer) | 500 unknown or non-registered e-mail; 400 validation; 500 `servizioAccessorio` null or > 5, `type` not 0/1 | OrderService.java:53-112; SpedizioneService.java:75-88, 132-143 |
| O2 | `PENDING` | EP-11 `confirm` with `req` | `req.codice`, `req.importo`, `req.tracking` non-null; no order has `codice`; `stato = PENDING` | `REGISTERED`, `tracking = Presa in carico` (whatever is sent), `confirmation_ts = now`, `codice`, `importo`, `importo_assicurato` if sent, `importo_contrassegno := importo_assicurato` if sent, optional `note`, `servizioAccessorio`, DDT if sent | e-mail "Sanchez Logistica - Conferma ordine N. <codice>" to the customer with the full detail | 409 `CODE_409`; 500 (missing field, wrong state); 400 (`importo` ≤ 0) | SpedizioneService.java:145-175; OrderService.java:114-121; EmailReqFactory.java:74-87 |
| O2b | not `REGISTERED` | EP-11 with `req` absent | — | `stato` ordinal + 1 (`PENDING` → `REGISTERED` **keeping the TEMP code**, no timestamp, tracking unchanged) | same e-mail | — | SpedizioneService.java:150, 107-114 |
| O3 | any | EP-12 `decline {email, id}` | the e-mail exists (any customer; not checked against the order) | `stato = NEW`, `tracking = Non gestito`, `codice` unchanged | e-mail "Aggiornamento ordine rifiutato" to that e-mail, quoting the numeric id | 500/404 | OrderService.java:135-152; EmailReqFactory.java:90-104 |
| O4 | `REGISTERED` | EP-14 `trace {codice, tracking}` | `stato = REGISTERED` | `tracking` := any 0–5; timestamps per §3.4 | e-mail "Consegna effettuata per ordine N. <codice>" when the new state is `CONSEGNATO` | 500 not registered / bad id; 404 unknown code | SpedizioneService.java:236-259; CmsController.java:111-123 |
| O5 | any | EP-16 `notes/update` | — | `note` replaced if different | `update_tms` | 404 unknown code; 500 `notes` null | OrderService.java:181-203 |
| O6 | any | `POST /mono/cms/order/update {id, req}` | if `req.codice` is sent, no order may have it (the order itself included) | any field of `PatchSpedizioneReq`: code, amounts, DDT, service, note, **stato**, **tracking**, timestamp flags | — | 409 | OrderService.java:205-211; SpedizioneFactory.java:83-109 |
| O7 | any | `/mono/utils/order/delete`, `/deleteAll` (optionally by state), `/deleteBeforeDate` (approved before a date **and** delivered) | ADMIN | hard delete of units, aggregate, order | — | `/deleteBeforeDate` fails with 500 when the customer has an order without `confirmation_ts` (NPE, inferred) | AdminToolsService.java:62-135 |

Of these transitions `ls-cms@dev` uses O2–O5. O6 and O7 have no known client, but the developer logs show
O6 calls with `importo`, `importoAssicurato`, `importoContrassegno`, `note`, `tracking`, `codice`
and `servizioAccessorio` (11 requests, values masked), so data may have been edited after approval
(inferred). Order declines: `DECLINED` from `ls@6187a37` (2024-02-29) to `ls@d7b2c56`
(2024-07-18), `NEW` from `ls@5debd84` (2024-07-22).

### 3.3 Codes (Q-06)

| | Temporary code | Definitive code |
|---|---|---|
| Who | the backend, at submission | staff, typed in the approval dialog (`req.codice`) |
| Format | `"TEMP" + UUID.randomUUID().toString()`: `TEMP` + 36 lower-case hex chars with hyphens = **40 chars**, e.g. `TEMP1b4e28ba-2fa1-41d2-883f-0016d3cca427` (illustrative). Since 2024-03-26 (`ls@ebcc438`) | none: any string ≤ 255 (column), `""` accepted (`requireNonNull` only), no trim, no case rule |
| Uniqueness | UNIQUE `codice` + random UUID | pre-check `findByCodice` (→ 409) + UNIQUE `codice`; both case-insensitive via the collation (inferred) |
| Life | kept on pending and declined orders; **overwritten** at approval (lost) | can be changed later through O6 only |
| Seen by the customer | only in the EP-08 answer, which the portal ignores | in the approval and delivery e-mail subjects and in public tracking |
| Source | ls@migration:M/business/service/crud/SpedizioneService.java:84; ls@migration:M/business/util/TextCodeFactory.java:8-10 | SpedizioneService.java:152, 163; OrderService.java:208-209 |

Observed shapes (developer logs, masked: digit → 9): of 121 distinct non-TEMP codes, most are
**12-digit numbers** (`999999999999`), a few 4-digit or test-like values; several carry a
**leading space or a tab** (pasted from a spreadsheet, inferred), stored as typed. A 12-digit
pattern suggests codes from an external system (a carrier waybill?) rather than an internal
sequence (inferred): the brief's default "the app proposes the next code" (Q-06) needs the
owner's confirmation.

### 3.4 Tracking updates and timestamps

At approval (O2): `tracking = PRESA`, `confirmation_ts = now`; the request's shipping/delivery
flags are forced to false (SpedizioneService.java:169-173). Then each EP-14 call:

| New state | `tracking` | `shipping_ts` | `delivery_ts` |
|---|---|---|---|
| `TRANSITO` | `Transito` | := now (**always**, overwrites) | unchanged |
| `CONSEGNATO` | `Consegnato` | := now if null | := now (overwrites) |
| `NON_GESTITO`, `PRESA`, `TERMINAL_DEST`, `ASSEGNATO` | label | := now **if null** | unchanged |

Source: ls@migration:M/business/service/crud/SpedizioneService.java:245-256;
ls@migration:M/business/factory/SpedizioneFactory.java:104-106. Any state may follow any state;
nothing is cleared when moving back.

Meaning for the migration: `confirmation_ts` = approval time (exact); `delivery_ts` = last time
set to `CONSEGNATO` (exact, may survive a move back); `shipping_ts` = first tracking change after
approval, or last time set to `TRANSITO` — not a "shipped" date. `update_tms` also moves on note
edits. No history.

The PDF report labels `confirmation_ts` "data spedizione" and `shipping_ts` "data consegna", the
latter replaced in memory by `update_tms` when `delivery_ts` is null
(ls@migration:M/business/factory/ReportFactory.java:37-38;
ls@migration:M/business/service/crud/SpedizioneService.java:224-229). Its date range filters
`create_tms` with UTC day boundaries (SpedizioneService.java:212-223).

### 3.5 Amounts (Q-05)

| Backend build | Approval requires | Stored |
|---|---|---|
| `ls@ebcc438` (2024-03-26) → before `ls@2949426` | `importoContrassegno` **and** `importoAssicurato` | both |
| `ls@2949426` (2024-06-04) → before `ls@d7732eb` | `importoAssicurato` | `importo_assicurato` (+ `importo_contrassegno` if sent) |
| `ls@d7732eb` (2024-07-16) | `importo` (and a free code: duplicate check added) | `importo`; `importo_assicurato` if sent |
| `ls@d7b2c56` (2024-07-18) → `migration` | `importo` | `importo`; `importo_assicurato` if sent; `importo_contrassegno := importo_assicurato` (unchanged if not sent) |

Sources: ls@migration:M/business/service/crud/SpedizioneService.java:152-154, 166-168 and the
cited commits (`git show <sha>:…/SpedizioneService.java`, method `confirm`).

* **What the CMS sends.** `ls-cms@dev` sends `{id, req: {codice, importoAssicurato, tracking: 1}}`
  and never `importo` (ls-cms@dev:js/function/component-handler.js:420-426, unchanged since
  `ls-cms@e5a72fc`, 2024-05-25). That contract works only with backends from 2024-06-04 to
  2024-07-15; before (contrassegno required) and after (`importo` required) the approval fails
  with a `NullPointerException` → 500 (inferred). The approvals in the developer logs send `codice`,
  `importo`, `tracking` (9 of 9) plus `importoAssicurato` and `importoContrassegno` (5 of 9). So
  the production CMS is a later build than `ls-cms@dev` (inferred) — add to CU-21.
* **Type**: JSON number or numeric string → `Double` (Jackson coercion). `"180.50"` passes;
  `"180,50"` fails parsing and the generic handler answers 500 (inferred,
  ls@migration:M/api/error/ErrorController.java:30-33); `@Positive` rejects 0 and negatives
  with 400 (ls@migration:M/api/req/crud/PatchSpedizioneReq.java:21-28). **The database holds
  only valid doubles**, never free text.
* **What is shown.** Lists EP-09/EP-10 return the `importo` column as is (null for approvals made
  before the column existed); the "Temp code" fallback in `SpedizioneFactory.java:28-34` is dead
  code, overwritten at line 38. The detail EP-17 shows `importo_assicurato` when `importo` is
  null and `tracking ≠ PRESA` (SpedizioneService.java:194). The approval e-mail prints all three
  columns (order-detail.html:86-94).
* **Meaning.** The column names say insured value (`assicurato`), cash on delivery
  (`contrassegno`) and amount (`importo`); the code gives them no further semantics, and the CMS
  label is "Importo Totale (per cliente)". **No VAT information exists anywhere.** Q-05 is
  answered for storage, not for meaning or VAT (owner).
* **Migration rule (proposal).** `amount = round_half_up(COALESCE(importo, importo_assicurato), 2)`,
  which is what EP-17 shows; flag when both are present and differ by more than 0.005;
  `importo_contrassegno` is not an amount of its own (keep it in `legacy_payload`).

### 3.6 Notes (Q-26)

There is **one column**, `spedizioni.note`. The portal writes the customer's note (`""` when
empty, max 255); EP-15 reads the same column, returning "Nessuna nota presente" when it is NULL
or the code does not exist; EP-16 overwrites it when different
(ls@migration:M/business/service/OrderService.java:170-203). Staff edits therefore replace the
customer's text. The approval e-mail prints `spedizione.note`
(ls@migration:R/templates/order-detail.html:106), so a staff note written before approval is
e-mailed to the customer (inferred). Line breaks are stored as received; more than 255 chars from
EP-16 fail in MySQL strict mode with 500 (inferred: EP-16 has no length check).

### 3.7 Public tracking lookup (Q-03, Q-38)

`POST /mono/api/order/trace/state {"codice": "…"}` runs
`SELECT s.tracking FROM SpedizioneEntity s WHERE s.codice = :codice` and answers
`{"status": "<label>"}`, e.g. `{"status":"Presa in carico"}`
(ls@migration:M/api/controller/OrderController.java:33-39;
ls@migration:M/persistence/repository/SpedizioneRepository.java:30-31):

* **status is the label**, never the name or id;
* an **unknown code answers 200 `{"status":"Non gestito"}`** (`orElse(NON_GESTITO)`,
  SpedizioneService.java:116-119): there is no 404;
* a missing or empty `codice` → 400 (`@NotEmpty`, ls@migration:M/api/req/OrderDetailReq.java:16-18);
* **TEMP codes** (pending or declined orders) answer `Non gestito`, exactly like unknown codes;
* matching is case- and accent-insensitive through the collation; the code does not trim;
  trailing spaces are ignored only under a PAD SPACE collation; leading spaces and tabs count
  (inferred).

Chapter 02's compatibility default (404 for unknown codes, FX-06 for TEMP codes) therefore
**differs** from the legacy answer; API-01 parity means 200 + "Non gestito" unless the owner
accepts the change (ADR).

### 3.8 Staff users and sessions

Login checks the bcrypt hash, then finds or creates the user's single `user_session` row
(`session_id` = random UUID = refresh token) and sets its expiry to now + the configured
refresh lifetime (LocalDateTime). `POST /mono/auth/refresh-token` exists (unknown to chapter 02)
and deletes expired sessions (ls@migration:M/business/service/AuthenticationService.java:35-95;
ls@migration:M/configuration/constant/PathConstants.java:16-17). Roles: `ADMIN`, `CUSTOMER`;
`policy` is one string. Staff are not migrated (chapter 08 §3.9 stands).

## 4. API DTOs and entities

### 4.1 Requests → columns

| Endpoint | Request DTO (wire) | Columns written | Source |
|---|---|---|---|
| EP-02 `/api/customer/state` | `{email}` | read `soggetti.stato` | CustomerController.java:27-33 |
| EP-03 `/api/customer/register` | `CreateSoggettoReq {ragioneSociale, indirizzo, provincia, citta, cap, telefono, email}` all required | `soggetti.*` same names in snake_case; `stato='NEW'`; `codice_verifica`; audit | CreateSoggettoReq.java:21-49; SoggettoFactory.java:33-43 |
| `GET /email-confirm` | `email`, `verificationCode` | `stato`, `update_*` | WebController.java:34-37 |
| EP-06 / EP-07 | `{email}` | `stato`, `update_*` | CmsController.java:51-70 |
| EP-08 `/api/order/register` | `CreateOrderReq {clienteEmail, destinatario{ragioneSociale, indirizzo, provincia, citta, cap, telefono?, email?}, dettaglioColli{colli?, palletList[{altezza, peso, larghezza, lunghezza, quantity, type}]}, codiceDocumentoTrasporto?, servizioAccessorio, note?}` | `spedizioni` (recipient columns ← `destinatario.*`, `email` ← `destinatario.email`, `mittente_id` ← customer by `clienteEmail`, `servizio_accessorio` ← label of id, `note`, DDT); `dettaglio_colli` (`colli` ← `dettaglioColli.colli`, counts, totals); `pallet` × Σ quantity | CreateOrderReq.java:22-39; CreateDestinatarioReq.java:20-46; OrderDettaglioColliReq.java:19-23; OrderPalletReq.java:16-37; OrderService.java:53-112; SpedizioneFactory.java:63-81 |
| EP-11 `/cms/order/confirm` | `OrderConfirmationReq {id, req: PatchSpedizioneReq}` | §3.2 O2 | OrderConfirmationReq.java:18-22; PatchSpedizioneReq.java:18-45 |
| EP-12 `/cms/order/decline` | `{email, id}` | `stato`, `tracking` | OrderDeclinationReq.java:15-22 |
| EP-14 `/cms/order/trace` | `{codice, tracking: 0–5}` | `tracking`, `shipping_ts`, `delivery_ts` | TraceReq.java:18-23 |
| EP-15 / EP-16 `/cms/notes(/update)` | `{codice}` / `{codice, notes}` | `note` | NotesReq.java; UpdateNotesReq.java:17-21 |
| `/cms/order/update` | as EP-11 | any column of §3.2 O6 | OrderService.java:205-211 |

Validation limits (server side): customer and recipient texts 1–50 chars, `cap` 1–9, `telefono`
pattern + ≤ 20, e-mails `@Email` ≤ 50 (Hibernate Validator's lenient `@Email`), DDT ≥ 1 char
when present, note ≤ 255, dimensions and weight `@Positive` (0 rejected), `quantity` ≥ 1, no
maximum anywhere (ls@migration:M/business/model/constant/SoggettoConst.java:5-25). Services
`DettaglioColliService` and `PalletService` are not `@Validated`, so the constraints declared on
`CreateDettaglioColliReq` are not applied (inferred: `pallet` may be 0, `volume` 0.0).

Wire variants of chapter 02 §8 against this backend:

| Variant | Backend result |
|---|---|
| line `type` `null` (builds before `e183526`) | 400 (`@NotNull`): never stored |
| line `type` number `1` (no choice, from `e183526`) | `EPAL` — OP-04 is real and undetectable |
| `servizioAccessorio` `null` | 400 before 2024-05-25 (`@NotNull`, removed in `ls@bb43bbc`), then 500 (unboxing in `fromId`): never stored |
| `servizioAccessorio` `1` as "none" (`ls-order@e183526` → `6176e85`, 2024-05-19 → 2024-05-25) | stored as `Prenotazione telefonica` (DQ-07) |
| `servizioAccessorio` `"2"` (string id) | coerced to 2 → `Sponda idraulica` |
| dimension `"0"` | 400 (`@Positive`) |
| recipient `telefono` `""` (`ls-order@1c84556`) | 400 (pattern) |
| `codiceDocumentoTrasporto` `null` / `""` | stored NULL / 400 |
| `note` `""` | stored `""` |

### 4.2 Responses ← columns

* **Order list item** (EP-09, EP-10, also EP-08/11/14 answers) = `Spedizione`: `id, codice,
  email, ragioneSociale, indirizzo, provincia, citta, cap, telefono, importoContrassegno,
  importoAssicurato, importo, codiceDocumentoTrasporto, servizioAccessorio (label), note, stato
  (label), tracking (label), confirmationTs, shippingTs, deliveryTs`
  (ls@migration:M/business/model/Spedizione.java:23-73). Lists hold only `PENDING` / `REGISTERED`
  rows created in the last 6 months (ls@migration:M/persistence/repository/SpedizioneRepository.java:41-45),
  in no defined order (no ORDER BY; physical order in practice, inferred) (CU-15).
* **Customer list item** (EP-04, EP-05, EP-03, EP-06) = `CustomerRepr {id, ragioneSociale,
  indirizzo, provincia, citta, cap, telefono, email, customer, emailConfirmed}` — no dates, no
  `stato` (ls@migration:M/api/repr/CustomerRepr.java:15-54; test schema
  ls@migration:src/test/resources/schema/CustomerRepr.json). All rows of the state, no window.
* **Detail** (EP-17) = `OrderDetailRepr {spedizione: Spedizione, mittente: Soggetto {id,
  ragioneSociale, indirizzo, provincia, citta, cap, telefono, email, stato}, dettaglioColli: {id,
  colli, pallet, epal, peso, volume}, palletList: [{id, altezza, peso, larghezza, lunghezza,
  type}]}` (ls@migration:M/api/repr/OrderDetailRepr.java:18-27; `codiceVerifica` is
  `@JsonIgnore`, Soggetto.java:53-54). This settles CU-09.
* `StatusRepr {status}`, `NotesRepr {notes}`, `EnumerationRepr {id, name, label}`.

### 4.3 The `dettaglioColli: {palletList}` nesting

The portal mirrors `OrderDettaglioColliReq {colli, palletList}`: the wrapper exists because the
backend models the aggregate (`dettaglio_colli`) as a child of the order and the units as
children of the aggregate. On submission the backend (ls@migration:M/business/service/OrderService.java:71-108):

1. expands each line into `quantity` identical unit requests, in line order;
2. counts units by type (`pallet`, `epal`), sums `peso` (per unit), sums unit volumes and rounds
   the total to an integer;
3. stores `colli` from the request (absent → NULL) and the unit rows.

In the detail the units come back as a top-level `palletList` next to the aggregate
`dettaglioColli` (OrderFactory.java:18-25). The original lines (and their quantities) are not
stored; they can be rebuilt by grouping consecutive identical units (§5.5).

### 4.4 Who is a `Soggetto`

Only the customer ("mittente"). The recipient is embedded in `spedizioni` (since `ls@22c4d03`,
2024-02-13; before, a `destinatario_id` FK pointed to `soggetti`). `PatchOrderReq.destinatario`
(a `PatchSoggettoReq`) and the `*Trait` interfaces are unused. `spedizioni.email` is not a
recipient address: it is whatever `destinatario.email` held, the customer's e-mail by portal
convention, and it is never used to resolve the customer (`mittente_id` is).

### 4.5 Serialisation quirks that affect exports

* Enumerations out as labels (§2.1).
* Dates: until 2024-07-26, Spring Boot's mapper wrote ISO strings with the Rome offset
  (`TemporalUtil.java:10-14`); since `ls@0f1a1fe` (2024-07-27) a plain `ObjectMapper` bean
  (ls@migration:M/configuration/JacksonConfig.java:13-18) writes **decimal epoch seconds**
  (e.g. `1724592033.253000000`); both forms appear in the developer logs (inferred from Jackson
  defaults, confirmed by the logs).
* The same plain mapper fails on unknown JSON properties (Jackson default), answered with 500 by
  the generic handler (inferred).
* `NoSuchElementException` → 404 (ErrorController.java:71-77); validation → 400 (:45-61);
  anything else → 500 with the generic text (:30-33).

## 5. Reconciliation with chapter 08 and consequences for the migration

### 5.1 Chapter 08 §1 (legacy entities as observed)

| Ch. 08 claim | Verdict | Fact (source) |
|---|---|---|
| §1.1 customer business key = e-mail | **Corrected** | surrogate `id`; e-mail is the lookup key everywhere but is not unique; registration matches (e-mail, ragione sociale) (SoggettoService.java:99-104) |
| §1.1 order→customer by e-mail or FK: unknown | **Corrected** | FK `mittente_id` NOT NULL, plus a copy of the e-mail in `spedizioni.email` |
| §1.1 / §1.5 lines without id | **Corrected** | one `pallet` row (with id) per unit; quantity expanded; aggregate in `dettaglio_colli` |
| §1.1 service = enumeration id or "none" | **Corrected** | stored as label; "none" is item 0 `Nessun servizio accessorio` |
| §1.1 / §1.7 one tracking state, no history | **Confirmed** (+) | stored as label; `confirmation_ts`, `shipping_ts`, `delivery_ts` kept; no history |
| §1.1 / §1.8 one free-text note | **Confirmed**, and it is the customer's note (§3.6) | |
| §1.2 `id` integer | Confirmed | bigint auto_increment |
| §1.2 e-mail as typed | Confirmed | no trim/lower-case; ≤ 50; case-insensitive comparisons (collation, inferred) |
| §1.2 `telefono` string or null | **Corrected** | NOT NULL, required, `\d{10}` until 2025-01-07 |
| §1.2 `cap` US-style ZIP+4 possible | **Corrected** | server allows 1–9 chars: `20831-1234` (10 chars) is rejected |
| §1.2 `emailConfirmed` set by the link | **Corrected** | derived: `stato ∈ {PENDING, REGISTERED}`; not stored |
| §1.2 status values NEW/PENDING/REGISTERED, NEW = no record | **Corrected** | stored NEW/PENDING/REGISTERED/DECLINED; NEW also = unconfirmed or declined |
| §1.2 state diagram (PENDING before the link; approval allowed unconfirmed; decline unknown) | **Corrected** | §3.1: NEW → link → PENDING → EP-06 → REGISTERED; EP-06 needs PENDING; decline → NEW |
| §1.2 no registration date | **Corrected** | `create_tms`; approval ≈ `update_tms` |
| §1.3 `codice` contains TEMP while pending | Confirmed | `TEMP`+UUID, 40 chars |
| §1.3 `clienteEmail`/`email` = customer | Confirmed with nuance | list `email` = `spedizioni.email` copy |
| §1.3 recipient flattened | Confirmed | embedded columns |
| §1.3 `dettaglioColli` / `spedizione` keys unknown | **Settled** | §4.2 |
| §1.3 `codiceDocumentoTrasporto` string or null | Confirmed | `""` rejected |
| §1.3 `servizioAccessorio` read raw | **Settled** | label |
| §1.3 `note` / `notes` same field: unknown | **Settled: same column** | §3.6 |
| §1.3 `importoAssicurato` free text | **Corrected** | DOUBLE; comma decimals rejected |
| §1.3 `importo` same as `importoAssicurato`: unknown | **Settled** | separate column since 2024-07-16; lists show `importo` |
| §1.3 `tracking` read raw | **Settled** | label |
| §1.3 `stato` values unknown | **Settled** | `PENDING` / `REGISTERED` in lists |
| §1.3 `updateShippingTs`/`updateDeliveryTs` | **Settled** | real flags, forced false at approval; tracking sets the timestamps (§3.4) |
| §1.3 orders carry a date | Confirmed | `create_tms` (report range), plus approval/shipping/delivery |
| §1.3 "Numero Colli" never sent | Confirmed | `dettaglio_colli.colli` exists and stays NULL |
| §1.3 totals never sent, lost | **Corrected** | the backend computes and stores weight, rounded volume and per-type counts |
| §1.3 no client edits code/amount/recipient after submission | Confirmed for clients; backend has `/cms/order/update` (used in developer logs) | §3.2 O6 |
| §1.3 approvals before `e5a72fc` may lack an amount | Refined | an amount was required by every backend build from 2024-03-26 (§3.5); `importo` is NULL for approvals before 2024-07-16 |
| §1.4 no pick-up address | Confirmed | none in the schema (Q-13 stays) |
| §1.5 `peso` per unit | Confirmed | backend replicates it per unit and sums |
| §1.5 type `"0"`/`"1"`/`1`/`null` | **Settled** | stored `PALLET`/`EPAL`; `null` never stored; `1` = EPAL |
| §1.6 accessory values unknown | **Settled** | §2.2 |
| §1.7 tracking ids 0–5, any → any | Confirmed | only on REGISTERED orders |
| §1.8 literal "Nessuna nota presente" | Refined | returned by EP-15 for NULL notes; stored only if the CMS saved it back |
| §1.9 staff: no roles, no refresh | **Corrected** | `role` ADMIN/CUSTOMER, `policy`, refresh endpoint, `user_session` |
| §1.10 no dates | **Corrected** | §1.6, §3.4 |
| §1.10 no declined records | **Corrected** | declined rows kept (`NEW`/`DECLINED`) |
| §1.10 no history, no VAT, no leads | Confirmed | |

### 5.2 Data-quality list DQ-01…DQ-24

| DQ | Verdict | What the code says → new handling |
|---|---|---|
| DQ-01 e-mail case duplicates | **Confirmed, sharpened** | lookups are case-insensitive, so duplicates arise when the ragione sociale differs (C1); they lock the customer out in the legacy. The developer logs already show one address in two spellings (count only). Blocking. Detection: `GROUP BY LOWER(TRIM(email)) HAVING COUNT(*) > 1` |
| DQ-02 registration data of a recipient | unchanged | not detectable |
| DQ-03 CAP not 5 digits | Confirmed (1–9 chars, any characters) | flag |
| DQ-04 required text empty | **Mostly impossible** (`@NotBlank`); untrimmed values possible | trim; flag only changed values |
| DQ-05 province free text | Confirmed (≤ 50) | flag |
| DQ-06 phone formats | **Refined**: exactly 10 digits until 2025-01-07, then `+`, spaces, dots, dashes, brackets, ≤ 20 | normalise 10-digit numbers as Italian; flag others |
| DQ-07 none vs real service | **Refined**: `Prenotazione telefonica` stored for "none" by `ls-order@e183526` builds; `null` never stored | flag `Prenotazione telefonica` orders created in that build's live window (deployment dates from the owner) |
| DQ-08 duplicate definitive codes | **Impossible** (UNIQUE; case-insensitive by collation, inferred) | integrity assertion, blocking only if found (drift) |
| DQ-09 approved with TEMP/blank code | **Possible**: `""` accepted; O2b keeps the TEMP code; whitespace and tabs observed | flag; blocking if blank |
| DQ-10 orphan order | **Impossible** (FK NOT NULL) | join on `mittente_id`, never on e-mail; assertion |
| DQ-11 missing DDT | Confirmed (nullable) | flag |
| DQ-12 unparseable amount | **Impossible** (DOUBLE) | flag > 2 decimals; report NULL amounts on approved orders |
| DQ-13 `importo` ≠ `importoAssicurato` | **Refined** (§3.5) | `COALESCE(importo, importo_assicurato)`; flag differences |
| DQ-14 sender snapshot from today's data | Confirmed | note |
| DQ-15 line type missing or wrong | **Refined**: never missing; `EPAL` may mean "no choice" | note on every `EPAL` line from portal builds with the fallback |
| DQ-16 zero or non-numeric line values | **Impossible** (`@Positive` Double) | flag non-integer centimetres only |
| DQ-17 tracking unmatched | **Impossible** unless edited by hand (NOT NULL label) | assertion |
| DQ-18 notes | Confirmed + one shared column | §5.5 rule |
| DQ-19 HTML/script | Confirmed (no sanitising) | flag |
| DQ-20 double submissions | Confirmed (no idempotency, no unique key) | detect with `create_tms` within minutes |
| DQ-21 unknown dates | **Corrected**: dates exist in the dump | applies to the API export only |
| DQ-22 amount copied from previous approval | unchanged | spot checks |
| DQ-23 recipient e-mail = customer's | Confirmed (`spedizioni.email`) | never import as recipient e-mail |
| DQ-24 approved unconfirmed customer | **Impossible** | drop |

### 5.3 New data-quality items (proposed DQ-25…DQ-37)

| DQ | Issue | Detection (on the dump) | Handling |
|---|---|---|---|
| DQ-25 | Customer `NEW`: unconfirmed or declined | `stato='NEW' AND update_user IS NULL` → unconfirmed; `stato='NEW' AND update_user='ROLE_ADMIN'` → declined (the only admin action that yields NEW) (inferred) | map per §5.5; anything else flagged |
| DQ-26 | Declined orders kept as `NEW` (or `DECLINED`) with TEMP codes | `spedizioni.stato IN ('NEW','DECLINED')` | import as `DECLINED`; `declined_at = update_tms` (estimated) |
| DQ-27 | Lines stored per unit | always | regroup (§5.5); report units = Σ quantity |
| DQ-28 | Aggregate inconsistent or coarse: `volume` integer m³ (0 for small loads), `colli` NULL, `pallet`/`epal`/`peso` vs units | compare `dettaglio_colli` with Σ `pallet` | recompute from units; keep legacy values in `legacy_payload` |
| DQ-29 | Codes with surrounding whitespace, tabs or empty | `CHAR_LENGTH(codice) <> CHAR_LENGTH(TRIM(codice))` or `codice REGEXP '[[:space:]]'` or `codice = ''` (do not use `<>` with TRIM under PAD SPACE) | store trimmed for matching, keep raw; empty → blocking |
| DQ-30 | Timestamp semantics: `shipping_ts` is not a shipping date; `delivery_ts` may survive a move back; `update_tms` moved by note edits | rules of §3.4 | events per §5.5; flag `delivery_ts` with tracking ≠ CONSEGNATO |
| DQ-31 | Amount columns depend on the approval period | `importo IS NULL AND importo_assicurato IS NOT NULL` | §3.5 rule |
| DQ-32 | Approved via EP-11 without `req` (TEMP code, no `confirmation_ts`) | `stato='REGISTERED' AND (codice LIKE 'TEMP%' OR confirmation_ts IS NULL)` | flag; blocking if the owner wants a code |
| DQ-33 | Schema drift | §1.8 queries | document; profile leftover columns |
| DQ-34 | Hard-deleted rows (admin tools) | id gaps | report counts only; a deleted order's code may be reused |
| DQ-35 | E-mail with `+` never confirmable | `stato='NEW' AND email LIKE '%+%'` | `PENDING_VERIFICATION`; verifies at first sign-in |
| DQ-36 | `spedizioni.email` differs from the customer's e-mail | `LOWER(TRIM(o.email)) <> LOWER(TRIM(c.email))` | ignore for linking; note |
| DQ-37 | Customer declined after having orders, or stuck `DECLINED` | `soggetti` NEW/DECLINED with `spedizioni` rows | import as `DECLINED` with its orders; list for the owner |

### 5.4 What changes in the target model (chapter 08 §2)

* **Customer state machine**: drop the guarded `PENDING_VERIFICATION → ACTIVE` transition
  "for legacy parity" — the legacy forbids it. Q-28's recommended default becomes "yes, as
  today". Keep `DECLINED`; re-registration of a declined customer matches the legacy in spirit.
* **`shipment_order`**: fill the existing `approved_at` from `confirmation_ts` (exact) and
  `submitted_at` from `create_tms` (exact; the `source = 'LEGACY'` escape hatch of the
  `submitted_at` CHECK becomes unnecessary for dump imports). `amount_raw varchar` becomes the text of the source doubles. Add `legacy_payload` to
  `customer` too (the raw `soggetti` row minus `codice_verifica`).
* **`order_line`**: legacy lines are rebuilt; keep the unit ids of each line in `legacy_payload`.
  `legacy_type_raw` holds `PALLET`/`EPAL` (the wire value is lost).
* **`accessory_service`**: seed the six items of §2.2 (`legacy_id` "0"…"5", `name`, `label`,
  `sort_order` = id, `is_none = true` for id 0, `legacy_json` = the EP-20 item); orders map by
  label; id 0 → `accessory_service_id = NULL`. FX-12/ADM-10 are no longer blocked by Q-25
  (still compare with a live EP-20 capture at cut-over).
* **`tracking_state`**: seed confirmed (§2.2).
* **Notes**: the legacy has one shared note; the target's split (`customer_notes` +
  `order_internal_note`) is a behaviour change to state in an ADR (§5.5 rule).
* **Codes**: uniqueness case-insensitive (as legacy); store trimmed + raw; do not assume a
  sequence (Q-06).
* **Public tracking compatibility**: legacy answers 200 "Non gestito" for unknown and TEMP codes
  (§3.7).

### 5.5 Legacy → target mapping (per table and column)

Status mapping:

| Legacy row | Condition | Target |
|---|---|---|
| `soggetti.stato = NEW` | `update_user IS NULL` | `customer.status = PENDING_VERIFICATION` |
| `soggetti.stato = NEW` | `update_user = 'ROLE_ADMIN'` | `DECLINED`, `declined_at = update_tms` |
| `soggetti.stato = NEW` | otherwise | `PENDING_VERIFICATION` + flag DQ-25 |
| `soggetti.stato = PENDING` | — | `PENDING_APPROVAL`, `email_verified_at = update_tms` |
| `soggetti.stato = REGISTERED` | — | `ACTIVE`, `approved_at = update_tms` (if `update_user='ROLE_ADMIN'`), `email_verified_at = approved_at` flagged "estimated (before approval)" |
| `soggetti.stato = DECLINED` | — | `DECLINED`, `declined_at = update_tms` |
| `spedizioni.stato = PENDING` | — | `shipment_order.status = PENDING`, `temp_code = codice` |
| `spedizioni.stato = REGISTERED` | — | `APPROVED`, `code = codice`, `approved_at = confirmation_ts` |
| `spedizioni.stato IN (NEW, DECLINED)` | — | `DECLINED`, `temp_code = codice`, `declined_at = update_tms` (estimated) |

`soggetti` → `customer` (+ one `customer_user` OWNER):

| Column | Target | Rule |
|---|---|---|
| `id` | `legacy_id` | as is |
| `email` | `email`, `customer_user.email`, `legacy_email_raw` | trim + lower-case; collisions → DQ-01 (blocking) |
| `ragione_sociale` | `company_name` | trim, collapse spaces |
| `indirizzo` / `citta` | `address_line` / `city` | trim |
| `provincia` | `province`, `province_code` | §3.8 of ch. 08 |
| `cap` | `postal_code` | §3.8 of ch. 08 |
| `telefono` | `phone` | 10 digits → `+39…`; else §3.8 of ch. 08 |
| `stato`, `update_tms`, `update_user` | `status`, `email_verified_at`, `approved_at`, `declined_at` | status table above |
| `create_tms` | `registered_at`, `created_at` | UTC |
| `update_tms` | `updated_at` | UTC |
| `codice_verifica` | — | not migrated; old links stop working (Q-30) |
| `create_user`, `update_user` | `legacy_payload`, optional `audit_log` rows with actor `IMPORT` | role names only |
| — | `source = LEGACY` | |

`spedizioni` (+ `dettaglio_colli`, `pallet`) → `shipment_order`:

| Column | Target | Rule |
|---|---|---|
| `id` | `legacy_id` | |
| `mittente_id` | `customer_id` | via `legacy_key_map` (always resolvable) |
| `stato`, `codice` | `status`, `code` / `temp_code` | status table; codes trimmed for matching, raw kept (DQ-29) |
| `ragione_sociale`, `indirizzo`, `citta`, `provincia`, `cap`, `telefono` | `recipient_*` | as customer fields; `telefono` NULL stays NULL |
| `email` | — (`legacy_payload`) | never `recipient_email` (DQ-23/36) |
| — | `sender_*` | snapshot of the customer row (DQ-14) |
| `codice_documento_trasporto` | `transport_document_code` | |
| `servizio_accessorio` | `accessory_service_id`, `legacy_service_raw` | by label (ignore case); id 0 → NULL; DQ-07 flag |
| `importo`, `importo_assicurato` | `amount`, `amount_raw` | §3.5 rule; DQ-13/31 |
| `importo_contrassegno` | `legacy_payload` | |
| `note` | `order_internal_note.body`; `customer_notes` | blank or "Nessuna nota presente" → none; else internal note (`imported_from_legacy`); also `customer_notes` **only** for `PENDING` orders with `update_user = 'CUSTOMER'` (never touched by staff) (proposal) |
| `tracking` | `tracking_state_id`, `legacy_tracking_raw` | by label → id 0–5 |
| `confirmation_ts` | `approved_at` | exact |
| `create_tms` | `submitted_at`, `created_at` | exact |
| `update_tms` | `updated_at`; `declined_at` for declined | |
| `dettaglio_colli.colli` | `package_count` | NULL for portal orders |
| `dettaglio_colli.peso`, `.volume`, `.pallet`, `.epal` | `legacy_payload` | checks only (DQ-28); totals recomputed from lines |
| — | `approved_by`, `declined_by` | NULL (the role is known, not the person) |
| whole rows | `legacy_payload` | `spedizioni` + `dettaglio_colli` + unit rows, verbatim |

`pallet` → `order_line`: per order, take units `ORDER BY pallet.id`; a run of consecutive units
equal on (`type`, `altezza`, `larghezza`, `lunghezza`, `peso`) becomes one line with `quantity` =
run length and `line_no` = 1, 2, … in order (inferred: units of one line were inserted
consecutively, OrderService.java:71-79, 106-108; two adjacent identical lines merge, totals
unaffected). `type` `PALLET` → `kind = GENERIC`, `EPAL` → `EPAL`; centimetres to integers (flag
non-integers); `weight_kg` = unit `peso`; line totals per chapter 08 §2.3.

Tracking events (per approved order; no invented intermediate states):

| Event | When | `occurred_at` | `time_is_estimated` |
|---|---|---|---|
| `PRESA`, source `LEGACY_IMPORT` | every `REGISTERED` order | `confirmation_ts` | false |
| current state, if not `PRESA` | `CONSEGNATO` | `delivery_ts` | false |
| | `TRANSITO` | `shipping_ts` | false |
| | `TERMINAL_DEST`, `ASSEGNATO`, `NON_GESTITO` | `update_tms` | true |

`users`, `user_session`: not migrated (counts in the report only). `dettaglio_colli_seq`:
ignored.

### 5.6 What changes in the migration plan (chapter 08 §4)

* **Option 1 is the plan, and its input is known**: `lsdb_<timestamp>.sql.gz` from
  `scripts/backup.sh` (§1.1). Restore it into an isolated MySQL 8.0.35 with the same collation
  (never into the target database), check drift (§1.8), then let the importer read the restored
  database through a read-only JDBC user, or export the canonical snapshot with views such as:

  ```sql
  CREATE VIEW v_order AS
    SELECT o.*, d.id dc_id, d.colli, d.pallet dc_pallet, d.epal dc_epal, d.peso dc_peso, d.volume dc_volume
    FROM spedizioni o LEFT JOIN dettaglio_colli d ON d.spedizione_id = o.id;
  CREATE VIEW v_unit AS
    SELECT p.id, d.spedizione_id, p.type, p.altezza, p.larghezza, p.lunghezza, p.peso
    FROM pallet p JOIN dettaglio_colli d ON d.id = p.dettaglio_colli_id;
  ```
* **Secrets in the dump**: `users` (bcrypt hashes) and `user_session` (live refresh tokens).
  Drop both tables right after restore in every non-production copy; the anonymised staging copy
  also replaces names, e-mails, phones, addresses and notes.
* **Time**: read every `datetime(6)` as UTC (§1.1); verify once with an order whose submission
  time the owner knows (or an e-mail timestamp).
* **Option 2 (API) is much weaker than chapter 08 says**: EP-09/EP-10 return only orders created
  in the last 6 months; declined orders and unconfirmed or declined customers are invisible;
  EP-17 gives units, not lines; dates come as epoch decimals. `POST /mono/cms/order?email=…`
  (ADMIN) returns **all** orders of one customer, any state, no window
  (ls@migration:M/business/service/crud/SpedizioneService.java:261-266) and would have to be
  called per customer of EP-04/EP-05. Keep option 2 only as an emergency fallback and state its
  gaps in the report.
* **Importer steps** (replacing ch. 08 §4.5 steps 2–6): (1) seed enumerations from §2.2 and
  assert that every stored label belongs to them (blocking otherwise); (2) customers by
  `soggetti.id` with the status table; (3) orders by `spedizioni.id`, customer via
  `mittente_id`; (4) lines rebuilt from units; (5) notes; (6) tracking events per §5.5.
* **Blocking issues**: DQ-01 (duplicate e-mails), empty codes (DQ-29), unknown enum labels, and
  any violated assertion (duplicate codes, orphan orders, order without aggregate). DQ-08, DQ-10
  and DQ-12 stop being blocking categories.
* **Reconciliation report** (pass = equal): customers per (`stato`, DQ-25 class) → per target
  status; orders per `stato` × `tracking`; `COUNT(pallet)` = Σ `order_line.quantity`, also per
  type; Σ `pallet.peso` = Σ line weights (± 0.01 kg); Σ
  `ROUND(COALESCE(importo, importo_assicurato), 2)` over approved orders = Σ `amount`;
  `dettaglio_colli` checks of DQ-28; counts of `users` and `user_session` (not imported).
* **Cut-over** (ch. 08 §4.7): the legacy write paths to block also include
  `/mono/cms/order/update`, `/mono/utils/**` and `GET /mono/email-confirm` (it writes);
  `/mono/auth/refresh-token` only touches `user_session`.

### 5.7 Worked example, corrected (synthetic values)

Legacy rows (abridged): `spedizioni {id: 118, mittente_id: 7, stato: "REGISTERED", codice:
"000000000118", tracking: "Presa in carico", servizio_accessorio: "Nessun servizio accessorio",
importo: 180.5, importo_assicurato: 180.5, importo_contrassegno: 180.5, note: "", create_tms:
"2024-09-02 08:14:03.512000", confirmation_ts: "2024-09-02 13:40:11.004000"}`, `dettaglio_colli
{colli: null, pallet: 0, epal: 2, peso: 840.0, volume: 3.0}`, units `pallet` ×2 `{type: "EPAL",
altezza: 150.0, larghezza: 80.0, lunghezza: 120.0, peso: 420.0}`.

Target: `shipment_order {legacy_id: 118, status: APPROVED, code: "000000000118", temp_code:
null, amount: 180.50, amount_raw: "importo=180.5; importo_assicurato=180.5",
accessory_service_id: null, tracking_state_id: 1, submitted_at: 2024-09-02T08:14:03.512Z,
approved_at: 2024-09-02T13:40:11.004Z, package_count: null, total_weight_kg: 840.00,
total_volume_m3: 2.88}`; one `order_line {line_no: 1, kind: EPAL, quantity: 2, …}` (legacy
`volume` 3.0 kept in `legacy_payload`); one `tracking_event {state_id: 1, occurred_at:
2024-09-02T13:40:11.004Z, time_is_estimated: false}`; no internal note (empty).

### 5.8 Contract unknowns settled (chapter 02 §11)

| CU | Answer |
|---|---|
| CU-01 | label, string (§3.7) |
| CU-02 | 200 "Non gestito" for unknown and TEMP codes; 400 for empty; case-insensitive (collation); no trim (§3.7) |
| CU-03 | `NEW` (no row or unconfirmed or declined), `PENDING`, `REGISTERED`, `DECLINED`; malformed e-mail → 400 |
| CU-04 | EP-03: 200 new/existing NEW, 409 registered, 500 pending; limits §4.1 |
| CU-05 | yes: EP-08 refuses unknown or non-REGISTERED e-mails (500) |
| CU-07 | values §2.2; id = number; `name` present; id 0 = none |
| CU-08 | `stato`/`tracking`/`servizioAccessorio` labels; `importo` is its own column; extra fields: amounts, three timestamps |
| CU-09 | §4.2 |
| CU-10 | same column |
| CU-11 | EP-14 answers the updated `Spedizione`; unknown code 404 |
| CU-15 | no ORDER BY (undefined; physical order in practice, inferred) |
| CU-16 | decline resets to NEW (since 2024-07-22); re-registration allowed |
| CU-17 | §3.3 |
| CU-18 | §3.4; no history |
| CU-19 | labels `PALLET`/`EPAL`; numbers as DOUBLE; totals computed |
| CU-20 | no: stored in `spedizioni.email`, used in no logic |
| CU-22 | yes: approval needs `PENDING` |
| CU-24 | `GET /mono/email-confirm?email=…&verificationCode=<UUID>`, no expiry, reusable while `NEW` |

### 5.9 Still unknown — verify on the dump or with the owner

* Real schema and collation (§1.8 queries); time zone check (§5.6).
* Distribution of `stato`/`tracking`/service labels; number of DQ-01 collisions; presence of
  `DECLINED` rows.
* Deployment dates of each backend, portal and CMS build (needed for DQ-07 and the amount
  periods); the source of the production CMS (CU-21).
* The external `config/application-prod.yml` (effective `ddl-auto`).
* Meaning of the amount and VAT (Q-05); whether definitive codes come from an external system
  (Q-06).

## 6. Data-quality risks visible in the code

| Risk | Cause in the code | Expect in the data | Source |
|---|---|---|---|
| Duplicate customers per e-mail | registration keyed on (e-mail, ragione sociale); unique key includes a random UUID; double-click races | 2+ rows with the same e-mail (any case); those customers are locked out | SoggettoService.java:99-104; SoggettoEntity.java:16 |
| E-mail case and spaces | no normalisation; case-insensitive collation | mixed case kept; first spelling wins | SoggettoFactory.java:33-43 |
| Ambiguous `NEW` | decline resets to `NEW` | unconfirmed and declined mixed | CustomerService.java:70-77 |
| Stale registration data | re-registration returns the old row unchanged | registration data older than the customer thinks | SoggettoService.java:102-104 |
| Lines lost | quantity expanded into units | regrouping needed | OrderService.java:71-79 |
| Coarse totals | volume rounded to integer m³; `colli` NULL; `pallet` excludes EPAL | totals not reusable | OrderService.java:80-104 |
| Default pallet type | portal sends 1 = EPAL when no type chosen | EPAL over-represented | ls-order@dev:js/function/data-handler.js:23-26 |
| Default service | portal sent 1 = "Prenotazione telefonica" for "none" (May 2024) | false phone-booking services | ls-order@e183526:js/function/data-handler.js:58-61 |
| Amount columns by period | approval contract changed 4 times | `importo` NULL on older approvals; `contrassegno` = copy | §3.5 |
| Floating-point amounts | `Double` | binary fractions (180.3 is held as 180.3000000000000113…; round half-up to 2 decimals when converting to `numeric`); values with > 2 decimals possible | SpedizioneEntity.java:50-55 |
| Codes not normalised | no trim, empty accepted | spaces, tabs, empty | SpedizioneService.java:152, 163 |
| TEMP code on an approved order | EP-11 without `req` promotes without a code | rare | SpedizioneService.java:150 |
| Notes overwritten | one shared column; placeholder literal may be saved by the CMS | customer text replaced; "Nessuna nota presente" stored | OrderService.java:170-203 |
| Timestamps with odd meaning | `shipping_ts` set on any first change; `delivery_ts` never cleared | §3.4 | SpedizioneService.java:245-256 |
| Free-text fields | no format rules for province, CAP (1–9), DDT | variants | CreateDestinatarioReq.java:20-46 |
| Phones | `\d{10}` until 2025-01-07, loose pattern after | two populations | ls@91cf025 |
| HTML in text | no sanitising anywhere | possible script payloads (CMS-04) | — |
| Hard deletes | admin tools | id gaps, reused codes possible | AdminToolsService.java:45-135 |
| Schema drift | `ddl-auto: update` | extra columns, unexpected NOT NULL | §1.8 |
| Undefined list order | no ORDER BY | CMS order not stable | SpedizioneRepository.java:39-80 |
| Data exposure (security lens, see the security notes) | `/api/order/pending`, `/active`, `/delivered`, `/download-report` carry no `@PreAuthorize`; the URL rule `/mono/cms/**` likely never matches because the context path `/mono` is not part of the servlet path (inferred) | personal data may have been read by third parties | OrderController.java:55-78; WebController.java:39-54; WebSecurityConfig.java:39-42 |

## 7. Answers to the open questions

| Q | Answer from the code |
|---|---|
| Q-03 | `status` is the **label** (`"Presa in carico"`); unknown codes answer **200 `{"status":"Non gestito"}`** (no 404); empty code 400; matching case-insensitive via the MySQL collation, no trimming (§3.7) |
| Q-05 | Stored as `DOUBLE`: `importo` (typed at approval since 2024-07-16, > 0), `importo_assicurato` (typed before; still accepted), `importo_contrassegno` (copy). Lists show `importo`; detail falls back to `importo_assicurato`. No VAT data; meaning still for the owner (§3.5) |
| Q-06 | TEMP = `"TEMP"` + random UUID (40 chars), set by the backend at submission, replaced at approval. Definitive code typed by staff, any format, unique (case-insensitive by collation), not trimmed; mostly 12-digit numbers in the logs (§3.3) |
| Q-09 | Since 2024-07-22 a decline keeps the row and resets it to `NEW` (orders: also tracking "Non gestito"); before, `DECLINED`. A declined customer can register again (same row) or re-use the old link (§3.1, §3.2) |
| Q-25 | Six values, ids 0–5: `Nessun servizio accessorio` (0 = none), `Prenotazione telefonica`, `Sponda idraulica`, `Segnalazione merce lunga`, `Facchinaggio`, `GDO`; the DB stores the label (§2.2) |
| Q-26 | Same column: the CMS notes are the customer's order note, overwritten in place (§3.6) |
| Q-28 | Yes: approval requires `PENDING` (e-mail confirmed); unconfirmed customers never reach the CMS lists (§3.1) |
| Q-38 | TEMP codes are looked up like any code and answer 200 "Non gestito", indistinguishable from unknown codes (§3.7) |
