package it.logisticasanchez.web.quotes;

import it.logisticasanchez.web.notifications.EmailMessage;
import it.logisticasanchez.web.notifications.MailLayout;
import it.logisticasanchez.web.notifications.MailLayout.Content;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EML-11 «Il tuo preventivo» (ENH-20, ADR 0037): to the customer's sign-in address, the
 * reference, the amount, the last valid day, the route and the button to the quote's page (the
 * link with the token, which also gives the PDF); how to order it. Queued through the outbox.
 */
@Component
public class QuoteMails {

    static final String KIND_QUOTE = "EML-11";

    private final MailLayout layout;
    private final BusinessProperties business;

    public QuoteMails(MailLayout layout, BusinessProperties business) {
        this.layout = layout;
        this.business = business;
    }

    EmailMessage quote(QuoteView quote, String customerEmail, String pickupCity, String token) {
        List<MailLayout.Row> rows = new ArrayList<>();
        rows.add(new MailLayout.Row("Preventivo", quote.code()));
        rows.add(new MailLayout.Row("Importo", quote.amountText()));
        rows.add(new MailLayout.Row("Valido fino al", quote.validUntilText()));
        if (quote.destination() != null) {
            rows.add(
                    new MailLayout.Row("Tratta", (pickupCity == null ? "" : pickupCity + " → ") + quote.destination()));
        }
        rows.add(new MailLayout.Row(
                "Merce", quote.units() + " unità, " + quote.lines().size() + " righe"));
        Content content = Content.titled("Il tuo preventivo " + quote.code())
                .paragraph("Grazie per la tua richiesta: ecco il preventivo che abbiamo preparato per te.")
                .table("Preventivo", rows)
                .paragraph("Dalla pagina del preventivo puoi scaricarlo in PDF e, quando sei pronto, trasformarlo in un"
                        + " ordine: accedi all'area clienti, completa destinatario e documento di trasporto e invia.")
                .button("Vedi il preventivo", link(token));
        return layout.customer(KIND_QUOTE, customerEmail, "Il tuo preventivo " + quote.code(), content);
    }

    String link(String token) {
        return business.siteUrl() + QuoteRoutes.page(token);
    }
}
