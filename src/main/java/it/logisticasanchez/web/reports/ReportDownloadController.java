package it.logisticasanchez.web.reports;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The authenticated downloads of ADM-09 (ADR 0016): the back-office one for any staff session,
 * the customer-area one only for the customer the report is about. The PDF is regenerated on
 * each request and never cached (the private areas are {@code no-store}).
 */
@Controller
public class ReportDownloadController {

    private final ReportService reports;

    public ReportDownloadController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping(ReportRoutes.STAFF_DOWNLOAD)
    public ResponseEntity<byte[]> staff(@PathVariable("id") long id) {
        return pdf(reports.pdfForStaff(id));
    }

    @GetMapping(ReportRoutes.CUSTOMER_DOWNLOAD)
    public ResponseEntity<byte[]> customer(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        return pdf(reports.pdfForCustomer(id, principal.customerId()));
    }

    private static ResponseEntity<byte[]> pdf(ReportService.Download download) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.fileName())
                                .build()
                                .toString())
                .body(download.bytes());
    }
}
