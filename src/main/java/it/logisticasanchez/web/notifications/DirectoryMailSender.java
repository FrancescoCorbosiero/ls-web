package it.logisticasanchez.web.notifications;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * The local sink: one RFC 5322 {@code .eml} file per delivery under
 * {@code ls.notifications.directory.path} ({@code .local/mail}, gitignored), openable with any
 * mail client. Nothing leaves the machine.
 */
public class DirectoryMailSender implements MailSender {

    private final Path directory;
    private final String from;
    private final Clock clock;

    public DirectoryMailSender(Path directory, String from, Clock clock) {
        this.directory = directory;
        this.from = from;
        this.clock = clock;
    }

    @Override
    public String send(OutboundEmail email) throws MailSendException {
        String messageId = UUID.randomUUID().toString();
        String boundary = "=_ls_" + messageId;
        ZonedDateTime now = ZonedDateTime.now(clock);
        StringBuilder eml = new StringBuilder();
        eml.append("From: ").append(from).append("\r\n");
        eml.append("To: ").append(email.recipient()).append("\r\n");
        if (email.replyTo() != null) {
            eml.append("Reply-To: ").append(email.replyTo()).append("\r\n");
        }
        eml.append("Subject: ").append(email.subject()).append("\r\n");
        eml.append("Date: ").append(now.format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\r\n");
        eml.append("Message-ID: <").append(messageId).append("@local>\r\n");
        eml.append("MIME-Version: 1.0\r\n");
        if (email.bodyHtml() == null) {
            eml.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n").append(email.bodyText());
        } else {
            eml.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n");
            eml.append("--").append(boundary).append("\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n");
            eml.append(email.bodyText()).append("\r\n");
            eml.append("--").append(boundary).append("\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n");
            eml.append(email.bodyHtml()).append("\r\n");
            eml.append("--").append(boundary).append("--\r\n");
        }
        String name = String.format(Locale.ROOT, "%s-%06d.eml", now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")), email.outboxId());
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name), eml.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MailSendException("cannot write " + name, e);
        }
        return messageId;
    }
}
