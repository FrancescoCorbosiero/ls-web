package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Routes of the back office (ADR 0002, 0017, 0028): customers, orders (their creation, change and
 * cancellation included; an approved order only by an ADMIN, checked by the service), tracking and
 * reports for every staff session, with the office tools of ADR 0035 (CSV exports, bulk tracking)
 * the website requests of ADR 0036, the saved quotes of ADR 0037 and the customers' data changes of ADR 0038 (404 while their
 * flag is off); settings, staff users, the privacy tools, the audit log viewer of ADR 0040 and the
 * site notices of ADR 0041 and the office closures of ADR 0042 for ADMIN only.
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
                        BackofficeRoutes.LEAD,
                        BackofficeRoutes.LEAD_QUOTE,
                        BackofficeRoutes.QUOTE_PDF)
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
                        BackofficeRoutes.LEAD_LINK,
                        BackofficeRoutes.LEAD_QUOTE,
                        BackofficeRoutes.QUOTE_WITHDRAW,
                        BackofficeRoutes.CUSTOMER_CHANGE_APPROVE,
                        BackofficeRoutes.CUSTOMER_CHANGE_DECLINE)
                .admin(
                        HttpMethod.GET,
                        BackofficeRoutes.SETTINGS,
                        BackofficeRoutes.STAFF_USERS,
                        BackofficeRoutes.PRIVACY,
                        BackofficeRoutes.AUDIT,
                        BackofficeRoutes.NOTICES,
                        BackofficeRoutes.CLOSURES)
                .admin(
                        HttpMethod.POST,
                        BackofficeRoutes.SETTINGS_FLAG,
                        BackofficeRoutes.SETTINGS_RECIPIENTS,
                        BackofficeRoutes.SETTINGS_RECIPIENT_REMOVE,
                        BackofficeRoutes.STAFF_USERS,
                        BackofficeRoutes.STAFF_USER_TWO_FACTOR_RESET,
                        BackofficeRoutes.NOTICES,
                        BackofficeRoutes.NOTICE_END,
                        BackofficeRoutes.NOTICE_DELETE,
                        BackofficeRoutes.CLOSURES,
                        BackofficeRoutes.CLOSURE_REMOVE,
                        BackofficeRoutes.PRIVACY_LOOKUP,
                        BackofficeRoutes.PRIVACY_EXPORT,
                        BackofficeRoutes.PRIVACY_ANONYMISE);
    }
}
