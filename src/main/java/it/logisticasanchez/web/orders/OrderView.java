package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** An order as the templates, the back office and the e-mails see it (never the entity). */
public record OrderView(
        long id,
        long customerId,
        OrderStatus status,
        String tempCode,
        String code,
        String transportDocumentCode,
        Integer packageCount,
        Integer accessoryServiceId,
        String accessoryServiceLabel,
        String customerNotes,
        BigDecimal amount,
        Boolean amountVatIncluded,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeM3,
        short trackingStateId,
        String trackingLabel,
        Instant trackingUpdatedAt,
        ShipmentOrder.Party sender,
        ShipmentOrder.Party recipient,
        Instant submittedAt,
        Instant approvedAt,
        Instant declinedAt,
        String declineReason,
        List<Line> lines) {

    /** The definitive code once approved, the temporary one before (chapter 08 §2.5). */
    public String displayCode() {
        return code != null ? code : tempCode;
    }

    /** The legacy CMS showed «-» for a temporary code (ADM-04). */
    public String codeOrDash() {
        return code != null ? code : "-";
    }

    public int palletCount() {
        return lines.stream()
                .filter(l -> l.kind() == LineKind.GENERIC)
                .mapToInt(Line::quantity)
                .sum();
    }

    public int epalCount() {
        return lines.stream()
                .filter(l -> l.kind() == LineKind.EPAL)
                .mapToInt(Line::quantity)
                .sum();
    }

    public String weightText() {
        return ItalianFormats.formatNumber(totalWeightKg, 2) + " kg";
    }

    public String volumeText() {
        return ItalianFormats.formatNumber(totalVolumeM3, 2) + " m³";
    }

    public String amountText() {
        return amount == null ? "-" : ItalianFormats.formatEuro(amount);
    }

    public record Line(
            int lineNo,
            LineKind kind,
            int heightCm,
            int lengthCm,
            int widthCm,
            BigDecimal weightKg,
            int quantity,
            BigDecimal lineWeightKg,
            BigDecimal lineVolumeM3) {}

    /** One tracking change (ADM-06 history). */
    public record TrackingChange(short stateId, String label, Instant occurredAt, String source) {}
}
