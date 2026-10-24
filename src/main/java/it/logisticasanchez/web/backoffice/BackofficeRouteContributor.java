package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Routes of the back office (ADR 0002, 0017): customers, orders, tracking and reports for every
 * staff session; settings, staff users and the privacy tools for ADMIN only. Every one is a
 * line of {@code route-matrix.tsv}.
 */
@Component
public class BackofficeRouteContributor implements SecurityRouteContributor {

    @Override
    public void contribute(SecurityRoutes routes) {
        routes.staff(
                        HttpMethod.GET,
                        BackofficeRoutes.CUSTOMERS,
                        BackofficeRoutes.CUSTOMERS_PENDING,
                        BackofficeRoutes.CUSTOMER,
                        BackofficeRoutes.CUSTOMER_APPROVE,
                        BackofficeRoutes.CUSTOMER_DECLINE,
                        BackofficeRoutes.ORDERS,
                        BackofficeRoutes.ORDERS_PENDING,
                        BackofficeRoutes.ORDER,
                        BackofficeRoutes.ORDER_APPROVE,
                        BackofficeRoutes.ORDER_DECLINE,
                        BackofficeRoutes.TRACKING,
                        BackofficeRoutes.REPORTS)
                .staff(
                        HttpMethod.POST,
                        BackofficeRoutes.CUSTOMER_APPROVE,
                        BackofficeRoutes.CUSTOMER_DECLINE,
                        BackofficeRoutes.ORDER_APPROVE,
                        BackofficeRoutes.ORDER_DECLINE,
                        BackofficeRoutes.ORDER_TRACKING,
                        BackofficeRoutes.ORDER_NOTE,
                        BackofficeRoutes.TRACKING,
                        BackofficeRoutes.REPORTS)
                .admin(
                        HttpMethod.GET,
                        BackofficeRoutes.SETTINGS,
                        BackofficeRoutes.STAFF_USERS,
                        BackofficeRoutes.PRIVACY)
                .admin(
                        HttpMethod.POST,
                        BackofficeRoutes.SETTINGS_FLAG,
                        BackofficeRoutes.SETTINGS_RECIPIENTS,
                        BackofficeRoutes.SETTINGS_RECIPIENT_REMOVE,
                        BackofficeRoutes.STAFF_USERS,
                        BackofficeRoutes.PRIVACY_LOOKUP,
                        BackofficeRoutes.PRIVACY_EXPORT,
                        BackofficeRoutes.PRIVACY_ANONYMISE);
    }
}
