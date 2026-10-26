package it.logisticasanchez.web.tracking;

import it.logisticasanchez.web.platform.config.TrackingProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public tracking lookup (TRK-02, TRK-03, API-01, ADR 0010): the state label of an approved
 * order whose definitive code matches the normalised input ({@link ShipmentCode}). Pending
 * orders — and so their temporary codes — declined orders and unknown codes answer nothing,
 * which the API renders as «Non gestito» exactly like the legacy (Q-38: {@code
 * ls.tracking.temp-codes-visible} stays false in M1). The result carries only the status and
 * the time of its last change: no personal data ever leaves this service (TRK-03). With ENH-05
 * ({@link #history}, called only while {@code tracking.history} is on) the answer adds the
 * states with their times and the delivery day, still nothing personal.
 */
@Service
public class TrackingLookupService {

    /** The legacy answer for an unknown code, a temporary code or an order not yet handled. */
    public static final String NOT_HANDLED = "Non gestito";

    private final JdbcClient jdbc;
    private final boolean caseInsensitive;
    private final DeliveryEstimate estimates;

    public TrackingLookupService(JdbcClient jdbc, TrackingProperties tracking, DeliveryEstimate estimates) {
        this.jdbc = jdbc;
        this.caseInsensitive = tracking.match().caseInsensitive(); // Q-03
        this.estimates = estimates;
    }

    public boolean caseInsensitive() {
        return caseInsensitive;
    }

    /** The public status of the approved order with this code, if any. */
    @Transactional(readOnly = true)
    public Optional<PublicTracking> lookup(String rawCode) {
        String key = ShipmentCode.normalise(rawCode, caseInsensitive);
        if (key == null) {
            return Optional.empty();
        }
        String stored = caseInsensitive
                ? "lower(regexp_replace(o.code, '\\s', '', 'g'))"
                : "regexp_replace(o.code, '\\s', '', 'g')";
        return jdbc.sql("SELECT ts.label, o.tracking_updated_at FROM shipment_order o"
                        + " JOIN tracking_state ts ON ts.id = o.tracking_state_id"
                        + " WHERE o.status = 'APPROVED' AND o.code IS NOT NULL AND " + stored + " = :code")
                .param("code", key)
                .query((rs, i) -> new PublicTracking(
                        rs.getString("label"),
                        rs.getObject("tracking_updated_at", java.time.OffsetDateTime.class) == null
                                ? null
                                : rs.getObject("tracking_updated_at", java.time.OffsetDateTime.class)
                                        .toInstant()))
                .optional();
    }

    /**
     * ENH-05: the public answer with the history of the states (newest first: time, label and the
     * event's location, a hub or a city when staff typed one) and the delivery day — the estimate
     * from the recipient's CAP while the shipment travels, the day it was delivered once delivered
     * ({@link DeliveryEstimate}). Only the CAP is read to compute the day; it never leaves.
     */
    @Transactional(readOnly = true)
    public Optional<PublicHistory> history(String rawCode) {
        String key = ShipmentCode.normalise(rawCode, caseInsensitive);
        if (key == null) {
            return Optional.empty();
        }
        String stored = caseInsensitive
                ? "lower(regexp_replace(o.code, '\\s', '', 'g'))"
                : "regexp_replace(o.code, '\\s', '', 'g')";
        return jdbc.sql("SELECT o.id, ts.id AS state_id, ts.label, o.tracking_updated_at, o.approved_at,"
                        + " o.recipient_postal_code FROM shipment_order o"
                        + " JOIN tracking_state ts ON ts.id = o.tracking_state_id"
                        + " WHERE o.status = 'APPROVED' AND o.code IS NOT NULL AND " + stored + " = :code")
                .param("code", key)
                .query((rs, i) -> new Found(
                        rs.getLong("id"),
                        rs.getInt("state_id"),
                        rs.getString("label"),
                        instant(rs.getObject("tracking_updated_at", OffsetDateTime.class)),
                        instant(rs.getObject("approved_at", OffsetDateTime.class)),
                        rs.getString("recipient_postal_code")))
                .optional()
                .map(found -> new PublicHistory(
                        found.label(),
                        found.updatedAt(),
                        estimates
                                .deliveryDay(found.stateId(), found.postalCode(), found.approvedAt(), found.updatedAt())
                                .orElse(null),
                        events(found.id())));
    }

    private List<PublicEvent> events(long orderId) {
        return jdbc.sql("SELECT e.occurred_at, ts.label, e.location FROM tracking_event e"
                        + " JOIN tracking_state ts ON ts.id = e.state_id"
                        + " WHERE e.order_id = :order ORDER BY e.occurred_at DESC, e.id DESC")
                .param("order", orderId)
                .query((rs, i) -> new PublicEvent(
                        instant(rs.getObject("occurred_at", OffsetDateTime.class)),
                        rs.getString("label"),
                        rs.getString("location")))
                .list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record Found(
            long id, int stateId, String label, Instant updatedAt, Instant approvedAt, String postalCode) {}

    /** What the public may know about a shipment: its state label and when it last changed (TRK-03). */
    public record PublicTracking(String status, Instant updatedAt) {}

    /** ENH-05: the public answer with the delivery day (null when unknown) and the states, newest first (TRK-03). */
    public record PublicHistory(String status, Instant updatedAt, LocalDate deliveryDay, List<PublicEvent> events) {}

    /** One change of state as the public sees it: when, the state label, the location (null when none). */
    public record PublicEvent(Instant time, String status, String location) {}
}
