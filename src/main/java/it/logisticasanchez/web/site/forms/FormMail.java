package it.logisticasanchez.web.site.forms;

import it.logisticasanchez.web.notifications.EmailMessage;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * The office e-mail of a form submission (EML-08), ported from
 * {@code legacy/ls-home/netlify/functions/form.mjs:155-210} ({@code compose}): subject, text and
 * HTML bodies with the sections and rows of the submission (empty rows and sections omitted),
 * the «Rispondi via email» / «Chiama» / «Scrivi su WhatsApp» buttons, the footer with the
 * Europe/Rome time, Reply-To = the customer. Queued through the notifications outbox.
 */
@Component
public class FormMail {

    public static final String KIND = "EML-08";
    static final ZoneId ROME = ZoneId.of("Europe/Rome");
    static final DateTimeFormatter SENT_AT = DateTimeFormatter.ofPattern("dd/MM/yy, HH:mm", Locale.ITALIAN);
    private static final Pattern ITALIAN_MOBILE = Pattern.compile("^3\\d{8,9}$");
    private static final Pattern ITALIAN_MOBILE_WITH_PREFIX = Pattern.compile("^393\\d{8,9}$");

    private final BusinessProperties business;
    private final Clock clock;

    public FormMail(BusinessProperties business, Clock clock) {
        this.business = business;
        this.clock = clock;
    }

    public EmailMessage compose(FormSubmission form) {
        String site = business.siteUrl().replaceFirst("^https?://", "");
        String sent = SENT_AT.format(clock.instant().atZone(ROME));
        String footer = "Inviato dal modulo «" + form.form().mailName() + "» di " + site + " il " + sent
                + ". Rispondi a questa email per scrivere direttamente al cliente.";
        List<FormSubmission.Section> sections = new ArrayList<>();
        for (FormSubmission.Section section : form.sections()) {
            List<FormSubmission.Row> rows =
                    section.rows().stream().filter(r -> !r.value().isEmpty()).toList();
            if (!rows.isEmpty()) {
                sections.add(new FormSubmission.Section(section.heading(), rows));
            }
        }
        String subject = FormValidator.oneLine(form.subject());
        if (subject.length() > 200) {
            subject = subject.substring(0, 200);
        }
        return EmailMessage.of(
                        KIND,
                        business.officeEmail(),
                        subject,
                        text(form, sections, footer),
                        html(form, sections, footer))
                .withReplyTo(form.email());
    }

    static String text(FormSubmission form, List<FormSubmission.Section> sections, String footer) {
        List<String> lines = new ArrayList<>();
        lines.add(form.title());
        lines.add("");
        for (FormSubmission.Section section : sections) {
            lines.add(section.heading().toUpperCase(Locale.ITALIAN));
            for (FormSubmission.Row row : section.rows()) {
                lines.add(
                        row.value().contains("\n")
                                ? row.label() + ":\n" + row.value()
                                : row.label() + ": " + row.value());
            }
            lines.add("");
        }
        lines.add(footer);
        return String.join("\n", lines);
    }

    static String html(FormSubmission form, List<FormSubmission.Section> sections, String footer) {
        String phone = form.phone();
        String wa = phone == null ? "" : whatsapp(phone, form.preferredContact());
        StringBuilder actions = new StringBuilder(button("mailto:" + form.email(), "Rispondi via email"));
        if (phone != null) {
            actions.append(button("tel:" + phone.replaceAll("[^\\d+]", ""), "Chiama " + phone));
        }
        if (!wa.isEmpty()) {
            actions.append(button(wa, "Scrivi su WhatsApp"));
        }
        StringBuilder table = new StringBuilder();
        for (FormSubmission.Section section : sections) {
            table.append(
                            "\n      <h2 style=\"margin:24px 0 8px;font-size:13px;letter-spacing:.08em;text-transform:uppercase;color:#5b6b7b\">")
                    .append(esc(section.heading()))
                    .append(
                            "</h2>\n      <table role=\"presentation\" style=\"width:100%;border-collapse:collapse;font-size:15px\">");
            for (FormSubmission.Row row : section.rows()) {
                table.append(
                                "\n        <tr><td style=\"padding:6px 12px 6px 0;width:140px;vertical-align:top;color:#5b6b7b\">")
                        .append(esc(row.label()))
                        .append(
                                "</td><td style=\"padding:6px 0;vertical-align:top;color:#0b2239;white-space:pre-wrap\">")
                        .append(esc(row.value()))
                        .append("</td></tr>");
            }
            table.append("\n      </table>");
        }
        return "<!doctype html>\n<html lang=\"it\"><head><meta charset=\"utf-8\"><title>" + esc(form.title())
                + "</title></head>\n<body style=\"margin:0;padding:24px;background:#f3f5f7;font-family:Arial,Helvetica,sans-serif;color:#0b2239\">\n"
                + "  <div style=\"max-width:640px;margin:0 auto;background:#ffffff;border-radius:12px;padding:24px 28px\">\n"
                + "    <p style=\"margin:0 0 4px;font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:#5b6b7b\">Logistica Sanchez · sito web</p>\n"
                + "    <h1 style=\"margin:0 0 16px;font-size:22px\">" + esc(form.title()) + "</h1>\n"
                + "    <div>" + actions + "</div>" + table + "\n"
                + "    <p style=\"margin:24px 0 0;font-size:12px;color:#5b6b7b\">" + esc(footer) + "</p>\n"
                + "  </div>\n</body></html>";
    }

    /** WhatsApp link for Italian mobile numbers ({@code 3xx…}), or when the customer asked for WhatsApp. */
    static String whatsapp(String phone, String preferred) {
        String digits = phone.replaceAll("\\D", "").replaceFirst("^00", "");
        if (ITALIAN_MOBILE.matcher(digits).matches()) {
            digits = "39" + digits;
        }
        boolean ok = ITALIAN_MOBILE_WITH_PREFIX.matcher(digits).matches()
                || ("whatsapp".equals(preferred) && digits.length() >= 8);
        return ok ? "https://wa.me/" + digits : "";
    }

    private static String button(String href, String label) {
        return "<a href=\"" + esc(href)
                + "\" style=\"display:inline-block;margin:0 8px 8px 0;padding:10px 16px;border-radius:8px;background:#0b2239;color:#ffffff;font-weight:700;text-decoration:none\">"
                + esc(label) + "</a>";
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
