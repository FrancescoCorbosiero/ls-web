package it.logisticasanchez.web.reports;

import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.notifications.EmailMessage;
import it.logisticasanchez.web.notifications.MailLayout;
import it.logisticasanchez.web.notifications.MailLayout.Content;
import it.logisticasanchez.web.notifications.MailLayout.Row;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EML-07 «Report ordini PDF» (legacy L-E9/L-E10 wording of 12c §1.3 through the reader notes):
 * the reference dates, «Puoi scaricare il report qui:» and the «Scarica PDF» button, whose link
 * is an authenticated download (ADR 0016): the back-office one for the office and the
 * allow-listed addresses, the customer-area one for the customer the report is about.
 */
@Component
public class ReportMails {

    static final String KIND = "EML-07";

    private final MailLayout layout;
    private final BusinessProperties business;

    public ReportMails(MailLayout layout, BusinessProperties business) {
        this.layout = layout;
        this.business = business;
    }

    /** To the office mailbox or an allow-listed address: the staff download link. */
    EmailMessage toOffice(String recipient, ReportView report, CustomerView customer) {
        Content content = content(report, customer, true);
        content.button("Scarica PDF", business.siteUrl() + ReportRoutes.staffDownload(report.id()));
        EmailMessage message = layout.office(KIND, "Report ordini PDF", content, null);
        return recipient.equalsIgnoreCase(business.officeEmail())
                ? message
                : new EmailMessage(
                        message.kind(),
                        recipient,
                        message.subject(),
                        message.bodyText(),
                        message.bodyHtml(),
                        message.replyTo(),
                        null);
    }

    /** To the customer the report is about: the customer-area download link (own session only). */
    EmailMessage toCustomer(ReportView report, CustomerView customer) {
        Content content = content(report, customer, false);
        content.button("Scarica PDF", business.siteUrl() + ReportRoutes.customerDownload(report.id()));
        return layout.customer(KIND, customer.email(), "Report ordini PDF", content);
    }

    private static Content content(ReportView report, CustomerView customer, boolean withCustomer) {
        Content content = Content.titled("Report ordini")
                .paragraph("Date di riferimento: " + report.periodText())
                .paragraph(
                        report.onlyDelivered()
                                ? "Il report contiene solo gli ordini consegnati nel periodo."
                                : "Il report contiene gli ordini confermati nel periodo.");
        if (withCustomer) {
            content.table(
                    "Cliente",
                    List.of(
                            new Row("Ragione sociale / nominativo", customer.companyName()),
                            new Row("Email", customer.email()),
                            new Row("Ordini nel report", Integer.toString(report.rowCount()))));
        } else {
            content.paragraph("Ordini nel report: " + report.rowCount() + ".");
        }
        content.paragraph("Puoi scaricare il report qui (è necessario accedere con il tuo account):");
        return content;
    }
}
