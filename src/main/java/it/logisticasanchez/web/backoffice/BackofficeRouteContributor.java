package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Routes of the back office (ADR 0002, 0017, 0028): customers, orders (their creation, change and
 * cancellation included; an approved order only by an ADMIN, checked by the service), tracking and
 * reports for every staff session, with the office tools of ADR 0035 (CSV exports, bulk tracking)
 * and the website requests of ADR 0036 (404 while their flag is off); settings, staff users and the
 * privacy tools for ADMIN only.
 * Every one is a line of {@code route-matrix.tsv}.
 */
@Component
public class BackofficeRouteContributor implements SecurityRouteContributor {

    @Override
    public void contribute(SecurityRoutes routes) {
        routes.staff(
                        HttpMethod.GET,
                        BackofficeRoutes.CUSTOMERS,
                        BackofficeRoutes.CUSTOMERS_PENDING,
                        BackofficeRoutes.CUSTOMER_NEW,
                        BackofficeRoutes.CUSTOMER,
                        BackofficeRoutes.CUSTOMER_APPROVE,
                        BackofficeRoutes.CUSTOMER_DECLINE,
                        BackofficeRoutes.CUSTOMER_EDIT,
                        BackofficeRoutes.ORDERS,
                        BackofficeRoutes.ORDERS_PENDING,
                        BackofficeRoutes.ORDER_NEW,
                        BackofficeRoutes.ORDER,
                        BackofficeRoutes.ORDER_APPROVE,
                        BackofficeRoutes.ORDER_DECLINE,
                        BackofficeRoutes.ORDER_EDIT,
                        BackofficeRoutes.ORDER_CANCEL,
                        BackofficeRoutes.TRACKING,
                        BackofficeRoutes.REPORTS,
                        BackofficeRoutes.CUSTOMERS_EXPORT,
                        BackofficeRoutes.ORDERS_EXPORT,
                        BackofficeRoutes.LEADS,
                        BackofficeRoutes.LEAD)
                .staff(
                        HttpMethod.POST,
                        BackofficeRoutes.CUSTOMER_NEW,
                        BackofficeRoutes.CUSTOMER_APPROVE,
                        BackofficeRoutes.CUSTOMER_DECLINE,
                        BackofficeRoutes.CUSTOMER_EDIT,
                        BackofficeRoutes.ORDER_NEW,
                        BackofficeRoutes.ORDER_APPROVE,
                        BackofficeRoutes.ORDER_DECLINE,
                        BackofficeRoutes.ORDER_TRACKING,
                        BackofficeRoutes.ORDER_NOTE,
                        BackofficeRoutes.ORDER_EDIT,
                        BackofficeRoutes.ORDER_CANCEL,
                        BackofficeRoutes.TRACKING,
                        BackofficeRoutes.REPORTS,
                        BackofficeRoutes.TRACKING_BULK,
                        BackofficeRoutes.LEAD_STATUS,
                        BackofficeRoutes.LEAD_LINK)
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
