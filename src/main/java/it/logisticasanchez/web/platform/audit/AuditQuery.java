package it.logisticasanchez.web.platform.audit;

import java.time.Instant;

/**
 * The filters of the audit viewer (ENH-13): each null means any. {@code from} is inclusive,
 * {@code to} exclusive.
 */
public record AuditQuery(
        AuditActorType actorType,
        Long actorId,
        String action,
        String entityType,
        Long entityId,
        Instant from,
        Instant to) {

    public static AuditQuery any() {
        return new AuditQuery(null, null, null, null, null, null, null);
    }
}
