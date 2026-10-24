package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * The routes of the auth module (ADR 0003, ADR 0004): the sign-in entries of both areas, the
 * link pages, the logouts and the Phase 1 stub home pages that the {@code orders} and
 * {@code backoffice} modules replace. Every one is also a line of {@code route-matrix.tsv}.
 */
@Component
public class AuthRouteContributor implements SecurityRouteContributor {

    @Override
    public void contribute(SecurityRoutes routes) {
        routes
                // Customer area (CUS-01): e-mail form, link page (GET renders, POST consumes), registration link,
                // logout.
                .permitGet(AuthRoutes.CUSTOMER_AREA, AuthRoutes.CUSTOMER_LINK, AuthRoutes.CUSTOMER_REGISTRATION)
                .permit(HttpMethod.POST, AuthRoutes.CUSTOMER_AREA, AuthRoutes.CUSTOMER_LINK)
                .customer(HttpMethod.POST, AuthRoutes.CUSTOMER_LOGOUT)
                .customer(HttpMethod.GET, AuthRoutes.CUSTOMER_HOME)
                // Back office (ADM-01): login page and form, set-password link, logout.
                .permitGet(AuthRoutes.BACKOFFICE, AuthRoutes.STAFF_SET_PASSWORD)
                .permit(HttpMethod.POST, AuthRoutes.STAFF_LOGIN, AuthRoutes.STAFF_SET_PASSWORD)
                .staff(HttpMethod.POST, AuthRoutes.STAFF_LOGOUT);
    }
}
