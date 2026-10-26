# 0025 · Feature flags and the M2 scope rules; extension points for the "Later" backlog

* **Status:** Accepted
* **Date:** 2026-10-17 (table, seeds and service contract; each M2 flag group adds a dated line when Phase 5 builds it)
* **Requirement IDs:** ENH-01…ENH-22 (scope rules; the IDs turn green in Phase 5), NFR-02 (ADMIN-only toggle, audited) · **Questions:** Q-14, Q-32, Q-33, Q-34, Q-44 (nothing in M1, behind the M2 flags), Q-17 (ENH-09 is a flag, not an `ls.*` setting) · **Findings:** chapter 10 §2 "Feature flags"; chapter 09 §M2 and "Later"; `PROMPT.md` "Later" (extension points only)

## Context

Chapter 09 §M2 lists 22 enhancements, each behind a named flag, default off, built after the
cut-over (Phase 5); the owner enables them one by one. Chapter 10 §2 asks for a
`feature_flag` table read through a cached service, with environment overrides for tests,
an ADMIN toggle screen, and the rule that a disabled flag behaves exactly like M1. The
"Later" backlog (chat assistant, WhatsApp, driver app, RKS, e-invoicing…) must get clean
extension points and no code. Business rules that are not features (Q-28, Q-24 …) are
settings or ADRs, never flags.

## Decision

### The table and the seed (Phase 1, stage 2)

`feature_flag (key varchar(60) PK, enabled boolean NOT NULL DEFAULT false, requirement_id
varchar(8) NOT NULL, updated_by → staff_user, updated_at timestamptz)` (`V008`), with
`ck_feature_flag_key` (dotted lower-camel keys) and `ck_feature_flag_requirement_id`
(`ENH-nn`). `V009` seeds **every** flag key of chapter 09 §M2, all off, with its ENH id:

| Key | ENH | Key | ENH |
|---|---|---|---|
| `pricing.lists` | 01 | `pricing.marginView` | 11 |
| `pricing.suggestedAmount` | 02 | `documents` | 12 |
| `pricing.customerEstimate` | 03 | `admin.audit` | 13 |
| `pricing.publicEstimate` | 04 | `admin.enumerations` | 14 |
| `tracking.history`, `tracking.notifyCustomer` | 05 | `orders.bulkTracking` | 15 |
| `leads.inbox` | 06 | `customers.profileEdit` | 16 |
| `customers.addressBook`, `orders.repeat` | 07 | `site.noticeBanner` | 17 |
| `admin.dashboard`, `admin.exports` | 08 | `site.openNow` | 18 |
| `security.adminTotp` | 09 | `site.pickupCutoff` | 19 |
| `delivery.unifiedTimes` | 10 | `quotes.saved` | 20 |
| | | `documents.monthlyReport` | 21 |
| | | `site.liveCounters` | 22 |

`FeatureFlagSeedIT` parses the M2 table of chapter 09 and fails if the seeded keys differ
from it or any is on. New flags are added by a migration (one row each); a flag is never
created at run time.

### The service contract (Phase 1, stage 3 implements it in `platform.flags`)

* `FeatureFlags` — the only way code reads a flag: `boolean isEnabled(FlagKey key)`, where
  `FlagKey` is an enum of the seeded keys (a typo is a compile error; a key missing from
  the table is a start-up failure, so the seed and the enum are checked against each other
  by a test).
* Reads are **cached** per key for `ls.flags.cache-seconds` (default 30) and evicted at once
  on a toggle made through the same service, so a toggle shows within one request on the
  node that made it and within the cache period elsewhere; no per-request query.
* `toggle(FlagKey key, boolean enabled, StaffUserId actor)` — ADMIN only (method security),
  writes the row (`updated_by`, `updated_at`) and the audit entry `flag.toggle` with
  `before`/`after` `{ "enabled": … }` (NFR-02). The screen is `/gestione/impostazioni/`
  (`backoffice`, Phase 2), which lists every row with its ENH id.
* **Environment override for tests and local runs:** `ls.flags.override.<key>=true|false`
  (environment `LS_FLAGS_OVERRIDE_<KEY>`, dots as underscores) wins over the table for that
  key and is logged once at start-up; it is refused in the `prod` profile (the app fails to
  start), so production state lives only in the table. Integration tests set an override
  with `@TestPropertySource`/`@DynamicPropertySource` instead of writing the table.
* A disabled flag never leaves a trace: no new column is read, no new template fragment
  is rendered, no new route is mapped (routes of a flagged feature answer 404 when the flag
  is off, exactly as if the code did not exist), no e-mail is queued, no event subscriber
  runs. The M1 test suite runs with every flag off and is the proof.

