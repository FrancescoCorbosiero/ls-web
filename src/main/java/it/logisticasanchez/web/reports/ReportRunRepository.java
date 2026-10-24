package it.logisticasanchez.web.reports;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReportRunRepository extends JpaRepository<ReportRun, Long> {

    List<ReportRun> findByCustomerIdOrderByIdDesc(long customerId);

    List<ReportRun> findTop20ByOrderByIdDesc();
}
