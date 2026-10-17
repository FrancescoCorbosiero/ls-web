package it.logisticasanchez.web.platform.audit;

/** Who performed an audited action (chapter 08 §2.3 {@code audit_log.actor_type}). */
public enum AuditActorType {
    STAFF,
    CUSTOMER,
    SYSTEM
}
