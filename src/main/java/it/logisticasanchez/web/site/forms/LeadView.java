package it.logisticasanchez.web.site.forms;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A stored website request as the back office shows it (ENH-06, ADR 0036): the columns of
 * {@code lead} (V006) without the abuse-control ones (IP hash, user agent, payload), with the
 * labels of the public form (the same as the office e-mail EML-08).
 */
public record LeadView(
        long id,
        FormSubmission.Form form,
        LeadStatus status,
        Instant createdAt,
        String name,
        String company,
        String email,
        String phone,
        String preferredContact,
        String subject,
        String message,
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
        Long customerId,
        Long convertedOrderId) {

    public boolean quote() {
        return form == FormSubmission.Form.PREVENTIVO;
    }

    /** «Preventivo» or «Contatti», the name of the public page the request came from. */
    public String formLabel() {
        return form.mailName();
    }

    public String serviceLabel() {
        return service == null ? null : FormValidator.SERVICE_LABELS.get(service);
    }

    public String palletLabel() {
        return palletFormat == null ? null : FormValidator.PALLET_LABELS.get(palletFormat);
    }

    public String recipientTypeLabel() {
        return recipientType == null ? null : FormValidator.RECIPIENT_LABELS.get(recipientType);
    }

    public String preferredContactLabel() {
        return preferredContact == null ? null : FormValidator.CONTACT_LABELS.get(preferredContact);
    }

    /** «20831 Seregno», or null. */
    public String pickup() {
        return place(pickupPostalCode, pickupCity);
    }

    /** «10121 Torino», or null. */
    public String delivery() {
        return place(deliveryPostalCode, deliveryCity);
    }

    /** «2 × EPAL 120×80 cm», or null. */
    public String goods() {
        return quantity == null || palletLabel() == null ? null : FormValidator.plain(quantity) + " × " + palletLabel();
    }

    public String weightText() {
        return totalWeightKg == null ? null : FormValidator.plain(totalWeightKg) + " kg";
    }

    public String heightText() {
        return maxHeightCm == null ? null : FormValidator.plain(maxHeightCm) + " cm";
    }

    /** One line for the list: the service and the route of a quote, the subject of a message. */
    public String summary() {
        if (quote()) {
            List<String> parts = new ArrayList<>();
            if (serviceLabel() != null) {
                parts.add(serviceLabel());
            }
            if (pickup() != null && delivery() != null) {
                parts.add(pickup() + " → " + delivery());
            }
            return String.join(" · ", parts);
        }
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        return message == null ? "" : abbreviate(message, 80);
    }

    /** Erased by the retention job or on request (NFR-03): nothing personal is left to show. */
    public boolean anonymised() {
        return email != null && email.endsWith("@anonimizzato.invalid");
    }

    private static String place(String postalCode, String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        return postalCode == null || postalCode.isBlank() ? city : postalCode + " " + city;
    }

    private static String abbreviate(String text, int max) {
        String line = FormValidator.oneLine(text);
        return line.length() <= max ? line : line.substring(0, max - 1) + "…";
    }
}
