# 0040 · The audit log viewer (ENH-13)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-13 (flag `admin.audit`); touches NFR-02, NFR-03 · **Owner:** request of 2026-10-26 («procedi con quelli che puoi fare ora»)

## Context

Chapter 09: «Audit log viewer (the log itself is written from M1).» Since M1 every staff,
customer and system action writes `audit_log` (ids, action, entity, the changed field names with
their non-personal values) and, apart, `audit_log_detail` (the personal values), which the erasure
(NFR-03) and the retention job (Q-18) delete (ADR 0020). The parity matrix asks for «filters by
user, action, date, showing ids and field names only (no personal values)».

## Decision

* **«Registro delle attività»** under «Impostazioni» (`/gestione/impostazioni/registro/`),
  **ADMIN only** like the rest of «Impostazioni»: the log, newest first, 50 entries a page.
* **Filters:** who (all, all staff, one staff user, the customers, the system), action (the
  actions found in the log), object (the entity types found in the log) and its id, from and to
  (days in Europe/Rome, both included). Unknown values are ignored.
* **Each entry:** date and time (Europe/Rome), who (the staff username; «Cliente · accesso N»
  for a customer login; «Sistema»), the action code, the object (type, id, reference; a link to
  the customer or order page), and the change as the log row holds it — field names with their
  non-personal values. **The personal values (`audit_log_detail`) are never read**: an entry that
  has them says «Dati personali registrati a parte: non si mostrano.».
* `AuditService.search` (platform) reads the log with plain SQL; the viewer is
  `BackofficeAuditController`. No export, no editing: the log stays append-only (ADR 0020).

### Flag off

«Impostazioni» has no link and the viewer answers 404 (`AuditViewerOffIT`); the log is written as
in M1. The flag is «Disponibile» in «Impostazioni».

## Consequences

* The owner sees who approved, changed or exported what, without a database client.
* Action codes are shown as they are (`order.approve`): stable, searchable, and no translation
  table to keep in step with the code.
* New Italian texts (page, filters, columns) wait for the owner's review.

## References

Chapter 09 ENH-13, NFR-02, NFR-03; ADR 0020 (audit tables), 0024 (privacy), 0025 (flags). Code:
`platform.audit.AuditService#search`, `AuditQuery`, `AuditView`,
`backoffice.BackofficeAuditController`, `templates/backoffice/audit.html`. Tests: `AuditViewerIT`,
`AuditViewerOffIT`, `e2e/tests/m2.spec.ts` [ENH-13].
