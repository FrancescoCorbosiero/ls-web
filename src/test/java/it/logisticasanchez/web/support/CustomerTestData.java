package it.logisticasanchez.web.support;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

/**
 * Synthetic customers for the integration tests, inserted by SQL so that every module's tests
 * start from the same rows: e-mails on {@code .example}, invented company names, the fictitious
 * phone of the test rules. {@link #session} builds the HTTP session of a signed-in customer.
 */
public final class CustomerTestData {

    private CustomerTestData() {}

    public static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@cliente.example";
    }

    /**
     * A synthetic customer password made at run time (ADR 0032): no password-like literal sits
     * in the sources, so the secret scans of CI have nothing to flag.
     */
    public static String newPassword() {
        return "Prova-" + UUID.randomUUID();
    }

    /** A customer in the given state (chapter 08 §2.4) with its OWNER login identity. */
    public static Ids customerUser(JdbcTemplate jdbc, String email, String status) {
        return customerUser(jdbc, email, status, "Ditta Esempio S.r.l.");
    }

    public static Ids customerUser(JdbcTemplate jdbc, String email, String status, String companyName) {
        Instant now = Instant.now();
        boolean verified = !"PENDING_VERIFICATION".equals(status);
        Long customerId = jdbc.queryForObject(
                "insert into customer (email, company_name, address_line, city, province, postal_code, phone,"
                        + " status, email_verified_at, approved_at, declined_at, source)"
                        + " values (?, ?, 'Via di Prova 1', 'Seregno', 'MB', '20831',"
                        + " '+393330000000', ?, ?, ?, ?, 'CUSTOMER_AREA') returning id",
                Long.class,
                email,
                companyName,
                status,
                verified ? Timestamp.from(now) : null,
                "ACTIVE".equals(status) ? Timestamp.from(now) : null,
                "DECLINED".equals(status) ? Timestamp.from(now) : null);
        Long userId = jdbc.queryForObject(
                "insert into customer_user (customer_id, email) values (?, ?) returning id",
                Long.class,
                customerId,
                email);
        return new Ids(customerId, userId, email);
    }

    /** A session carrying the customer's principal, as {@code CustomerSessionService.signIn} stores it. */
    public static MockHttpSession session(Ids ids) {
        return Sessions.customer(
                new CustomerPrincipal(ids.customerUserId(), ids.customerId(), ids.email()), Instant.now());
    }

    public record Ids(long customerId, long customerUserId, String email) {
        public CustomerPrincipal principal() {
            return new CustomerPrincipal(customerUserId, customerId, email);
        }
    }
}
