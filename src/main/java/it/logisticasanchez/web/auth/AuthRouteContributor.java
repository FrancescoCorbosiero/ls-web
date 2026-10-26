package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * The routes of the auth module (ADR 0003, ADR 0004): the sign-in entries of both areas, the
 * link pages (sign-in, e-mail change) and the logouts. Every one is also a line of
 * {@code route-matrix.tsv}.
 */
@Component
public class AuthRouteContributor implements SecurityRouteContributor {

    @Override
    public void contribute(SecurityRoutes routes) {
        routes
                // Customer area (CUS-01, CUS-06, ADR 0032): sign-in page, password sign-in, link pages
                // (GET renders, POST consumes), logout. The registration page is the customers
                // module's; the home is the orders module's.
                .permitGet(AuthRoutes.CUSTOMER_AREA, AuthRoutes.CUSTOMER_LINK, AuthRoutes.CUSTOMER_CHANGE_EMAIL)
                .permit(
                        HttpMethod.POST,
                        AuthRoutes.CUSTOMER_AREA,
                        AuthRoutes.CUSTOMER_PASSWORD,
                        AuthRoutes.CUSTOMER_LINK,
                        AuthRoutes.CUSTOMER_CHANGE_EMAIL)
                .customer(HttpMethod.POST, AuthRoutes.CUSTOMER_LOGOUT)
                // Back office (ADM-01): login page and form, set-password link, logout; the code
                // after the password (ENH-09: the controller checks the pending login, 404 while
                // security.adminTotp is off).
                .permitGet(AuthRoutes.BACKOFFICE, AuthRoutes.STAFF_SET_PASSWORD, AuthRoutes.STAFF_TWO_FACTOR)
                .permit(
                        HttpMethod.POST,
                        AuthRoutes.STAFF_LOGIN,
                        AuthRoutes.STAFF_SET_PASSWORD,
                        AuthRoutes.STAFF_TWO_FACTOR)
                .staff(HttpMethod.POST, AuthRoutes.STAFF_LOGOUT);
    }
}
