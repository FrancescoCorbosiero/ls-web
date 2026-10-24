package it.logisticasanchez.web.reports;

import it.logisticasanchez.web.auth.AuthRoutes;

/** The paths of the reports module (chapter 10 §2 URL map; ADR 0016): the two authenticated downloads. */
public final class ReportRoutes {

    public static final String STAFF_DOWNLOAD = AuthRoutes.BACKOFFICE + "report/{id}/download";
    public static final String CUSTOMER_DOWNLOAD = AuthRoutes.CUSTOMER_AREA + "report/{id}/download";

    private ReportRoutes() {}

    public static String staffDownload(long id) {
        return STAFF_DOWNLOAD.replace("{id}", Long.toString(id));
    }

    public static String customerDownload(long id) {
        return CUSTOMER_DOWNLOAD.replace("{id}", Long.toString(id));
    }
}
