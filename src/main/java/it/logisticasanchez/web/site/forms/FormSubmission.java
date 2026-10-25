package it.logisticasanchez.web.site.forms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A validated quote or contact submission (chapter 06 §5.5–5.6): what the e-mail (EML-08) and
 * the {@code lead} row (PUB-11) are built from. {@code sections} are the label/value rows of
 * the office e-mail in the legacy order, empty values included (omitted at rendering);
 * {@code payload} is every accepted field under its form name, as received.
 */
public record FormSubmission(
        Form form,
        String subject,
        String title,
        List<Section> sections,
        String name,
        String email,
        String phone,
        String preferredContact,
        Quote quote,
        Contact contact,
        Map<String, Object> payload) {

    public enum Form {
        PREVENTIVO("Preventivo", "/preventivo/"),
        CONTATTI("Contatti", "/contatti/");

        private final String mailName;
        private final String page;

        Form(String mailName, String page) {
            this.mailName = mailName;
            this.page = page;
        }

        /** The name printed in the e-mail footer («Inviato dal modulo «Preventivo»…»). */
        public String mailName() {
            return mailName;
        }

        /** The page the native-mode error page sends the visitor back to. */
        public String page() {
            return page;
        }

        /** The {@code form-name} field value ({@code preventivo}, {@code contatti}). */
        public String fieldValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Section(String heading, List<Row> rows) {}

    public record Row(String label, String value) {}

    /** The quote fields stored in the lead row. */
    public record Quote(
            String service,
            LocalDate pickupDate,
            String pickupPostalCode,
            String pickupCity,
            String deliveryPostalCode,
            String deliveryCity,
            String recipientType,
            String palletFormat,
            BigDecimal quantity,
            BigDecimal totalWeightKg,
            BigDecimal maxHeightCm,
            String goodsDescription,
            List<String> extras,
            String company,
            String notes) {}

    /** The contact-form fields stored in the lead row. */
    public record Contact(String subject, String message) {}
}
