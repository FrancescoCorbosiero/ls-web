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
        List<Line> lines,
        int version,
        String source,
        Instant cancelledAt,
        String cancelledByType,
        String cancelReason,
        Instant editedAt,
        String editedByType) {

    /** The definitive code once approved, the temporary one before (chapter 08 §2.5). */
    public String displayCode() {
        return code != null ? code : tempCode;
    }

    public boolean isPending() {
        return status == OrderStatus.PENDING;
    }

    public boolean isApproved() {
        return status == OrderStatus.APPROVED;
    }

    public boolean isDeclined() {
        return status == OrderStatus.DECLINED;
    }

    public boolean isCancelled() {
        return status == OrderStatus.CANCELLED;
    }

    /** CUS-07: the customer may still change or cancel it (until the office decides). */
    public boolean editableByCustomer() {
        return status == OrderStatus.PENDING;
    }

    /** ADM-11: staff may edit or cancel it (an approved one only as ADMIN, checked by the service). */
    public boolean editableByStaff() {
        return status == OrderStatus.PENDING || status == OrderStatus.APPROVED;
    }

    public boolean cancelledByCustomer() {
        return ShipmentOrder.BY_CUSTOMER.equals(cancelledByType);
    }

    public boolean editedByCustomer() {
        return ShipmentOrder.BY_CUSTOMER.equals(editedByType);
    }

    public boolean createdByStaff() {
        return ShipmentOrder.SOURCE_BACKOFFICE.equals(source);
    }

    /** Units of every line (pallets, EPAL and parcels together). */
    public int unitCount() {
        return lines.stream().mapToInt(Line::quantity).sum();
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
