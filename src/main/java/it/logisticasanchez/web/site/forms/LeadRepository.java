package it.logisticasanchez.web.site.forms;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code lead} rows (V006; PUB-11) with plain SQL: the table has array and jsonb
 * columns, and M1 only inserts (the inbox UI is M2, ENH-06).
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
}
