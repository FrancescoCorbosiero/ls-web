package it.logisticasanchez.web.notifications;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** The outbox; used only inside the {@code notifications} module (worker and retention job). */
public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {

    /** The rows the worker may send now, oldest first. */
    @Query("select e from EmailOutbox e where e.status = it.logisticasanchez.web.notifications.EmailOutboxStatus.QUEUED"
            + " and e.nextAttemptAt <= :now order by e.nextAttemptAt, e.id")
    List<EmailOutbox> findDue(Instant now, Pageable page);

    /** Sent rows whose bodies the retention job has not purged yet. */
    @Query("select e from EmailOutbox e where e.status = it.logisticasanchez.web.notifications.EmailOutboxStatus.SENT"
            + " and e.bodiesPurgedAt is null and e.sentAt < :before order by e.sentAt, e.id")
    List<EmailOutbox> findSentBeforeWithBodies(Instant before, Pageable page);

    long countByStatus(EmailOutboxStatus status);

    /** Rows addressed to, replying to, or mentioning in their bodies the (normalised) address: the erasure tool (NFR-03). */
    @Query("select e from EmailOutbox e where lower(e.recipient) = :email or lower(e.replyTo) = :email"
            + " or lower(e.bodyText) like :like or lower(e.bodyHtml) like :like")
    List<EmailOutbox> findByAddress(String email, String like);
}
