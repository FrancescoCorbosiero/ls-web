package it.logisticasanchez.web.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The personal values of an audited change (table {@code audit_log_detail}, chapter 08 §2.3),
 * kept apart from {@link AuditLog} so the erasure tool (NFR-03) and the retention job (Q-18) can
 * delete them in a maintenance transaction (ADR 0020).
 */
@Entity
@Table(name = "audit_log_detail")
public class AuditLogDetail {

    @Id
    @Column(name = "audit_log_id", nullable = false)
    private Long auditLogId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before", updatable = false)
    private String before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after", updatable = false)
    private String after;

    protected AuditLogDetail() {}

    public AuditLogDetail(Long auditLogId, String beforeJson, String afterJson) {
        this.auditLogId = Objects.requireNonNull(auditLogId, "auditLogId");
        this.before = beforeJson;
        this.after = afterJson;
    }

    public Long getAuditLogId() {
        return auditLogId;
    }

    public String getBefore() {
        return before;
    }

    public String getAfter() {
        return after;
    }
}
