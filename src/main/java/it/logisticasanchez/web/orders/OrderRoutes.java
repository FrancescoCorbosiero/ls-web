package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.auth.AuthRoutes;

/** The customer-area paths of the orders module (chapter 10 §2 URL map; CUS-07 edit and cancel, ADR 0028; ENH-07 repeat, ADR 0033). */
public final class OrderRoutes {

    public static final String LIST = AuthRoutes.CUSTOMER_HOME;
    public static final String NEW = LIST + "nuovo/";
    public static final String NEW_REVIEW = NEW + "riepilogo/";
    public static final String NEW_DONE = NEW + "inviato/";
    public static final String DETAIL = LIST + "{codice}/";
    public static final String EDIT = DETAIL + "modifica";
    public static final String CANCEL = DETAIL + "annulla";
    /** ENH-07 «Ordina di nuovo» (flag orders.repeat, ADR 0033). */
    public static final String REPEAT = DETAIL + "ripeti";

    private OrderRoutes() {}

    public static String detail(String code) {
        return LIST + code + "/";
    }
}
