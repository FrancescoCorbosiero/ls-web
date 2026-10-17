package it.logisticasanchez.web.platform.audit;

import org.springframework.data.jpa.repository.JpaRepository;

/** Personal values of audited changes; deleted only in a maintenance transaction (ADR 0020). */
public interface AuditLogDetailRepository extends JpaRepository<AuditLogDetail, Long> {}
