package it.logisticasanchez.web.site.forms;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code lead} rows (V006; PUB-11) with plain SQL: the table has array and jsonb
 * columns. M1 only inserts; the inbox of ENH-06 (ADR 0036) reads the rows that are not
 * anonymised and moves their status.
 */
@Repository
public class LeadRepository {

    private final JdbcClient jdbc;

    public LeadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the lead and returns its id. */
    public long insert(
            FormSubmission form,
            String payloadJson,
            Instant now,
            LocalDate retainUntil,
            String ipHash,
            String userAgent) {
        FormSubmission.Quote q = form.quote();
        FormSubmission.Contact c = form.contact();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO lead (form, status, name, company, email, phone, preferred_contact, subject, message,
                            service, pickup_date, pickup_postal_code, pickup_city, delivery_postal_code, delivery_city,
                            recipient_type, pallet_format, quantity, total_weight_kg, max_height_cm, goods_description,
                            extras, payload, privacy_accepted_at, ip_hash, user_agent, created_at, retain_until)
                        VALUES (:form, 'NEW', :name, :company, :email, :phone, :preferred, :subject, :message,
                            :service, :pickupDate, :pickupCap, :pickupCity, :deliveryCap, :deliveryCity,
                            :recipientType, :palletFormat, :quantity, :weight, :height, :goods,
                            :extras, :payload::jsonb, :now, :ipHash, :userAgent, :now, :retainUntil)
                        """)
                .param("form", form.form().name())
                .param("name", form.name())
                .param("company", q == null ? null : q.company(), Types.VARCHAR)
                .param("email", form.email())
                .param("phone", form.phone(), Types.VARCHAR)
                .param("preferred", form.preferredContact(), Types.VARCHAR)
                .param("subject", c == null ? null : c.subject(), Types.VARCHAR)
                .param("message", c != null ? c.message() : q.notes(), Types.VARCHAR)
                .param("service", q == null ? null : q.service(), Types.VARCHAR)
                .param("pickupDate", q == null ? null : q.pickupDate(), Types.DATE)
                .param("pickupCap", q == null ? null : q.pickupPostalCode(), Types.VARCHAR)
                .param("pickupCity", q == null ? null : q.pickupCity(), Types.VARCHAR)
                .param("deliveryCap", q == null ? null : q.deliveryPostalCode(), Types.VARCHAR)
                .param("deliveryCity", q == null ? null : q.deliveryCity(), Types.VARCHAR)
                .param("recipientType", q == null ? null : q.recipientType(), Types.VARCHAR)
                .param("palletFormat", q == null ? null : q.palletFormat(), Types.VARCHAR)
                .param("quantity", q == null ? null : q.quantity(), Types.NUMERIC)
                .param("weight", q == null ? null : q.totalWeightKg(), Types.NUMERIC)
                .param("height", q == null ? null : q.maxHeightCm(), Types.NUMERIC)
                .param("goods", q == null ? null : q.goodsDescription(), Types.VARCHAR)
                .param("extras", q == null ? new String[0] : q.extras().toArray(String[]::new))
                .param("payload", payloadJson)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("ipHash", ipHash, Types.VARCHAR)
                .param("userAgent", userAgent, Types.VARCHAR)
                .param("retainUntil", retainUntil, Types.DATE)
                .update(keys, "id");
        return keys.getKeyAs(Long.class);
    }

    /**
     * Anonymises the rows that match {@code where} (NFR-03, Q-18): every personal column becomes
     * a placeholder (the e-mail {@code lead-<id>@anonimizzato.invalid}), the payload an empty
     * object, the IP hash and user agent null; the form, dates and status stay for statistics.
     */
    private int anonymise(String where, Object... params) {
        return jdbc.sql("UPDATE lead SET name = '-', company = NULL, email = 'lead-' || id || '@anonimizzato.invalid',"
                        + " phone = CASE WHEN form = 'PREVENTIVO' THEN '-' ELSE NULL END, subject = NULL, message = NULL,"
                        + " goods_description = NULL, payload = '{}'::jsonb, ip_hash = NULL, user_agent = NULL"
                        + " WHERE email NOT LIKE '%@anonimizzato.invalid' AND " + where)
                .params(params)
                .update();
    }

    /** Erasure on request (NFR-03): every lead sent with this (normalised) e-mail. */
    public int anonymiseByEmail(String email) {
        return anonymise("lower(email) = ?", email);
    }

    /** Erasure on request (NFR-03, ADR 0036): every lead linked to the customer, whatever its e-mail. */
    public int anonymiseByCustomer(long customerId) {
        return anonymise("customer_id = ?", customerId);
    }

    /** Retention (Q-18): every lead past its {@code retain_until}. */
    public int anonymiseExpired(LocalDate today) {
        return anonymise("retain_until < ?", today);
    }

    /** Rows that still hold personal data: the inbox never lists an anonymised request. */
    private static final String LIVE = "email NOT LIKE '%@anonimizzato.invalid'";

    private static final String COLUMNS =
            "id, form, status, created_at, name, company, email, phone, preferred_contact,"
                    + " subject, message, service, pickup_date, pickup_postal_code, pickup_city, delivery_postal_code,"
                    + " delivery_city, recipient_type, pallet_format, quantity, total_weight_kg, max_height_cm,"
                    + " goods_description, extras, customer_id, converted_order_id";

    private static final String MATCHES =
            "(:like = '' OR lower(name) LIKE :like OR lower(coalesce(company, '')) LIKE :like"
                    + " OR lower(email) LIKE :like OR coalesce(phone, '') LIKE :like OR lower(coalesce(pickup_city, '')) LIKE :like"
                    + " OR lower(coalesce(delivery_city, '')) LIKE :like OR lower(coalesce(subject, '')) LIKE :like)";

    /** ENH-06: a page of the live requests in these states that match {@code like}, newest first. */
    public List<LeadView> page(Collection<LeadStatus> statuses, String like, int offset, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM lead WHERE " + LIVE + " AND status IN (:statuses) AND " + MATCHES
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("statuses", statuses.stream().map(Enum::name).toList())
                .param("like", like)
                .param("limit", limit)
                .param("offset", offset)
                .query(LeadRepository::view)
                .list();
    }

    /** ENH-06: how many live requests are in these states and match {@code like}. */
    public long count(Collection<LeadStatus> statuses, String like) {
        return jdbc.sql("SELECT count(*) FROM lead WHERE " + LIVE + " AND status IN (:statuses) AND " + MATCHES)
                .param("statuses", statuses.stream().map(Enum::name).toList())
                .param("like", like)
                .query(Long.class)
                .single();
    }

    /** ENH-06: a live request. */
    public Optional<LeadView> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM lead WHERE id = :id AND " + LIVE)
                .param("id", id)
                .query(LeadRepository::view)
                .optional();
    }

    /** ENH-06: moves the request from {@code from} to {@code to}; false when it was no longer in {@code from}. */
    public boolean moveStatus(long id, LeadStatus from, LeadStatus to) {
        return jdbc.sql("UPDATE lead SET status = :to WHERE id = :id AND status = :from AND " + LIVE)
                        .param("id", id)
                        .param("from", from.name())
                        .param("to", to.name())
                        .update()
                == 1;
    }

    /** ENH-06: the customer the request belongs to (created from it, or found by its e-mail). */
    public boolean linkCustomer(long id, long customerId) {
        return jdbc.sql("UPDATE lead SET customer_id = :customer WHERE id = :id AND " + LIVE)
                        .param("id", id)
                        .param("customer", customerId)
                        .update()
                == 1;
    }

    /** ENH-06: the order made from an open request, which becomes WON; false when it was no longer open. */
    public boolean markConverted(long id, long customerId, long orderId) {
        return jdbc.sql("UPDATE lead SET status = 'WON', customer_id = :customer, converted_order_id = :order"
                                + " WHERE id = :id AND status IN ('NEW', 'CONTACTED') AND converted_order_id IS NULL AND "
                                + LIVE)
                        .param("id", id)
                        .param("customer", customerId)
                        .param("order", orderId)
                        .update()
                == 1;
    }

    private static LeadView view(ResultSet rs, int row) throws SQLException {
        Array extras = rs.getArray("extras");
        return new LeadView(
                rs.getLong("id"),
                FormSubmission.Form.valueOf(rs.getString("form")),
                LeadStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("name"),
                rs.getString("company"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("preferred_contact"),
                rs.getString("subject"),
                rs.getString("message"),
                rs.getString("service"),
                rs.getObject("pickup_date", LocalDate.class),
                rs.getString("pickup_postal_code"),
                rs.getString("pickup_city"),
                rs.getString("delivery_postal_code"),
                rs.getString("delivery_city"),
                rs.getString("recipient_type"),
                rs.getString("pallet_format"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("total_weight_kg"),
                rs.getBigDecimal("max_height_cm"),
                rs.getString("goods_description"),
                extras == null ? List.of() : Arrays.asList((String[]) extras.getArray()),
                rs.getObject("customer_id", Long.class),
                rs.getObject("converted_order_id", Long.class));
    }

    /** The leads of an e-mail as the export tool lists them (NFR-03): form, date, name, subject or message. */
    public List<Map<String, Object>> exportByEmail(String email) {
        return exportFor(email, null);
    }

    /** The leads of an e-mail and those linked to the customer (ENH-06, ADR 0036), as the export tool lists them. */
    public List<Map<String, Object>> exportFor(String email, Long customerId) {
        return jdbc.sql(
                        "SELECT id, form, status, created_at, name, company, phone, subject, message, payload::text AS payload"
                                + " FROM lead WHERE lower(email) = :email OR customer_id = :customer ORDER BY id")
                .param("email", email)
                .param("customer", customerId, Types.BIGINT)
                .query()
                .listOfRows();
    }
}
