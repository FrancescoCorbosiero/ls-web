package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A shipment request (table {@code shipment_order}, V005; chapter 08 §2.3): the lines, the
 * transport document, the optional package count, accessory service and notes, the sender
 * snapshot (the customer at submission, Q-13) and the recipient; the state machine of chapter 08
 * §2.4 ({@code PENDING → APPROVED | DECLINED}) and the tracking state of ADM-06.
 */
@Entity
@Table(name = "shipment_order")
public class ShipmentOrder {

    static final String SOURCE_CUSTOMER_AREA = "CUSTOMER_AREA";
    static final String SOURCE_BACKOFFICE = "BACKOFFICE";

    /** Who edited or cancelled an order (columns {@code edited_by_type}, {@code cancelled_by_type}, V013). */
    static final String BY_CUSTOMER = "CUSTOMER";

    static final String BY_STAFF = "STAFF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "temp_code", nullable = false, length = 64, updatable = false)
    private String tempCode;

    @Column(name = "code", length = 255)
    private String code;

    @Column(name = "transport_document_code", nullable = false, length = 100)
    private String transportDocumentCode;

    @Column(name = "package_count")
    private Integer packageCount;

    @Column(name = "accessory_service_id")
    private Integer accessoryServiceId;

    @Column(name = "customer_notes")
    private String customerNotes;

    @Column(name = "amount", precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "amount_vat_included")
    private Boolean amountVatIncluded;

    @Column(name = "total_weight_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalWeightKg = BigDecimal.ZERO;

    @Column(name = "total_volume_m3", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalVolumeM3 = BigDecimal.ZERO;

    @Column(name = "tracking_state_id", nullable = false, columnDefinition = "smallint")
    private Short trackingStateId = 0;

    @Column(name = "tracking_updated_at")
    private Instant trackingUpdatedAt;

    @Column(name = "sender_name", nullable = false, length = 200)
    private String senderName;

    @Column(name = "sender_address_line", nullable = false, length = 200)
    private String senderAddressLine;

    @Column(name = "sender_city", nullable = false, length = 100)
    private String senderCity;

    @Column(name = "sender_province", nullable = false, length = 60)
    private String senderProvince;

    @Column(name = "sender_postal_code", nullable = false, length = 10)
    private String senderPostalCode;

    @Column(name = "sender_email", nullable = false, length = 254)
    private String senderEmail;

    @Column(name = "sender_phone", length = 30)
    private String senderPhone;

    @Column(name = "recipient_name", nullable = false, length = 200)
    private String recipientName;

    @Column(name = "recipient_address_line", nullable = false, length = 200)
    private String recipientAddressLine;

    @Column(name = "recipient_city", nullable = false, length = 100)
    private String recipientCity;

    @Column(name = "recipient_province", nullable = false, length = 60)
    private String recipientProvince;

    @Column(name = "recipient_postal_code", nullable = false, length = 10)
    private String recipientPostalCode;

    @Column(name = "recipient_phone", length = 30)
    private String recipientPhone;

    @Column(name = "recipient_email", length = 254)
    private String recipientEmail;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "declined_by")
    private Long declinedBy;

    @Column(name = "decline_reason")
    private String declineReason;

    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    @Column(name = "source", nullable = false, length = 16, updatable = false)
    private String source = SOURCE_CUSTOMER_AREA;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by_type", length = 16)
    private String cancelledByType;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "edited_by_type", length = 16)
    private String editedByType;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<OrderLine> lines = new ArrayList<>();

    protected ShipmentOrder() {}

    /** A new PENDING order of the wizard with its temporary code and the sender snapshot. */
    ShipmentOrder(long customerId, String tempCode, UUID idempotencyKey, Instant now, Party sender, Party recipient) {
        this(customerId, tempCode, idempotencyKey, now, sender, recipient, SOURCE_CUSTOMER_AREA);
    }