### M2 scope rules (Phase 5, per flag group)

1. Each ENH sits behind its flag(s) above, checked at the single entry point of the feature
   (controller, template fragment, scheduler, event subscriber); no flag checks sprinkled
   through domain services.
2. No M1 contract changes: the compatibility API, the public pages' HTML allow-list (ADR
   0012), the e-mail catalogue and the database rows written by M1 code stay as they are;
   an enhancement adds tables or nullable columns through new migrations.
3. No JavaScript framework on public pages; M2 widgets are vanilla modules under the CSP.
4. Each flag group has its own tests with the flag on and the M1 tests with it off, tagged
   with its ENH IDs; the parity matrix shows an ENH as green only with the flag-on tests.
5. Confidential data (price lists, partner costs, ENH-01/ENH-11) never enter code, fixtures
   or seeds (ADR 0026).
6. Phase 5 appends a dated line per flag group to this ADR when the group is built.

### Extension points for the "Later" backlog (no code in M1/M2)

`platform` publishes **in-process domain events** (Spring application events, synchronous
after commit): `CustomerVerified`, `CustomerApproved`, `CustomerDeclined`, `OrderCreated`,
`OrderApproved`, `OrderDeclined`, `TrackingChanged`, `LeadStored`, each carrying ids and
codes only (no personal data), published by the owning module's service in the business
transaction. The backlog integrations (chat assistant, WhatsApp notifications, driver app,
RKS, e-invoicing) would subscribe there; **no subscriber code exists in M1 or M2**, and the
M1 notifications are not routed through the events (they are written to the outbox by the
services themselves, so an e-mail is never lost to a missing listener). The event classes
are added by stage 3 with the publisher and a test that each named event is published once.

### Flag groups built (dated lines, Phase 5 rule 6)

* **2026-10-26 — the settings screen (M2.1, item 0).** `/gestione/impostazioni/` shows for
  every flag a one-line Italian description of what it does (`backoffice.settings.flag.<key>`
  in the bundle, `FlagDescriptionsTest`) and whether it is built: `FlagKey.built()` is set in
  the code by the change that builds the feature, «Disponibile» when true, «Non ancora
  sviluppata» otherwise. Built flags come first, each group in ENH order. An ADMIN can turn on
  only a built flag: the screen disables the toggle and `BackofficeSettingsController` refuses
  a crafted request with a message, so the table never shows on a flag that changes nothing; a
  flag left on (by hand in the database) can always be turned off. The service contract above
  is unchanged (`FeatureFlagService.toggle` and the overrides still accept any key).
* **2026-10-26 — ENH-07, `customers.addressBook` and `orders.repeat` built** (M2.1, item 1; ADR
  0033): the address book of saved recipients (profile, customer and staff order forms) and
  «Ordina di nuovo» / «Ripeti ordine». Flag-on tests `AddressBookIT`, «flag off = M1»
  `AddressBookOffIT`, E2E `m2.spec.ts`.
* **2026-10-26 — ENH-05, `tracking.history` and `tracking.notifyCustomer` built** (M2.1, item 2;
  ADR 0034): the states with their dates and the delivery day on public tracking and in the
  customer area, EML-10 at each change to TRANSITO, TERMINAL_DEST or ASSEGNATO. Flag-on tests
  `TrackingHistoryIT`, «flag off = M1» `TrackingHistoryOffIT`, E2E `m2.spec.ts`.

## Consequences

* M1 ships with the complete flag table so the owner sees the whole M2 menu off from day
  one, and Phase 5 never needs a schema change to add a known flag.
* The enum `FlagKey` ties code to the seed: adding a flag means one migration row and one
  enum constant, checked by a test.
* Overrides make flag-on tests cheap and keep production honest (table only).
* Chapter 09 "Later" stays a backlog: the only code is the event publisher and the event
  records; nothing listens.

## References

* Chapter 09 §M2 (the flag table), "Later"; chapter 10 §2 "Feature flags"; `PROMPT.md`
  "What to build" / "Later"; `docs/plan.md` §4.3 "Feature flags", §5 Phase 1 item 7, §8.1
  Q-14/Q-32/Q-33/Q-34/Q-44 row.
* ADR 0020 (table conventions), ADR 0002 (ADMIN-only routes), ADR 0026 (pricing, M2).
* `V008__feature_flags_and_audit.sql`, `V009__seeds.sql`; `platform.flags.FeatureFlag`,
  `FeatureFlagRepository`; `FeatureFlagSeedIT`, `PhaseOneEntitiesIT`; the screen:
  `BackofficeSettingsController`, `backoffice/settings.html`, `BackofficeSettingsIT`,
  `FlagDescriptionsTest`.
