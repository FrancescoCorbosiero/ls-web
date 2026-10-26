package it.logisticasanchez.web.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One tracking change (table {@code tracking_event}, V005; ADM-06): append-only, one row per change, source APPROVAL or STAFF. */
@Entity
@Table(name = "tracking_event")
public class TrackingEvent {

    public static final String SOURCE_APPROVAL = "APPROVAL";
    public static final String SOURCE_STAFF = "STAFF";
    /** ENH-15: set on many orders at once from «Tracking» (ADR 0035). */
    public static final String SOURCE_BULK = "BULK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private long orderId;

    @Column(name = "state_id", nullable = false, columnDefinition = "smallint")
    private Short stateId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "staff_user_id")
    private Long staffUserId;

    protected TrackingEvent() {}

    TrackingEvent(long orderId, short stateId, Instant at, String source, Long staffUserId) {
        this.orderId = orderId;
        this.stateId = stateId;
        this.occurredAt = at;
        this.recordedAt = at;
        this.source = source;
        this.staffUserId = staffUserId;
    }

    public Long getId() {
        return id;
    }

    public long getOrderId() {
        return orderId;
    }

    public short getStateId() {
        return stateId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getSource() {
        return source;
    }

    public Long getStaffUserId() {
        return staffUserId;
    }
}
