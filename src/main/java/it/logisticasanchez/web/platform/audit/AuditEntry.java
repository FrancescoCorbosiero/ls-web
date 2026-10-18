package it.logisticasanchez.web.platform.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a module records about an action (ADR 0020, NFR-02). {@link #change} carries field names
 * with non-personal values (ids, states, amounts, flags); {@link #personal} carries the personal
 * values of the same change, stored apart in {@code audit_log_detail} so the erasure tool and
 * the retention job can remove them.
 */
public final class AuditEntry {

    private final AuditActorType actorType;
    private final Long actorId;
    private final String action;
    private String entityType;
    private Long entityId;
    private String entityRef;
    private Map<String, Object> before;
    private Map<String, Object> after;
    private Map<String, Object> personalBefore;
    private Map<String, Object> personalAfter;

    private AuditEntry(AuditActorType actorType, Long actorId, String action) {
        this.actorType = Objects.requireNonNull(actorType, "actorType");
        this.actorId = actorId;
        this.action = Objects.requireNonNull(action, "action");
    }

    /** An action such as {@code order.approve} by a staff user, a customer user or the system (null id). */
    public static AuditEntry of(AuditActorType actorType, Long actorId, String action) {
        return new AuditEntry(actorType, actorId, action);
    }

    public static AuditEntry system(String action) {
        return new AuditEntry(AuditActorType.SYSTEM, null, action);
    }

    /** The entity touched: type, internal id and a non-personal reference (an order code, a key). */
    public AuditEntry entity(String entityType, Long entityId, String entityRef) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.entityRef = entityRef;
        return this;
    }

    /** Non-personal before/after values; either side may be null (creation, deletion). */
    public AuditEntry change(Map<String, ?> before, Map<String, ?> after) {
        this.before = copy(before);
        this.after = copy(after);
        return this;
    }

    /** Personal before/after values, stored in {@code audit_log_detail} only. */
    public AuditEntry personal(Map<String, ?> before, Map<String, ?> after) {
        this.personalBefore = copy(before);
        this.personalAfter = copy(after);
        return this;
    }

    private static Map<String, Object> copy(Map<String, ?> values) {
        return values == null ? null : new LinkedHashMap<>(values);
    }

    public AuditActorType actorType() {
        return actorType;
    }

    public Long actorId() {
        return actorId;
    }

    public String action() {
        return action;
    }

    public String entityType() {
        return entityType;
    }

    public Long entityId() {
        return entityId;
    }

    public String entityRef() {
        return entityRef;
    }

    public Map<String, Object> before() {
        return before;
    }

    public Map<String, Object> after() {
        return after;
    }

    public Map<String, Object> personalBefore() {
        return personalBefore;
    }

    public Map<String, Object> personalAfter() {
        return personalAfter;
    }

    public boolean hasPersonalValues() {
        return personalBefore != null || personalAfter != null;
    }
}
