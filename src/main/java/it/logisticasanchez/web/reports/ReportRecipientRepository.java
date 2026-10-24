package it.logisticasanchez.web.reports;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReportRecipientRepository extends JpaRepository<ReportRecipient, Long> {

    Optional<ReportRecipient> findByEmail(String email);

    List<ReportRecipient> findAllByOrderByEmailAsc();
}
