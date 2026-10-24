package it.logisticasanchez.web.reports;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A report run as the back office and the e-mails see it (never the entity). */
public record ReportView(
        long id,
        long customerId,
        long requestedBy,
        LocalDate from,
        LocalDate to,
        boolean onlyDelivered,
        boolean sentToCustomer,
        List<String> recipients,
        int rowCount,
        Instant createdAt) {

    static ReportView of(ReportRun run) {
        return new ReportView(
                run.getId(),
                run.getCustomerId(),
                run.getRequestedBy(),
                run.getDateFrom(),
                run.getDateTo(),
                run.isOnlyDelivered(),
                run.isSendToCustomer(),
                run.getRecipients(),
                run.getRowCount(),
                run.getCreatedAt());
    }

    /** {@code report-ordini-01_06_2026_30_06_2026.pdf} (the legacy pattern without the local part of an e-mail). */
    public String fileName() {
        return "report-ordini-" + ReportPeriod.fileName(from) + "_" + ReportPeriod.fileName(to) + ".pdf";
    }

    public String periodText() {
        return ReportPeriod.italian(from) + " / " + ReportPeriod.italian(to);
    }
}