    /** A new PENDING order created from {@code source}: the wizard ({@link #SOURCE_CUSTOMER_AREA}) or staff for a customer ({@link #SOURCE_BACKOFFICE}, ADM-11). */
    ShipmentOrder(
            long customerId,
            String tempCode,
            UUID idempotencyKey,
            Instant now,
            Party sender,
            Party recipient,
            String source) {
        this.source = Objects.requireNonNull(source, "source");
        this.customerId = customerId;
        this.tempCode = Objects.requireNonNull(tempCode, "tempCode");
        this.idempotencyKey = idempotencyKey;
        this.submittedAt = Objects.requireNonNull(now, "now");
        this.createdAt = now;
        this.senderName = sender.name();
        this.senderAddressLine = sender.addressLine();
        this.senderCity = sender.city();
        this.senderProvince = sender.province();
        this.senderPostalCode = sender.postalCode();
        this.senderEmail = Objects.requireNonNull(sender.email(), "sender e-mail");
        this.senderPhone = sender.phone();
        this.recipientName = recipient.name();
        this.recipientAddressLine = recipient.addressLine();
        this.recipientCity = recipient.city();
        this.recipientProvince = recipient.province();
        this.recipientPostalCode = recipient.postalCode();
        this.recipientPhone = recipient.phone();
        this.recipientEmail = recipient.email();
    }

    void setDetails(
            String transportDocumentCode, Integer packageCount, Integer accessoryServiceId, String customerNotes) {
        this.transportDocumentCode = transportDocumentCode;
        this.packageCount = packageCount;
        this.accessoryServiceId = accessoryServiceId;
        this.customerNotes = customerNotes;
    }

    void addLine(LineKind kind, int heightCm, int lengthCm, int widthCm, BigDecimal weightKg, int quantity) {
        OrderLine line = new OrderLine(this, lines.size() + 1, kind, heightCm, lengthCm, widthCm, weightKg, quantity);
        lines.add(line);
        totalWeightKg = totalWeightKg.add(line.getLineWeightKg());
        totalVolumeM3 = totalVolumeM3.add(line.getLineVolumeM3());
    }

    /**
     * An edit (CUS-07, ADM-11): the lines become {@code replacement}, in order. Existing lines are
     * rewritten in place and the surplus ones removed, so line numbers stay 1…n without ever
     * colliding with a row still waiting to be deleted (unique {@code (order_id, line_no)});
     * the totals are recomputed from the lines (OP-06).
     */
    void replaceLines(List<OrderDraft.Line> replacement) {
        if (replacement.isEmpty()) {
            throw new RuleViolationException("Aggiungi almeno una riga di pallet o colli.");
        }
        for (int i = 0; i < replacement.size(); i++) {
            OrderDraft.Line l = replacement.get(i);
            if (i < lines.size()) {
                lines.get(i).update(l.kind(), l.heightCm(), l.lengthCm(), l.widthCm(), l.weightKg(), l.quantity());
            } else {
                lines.add(new OrderLine(
                        this, i + 1, l.kind(), l.heightCm(), l.lengthCm(), l.widthCm(), l.weightKg(), l.quantity()));
            }
        }
        while (lines.size() > replacement.size()) {
            lines.remove(lines.size() - 1);
        }
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        for (OrderLine line : lines) {
            weight = weight.add(line.getLineWeightKg());
            volume = volume.add(line.getLineVolumeM3());
        }
        totalWeightKg = weight;
        totalVolumeM3 = volume;
    }

    /** The recipient of an edit (CUS-07, ADM-11). */
    void setRecipient(Party recipient) {
        this.recipientName = recipient.name();
        this.recipientAddressLine = recipient.addressLine();
        this.recipientCity = recipient.city();
        this.recipientProvince = recipient.province();
        this.recipientPostalCode = recipient.postalCode();
        this.recipientPhone = recipient.phone();
        this.recipientEmail = recipient.email();
    }

