package it.logisticasanchez.web.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * NFR-02 (chapter 10 §4 "audit log of every staff action, without personal values"; ADR 0020):
 * the entry holds actor, action, entity and non-personal values; personal values go only to
 * {@code audit_log_detail}; the rows cannot be updated or deleted by the application.
 */
@Tag("NFR-02")
@SpringBootTest
class AuditServiceIT extends AbstractPostgresIT {

    @Autowired
    private AuditService audit;

    @Autowired
    private AuditLogRepository logs;

    @Autowired
    private AuditLogDetailRepository details;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void recordsIdsAndNonPersonalValuesInTheLogAndPersonalValuesInTheDetail() {
        long id = audit.record(AuditEntry.of(AuditActorType.STAFF, 3L, "customer.approve")
                .entity("customer", 42L, "CUST-42")
                .change(Map.of("status", "PENDING_APPROVAL"), Map.of("status", "ACTIVE"))
                .personal(Map.of("email", "mario.rossi@ls.example"), Map.of("email", "mario.rossi@ls.example")));

        AuditLog entry = logs.findById(id).orElseThrow();
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.STAFF);
        assertThat(entry.getActorId()).isEqualTo(3L);
        assertThat(entry.getAction()).isEqualTo("customer.approve");
        assertThat(entry.getEntityType()).isEqualTo("customer");
        assertThat(entry.getEntityId()).isEqualTo(42L);
        assertThat(entry.getEntityRef()).isEqualTo("CUST-42");
        assertThat(entry.getBefore()).contains("PENDING_APPROVAL").doesNotContain("@");
        assertThat(entry.getAfter()).contains("ACTIVE").doesNotContain("@");
        assertThat(entry.getOccurredAt()).isNotNull();
        AuditLogDetail detail = details.findById(id).orElseThrow();
        assertThat(detail.getAfter()).contains("mario.rossi@ls.example");
        assertThat(logs.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("customer", 42L))
                .extracting(AuditLog::getId)
                .contains(id);
    }

    @Test
    void systemEntriesHaveNoActorIdAndNoDetailRow() {
        long id = audit.record(AuditEntry.system("retention.run").change(null, Map.of("deleted", 12)));
        assertThat(logs.findById(id).orElseThrow().getActorId()).isNull();
        assertThat(details.findById(id)).isEmpty();
    }

    @Test
    void theRowsAreAppendOnly() {
        long id = audit.record(AuditEntry.system("test.immutable"));
        assertThatThrownBy(() -> jdbc.update("update audit_log set action = 'x' where id = ?", id))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log where id = ?", id))
                .hasStackTraceContaining("append-only");
        assertThat(logs.findById(id)).isPresent();
    }
}
