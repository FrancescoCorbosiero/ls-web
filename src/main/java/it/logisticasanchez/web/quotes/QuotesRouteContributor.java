package it.logisticasanchez.web.quotes;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Routes of the saved quotes (ENH-20, ADR 0037): the page and the PDF of a link, public and never
 * cached; the order form of a quote behind a customer session. All 404 while {@code quotes.saved}
 * is off.
 */
@Component
public class QuotesRouteContributor implements SecurityRouteContributor {

    @Override
    public void contribute(SecurityRoutes routes) {
        routes.permitGet(QuoteRoutes.PAGE, QuoteRoutes.PDF)
                .noStore(QuoteRoutes.PAGE, QuoteRoutes.PDF)
                .customer(HttpMethod.GET, QuoteRoutes.ACCEPT)
                .customer(HttpMethod.POST, QuoteRoutes.ACCEPT);
    }
}
