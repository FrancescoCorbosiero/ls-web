package it.logisticasanchez.web.platform.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Audit entries; written through the {@code AuditService} of the {@code platform} module. */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, Long entityId);
}
