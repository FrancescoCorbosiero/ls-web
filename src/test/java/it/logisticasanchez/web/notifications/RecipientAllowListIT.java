package it.logisticasanchez.web.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * NFR-04 (chapter 10 §3 "Staging e-mail"): outside production, with an allow-list configured, a
 * recipient not on it is dropped and logged (by row id, never by address) and a listed one is
 * sent.
 */
@Tag("NFR-04")
@SpringBootTest
@TestPropertySource(properties = "ls.notifications.allow-list=Titolare@ls.example, collega@ls.example")
class RecipientAllowListIT extends AbstractPostgresIT {

    @Autowired
    private NotificationService notifications;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private InMemoryMailSender sender;

    @Test
    void unlistedRecipientsAreDroppedAndLoggedListedOnesAreSent() {
        sender.clear();
        Logger logger = (Logger) LoggerFactory.getLogger(OutboxWorker.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            long dropped = notifications.enqueue(
                    EmailMessage.of("EML-03", "cliente.vero@ls.example", "Benvenuto", "testo", null));
            long sent =
                    notifications.enqueue(EmailMessage.of("EML-03", "titolare@ls.example", "Benvenuto", "testo", null));

            worker.runOnce();

            assertThat(sender.sent()).extracting(OutboundEmail::recipient).containsExactly("titolare@ls.example");
            EmailOutbox droppedRow = outbox.findById(dropped).orElseThrow();
            assertThat(droppedRow.getStatus()).isEqualTo(EmailOutboxStatus.FAILED);
            assertThat(droppedRow.getLastError()).isEqualTo(OutboxWorker.DROPPED);
            assertThat(outbox.findById(sent).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.SENT);
            String log = String.join(
                    "\n",
                    captured.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .toList());
            assertThat(log).contains("row=" + dropped).contains("dropped").doesNotContain("cliente.vero");
        } finally {
            logger.detachAppender(captured);
        }
    }
}
