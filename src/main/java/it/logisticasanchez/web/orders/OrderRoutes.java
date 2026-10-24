package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.auth.AuthRoutes;

/** The customer-area paths of the orders module (chapter 10 §2 URL map). */
public final class OrderRoutes {

    public static final String LIST = AuthRoutes.CUSTOMER_HOME;
    public static final String NEW = LIST + "nuovo/";
    public static final String NEW_RECIPIENT = NEW + "destinatario/";
    public static final String NEW_REVIEW = NEW + "riepilogo/";
    public static final String NEW_DONE = NEW + "inviato/";
    public static final String DETAIL = LIST + "{codice}/";

    private OrderRoutes() {}
}
