package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** Routes of the orders module: the customer's list, detail and wizard, all behind a customer session. */
@Component
public class OrdersRouteContributor implements SecurityRouteContributor {

    @Override
    public void contribute(SecurityRoutes routes) {
        routes.customer(HttpMethod.GET, OrderRoutes.LIST, OrderRoutes.NEW + "**", OrderRoutes.DETAIL)
                .customer(HttpMethod.POST, OrderRoutes.NEW + "**");
    }
}
