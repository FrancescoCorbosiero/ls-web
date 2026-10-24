package it.logisticasanchez.web.tracking;

import it.logisticasanchez.web.platform.config.TrackingProperties;
import java.time.Instant;
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
 * the time of its last change: no personal data ever leaves this service (TRK-03).
 */
@Service
public class TrackingLookupService {

    /** The legacy answer for an unknown code, a temporary code or an order not yet handled. */
    public static final String NOT_HANDLED = "Non gestito";

    private final JdbcClient jdbc;
    private final boolean caseInsensitive;

    public TrackingLookupService(JdbcClient jdbc, TrackingProperties tracking) {
        this.jdbc = jdbc;
        this.caseInsensitive = tracking.match().caseInsensitive(); // Q-03
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

    /** What the public may know about a shipment: its state label and when it last changed (TRK-03). */
    public record PublicTracking(String status, Instant updatedAt) {}
}
