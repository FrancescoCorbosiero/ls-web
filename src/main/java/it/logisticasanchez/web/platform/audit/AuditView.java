package it.logisticasanchez.web.platform.audit;

import java.time.Instant;
import java.util.List;

/**
 * An entry of the audit log as the viewer shows it (ENH-13, ADR 0040): who, when, the action, the
 * entity by id and reference, and the change as the log row holds it — field names with their
 * non-personal values. The personal values ({@code audit_log_detail}) are never read here;
 * {@code personalDetail} only says that some were recorded apart.
 */
public record AuditView(
        long id,
        Instant occurredAt,
        AuditActorType actorType,
        Long actorId,
        String action,
        String entityType,
        Long entityId,
        String entityRef,
        List<Change> changes,
        boolean personalDetail) {

    /** One field of the change: «-» for a value that is absent on that side. */
    public record Change(String field, String before, String after) {}
}
