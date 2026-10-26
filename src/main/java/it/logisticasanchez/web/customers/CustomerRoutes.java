package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.AuthRoutes;

/** The customer-area paths of the customers module (chapter 10 §2 URL map; the address book of ENH-07, ADR 0033). */
public final class CustomerRoutes {

    public static final String REGISTRATION = AuthRoutes.CUSTOMER_REGISTRATION;
    public static final String PROFILE = AuthRoutes.CUSTOMER_AREA + "profilo/";
    public static final String PROFILE_EMAIL = PROFILE + "email";
    public static final String PROFILE_PASSWORD = PROFILE + "password";
    public static final String ADDRESS_BOOK = PROFILE + "rubrica/";
    public static final String ADDRESS_NEW = ADDRESS_BOOK + "nuovo";
    public static final String ADDRESS_EDIT = ADDRESS_BOOK + "{id}";
    public static final String ADDRESS_DELETE = ADDRESS_BOOK + "{id}/elimina";

    private CustomerRoutes() {}

    public static String address(long id) {
        return ADDRESS_BOOK + id;
    }
}
