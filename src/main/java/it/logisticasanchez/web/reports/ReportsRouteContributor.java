package it.logisticasanchez.web.reports;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** Routes of the reports module: the staff download and the customer's own download (ADM-09). */
@Component
public class ReportsRouteContributor implements SecurityRouteContributor {

    @Override
    public void contribute(SecurityRoutes routes) {
        routes.staff(HttpMethod.GET, ReportRoutes.STAFF_DOWNLOAD)
                .customer(HttpMethod.GET, ReportRoutes.CUSTOMER_DOWNLOAD);
    }
}
