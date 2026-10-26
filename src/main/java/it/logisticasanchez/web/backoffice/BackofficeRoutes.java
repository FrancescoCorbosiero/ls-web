package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.auth.AuthRoutes;

/** The paths of the back office (chapter 10 §2 URL map: {@code clienti/}, {@code ordini/}, {@code tracking/}, {@code report/}, {@code impostazioni/}; the record editing of ADM-11 and ADM-12; the office tools of ADR 0035; the website requests of ADR 0036). */
public final class BackofficeRoutes {

    public static final String HOME = AuthRoutes.BACKOFFICE;

    public static final String CUSTOMERS = HOME + "clienti/";
    public static final String CUSTOMERS_PENDING = CUSTOMERS + "in-approvazione/";
    public static final String CUSTOMER_NEW = CUSTOMERS + "nuovo";
    public static final String CUSTOMER = CUSTOMERS + "{id}/";
    public static final String CUSTOMER_APPROVE = CUSTOMER + "approva";
    public static final String CUSTOMER_DECLINE = CUSTOMER + "rifiuta";
    public static final String CUSTOMER_EDIT = CUSTOMER + "modifica";
    /** ENH-08 (admin.exports, ADR 0035): the list as a CSV file. */
    public static final String CUSTOMERS_EXPORT = CUSTOMERS + "esporta";

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
    /** ENH-08 (admin.exports, ADR 0035): the list as a CSV file, with the same filters. */
    public static final String ORDERS_EXPORT = ORDERS + "esporta";

    public static final String TRACKING = HOME + "tracking/";
    /** ENH-15 (orders.bulkTracking, ADR 0035): one state on many codes. */
    public static final String TRACKING_BULK = TRACKING + "in-blocco";

    public static final String REPORTS = HOME + "report/";

    /** ENH-06 (leads.inbox, ADR 0036): the quote and contact requests of the website. */
    public static final String LEADS = HOME + "richieste/";

    public static final String LEAD = LEADS + "{id}/";
    public static final String LEAD_STATUS = LEAD + "stato";
    public static final String LEAD_LINK = LEAD + "collega";

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

    public static String lead(long id) {
        return LEAD.replace("{id}", Long.toString(id));
    }
}
