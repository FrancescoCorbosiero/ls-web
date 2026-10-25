package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.auth.AuthRoutes;

/** The paths of the back office (chapter 10 §2 URL map: {@code clienti/}, {@code ordini/}, {@code tracking/}, {@code report/}, {@code impostazioni/}; the record editing of ADM-11 and ADM-12). */
public final class BackofficeRoutes {

    public static final String HOME = AuthRoutes.BACKOFFICE;

    public static final String CUSTOMERS = HOME + "clienti/";
    public static final String CUSTOMERS_PENDING = CUSTOMERS + "in-approvazione/";
    public static final String CUSTOMER_NEW = CUSTOMERS + "nuovo";
    public static final String CUSTOMER = CUSTOMERS + "{id}/";
    public static final String CUSTOMER_APPROVE = CUSTOMER + "approva";
    public static final String CUSTOMER_DECLINE = CUSTOMER + "rifiuta";
    public static final String CUSTOMER_EDIT = CUSTOMER + "modifica";

    public static final String ORDERS = HOME + "ordini/";
    public static final String ORDERS_PENDING = ORDERS + "in-approvazione/";
    public static final String ORDER_NEW = ORDERS + "nuovo";
    public static final String ORDER = ORDERS + "{id}/";
    public static final String ORDER_APPROVE = ORDER + "approva";
    public static final String ORDER_DECLINE = ORDER + "rifiuta";
    public static final String ORDER_TRACKING = ORDER + "tracking";
    public static final String ORDER_NOTE = ORDER + "note";
    public static final String ORDER_EDIT = ORDER + "modifica";
    public static final String ORDER_CANCEL = ORDER + "annulla";

    public static final String TRACKING = HOME + "tracking/";

    public static final String REPORTS = HOME + "report/";

    public static final String SETTINGS = HOME + "impostazioni/";
    public static final String SETTINGS_FLAG = SETTINGS + "flag";
    public static final String SETTINGS_RECIPIENTS = SETTINGS + "destinatari";
    public static final String SETTINGS_RECIPIENT_REMOVE = SETTINGS + "destinatari/{id}/rimuovi";
    public static final String STAFF_USERS = SETTINGS + "utenti/";
    public static final String PRIVACY = SETTINGS + "privacy/";
    public static final String PRIVACY_LOOKUP = PRIVACY + "cerca";
    public static final String PRIVACY_EXPORT = PRIVACY + "esporta";
    public static final String PRIVACY_ANONYMISE = PRIVACY + "anonimizza";

    private BackofficeRoutes() {}

    public static String customer(long id) {
        return CUSTOMER.replace("{id}", Long.toString(id));
    }

    public static String order(long id) {
        return ORDER.replace("{id}", Long.toString(id));
    }
}
