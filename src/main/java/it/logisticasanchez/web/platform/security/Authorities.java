package it.logisticasanchez.web.platform.security;

/**
 * The granted authorities of the app (chapter 10 §2, Q-17): a customer session carries
 * {@code ROLE_CUSTOMER}; a staff session carries {@code ROLE_ADMIN} or {@code ROLE_OPERATOR}.
 * Role names without the prefix for {@code hasRole}/{@code hasAnyRole}.
 */
public final class Authorities {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String ADMIN = "ADMIN";
    public static final String OPERATOR = "OPERATOR";

    public static final String ROLE_CUSTOMER = "ROLE_" + CUSTOMER;
    public static final String ROLE_ADMIN = "ROLE_" + ADMIN;
    public static final String ROLE_OPERATOR = "ROLE_" + OPERATOR;

    private Authorities() {}
}
