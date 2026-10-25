package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.auth.AuthRoutes;
import it.logisticasanchez.web.notifications.EmailMessage;
import it.logisticasanchez.web.notifications.MailLayout;
import it.logisticasanchez.web.notifications.MailLayout.Content;
import it.logisticasanchez.web.notifications.MailLayout.Row;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The e-mails of the orders module (chapter 09 "E-mails"; legacy wording of 12c §1 through the
 * reader notes; brand «Logistica Sanchez», Q-37): EML-04 order received (new, Q-07), EML-05
 * approved (code, detail, amount, tracking link) / declined (quoting the code), EML-06 new order
 * to the office, EML-09 delivered. Labels, Italian numbers, no staff notes (12c §1.6).
 */
@Component
public class OrderMails {

    static final String KIND_ORDER_RECEIVED = "EML-04";
    static final String KIND_ORDER_OUTCOME = "EML-05";
    static final String KIND_OFFICE_NOTIFICATION = "EML-06";
    static final String KIND_DELIVERED = "EML-09";

    private final MailLayout layout;
    private final BusinessProperties business;

    public OrderMails(MailLayout layout, BusinessProperties business) {
        this.layout = layout;
        this.business = business;
    }

    /** EML-04 (new): the request is in, wait for the confirmation e-mail. */
    EmailMessage orderReceived(OrderView order) {
        Content content = Content.titled("Abbiamo inoltrato la tua richiesta")
                .paragraph("Il tuo ordine è stato inoltrato correttamente con il codice provvisorio " + order.tempCode()
                        + ". Ti preghiamo di attendere la conferma via email dal nostro centro logistico.")
                .paragraph("Controlla la tua casella postale in attesa di conferma.");
        detail(content, order, false);
        content.button("Vedi l'ordine", orderUrl(order));
        return layout.customer(
                KIND_ORDER_RECEIVED, order.sender().email(), "Richiesta di spedizione ricevuta", content);
    }

    /** EML-06 new order to the office (legacy L-E5 «Nuova richiesta di spedizione in attesa di conferma»). */
    EmailMessage newOrder(OrderView order) {
        Content content = Content.titled(business.brandName() + " - Notifica")
                .paragraph("Hai una nuova notifica: una richiesta di spedizione attende la conferma.")
                .table(
                        "Richiesta",
                        List.of(
                                new Row("Azione", "Nuova richiesta di spedizione in attesa di conferma"),
                                new Row("Cliente", order.sender().email()),
                                new Row("Codice provvisorio", order.tempCode())));
        detail(content, order, false);
        content.button("Apri la gestione", business.siteUrl() + AuthRoutes.BACKOFFICE);
        return layout.office(
                KIND_OFFICE_NOTIFICATION,
                "Nuova richiesta di spedizione in attesa di conferma",
                content,
                order.sender().email());
    }

    /** EML-05 approved (legacy L-E6 «Conferma ordine N. {codice}»), with the amount and the tracking link. */
    EmailMessage approved(OrderView order) {
        Content content = Content.titled("Ti confermiamo la presa in carico dell'ordine N. " + order.code())
                .paragraph("Grazie per aver scelto " + business.brandName() + "!");
        detail(content, order, true);
        content.paragraph("Puoi seguire la spedizione in ogni momento con il codice " + order.code() + ".")
                .button("Traccia la spedizione", trackingUrl(order));
        return layout.customer(
                KIND_ORDER_OUTCOME, order.sender().email(), "Conferma ordine N. " + order.code(), content);
    }

