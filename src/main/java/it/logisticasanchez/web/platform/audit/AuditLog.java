package it.logisticasanchez.web.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An append-only audit entry (table {@code audit_log}, chapter 08 §2.3; NFR-02). The row holds
 * ids and non-personal values only; personal values of a change go to {@link AuditLogDetail}.
 * The database refuses UPDATE and DELETE (ADR 0020), so the entity exposes no setters.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_type", nullable = false, updatable = false, length = 10)
    private AuditActorType actorType;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "action", nullable = false, updatable = false, length = 60)
    private String action;

    @Column(name = "entity_type", updatable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private Long entityId;

    @Column(name = "entity_ref", updatable = false, length = 100)
    private String entityRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before", updatable = false)
    private String before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after", updatable = false)
    private String after;

    @Column(name = "ip_hash", updatable = false, length = 64)
    private String ipHash;

    @Column(name = "user_agent", updatable = false, length = 255)
    private String userAgent;

    @Column(name = "request_id", updatable = false, length = 64)
    private String requestId;

    protected AuditLog() {}

    private AuditLog(Builder builder) {
        this.occurredAt = builder.occurredAt;
        this.actorType = builder.actorType;
        this.actorId = builder.actorId;
        this.action = builder.action;
        this.entityType = builder.entityType;
        this.entityId = builder.entityId;
        this.entityRef = builder.entityRef;
        this.before = builder.before;
        this.after = builder.after;
        this.ipHash = builder.ipHash;
        this.userAgent = builder.userAgent;
        this.requestId = builder.requestId;
    }

    /** Starts an entry for an action such as {@code order.approve}. */
    public static Builder of(AuditActorType actorType, String action) {
        return new Builder(actorType, action);
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public String getEntityRef() {
        return entityRef;
    }

    public String getBefore() {
        return before;
    }

    public String getAfter() {
        return after;
    }

    public String getIpHash() {
        return ipHash;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getRequestId() {
        return requestId;
    }

    /** Builds an immutable entry; JSON values are passed already serialised. */
    public static final class Builder {
        private Instant occurredAt = Instant.now();
        private final AuditActorType actorType;
        private Long actorId;
        private final String action;
        private String entityType;
        private Long entityId;
        private String entityRef;
        private String before;
        private String after;
        private String ipHash;
        private String userAgent;
        private String requestId;

        private Builder(AuditActorType actorType, String action) {
            this.actorType = Objects.requireNonNull(actorType, "actorType");
            this.action = Objects.requireNonNull(action, "action");
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            return this;
        }

        public Builder actor(Long actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder entity(String entityType, Long entityId, String entityRef) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.entityRef = entityRef;
            return this;
        }

        public Builder change(String beforeJson, String afterJson) {
            this.before = beforeJson;
            this.after = afterJson;
            return this;
        }

        public Builder request(String ipHash, String userAgent, String requestId) {
            this.ipHash = ipHash;
            this.userAgent = userAgent;
            this.requestId = requestId;
            return this;
        }

        public AuditLog build() {
            return new AuditLog(this);
        }
    }
}
