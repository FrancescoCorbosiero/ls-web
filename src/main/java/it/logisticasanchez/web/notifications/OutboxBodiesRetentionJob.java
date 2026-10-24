package it.logisticasanchez.web.notifications;

import it.logisticasanchez.web.platform.config.RetentionProperties;
import it.logisticasanchez.web.platform.retention.RetentionJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q-18 / chapter 10 §2: the bodies (and any token ciphertext) of a sent e-mail are removed
 * {@code ls.retention.outbox-bodies-days} after delivery; the metadata (kind, recipient,
 * subject, times) stays for the audit of what was sent. Runs in batches until nothing is left.
 */
@Component
public class OutboxBodiesRetentionJob implements RetentionJob {

    private static final int BATCH = 500;

    private final EmailOutboxRepository outbox;
    private final RetentionProperties retention;

    public OutboxBodiesRetentionJob(EmailOutboxRepository outbox, RetentionProperties retention) {
        this.outbox = outbox;
        this.retention = retention;
    }

    @Override
    public String name() {
        return "outbox-bodies";
    }

    @Override
    @Transactional
    public int run(Instant now) {
        Instant before = now.minus(Duration.ofDays(retention.outboxBodiesDays()));
        int purged = 0;
        List<EmailOutbox> batch;
        do {
            batch = outbox.findSentBeforeWithBodies(before, PageRequest.of(0, BATCH));
            for (EmailOutbox row : batch) {
                row.purgeBodies(now);
            }
            purged += batch.size();
        } while (batch.size() == BATCH);
        return purged;
    }
}
