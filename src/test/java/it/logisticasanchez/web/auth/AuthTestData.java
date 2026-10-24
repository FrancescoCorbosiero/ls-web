package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.support.CustomerTestData;
import org.springframework.jdbc.core.JdbcTemplate;

/** The auth tests' view of {@link CustomerTestData}: synthetic customers on {@code .example} addresses. */
final class AuthTestData {

    private AuthTestData() {}

    static String uniqueEmail(String prefix) {
        return CustomerTestData.uniqueEmail(prefix);
    }

    /** A customer in the given state (chapter 08 §2.4) with its login identity; returns the customer_user id. */
    static long customerUser(JdbcTemplate jdbc, String email, String status) {
        return CustomerTestData.customerUser(jdbc, email, status).customerUserId();
    }
}
