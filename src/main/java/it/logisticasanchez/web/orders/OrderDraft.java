package it.logisticasanchez.web.orders;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The wizard state kept in the HTTP session between its pages (CUS-04): the lines with stable
 * ids (OP-05 fix), the order fields, the recipient and the idempotency key created with the
 * draft, so that confirming twice creates one order (OP-02 fix). Totals are recomputed from the
 * lines every time (OP-06 fix).
 */
public final class OrderDraft implements Serializable {

    static final String SESSION_KEY = OrderDraft.class.getName();

    private final UUID idempotencyKey = UUID.randomUUID();
    private final List<Line> lines = new ArrayList<>();
    private long nextLineId = 1;
    private String transportDocumentCode = "";
    private String packageCount = "";
    private Integer accessoryServiceId;
    private String notes = "";
    private Recipient recipient;

    public UUID idempotencyKey() {
        return idempotencyKey;
    }

    public List<Line> lines() {
        return List.copyOf(lines);
    }

    public void addLine(LineKind kind, int heightCm, int lengthCm, int widthCm, BigDecimal weightKg, int quantity) {
        lines.add(new Line(nextLineId++, kind, heightCm, lengthCm, widthCm, weightKg, quantity));
    }

    public boolean removeLine(long id) {
        return lines.removeIf(line -> line.id() == id);
    }

    public BigDecimal totalWeightKg() {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Line line : lines) {
            total = total.add(line.weightTotal());
        }
        return total;
    }

    public BigDecimal totalVolumeM3() {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Line line : lines) {
            total = total.add(line.volumeTotal());
        }
        return total;
    }

    public String transportDocumentCode() {
        return transportDocumentCode;
    }

    public String packageCount() {
        return packageCount;
    }

    public Integer accessoryServiceId() {
        return accessoryServiceId;
    }

    public String notes() {
        return notes;
    }

    public void setDetails(
            String transportDocumentCode, String packageCount, Integer accessoryServiceId, String notes) {
        this.transportDocumentCode = transportDocumentCode;
        this.packageCount = packageCount;
        this.accessoryServiceId = accessoryServiceId;
        this.notes = notes;
    }

    public Recipient recipient() {
        return recipient;
    }

    public void setRecipient(Recipient recipient) {
        this.recipient = recipient;
    }

    /** True when every page has been completed, so the summary can be shown. */
    public boolean isComplete() {
        return !lines.isEmpty() && !transportDocumentCode.isBlank() && recipient != null;
    }

    /** One line of the dialog (OP-R4) with its legacy totals. */
    public record Line(
            long id, LineKind kind, int heightCm, int lengthCm, int widthCm, BigDecimal weightKg, int quantity)
            implements Serializable {

        public BigDecimal weightTotal() {
            return OrderTotals.lineWeight(weightKg, quantity);
        }

        public BigDecimal volumeTotal() {
            return OrderTotals.lineVolume(heightCm, lengthCm, widthCm, quantity);
        }
    }

    /** The recipient fields (OP-R7) plus the optional e-mail; phone and e-mail null when empty. */
    public record Recipient(
            String name,
            String addressLine,
            String province,
            String city,
            String postalCode,
            String phone,
            String email)
            implements Serializable {}
}
