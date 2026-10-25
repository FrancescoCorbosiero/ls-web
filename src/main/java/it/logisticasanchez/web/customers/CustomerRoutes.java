package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.AuthRoutes;

/** The customer-area paths of the customers module (chapter 10 §2 URL map). */
public final class CustomerRoutes {

    public static final String REGISTRATION = AuthRoutes.CUSTOMER_REGISTRATION;
    public static final String PROFILE = AuthRoutes.CUSTOMER_AREA + "profilo/";
    public static final String PROFILE_EMAIL = PROFILE + "email";
    public static final String PROFILE_PASSWORD = PROFILE + "password";

    private CustomerRoutes() {}
}
