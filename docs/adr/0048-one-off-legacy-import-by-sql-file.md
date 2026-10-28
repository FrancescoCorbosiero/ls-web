# 0048 · The legacy data move once, through a generated SQL file

* **Status:** Accepted
* **Date:** 2026-10-28
* **Requirement IDs:** MIG-01, MIG-02 (chapter 09; carried out outside the application) ·
  **Questions:** Q-55 (reopened by the owner), Q-05, Q-24, Q-26, Q-30 · **Owner:** decision of
  2026-10-28 («riapro Q-55, migrazione una tantum dei dati legacy tramite file SQL, senza
  scrivere un importatore nell'applicativo»)

## Context

On 2026-10-17 the owner decided that no legacy data would be migrated (Q-55): the schema has no
legacy columns and the cut-over was DNS plus an empty database (ADR 0020, ADR 0022). On
2026-10-28 the owner reopened Q-55: every customer of the legacy `soggetti` table and the orders
of the last six months (`spedizioni` created from 2026-04-28 00:00 UTC, with their
`dettaglio_colli` and `pallet` rows) move into the new app once, through one SQL file loaded
with `psql`, following 12b §5.5. The owner gave a real dump of `lsdb` for the work, as an
authorised exception to CLAUDE.md, kept in `.local/legacy/` (gitignored). `users`,
`user_session` and `dettaglio_colli_seq` are not moved.

## Decision

* **A generator outside the application.** `tools/legacy-import/generate_import_sql.py` reads
  the mysqldump directly (`mysqldump.py`: no MySQL server needed) and writes the SQL file; the
  application gains no importer, module, table or column. The generator holds rules only; the
  file it writes holds real data and stays under `.local/` or on the server (`*.sql` is refused
  by `tools/ci/guard-tracked-files.sh`). Its report on stdout carries counts and legacy ids only.
  `test_generate_import_sql.py` pins the mapping on a synthetic dump.
* **The file.** One transaction (`BEGIN`…`COMMIT`, `ON_ERROR_STOP`); the rows travel in
  temporary tables (`ON COMMIT DROP`), then are inserted in dependency order with the ids the
  identity sequences hand out, never fixed ids, so the file also loads into a database that
  already holds data. Guards: it refuses to run twice (an `audit_log` row with action
  `legacy.import`, actor `SYSTEM`, carries the counts, never personal data); it refuses when a
  definitive or temporary code is already used, or when a legacy e-mail is the login of another
  customer; a legacy customer whose e-mail is already a customer of the new app is not created
  again, its orders join that customer. At the end it asserts the written counts, the order
  totals against their lines and every identity sequence against its table's largest id.
* **The 12b §5.5 mapping**, with these choices where the schema asks for more than §5.5 says:

  | Need of the schema | Choice |
  |---|---|
  | `customer.source` has no `LEGACY` | `CUSTOMER_AREA`; `BACKOFFICE` for rows the legacy staff created (`create_user` `ADMIN`/`ROLE_ADMIN`) |
  | `REGISTERED` created by staff has no approval time (`update_user` NULL) | `approved_at` = registration time; `email_verified_at` = `approved_at` (estimated, as §5.5 does for every `REGISTERED`) |
  | `customer_user` | one `OWNER` per customer with the customer's e-mail, no password: the first access is the e-mailed link (ADR 0032); the legacy confirmation codes are not moved (Q-30) |
  | DQ-01, one e-mail on several rows | merged by rule only when every row is an unconfirmed `NEW` without orders (the legacy could not use them): the latest registration is kept; any other duplicate stops the generator |
  | `shipment_order.temp_code` is required, and an approval overwrote the legacy TEMP code | `LEGACY-<legacy id>`: unique, says where the order came from; pending and declined orders keep their legacy TEMP code |
  | `transport_document_code` is required (Q-24) | the legacy DDT trimmed; `N/D` when it is missing |
  | `amount_vat_included` | `false`, the Q-05 default of `ls.orders.amount.vat-included-default` |
  | `order_internal_note.updated_by` is required | the first ADMIN (oldest `staff_user`): the legacy stored a role, never a person; create the ADMIN (`create-staff-user`) before loading |
  | `tracking_event.source` has no `LEGACY_IMPORT` | `APPROVAL` for the `PRESA` event at `confirmation_ts`, `STAFF` for the current state; `staff_user_id` NULL; `recorded_at` is the load time |
  | `tracking_event` has no «estimated» flag | `ASSEGNATO`, `TERMINAL_DEST` and `NON_GESTITO` take `update_tms`, listed as estimated in the generator's report |
  | phones | `ItalianFormats.normalisePhone` (E.164, `+39` when no prefix); a value that is not a phone stays as typed and is reported |
  | `province_code` | NULL, as the app writes it |

  Every other column follows §5.5: e-mails trimmed and lower-cased, text trimmed with inner
  spaces collapsed, CAPs must be five digits (otherwise the generator stops), the amount is
  `round_half_up(COALESCE(importo, importo_assicurato), 2)`, lines are rebuilt from consecutive
  equal units, totals recomputed per line with `OrderTotals` (the legacy `volume` rounded to whole
  cubic metres is not reused), the shared legacy note becomes the internal note (the customer's
  note only for a `PENDING` order never touched by staff), «Nessuna nota presente» is no note,
  `spedizioni.email` is never the recipient's e-mail, every legacy datetime is UTC.
* **No e-mail is sent**: the file writes no `email_outbox` row.

## Consequences

* MIG-01 and MIG-02 are met once, outside the application; the parity matrix keeps them
  «blocked (Q-55)» because no test of the application covers them. MIG-03's cut-over adds two
  steps: create the ADMIN, then load the file generated from the dump taken at the freeze.
* The generator must be re-run on the dump taken at the cut-over: a file made from an earlier dump
  lacks the later orders and registrations.
* The imported customers sign in with the e-mailed link the first time, then may set a password.
* The imported orders are ordinary rows: staff may edit, cancel or change their tracking; the
  `LEGACY-` temporary code tells them apart.

## Alternatives considered

* **An importer in the application** (chapter 08 §4, the former MIG-01 plan): refused by the owner.
* **Legacy columns** (`legacy_id`, `legacy_payload`): not added; the legacy id lives only in the
  `LEGACY-` temporary code, the raw rows stay in the dump.
* **Restoring the dump into MySQL first** (12b §5.6): no MySQL server in this environment; the
  generator reads the dump as `mysqldump` writes it, which gives the same rows.

## References

* Chapter 12b §5.5 (mapping), §5.2–5.3 (DQ items), §3.4–3.6; chapter 11 Q-55; ADR 0020, 0022, 0028,
  0032; `tools/legacy-import/README.md`.