    /** EML-05 declined (legacy L-E7 «Aggiornamento ordine rifiutato»), quoting the order code. */
    EmailMessage declined(OrderView order) {
        Content content = Content.titled("Abbiamo riscontrato un errore nella tua richiesta")
                .paragraph("Richiesta: ordine " + order.tempCode() + " invalido, rifiutato dall'amministratore.");
        if (order.declineReason() != null) {
            content.paragraph("Motivo: " + order.declineReason());
        }
        content.paragraph("Si prega di riprovare più tardi o contattare il supporto se ritieni ci sia stato un errore.")
                .paragraph("Grazie per la comprensione!")
                .button("Vedi l'ordine", orderUrl(order));
        return layout.customer(
                KIND_ORDER_OUTCOME,
                order.sender().email(),
                "Aggiornamento ordine rifiutato N. " + order.tempCode(),
                content);
    }

    /** EML-09 (legacy L-E8 «Consegna effettuata per ordine N. {codice}»). */
    EmailMessage delivered(OrderView order) {
        Content content = Content.titled("Ti confermiamo l'avvenuta consegna dell'ordine N. " + order.code())
                .paragraph("Ricordiamo che è sempre possibile verificare lo stato di qualsiasi spedizione effettuata"
                        + " presso il nostro sito web.");
        detail(content, order, true);
        content.paragraph("Grazie per aver scelto " + business.brandName() + "!")
                .button("www.logisticasanchez.com", trackingUrl(order));
        return layout.customer(
                KIND_DELIVERED, order.sender().email(), "Consegna effettuata per ordine N. " + order.code(), content);
    }

    /** The legacy blocks «Dettaglio Spedizione», «Dettaglio Ordine», «Pallets» and «I tuoi dati» (12c §1; labels, not enum names). */
    private static void detail(Content content, OrderView order, boolean withAmount) {
        List<Row> shipment = new ArrayList<>();
        shipment.add(new Row("Codice", order.displayCode()));
        shipment.add(new Row("Destinatario", order.recipient().name()));
        shipment.add(new Row("Indirizzo", order.recipient().addressLine()));
        shipment.add(new Row("Provincia", order.recipient().province()));
        shipment.add(new Row("Città", order.recipient().city()));
        shipment.add(new Row("CAP", order.recipient().postalCode()));
        shipment.add(new Row("Telefono", order.recipient().phone()));
        shipment.add(new Row("Email", order.recipient().email()));
        if (withAmount) {
            shipment.add(new Row("Importo", order.amountText()));
        }
        shipment.add(new Row("Codice documento trasporto", order.transportDocumentCode()));
        shipment.add(new Row("Servizio accessorio", order.accessoryServiceLabel()));
        shipment.add(new Row("Note", order.customerNotes()));
        if (withAmount) {
            shipment.add(new Row("Tracking", order.trackingLabel()));
        }
        content.table("Dettaglio spedizione", shipment);
        content.table(
                "Dettaglio ordine",
                List.of(
                        new Row(
                                "Colli",
                                order.packageCount() == null
                                        ? null
                                        : order.packageCount().toString()),
                        new Row("Pallet", Integer.toString(order.palletCount())),
                        new Row("EPAL", Integer.toString(order.epalCount())),
                        new Row("Peso", order.weightText()),
                        new Row("Volume", order.volumeText())));
        List<Row> lines = new ArrayList<>();
        for (OrderView.Line line : order.lines()) {
            lines.add(new Row(
                    "Riga " + line.lineNo(),
                    line.kind().label() + " · " + line.heightCm() + "×" + line.lengthCm() + "×" + line.widthCm()
                            + " cm · " + ItalianFormats.formatNumber(line.weightKg(), 0) + " kg · quantità "
                            + line.quantity()));
        }
        content.table("Pallet e colli", lines);
        content.table(
                "I tuoi dati",
                List.of(
                        new Row("Ragione sociale / nominativo", order.sender().name()),
                        new Row("Indirizzo", order.sender().addressOneLine()),
                        new Row("Telefono", order.sender().phone()),
                        new Row("Email", order.sender().email())));
    }

    private String orderUrl(OrderView order) {
        return business.siteUrl() + OrderRoutes.LIST + order.displayCode() + "/";
    }

    private String trackingUrl(OrderView order) {
        return business.siteUrl() + "/tracking/?code=" + order.code();
    }
}
