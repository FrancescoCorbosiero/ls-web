package it.logisticasanchez.web.quotes;

import it.logisticasanchez.web.orders.LineKind;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code quote} and {@code quote_line} (V018; ENH-20, ADR 0037) with plain SQL. The views come
 * back without the accessory service's label, which {@link QuoteService} reads from the tracking
 * catalog (the enumeration is that module's).
 */
@Repository
class QuoteRepository {

    private static final String COLUMNS = "id, lead_id, customer_id, status, amount, valid_until, accessory_service_id,"
            + " recipient_city, recipient_postal_code, note, created_by, created_at, order_id, accepted_at, withdrawn_at";

    private final JdbcClient jdbc;

    QuoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new SENT quote with its lines; returns its id. */
    long insert(NewQuote quote, String tokenHash, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO quote (lead_id, customer_id, token_hash, status, amount, valid_until,
                            accessory_service_id, recipient_city, recipient_postal_code, note, created_by, created_at)
                        VALUES (:lead, :customer, :token, 'SENT', :amount, :validUntil,
                            :service, :city, :cap, :note, :staff, :now)
                        """)
                .param("lead", quote.leadId())
                .param("customer", quote.customerId())
                .param("token", tokenHash)
                .param("amount", quote.amount())
                .param("validUntil", quote.validUntil(), Types.DATE)
                .param("service", quote.accessoryServiceId(), Types.INTEGER)
                .param("city", quote.recipientCity(), Types.VARCHAR)
                .param("cap", quote.recipientPostalCode(), Types.VARCHAR)
                .param("note", quote.note(), Types.VARCHAR)
                .param("staff", quote.staffUserId())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update(keys, "id");
        long id = keys.getKeyAs(Long.class);
        int no = 1;
        for (QuoteView.Line line : quote.lines()) {
            jdbc.sql("""
                            INSERT INTO quote_line (quote_id, line_no, kind, height_cm, length_cm, width_cm, weight_kg, quantity)
                            VALUES (:quote, :no, :kind, :height, :length, :width, :weight, :quantity)
                            """)
                    .param("quote", id)
                    .param("no", no++)
                    .param("kind", line.kind().name())
                    .param("height", line.heightCm())
                    .param("length", line.lengthCm())
                    .param("width", line.widthCm())
                    .param("weight", line.weightKg())
                    .param("quantity", line.quantity())
                    .update();
        }
        return id;
    }

    Optional<QuoteView> find(long id) {
        return one("id = :value", id);
    }

    Optional<QuoteView> findByTokenHash(String hash) {
        return one("token_hash = :value", hash);
    }

    Optional<QuoteView> findByOrder(long orderId) {
        return one("order_id = :value", orderId);
    }

    /** The quotes made from a website request, newest first. */
    List<QuoteView> forLead(long leadId) {
        return many("lead_id = :value ORDER BY id DESC", leadId);
    }

    /** The customer's quotes still SENT and valid on {@code today}, the next to expire first. */
    List<QuoteView> openForCustomer(long customerId, LocalDate today) {
        List<QuoteView> sent = many("customer_id = :value AND status = 'SENT' ORDER BY valid_until, id", customerId);
        return sent.stream().filter(q -> !q.expired(today)).toList();
    }

    /** SENT → ACCEPTED with the order made from it; false when the quote was no longer SENT. */
    boolean accept(long id, long orderId, Instant now) {
        return jdbc.sql("UPDATE quote SET status = 'ACCEPTED', order_id = :order, accepted_at = :now"
                                + " WHERE id = :id AND status = 'SENT'")
                        .param("id", id)
                        .param("order", orderId)
                        .param("now", now.atOffset(ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /** SENT → WITHDRAWN; false when the quote was no longer SENT. */
    boolean withdraw(long id, Instant now) {
        return jdbc.sql("UPDATE quote SET status = 'WITHDRAWN', withdrawn_at = :now WHERE id = :id AND status = 'SENT'")
                        .param("id", id)
                        .param("now", now.atOffset(ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /** NFR-03: the customer's quotes as the export tool lists them (plain values). */
    List<Map<String, Object>> exportFor(long customerId) {
        return jdbc.sql("SELECT id, status, amount, valid_until, recipient_city, recipient_postal_code, note,"
                        + " created_at, order_id FROM quote WHERE customer_id = :customer ORDER BY id")
                .param("customer", customerId)
                .query()
                .listOfRows();
    }

    /** NFR-03 erasure: the customer's open quotes withdrawn, every note and destination emptied; returns how many. */
    int erase(long customerId, Instant now) {
        return jdbc.sql("UPDATE quote SET note = NULL, recipient_city = NULL, recipient_postal_code = NULL,"
                        + " withdrawn_at = CASE WHEN status = 'SENT' THEN :now ELSE withdrawn_at END,"
                        + " status = CASE WHEN status = 'SENT' THEN 'WITHDRAWN' ELSE status END"
                        + " WHERE customer_id = :customer")
                .param("customer", customerId)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    private Optional<QuoteView> one(String where, Object value) {
        List<QuoteView> found = many(where, value);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private List<QuoteView> many(String where, Object value) {
        List<QuoteView> quotes = jdbc.sql("SELECT " + COLUMNS + " FROM quote WHERE " + where)
                .param("value", value)
                .query(QuoteRepository::header)
                .list();
        if (quotes.isEmpty()) {
            return quotes;
        }
        Map<Long, List<QuoteView.Line>> lines = new LinkedHashMap<>();
        jdbc.sql("SELECT quote_id, line_no, kind, height_cm, length_cm, width_cm, weight_kg, quantity FROM quote_line"
                        + " WHERE quote_id IN (:ids) ORDER BY quote_id, line_no")
                .param("ids", quotes.stream().map(QuoteView::id).toList())
                .query(rs -> {
                    lines.computeIfAbsent(rs.getLong("quote_id"), k -> new ArrayList<>())
                            .add(new QuoteView.Line(
                                    rs.getInt("line_no"),
                                    LineKind.valueOf(rs.getString("kind")),
                                    rs.getInt("height_cm"),
                                    rs.getInt("length_cm"),
                                    rs.getInt("width_cm"),
                                    rs.getBigDecimal("weight_kg"),
                                    rs.getInt("quantity")));
                });
        return quotes.stream()
                .map(q -> withLines(q, lines.getOrDefault(q.id(), List.of())))
                .toList();
    }

    private static QuoteView withLines(QuoteView q, List<QuoteView.Line> lines) {
        return new QuoteView(
                q.id(),
                q.leadId(),
                q.customerId(),
                q.status(),
                q.amount(),
                q.validUntil(),
                q.accessoryServiceId(),
                q.accessoryServiceLabel(),
                q.recipientCity(),
                q.recipientPostalCode(),
                q.note(),
                q.createdBy(),
                q.createdAt(),
                q.orderId(),
                q.acceptedAt(),
                q.withdrawnAt(),
                List.copyOf(lines));
    }

    private static QuoteView header(ResultSet rs, int row) throws SQLException {
        return new QuoteView(
                rs.getLong("id"),
                rs.getLong("lead_id"),
                rs.getLong("customer_id"),
                QuoteStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("amount"),
                rs.getObject("valid_until", LocalDate.class),
                rs.getObject("accessory_service_id", Integer.class),
                null,
                rs.getString("recipient_city"),
                rs.getString("recipient_postal_code"),
                rs.getString("note"),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("order_id", Long.class),
                instant(rs, "accepted_at"),
                instant(rs, "withdrawn_at"),
                List.of());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** What staff typed for a new quote (ENH-20). */
    record NewQuote(
            long leadId,
            long customerId,
            long staffUserId,
            List<QuoteView.Line> lines,
            BigDecimal amount,
            LocalDate validUntil,
            Integer accessoryServiceId,
            String recipientCity,
            String recipientPostalCode,
            String note) {}
}
