package it.logisticasanchez.web.auth;

import java.io.Serializable;
import java.util.Objects;

/**
 * The customer user as the session carries it (CUS-01, ADR 0004 "identity from the session"):
 * the {@code customer_user} id, its customer and the e-mail at sign-in time. Equality is by
 * customer-user id, so the session registry groups every session of the same login and
 * {@link CustomerSessionService#endAllSessions} finds them all.
 */
public final class CustomerPrincipal implements Serializable {

    private final long customerUserId;
    private final long customerId;
    private final String email;

    public CustomerPrincipal(long customerUserId, long customerId, String email) {
        this.customerUserId = customerUserId;
        this.customerId = customerId;
        this.email = Objects.requireNonNull(email, "email");
    }

    public long customerUserId() {
        return customerUserId;
    }

    public long customerId() {
        return customerId;
    }

    public String email() {
        return email;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CustomerPrincipal that && that.customerUserId == customerUserId;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(customerUserId);
    }

    @Override
    public String toString() {
        return "customer-user:" + customerUserId;
    }
}
