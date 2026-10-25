package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Routes of the customers module: the registration page is public (it is reached from a
 * {@code REGISTER} link; the token, or a customer session, is checked by the controller), the
 * profile needs a customer session. Every one is a line of {@code route-matrix.tsv}.
 */
@Component
public class CustomersRouteContributor implements SecurityRouteContributor {

    @Override
    public void contribute(SecurityRoutes routes) {
        routes.permitGet(CustomerRoutes.REGISTRATION)
                .permit(HttpMethod.POST, CustomerRoutes.REGISTRATION)
                .customer(HttpMethod.GET, CustomerRoutes.PROFILE)
                .customer(HttpMethod.POST, CustomerRoutes.PROFILE_EMAIL, CustomerRoutes.PROFILE_PASSWORD);
    }
}
