package it.logisticasanchez.web.reports;

import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.tracking.TrackingState;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/**
 * The report PDF of ADM-09 (12c §3.4, ADR 0016): A4 landscape, the «I tuoi dati» block with the
 * customer's company name, address, province, city, CAP and e-mail, then one row per order with
 * the 13 legacy columns and headers, «-» for a missing value. Differences from the legacy
 * generator, all deliberate: no partner-network header (confidential), the period printed
 * under the title, the table header repeated on every page, Italian numbers, «Dt/Cons.» = the
 * delivery date or «-». No internal notes, no margins.
 */
@Component
public class ReportPdf {

    /** The 13 headers, verbatim from the legacy generator (12c §3.4). */
    public static final List<String> COLUMNS = List.of(
            "Codice",
            "Codice Tras.",
            "Dt/Sped.",
            "Dt/Cons.",
            "Servizio",
            "Destinazione",
            "Cliente",
            "Cap",
            "Città",
            "Colli",
            "Peso",
            "Volume",
            "Importo");

    /** Relative widths: the legacy used equal columns, which wrap codes and labels on every hyphen. */
    private static final float[] COLUMN_WIDTHS = {12, 10, 7, 7, 10, 13, 10, 5, 9, 4, 6, 6, 6};

    private static final Color LIGHT_GREY = new Color(0xE4, 0xE7, 0xEB);
    private static final Color DARK_GREY = new Color(0xB0, 0xB7, 0xBF);

    private final BusinessProperties business;

    public ReportPdf(BusinessProperties business) {
        this.business = business;
    }

    public byte[] render(CustomerView customer, ReportView report, List<OrderView> orders) {
        Document document = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, out);
        document.addAuthor(business.brandName());
        document.addCreator(business.brandName());
        document.addTitle(business.brandName() + " - Report");
        document.open();

        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font boldOblique = FontFactory.getFont(FontFactory.HELVETICA_BOLDOBLIQUE, 8);

        document.add(new Paragraph(business.brandName() + " - Report ordini", title));
        document.add(new Paragraph("Periodo: " + report.periodText(), small));

        PdfPTable data = new PdfPTable(new float[] {30, 70});
        data.setWidthPercentage(100);
        data.setSpacingBefore(10);
        PdfPCell heading = new PdfPCell(new Phrase("I tuoi dati", boldOblique));
        heading.setColspan(2);
        heading.setBackgroundColor(LIGHT_GREY);
        data.addCell(heading);
        row(data, "Ragione Sociale:", customer.companyName(), boldOblique);
        row(data, "Indirizzo:", customer.addressLine(), boldOblique);
        row(data, "Provincia:", customer.province(), boldOblique);
        row(data, "Città:", customer.city(), boldOblique);
        row(data, "CAP:", customer.postalCode(), boldOblique);
        row(data, "Email:", customer.email(), boldOblique);
        document.add(data);
        document.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(COLUMN_WIDTHS);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String column : COLUMNS) {
            PdfPCell cell = new PdfPCell(new Phrase(column, bold));
            cell.setBackgroundColor("Codice".equals(column) ? DARK_GREY : LIGHT_GREY);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
        for (OrderView order : orders) {
            List<String> values = values(order);
            for (int i = 0; i < values.size(); i++) {
                PdfPCell cell = new PdfPCell(new Phrase(values.get(i), bold));
                if (i == 0) {
                    cell.setBackgroundColor(LIGHT_GREY);
                }
                table.addCell(cell);
            }
        }
        document.add(table);
        if (orders.isEmpty()) {
            document.add(new Paragraph("Nessun ordine nel periodo.", small));
        }
        document.close();
        return out.toByteArray();
    }

    /** The 13 values of one row, in column order (12c §3.4; «-» for a missing value). */
    static List<String> values(OrderView order) {
        ShipmentPartyText recipient = new ShipmentPartyText(order);
        return List.of(
                dash(order.code()),
                dash(order.transportDocumentCode()),
                isoDate(order.approvedAt()),
                order.trackingStateId() == TrackingState.CONSEGNATO ? isoDate(order.trackingUpdatedAt()) : "-",
                dash(order.accessoryServiceLabel()),
                recipient.destination(),
                dash(order.recipient().name()),
                dash(order.recipient().postalCode()),
                recipient.cityAndProvince(),
                order.packageCount() != null ? order.packageCount().toString() : Integer.toString(order.palletCount()),
                ItalianFormats.formatNumber(order.totalWeightKg(), 2),
                ItalianFormats.formatNumber(order.totalVolumeM3(), 2),
                order.amount() == null ? "-" : ItalianFormats.formatNumber(order.amount(), 2));
    }

    private static void row(PdfPTable table, String label, String value, Font font) {
        table.addCell(new PdfPCell(new Phrase(label, font)));
        table.addCell(new PdfPCell(new Phrase(dash(value), font)));
    }

    private static String isoDate(Instant instant) {
        return instant == null
                ? "-"
                : instant.atZone(ItalianFormats.ROME).toLocalDate().toString();
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** «Destinazione» = {@code indirizzo(città)} and «Città» = {@code città(provincia)} with the legacy fallbacks. */
    private record ShipmentPartyText(OrderView order) {
        String destination() {
            return dash(order.recipient().addressLine()) + "("
                    + dash(order.recipient().city()) + ")";
        }

        String cityAndProvince() {
            String city = order.recipient().city();
            return (city == null || city.isBlank() ? "." : city) + "("
                    + dash(order.recipient().province()) + ")";
        }
    }
}
