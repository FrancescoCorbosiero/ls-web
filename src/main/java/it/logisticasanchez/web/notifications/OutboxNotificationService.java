package it.logisticasanchez.web.notifications;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link NotificationService} over {@code email_outbox}; the raw token is stored only encrypted. */
@Service
public class OutboxNotificationService implements NotificationService {

    private final EmailOutboxRepository outbox;
    private final TokenCipher cipher;
    private final Clock clock;

    public OutboxNotificationService(EmailOutboxRepository outbox, TokenCipher cipher, Clock clock) {
        this.outbox = outbox;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public long enqueue(EmailMessage message) {
        EmailOutbox row = new EmailOutbox(
                message.kind(), message.recipient(), message.subject(), message.bodyText(), message.bodyHtml());
        row.queuedAt(clock.instant());
        row.setReplyTo(message.replyTo());
        if (message.tokenLink() != null) {
            row.attachToken(
                    message.tokenLink().loginTokenId(),
                    cipher.encrypt(message.tokenLink().rawToken()));
        }
        return outbox.save(row).getId();
    }
}
