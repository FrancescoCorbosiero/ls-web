package it.logisticasanchez.web.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Synthetic customers for the auth tests, inserted by SQL because the {@code customer} entity
 * belongs to the Phase 2 {@code customers} module: e-mails on {@code .example}, invented company
 * names, the fictitious phone of the test rules.
 */
final class AuthTestData {

    private AuthTestData() {}

    static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@cliente.example";
    }

    /** A customer in the given state (chapter 08 §2.4) with its login identity; returns the customer_user id. */
    static long customerUser(JdbcTemplate jdbc, String email, String status) {
        Instant now = Instant.now();
        boolean verified = !"PENDING_VERIFICATION".equals(status);
        Long customerId = jdbc.queryForObject(
                "insert into customer (email, company_name, address_line, city, province, postal_code, phone,"
                        + " status, email_verified_at, approved_at, declined_at, source)"
                        + " values (?, 'Ditta Esempio S.r.l.', 'Via di Prova 1', 'Seregno', 'MB', '20831',"
                        + " '+39 333 000 0000', ?, ?, ?, ?, 'CUSTOMER_AREA') returning id",
                Long.class,
                email,
                status,
                verified ? Timestamp.from(now) : null,
                "ACTIVE".equals(status) ? Timestamp.from(now) : null,
                "DECLINED".equals(status) ? Timestamp.from(now) : null);
        return jdbc.queryForObject(
                "insert into customer_user (customer_id, email) values (?, ?) returning id",
                Long.class,
                customerId,
                email);
    }
}
