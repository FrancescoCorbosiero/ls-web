package it.logisticasanchez.web.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.auth.LoginToken;
import it.logisticasanchez.web.auth.LoginTokenRepository;
import it.logisticasanchez.web.platform.config.NotificationsProperties;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * NFR-04 (chapter 10 §2 "E-mail", ADR 0015): the outbox row is written with the raw token only
 * encrypted and the placeholder in the body; the worker decrypts, substitutes, sends through
 * the sink, marks SENT and wipes the token; a failure is retried with exponential backoff and
 * the row is dead ({@code FAILED}) after {@code max-attempts}; no business row is touched.
 */
@Tag("NFR-04")
@SpringBootTest
@Import(MutableClock.Configuration.class)
@TestPropertySource(properties = {"ls.notifications.worker.max-attempts=2", "ls.notifications.worker.backoff-base=30s"})
class OutboxWorkerIT extends AbstractPostgresIT {

    @Autowired
    private NotificationService notifications;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private LoginTokenRepository tokens;

    @Autowired
    private InMemoryMailSender sender;

    @Autowired
    private NotificationsProperties properties;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private MutableClock clock;

    private Instant now;

    @BeforeEach
    void reset() {
        // Other tests (stage 2 entity round-trips) leave queued rows in the shared test database.
        outbox.deleteAll();
        sender.clear();
        clock.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        now = clock.instant();
    }

    @Test
    void aSignInEmailIsStoredWithTheTokenEncryptedAndSentWithItSubstituted() {
        LoginToken token = tokens.save(
                LoginToken.forRegistration("a".repeat(64), "b".repeat(64), now.plus(Duration.ofMinutes(15))));
        String raw = "rawTokenValue-0123456789";
        long id = notifications.enqueue(EmailMessage.of(
                        "EML-01",
                        "cliente@ls.example",
                        "Accedi all'area clienti",
                        "Apri https://logisticasanchez.com/area-clienti/accesso?t=" + EmailMessage.TOKEN_PLACEHOLDER,
                        "<p><a href=\"https://logisticasanchez.com/area-clienti/accesso?t="
                                + EmailMessage.TOKEN_PLACEHOLDER + "\">Accedi</a></p>")
                .withTokenLink(token.getId(), raw));

        EmailOutbox stored = outbox.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(EmailOutboxStatus.QUEUED);
        assertThat(stored.getBodyText())
                .contains(EmailMessage.TOKEN_PLACEHOLDER)
                .doesNotContain(raw);
        assertThat(stored.getBodyHtml()).doesNotContain(raw);
        assertThat(stored.getTokenCiphertext()).isNotNull();
        assertThat(new String(stored.getTokenCiphertext(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(raw);
        assertThat(stored.getLoginTokenId()).isEqualTo(token.getId());

        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(sender.sent()).hasSize(1);
        OutboundEmail sent = sender.sent().get(0);
        assertThat(sent.recipient()).isEqualTo("cliente@ls.example");
        assertThat(sent.bodyText()).contains("?t=" + raw).doesNotContain(EmailMessage.TOKEN_PLACEHOLDER);
        assertThat(sent.bodyHtml()).contains("?t=" + raw);
        EmailOutbox after = outbox.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EmailOutboxStatus.SENT);
        assertThat(after.getSentAt()).isEqualTo(now);
        assertThat(after.getTokenCiphertext())
                .as("the token is wiped after delivery")
                .isNull();
        assertThat(after.getLoginTokenId()).isNull();
        assertThat(after.getProviderMessageId()).startsWith("memory-");
        assertThat(after.getAttempts()).isEqualTo(1);
        assertThat(tokens.findById(token.getId()))
                .as("the business row is untouched")
                .isPresent();
        tokens.delete(token);
    }

    @Test
    void failuresBackOffExponentiallyAndTheRowIsDeadAfterMaxAttempts() {
        long id = notifications.enqueue(
                EmailMessage.of("EML-08", "ufficio@ls.example", "Nuova richiesta", "testo", null));
        sender.failNext(5);

        assertThat(worker.runOnce()).isEqualTo(1);
        EmailOutbox first = outbox.findById(id).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(EmailOutboxStatus.QUEUED);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getNextAttemptAt()).isEqualTo(now.plus(Duration.ofSeconds(30)));
        assertThat(first.getLastError()).contains("MailSendException");

        assertThat(worker.runOnce()).as("not due yet").isZero();
        clock.advance(Duration.ofSeconds(31));
        assertThat(worker.runOnce()).isEqualTo(1);
        EmailOutbox dead = outbox.findById(id).orElseThrow();
        assertThat(dead.getStatus()).as("dead after max-attempts=2").isEqualTo(EmailOutboxStatus.FAILED);
        assertThat(dead.getAttempts()).isEqualTo(2);
        clock.advance(Duration.ofHours(1));
        assertThat(worker.runOnce()).as("a dead row is never retried").isZero();
        assertThat(sender.sent()).isEmpty();
        assertThat(worker.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(worker.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(worker.backoff(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(worker.backoff(30)).isEqualTo(properties.worker().backoffMax());
    }
}
