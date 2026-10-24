package it.logisticasanchez.web.notifications;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * The branded shape of every transactional e-mail (ADR 0015, chapter 09 "E-mails"): a plain-text
 * body and a minimal, inline-styled HTML body built from the same blocks — a title, paragraphs,
 * label/value tables, one button, the brand signature and the footer sentence. Modules compose
 * their messages with {@link #customer} (Reply-To the office, the "do not reply" footer replaced
 * by an invitation to answer) or {@link #office} (Reply-To the person the mail is about, when
 * known). The subject gets the legacy {@code "<brand> - "} prefix (Q-37: «Logistica Sanchez»).
 */
@Component
public class MailLayout {

    private final BusinessProperties business;

    public MailLayout(BusinessProperties business) {
        this.business = business;
    }

    /** The legacy subject form: {@code Logistica Sanchez - <subject>}. */
    public String subject(String subject) {
        return business.brandName() + " - " + subject;
    }

    /** An e-mail to a customer: Reply-To the office, footer inviting to answer. */
    public EmailMessage customer(String kind, String recipient, String subject, Content content) {
        String footer = "Questa email viene generata automaticamente dal servizio online di " + business.brandName()
                + ". Per qualsiasi domanda rispondi pure a questo messaggio o chiama il " + business.phone() + ".";
        return EmailMessage.of(kind, recipient, subject(subject), text(content, footer), html(content, footer))
                .withReplyTo(business.officeEmail());
    }

    /** An e-mail to the office mailbox; {@code replyTo} is the customer's address when there is one. */
    public EmailMessage office(String kind, String subject, Content content, String replyTo) {
        String footer = "Notifica automatica del servizio online di " + business.brandName() + ".";
        EmailMessage message = EmailMessage.of(
                kind, business.officeEmail(), subject(subject), text(content, footer), html(content, footer));
        return replyTo == null ? message : message.withReplyTo(replyTo);
    }

    private String text(Content content, String footer) {
        StringBuilder sb = new StringBuilder();
        sb.append(content.title).append("\n\n");
        for (Block block : content.blocks) {
            block.appendText(sb);
        }
        if (content.button != null) {
            sb.append(content.button.label())
                    .append(": ")
                    .append(content.button.url())
                    .append("\n\n");
        }
        sb.append(business.brandName()).append("\n\n").append(footer).append("\n");
        return sb.toString();
    }

    private String html(Content content, String footer) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"it\"><head><meta charset=\"utf-8\"><title>")
                .append(escape(content.title))
                .append("</title></head>")
                .append(
                        "<body style=\"margin:0;padding:0;background:#f4f6f8;font-family:Arial,Helvetica,sans-serif;color:#1f2933\">")
                .append(
                        "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\" style=\"padding:24px 12px\">")
                .append(
                        "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;width:100%;background:#ffffff;border-radius:8px\">")
                .append(
                        "<tr><td style=\"background:#04233b;color:#ffffff;padding:18px 24px;font-size:20px;font-weight:bold;border-radius:8px 8px 0 0\">")
                .append(escape(business.brandName()))
                .append("</td></tr>")
                .append("<tr><td style=\"padding:24px\">")
                .append("<h1 style=\"margin:0 0 16px;font-size:22px;color:#04233b\">")
                .append(escape(content.title))
                .append("</h1>");
        for (Block block : content.blocks) {
            block.appendHtml(sb);
        }
        if (content.button != null) {
            sb.append("<p style=\"margin:24px 0\"><a href=\"")
                    .append(escape(content.button.url()))
                    .append(
                            "\" style=\"display:inline-block;background:#f28c28;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:6px;font-weight:bold\">")
                    .append(escape(content.button.label()))
                    .append("</a></p>")
                    .append("<p style=\"margin:0 0 16px;font-size:13px;color:#52606d\">Oppure copia questo link: ")
                    .append(escape(content.button.url()))
                    .append("</p>");
        }
        sb.append("<p style=\"margin:16px 0 0\">")
                .append(escape(business.brandName()))
                .append("</p>")
                .append("</td></tr>")
                .append(
                        "<tr><td style=\"padding:16px 24px;font-size:12px;color:#7b8794;border-top:1px solid #e4e7eb\">")
                .append(escape(footer))
                .append("</td></tr>")
                .append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    /** The blocks of one e-mail, in order. */
    public static final class Content {
        private final String title;
        private final List<Block> blocks = new ArrayList<>();
        private Button button;

        private Content(String title) {
            this.title = Objects.requireNonNull(title, "title");
        }

        public static Content titled(String title) {
            return new Content(title);
        }

        public Content paragraph(String text) {
            blocks.add(new Paragraph(text));
            return this;
        }

        /** A block of label/value rows under a heading (for example «Dettaglio spedizione»). */
        public Content table(String heading, List<Row> rows) {
            blocks.add(new Table(heading, List.copyOf(rows)));
            return this;
        }

        public Content button(String label, String url) {
            this.button = new Button(label, url);
            return this;
        }
    }

    public record Row(String label, String value) {}

    private record Button(String label, String url) {}

    private sealed interface Block permits Paragraph, Table {
        void appendText(StringBuilder sb);

        void appendHtml(StringBuilder sb);
    }

    private record Paragraph(String text) implements Block {
        @Override
        public void appendText(StringBuilder sb) {
            sb.append(text).append("\n\n");
        }

        @Override
        public void appendHtml(StringBuilder sb) {
            sb.append("<p style=\"margin:0 0 16px;line-height:1.5\">")
                    .append(escape(text))
                    .append("</p>");
        }
    }

    private record Table(String heading, List<Row> rows) implements Block {
        @Override
        public void appendText(StringBuilder sb) {
            sb.append(heading).append("\n");
            for (Row row : rows) {
                sb.append("  ")
                        .append(row.label())
                        .append(": ")
                        .append(row.value() == null ? "-" : row.value())
                        .append("\n");
            }
            sb.append("\n");
        }

        @Override
        public void appendHtml(StringBuilder sb) {
            sb.append("<h2 style=\"margin:20px 0 8px;font-size:16px;color:#04233b\">")
                    .append(escape(heading))
                    .append("</h2>")
                    .append(
                            "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;width:100%;font-size:14px\">");
            for (Row row : rows) {
                sb.append(
                                "<tr><td style=\"padding:4px 8px 4px 0;color:#52606d;vertical-align:top;white-space:nowrap\">")
                        .append(escape(row.label()))
                        .append("</td><td style=\"padding:4px 0\">")
                        .append(escape(row.value() == null ? "-" : row.value()))
                        .append("</td></tr>");
            }
            sb.append("</table>");
        }
    }
}
