package it.logisticasanchez.web.quotes;

import it.logisticasanchez.web.auth.AuthRoutes;

/**
 * The paths of the saved quotes (ENH-20, ADR 0037): the page of the e-mailed link and its PDF,
 * public to whoever holds the token; the order form of a quote, in the customer area.
 */
public final class QuoteRoutes {

    public static final String PAGE = "/preventivi/{token}";
    public static final String PDF = PAGE + "/pdf";
    public static final String ACCEPT = AuthRoutes.CUSTOMER_AREA + "preventivi/{id}/ordina";

    private QuoteRoutes() {}

    public static String page(String token) {
        return PAGE.replace("{token}", token);
    }

    public static String accept(long id) {
        return ACCEPT.replace("{id}", Long.toString(id));
    }
}
