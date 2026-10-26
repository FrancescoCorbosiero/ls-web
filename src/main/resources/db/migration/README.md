# Flyway migrations

Only the integrator creates migrations here (`docs/plan.md` §6): versions are strictly
sequential (`V001__…`, `V002__…`), forward-only, and a merged migration is never edited.
Conventions and the reasons behind the schema: `docs/adr/0020-data-model-conventions.md`;
feature flags: `docs/adr/0025-feature-flags.md`.

| Version | Area |
|---|---|
| `V001__conventions.sql` | the `set_updated_at()` trigger function and the conventions |
| `V002__staff_users.sql` | `staff_user` (never seeded, ADM-01) |
| `V003__customers.sql` | `customer`, `customer_pending_registration`, `customer_user`, `login_token` |
| `V004__tracking_enumerations.sql` | `tracking_state`, `accessory_service` |
| `V005__orders.sql` | `shipment_order` (`code varchar(255)`, plan D-09), `order_line`, `order_internal_note`, `tracking_event` |
| `V006__leads.sql` | `lead` |
| `V007__notifications_and_reports.sql` | `email_outbox`, `report_run` |
| `V008__feature_flags_and_audit.sql` | `feature_flag`, `audit_log`, `audit_log_detail`, the immutability trigger |
| `V009__seeds.sql` | the six tracking states, the six accessory services, the 25 flags (all off) |
| `V010__staff_setup_tokens.sql` | `staff_setup_token` (one-time set-password links, ADM-01, ADR 0003); `email_outbox.kind` also accepts `EML-S1` and the token ciphertext no longer requires a `login_token` reference |
| `V011__customer_user_pending_email.sql` | `customer_user.pending_email` (the e-mail change of CUS-06) |
| `V012__report_recipients_and_anonymisation.sql` | `report_recipient` (ADM-09 allow-list), `customer.anonymised_at` (NFR-03) |
| `V013__record_editing.sql` | `shipment_order` status `CANCELLED` with `cancelled_*`, `cancel_reason`, `edited_at`, `edited_by_type`; customers created by staff are `ACTIVE` before their e-mail is verified (ADR 0028) |
| `V014__remembered_sign_in.sql` | `remembered_sign_in`: the devices on which a customer stays signed in for 60 days after a sign-in link (owner decision of 2026-10-25, ADR 0031) |
| `V015__customer_password.sql` | `customer_user.password_hash`, `password_changed_at`: customers may sign in with e-mail and password (owner decision of 2026-10-25, ADR 0032) |
| `V016__customer_address_book.sql` | `customer_address`: the address book of saved recipients (ENH-07, flag `customers.addressBook`; ADR 0033) |

Rules for the next migration: no `legacy_*`, `*_raw` or `data_quality` column (owner
decision Q-55, no data migration); no `INSERT` into `staff_user`, `customer` or
`shipment_order` (tests fail); names unqualified by schema; `timestamptz` for time,
`numeric(10,2)` for money; a `COMMENT ON` for every table; CHECK constraints named
`ck_<table>_<rule>`, unique indexes `ux_<table>_<columns>`. After adding, renaming or
removing a migration run `./mvnw clean verify` (stale copies under `target/classes` would
otherwise be applied) and update `src/test/resources/schema/expected-columns.txt` and
`expected-constraints.txt`.
