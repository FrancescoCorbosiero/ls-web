package it.logisticasanchez.web.notifications;

import it.logisticasanchez.web.platform.config.NotificationsProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the due outbox rows (chapter 10 §2, ADR 0015): one transaction per row, so a failure
 * never touches another row or the business action that queued it; exponential backoff
 * ({@code backoff-base × 2^attempts}, capped at {@code backoff-max}); dead ({@code FAILED})
 * after {@code max-attempts}; recipients refused by the {@link RecipientPolicy} are dropped
 * and logged by id. The raw token is decrypted only here, substituted into the bodies and
 * wiped with the reference after delivery.
 */
@Component
public class OutboxWorker {

    static final String DROPPED = "DROPPED_RECIPIENT_NOT_ALLOW_LISTED";
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final EmailOutboxRepository outbox;
    private final MailSender sender;
    private final RecipientPolicy policy;
    private final TokenCipher cipher;
    private final NotificationsProperties.Worker settings;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OutboxWorker(
            EmailOutboxRepository outbox,
            MailSender sender,
            RecipientPolicy policy,
            TokenCipher cipher,
            NotificationsProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.outbox = outbox;
        this.sender = sender;
        this.policy = policy;
        this.cipher = cipher;
        this.settings = properties.worker();
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Processes one batch; returns the number of rows handled (sent, retried, dead or dropped). */
    public int runOnce() {
        Instant now = clock.instant();
        List<Long> due =
                transaction.execute(status -> outbox.findDue(now, PageRequest.of(0, settings.batchSize())).stream()
                        .map(EmailOutbox::getId)
                        .toList());
        int handled = 0;
        for (Long id : due) {
            try {
                transaction.executeWithoutResult(status -> process(id));
                handled++;
            } catch (RuntimeException e) {
                log.error("outbox row={} could not be processed", id, e);
            }
        }
        return handled;
    }

    private void process(long id) {
        EmailOutbox row = outbox.findById(id).orElse(null);
        if (row == null || row.getStatus() != EmailOutboxStatus.QUEUED) {
            return;
        }
        Instant now = clock.instant();
        if (!policy.allows(row.getRecipient())) {
            row.markAttemptFailed(now, DROPPED, null);
            log.warn("outbox row={} kind={} dropped: recipient not on the allow-list", id, row.getKind());
            return;
        }
        try {
            String messageId = sender.send(toOutbound(row));
            row.markSent(now, messageId);
            log.info("outbox row={} kind={} sent", id, row.getKind());
        } catch (MailSender.MailSendException | RuntimeException e) {
            int attempt = row.getAttempts() + 1;
            Instant retryAt = attempt >= settings.maxAttempts() ? null : now.plus(backoff(attempt));
            row.markAttemptFailed(now, e.getClass().getSimpleName() + ": " + safeMessage(e), retryAt);
            if (retryAt == null) {
                log.error("outbox row={} kind={} dead after {} attempts", id, row.getKind(), attempt, e);
            } else {
                log.warn("outbox row={} kind={} attempt {} failed, retry at {}", id, row.getKind(), attempt, retryAt);
            }
        }
    }

    private OutboundEmail toOutbound(EmailOutbox row) {
        String text = row.getBodyText();
        String html = row.getBodyHtml();
        if (row.getTokenCiphertext() != null) {
            String token = cipher.decrypt(row.getTokenCiphertext());
            text = text.replace(EmailMessage.TOKEN_PLACEHOLDER, token);
            html = html == null ? null : html.replace(EmailMessage.TOKEN_PLACEHOLDER, token);
        }
        return new OutboundEmail(row.getId(), row.getRecipient(), row.getReplyTo(), row.getSubject(), text, html);
    }

    Duration backoff(int attempt) {
        long seconds = settings.backoffBase().toSeconds() * (1L << Math.min(attempt - 1, 20));
        return Duration.ofSeconds(Math.min(seconds, settings.backoffMax().toSeconds()));
    }

    /** The exception message without any address or body that might be in it. */
    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return "";
        }
        return message.replaceAll("[^\\s@]+@[^\\s@]+", "<address>");
    }
}