    /** Stamps the last edit; the row always changes, so its version moves on even when only the lines did. */
    void edited(String byType, Instant at) {
        this.editedByType = Objects.requireNonNull(byType, "byType");
        this.editedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * Cancels the order (V013, ADR 0028): a PENDING order by the customer or by staff, an APPROVED
     * one by staff only (the caller checks the ADMIN role). Nothing is deleted.
     */
    void cancel(String byType, Long staffUserId, String reason, Instant at) {
        boolean allowed = status == OrderStatus.PENDING || (status == OrderStatus.APPROVED && BY_STAFF.equals(byType));
        if (!allowed) {
            throw new RuleViolationException(
                    status == OrderStatus.APPROVED
                            ? "L'ordine è già confermato: per annullarlo contatta il centro logistico."
                            : "L'ordine non può più essere annullato.");
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = at;
        this.cancelledByType = byType;
        this.cancelledBy = BY_STAFF.equals(byType) ? Objects.requireNonNull(staffUserId, "staffUserId") : null;
        this.cancelReason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    /** An ADMIN correction of an approved order (ADM-11, ADR 0028): the definitive code and the amount. */
    void correctApproval(String code, BigDecimal amount) {
        if (status != OrderStatus.APPROVED) {
            throw new RuleViolationException("Codice e importo si correggono solo su un ordine confermato.");
        }
        this.code = Objects.requireNonNull(code, "code");
        this.amount = Objects.requireNonNull(amount, "amount");
    }

    /** Staff approve (ADM-04): the definitive code and the amount; tracking becomes PRESA (the caller writes the event). */
    void approve(
            String code, BigDecimal amount, boolean vatIncluded, long staffUserId, Instant at, short presaStateId) {
        if (status != OrderStatus.PENDING) {
            throw new RuleViolationException("L'ordine non è in attesa di approvazione.");
        }
        this.status = OrderStatus.APPROVED;
        this.code = code;
        this.amount = amount;
        this.amountVatIncluded = vatIncluded;
        this.approvedAt = at;
        this.approvedBy = staffUserId;
        this.trackingStateId = presaStateId;
        this.trackingUpdatedAt = at;
    }

    void decline(long staffUserId, Instant at, String reason) {
        if (status != OrderStatus.PENDING) {
            throw new RuleViolationException("L'ordine non è in attesa di approvazione.");
        }
        this.status = OrderStatus.DECLINED;
        this.declinedAt = at;
        this.declinedBy = staffUserId;
        this.declineReason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    /** ADM-06: a new tracking state on an approved order; returns false (nothing to record) when it is the current one. */
    boolean setTrackingState(short stateId, Instant at) {
        if (status != OrderStatus.APPROVED) {
            throw new RuleViolationException("Il tracking si aggiorna solo sugli ordini approvati.");
        }
        if (trackingStateId == stateId) {
            return false;
        }
        this.trackingStateId = stateId;
        this.trackingUpdatedAt = at;
        return true;
    }

    /** The code the customer sees and uses in URLs: the definitive one once approved, the temporary one before. */
    public String displayCode() {
        return code != null ? code : tempCode;
    }

    public Long getId() {
        return id;
    }

    public long getCustomerId() {
        return customerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getTempCode() {
        return tempCode;
    }

    public String getCode() {
        return code;
    }

    public String getTransportDocumentCode() {
        return transportDocumentCode;
    }

    public Integer getPackageCount() {
        return packageCount;
    }

    public Integer getAccessoryServiceId() {
        return accessoryServiceId;
    }

    public String getCustomerNotes() {
        return customerNotes;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Boolean getAmountVatIncluded() {
        return amountVatIncluded;
    }

    public BigDecimal getTotalWeightKg() {
        return totalWeightKg;
    }

    public BigDecimal getTotalVolumeM3() {
        return totalVolumeM3;
    }

    public short getTrackingStateId() {
        return trackingStateId;
    }

    public Instant getTrackingUpdatedAt() {
        return trackingUpdatedAt;
    }

    public Party sender() {
        return new Party(
                senderName, senderAddressLine, senderProvince, senderCity, senderPostalCode, senderPhone, senderEmail);
    }

    public Party recipient() {
        return new Party(
                recipientName,
                recipientAddressLine,
                recipientProvince,
                recipientCity,
                recipientPostalCode,
                recipientPhone,
                recipientEmail);
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public Instant getDeclinedAt() {
        return declinedAt;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public String getSource() {
        return source;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelledByType() {
        return cancelledByType;
    }

    public Long getCancelledBy() {
        return cancelledBy;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public String getEditedByType() {
        return editedByType;
    }

    public int getVersion() {
        return version;
    }

    /** A sender or recipient: the legacy fields of chapter 03 §6.6 plus the optional e-mail (OP-14). */
    public record Party(
            String name,
            String addressLine,
            String province,
            String city,
            String postalCode,
            String phone,
            String email) {

        public String addressOneLine() {
            return addressLine + ", " + postalCode + " " + city + " (" + province + ")";
        }
    }
}
