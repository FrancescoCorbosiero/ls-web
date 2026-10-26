package it.logisticasanchez.web.quotes;

import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
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
 * The PDF of a saved quote (ENH-20, ADR 0037): A4, the brand and the reference, the date and the
 * last valid day, the customer, the route, the lines (type and size, weight of one unit,
 * quantity), the accessory service, the amount and the staff's note. Nothing internal (no lead
 * data beyond the route, no staff names).
 */
@Component
public class QuotePdf {

    private static final Color LIGHT_GREY = new Color(0xE4, 0xE7, 0xEB);

    private final BusinessProperties business;

    public QuotePdf(BusinessProperties business) {
        this.business = business;
    }

    public byte[] render(QuoteView quote, CustomerView customer) {
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, out);
        document.addAuthor(business.brandName());
        document.addCreator(business.brandName());
        document.addTitle(business.brandName() + " - Preventivo " + quote.code());
        document.open();

        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font text = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font big = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);

        document.add(new Paragraph(business.brandName() + " - Preventivo " + quote.code(), title));
        document.add(new Paragraph(
                "Del " + ItalianFormats.formatDate(quote.createdAt()) + ", valido fino al " + quote.validUntilText(),
                text));

        PdfPTable data = new PdfPTable(new float[] {30, 70});
        data.setWidthPercentage(100);
        data.setSpacingBefore(12);
        heading(data, "Cliente", bold, 2);
        row(data, "Ragione sociale", customer.companyName(), text);
        row(data, "Indirizzo", customer.addressOneLine(), text);
        row(data, "Ritiro", customer.postalCode() + " " + customer.city(), text);
        row(data, "Consegna", quote.destination(), text);
        row(data, "Servizio accessorio", quote.accessoryServiceLabel(), text);
        document.add(data);

        PdfPTable lines = new PdfPTable(new float[] {50, 20, 15, 15});
        lines.setWidthPercentage(100);
        lines.setSpacingBefore(12);
        lines.setHeaderRows(1);
        for (String column : new String[] {"Pallet / colli", "Peso unitario", "Quantità", "Peso totale"}) {
            PdfPCell cell = new PdfPCell(new Phrase(column, bold));
            cell.setBackgroundColor(LIGHT_GREY);
            lines.addCell(cell);
        }
        for (QuoteView.Line line : quote.lines()) {
            lines.addCell(new PdfPCell(new Phrase(line.description(), text)));
            lines.addCell(number(line.weightText(), text));
            lines.addCell(number(Integer.toString(line.quantity()), text));
            lines.addCell(number(ItalianFormats.formatNumber(line.weightTotal(), 2) + " kg", text));
        }
        document.add(lines);

        Paragraph amount = new Paragraph("Importo: " + quote.amountText(), big);
        amount.setSpacingBefore(14);
        document.add(amount);
        if (quote.note() != null) {
            Paragraph note = new Paragraph(quote.note(), text);
            note.setSpacingBefore(8);
            document.add(note);
        }
        Paragraph how = new Paragraph(
                "Per ordinare apri il link ricevuto per e-mail, accedi all'area clienti e completa destinatario e"
                        + " documento di trasporto.",
                text);
        how.setSpacingBefore(14);
        document.add(how);
        document.add(
                new Paragraph(business.brandName() + " · " + business.siteUrl().replaceFirst("^https?://", ""), text));
        document.close();
        return out.toByteArray();
    }

    private static void heading(PdfPTable table, String label, Font font, int span) {
        PdfPCell cell = new PdfPCell(new Phrase(label, font));
        cell.setColspan(span);
        cell.setBackgroundColor(LIGHT_GREY);
        table.addCell(cell);
    }

    private static void row(PdfPTable table, String label, String value, Font font) {
        table.addCell(new PdfPCell(new Phrase(label, font)));
        table.addCell(new PdfPCell(new Phrase(value == null || value.isBlank() ? "-" : value, font)));
    }

    private static PdfPCell number(String value, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }
}
