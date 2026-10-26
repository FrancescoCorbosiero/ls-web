package it.logisticasanchez.web.quotes;

import it.logisticasanchez.web.orders.LineKind;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A saved quote (ENH-20, ADR 0037) as the staff, the customer and the PDF show it. {@link #code()}
 * is the reference printed everywhere («PRV-00007»). Open = SENT and not past its last day
 * (Europe/Rome, inclusive).
 */
public record QuoteView(
        long id,
        long leadId,
        long customerId,
        QuoteStatus status,
        BigDecimal amount,
        LocalDate validUntil,
        Integer accessoryServiceId,
        String accessoryServiceLabel,
        String recipientCity,
        String recipientPostalCode,
        String note,
        long createdBy,
        Instant createdAt,
        Long orderId,
        Instant acceptedAt,
        Instant withdrawnAt,
        List<Line> lines) {

    public static String code(long id) {
        return String.format("PRV-%05d", id);
    }

    public String code() {
        return code(id);
    }

    public boolean expired(LocalDate today) {
        return today.isAfter(validUntil);
    }

    /** It can still become an order. */
    public boolean open(LocalDate today) {
        return status == QuoteStatus.SENT && !expired(today);
    }

    public String amountText() {
        return ItalianFormats.formatEuro(amount);
    }

    public String validUntilText() {
        return validUntil.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /** «10121 Torino», or null. */
    public String destination() {
        if (recipientCity == null || recipientCity.isBlank()) {
            return null;
        }
        return recipientPostalCode == null || recipientPostalCode.isBlank()
                ? recipientCity
                : recipientPostalCode + " " + recipientCity;
    }

    public BigDecimal totalWeightKg() {
        return lines.stream().map(Line::weightTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public int units() {
        return lines.stream().mapToInt(Line::quantity).sum();
    }

    /** One line of the quote, as an order line (OP-R4). */
    public record Line(
            int lineNo, LineKind kind, int heightCm, int lengthCm, int widthCm, BigDecimal weightKg, int quantity) {

        public BigDecimal weightTotal() {
            return weightKg.multiply(BigDecimal.valueOf(quantity));
        }

        /** «Epal 120 × 80 × 150 cm». */
        public String description() {
            return kind.label() + " " + lengthCm + " × " + widthCm + " × " + heightCm + " cm";
        }

        public String weightText() {
            return ItalianFormats.formatNumber(weightKg, 2) + " kg";
        }
    }
}
