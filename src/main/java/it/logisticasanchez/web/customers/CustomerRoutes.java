package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.AuthRoutes;

/** The customer-area paths of the customers module (chapter 10 §2 URL map; the address book of ENH-07, ADR 0033; the data change of ENH-16, ADR 0038). */
public final class CustomerRoutes {

    public static final String REGISTRATION = AuthRoutes.CUSTOMER_REGISTRATION;
    public static final String PROFILE = AuthRoutes.CUSTOMER_AREA + "profilo/";
    public static final String PROFILE_EMAIL = PROFILE + "email";
    public static final String PROFILE_PASSWORD = PROFILE + "password";
    /** ENH-16 (customers.profileEdit, ADR 0038): the customer's own data, and taking back the change waiting for approval. */
    public static final String PROFILE_DATA = PROFILE + "dati";

    public static final String PROFILE_DATA_WITHDRAW = PROFILE + "dati/ritira";
    public static final String ADDRESS_BOOK = PROFILE + "rubrica/";
    public static final String ADDRESS_NEW = ADDRESS_BOOK + "nuovo";
    public static final String ADDRESS_EDIT = ADDRESS_BOOK + "{id}";
    public static final String ADDRESS_DELETE = ADDRESS_BOOK + "{id}/elimina";

    private CustomerRoutes() {}

    public static String address(long id) {
        return ADDRESS_BOOK + id;
    }
}
