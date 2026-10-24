package it.logisticasanchez.web.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The staff note of an order (table {@code order_internal_note}, V005; ADM-07, Q-26): one per order, never shown to the customer. */
@Entity
@Table(name = "order_internal_note")
public class OrderInternalNote {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private long updatedBy;

    protected OrderInternalNote() {}

    OrderInternalNote(long orderId, String body, long staffUserId, Instant at) {
        this.orderId = orderId;
        update(body, staffUserId, at);
    }

    void update(String body, long staffUserId, Instant at) {
        this.body = body;
        this.updatedBy = staffUserId;
        this.updatedAt = at;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getBody() {
        return body;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getUpdatedBy() {
        return updatedBy;
    }
}
