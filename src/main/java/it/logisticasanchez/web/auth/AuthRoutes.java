package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.security.SecurityRoutes;

/** The paths of the auth module (chapter 10 §2 URL map; PD-14: {@code accesso} has no trailing slash). */
public final class AuthRoutes {

    public static final String CUSTOMER_AREA = SecurityRoutes.CUSTOMER_AREA;
    public static final String CUSTOMER_LINK = CUSTOMER_AREA + "accesso";
    public static final String CUSTOMER_PASSWORD = CUSTOMER_AREA + "password";
    public static final String CUSTOMER_REGISTRATION = CUSTOMER_AREA + "registrazione/";
    public static final String CUSTOMER_CHANGE_EMAIL = CUSTOMER_AREA + "cambio-email";
    public static final String CUSTOMER_LOGOUT = CUSTOMER_AREA + "logout";
    public static final String CUSTOMER_HOME = CUSTOMER_AREA + "ordini/";

    public static final String BACKOFFICE = SecurityRoutes.BACKOFFICE;
    public static final String STAFF_LOGIN = BACKOFFICE + "login";
    public static final String STAFF_LOGOUT = BACKOFFICE + "logout";
    public static final String STAFF_SET_PASSWORD = BACKOFFICE + "imposta-password";
    /** ENH-09 (security.adminTotp, ADR 0039): the code after the password, or the set-up of the app. */
    public static final String STAFF_TWO_FACTOR = BACKOFFICE + "verifica";

    private AuthRoutes() {}
}
