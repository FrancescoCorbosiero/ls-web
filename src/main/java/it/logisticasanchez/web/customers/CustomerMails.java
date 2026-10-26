package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.AuthRoutes;
import it.logisticasanchez.web.notifications.EmailMessage;
import it.logisticasanchez.web.notifications.MailLayout;
import it.logisticasanchez.web.notifications.MailLayout.Content;
import it.logisticasanchez.web.notifications.MailLayout.Row;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The e-mails of the customers module (chapter 09 "E-mails"; wording of 12c §1 through the
 * Phase 0 reader notes, brand «Logistica Sanchez» per Q-37, defects of 12c §1.6 dropped):
 * EML-02 registration received, EML-03 account approved / declined, EML-06 new registration
 * to the office (once, when the e-mail is verified); with ENH-16 (ADR 0038) EML-06 also tells the
 * office about a customer's own data change, and EML-12 tells the customer the office's answer.
 */
@Component
public class CustomerMails {

    static final String KIND_REGISTRATION_RECEIVED = "EML-02";
    static final String KIND_ACCOUNT_OUTCOME = "EML-03";
    static final String KIND_OFFICE_NOTIFICATION = "EML-06";
    static final String KIND_CHANGE_OUTCOME = "EML-12";

    /** The labels of the profile fields in the e-mails (the registration form's, CUS-02). */
    private static final Map<String, String> LABELS = Map.of(
            "companyName", "Ragione sociale / nominativo",
            "addressLine", "Via",
            "province", "Provincia",
            "city", "Città",
            "postalCode", "CAP",
            "phone", "Telefono",
            "vatNumber", "Partita IVA",
            "taxCode", "Codice fiscale");

    private final MailLayout layout;
    private final BusinessProperties business;

    public CustomerMails(MailLayout layout, BusinessProperties business) {
        this.layout = layout;
        this.business = business;
    }

    /** EML-02 (new, Q-07): the e-mail is verified, the registration waits for the office. */
    EmailMessage registrationReceived(Customer customer) {
        Content content = Content.titled("Abbiamo verificato il tuo indirizzo email")
                .paragraph("Ciao " + customer.getCompanyName()
                        + ", la tua richiesta di registrazione è andata a buon fine," + " a breve verrà approvata.")
                .paragraph("Rimani in attesa della email di conferma diretta da parte del nostro centro logistico:"
                        + " appena l'ufficio avrà approvato la registrazione potrai inserire il tuo primo ordine.")
                .button("Vai all'area clienti", customerArea());
        return layout.customer(KIND_REGISTRATION_RECEIVED, customer.getEmail(), "Registrazione ricevuta", content);
    }

    /** EML-03 approved (legacy L-E3 «Conferma nuovo cliente»). */
    EmailMessage approved(Customer customer) {
        Content content = Content.titled("Benvenuto in " + business.brandName() + "!")
                .paragraph("Da questo momento in poi fai parte dei clienti del nostro centro logistico.")
                .paragraph("Effettua subito un nuovo ordine:")
                .button("Area clienti", customerArea());
        return layout.customer(KIND_ACCOUNT_OUTCOME, customer.getEmail(), "Conferma nuovo cliente", content);
    }

    /** EML-03 declined (legacy L-E4 wording with a truthful subject; the reason when staff gave one). */
    EmailMessage declined(Customer customer) {
        Content content = Content.titled("Abbiamo riscontrato un errore nella tua richiesta")
                .paragraph("Richiesta: indirizzo email e/o dati cliente rifiutati dall'amministratore.");
        if (customer.getDeclineReason() != null) {
            content.paragraph("Motivo: " + customer.getDeclineReason());
        }
        content.paragraph("Si prega di riprovare più tardi o contattare il supporto se ritieni ci sia stato un errore."
                        + " Puoi registrarti di nuovo dall'area clienti con i dati corretti.")
                .paragraph("Grazie per la comprensione!")
                .button("Area clienti", customerArea());
        return layout.customer(KIND_ACCOUNT_OUTCOME, customer.getEmail(), "Registrazione non accettata", content);
    }

    /** EML-06 new registration to the office (legacy L-E2 «Nuovo cliente in attesa di conferma»), once per verified registration. */
    EmailMessage newRegistration(Customer customer) {
        Content content = Content.titled(business.brandName() + " - Notifica")
                .paragraph("Hai una nuova notifica: un cliente attende la conferma della registrazione.")
                .table(
                        "Cliente",
                        List.of(
                                new Row("Azione", "Nuovo cliente in attesa di conferma"),
                                new Row("Cliente", customer.getEmail()),
                                new Row("Ragione sociale / nominativo", customer.getCompanyName()),
                                new Row(
                                        "Indirizzo",
                                        customer.getAddressLine() + ", " + customer.getPostalCode() + " "
                                                + customer.getCity() + " (" + customer.getProvince() + ")"),
                                new Row("Telefono", customer.getPhone()),
                                new Row("Partita IVA", customer.getVatNumber()),
                                new Row("Codice fiscale", customer.getTaxCode())))
                .button("Apri la gestione", business.siteUrl() + AuthRoutes.BACKOFFICE);
        return layout.office(
                KIND_OFFICE_NOTIFICATION, "Nuovo cliente in attesa di conferma", content, customer.getEmail());
    }

    /**
     * EML-06 office notice (new, ENH-16): the customer changed its data from the profile — the
     * address and phone already saved and, when it asked for one, the change of company name, VAT
     * number or tax code that waits for approval on the customer's page.
     */
    EmailMessage profileChanged(Customer customer, List<ChangeRequestView.Field> applied, ChangeRequestView request) {
        boolean toApprove = request != null;
        Content content = Content.titled(business.brandName() + " - Notifica")
                .paragraph(
                        toApprove
                                ? "Hai una nuova notifica: un cliente chiede di cambiare ragione sociale, partita IVA o"
                                        + " codice fiscale. La modifica aspetta la tua approvazione nella pagina del cliente."
                                : "Hai una nuova notifica: un cliente ha aggiornato i suoi dati dall'area clienti. Valgono"
                                        + " per i prossimi ordini.")
                .table(
                        "Cliente",
                        List.of(
                                new Row(
                                        "Azione",
                                        toApprove ? "Modifica dei dati da approvare" : "Dati aggiornati dal cliente"),
                                new Row("Cliente", customer.getEmail()),
                                new Row("Ragione sociale / nominativo", customer.getCompanyName())));
        if (!applied.isEmpty()) {
            content.table(
                    "Dati aggiornati",
                    applied.stream()
                            .map(f -> new Row(LABELS.get(f.name()), f.requested() + " (prima: " + f.current() + ")"))
                            .toList());
        }
        if (toApprove) {
            content.table(
                    "Da approvare",
                    request.changes().stream()
                            .map(f -> new Row(LABELS.get(f.name()), f.requested() + " (ora: " + f.current() + ")"))
                            .toList());
        }
        content.button(
                "Apri il cliente", business.siteUrl() + AuthRoutes.BACKOFFICE + "clienti/" + customer.getId() + "/");
        return layout.office(
                KIND_OFFICE_NOTIFICATION,
                toApprove ? "Modifica dei dati da approvare" : "Dati del cliente aggiornati",
                content,
                customer.getEmail());
    }

    /** EML-12 (new, ENH-16): the office approved the change of identity the customer asked for. */
    EmailMessage changeApproved(Customer customer) {
        Content content = Content.titled("Abbiamo aggiornato i tuoi dati")
                .paragraph("Il nostro ufficio ha approvato la modifica che hai chiesto. Ecco i tuoi dati:")
                .table(
                        "I tuoi dati",
                        List.of(
                                new Row(LABELS.get("companyName"), customer.getCompanyName()),
                                new Row(LABELS.get("vatNumber"), orDash(customer.getVatNumber())),
                                new Row(LABELS.get("taxCode"), orDash(customer.getTaxCode()))))
                .paragraph("Valgono per i prossimi ordini: quelli già inviati restano con i dati di allora.")
                .button("Vai al tuo profilo", business.siteUrl() + CustomerRoutes.PROFILE);
        return layout.customer(KIND_CHANGE_OUTCOME, customer.getEmail(), "Dati aggiornati", content);
    }

    /** EML-12 (new, ENH-16): the office declined it; the reason when staff gave one. */
    EmailMessage changeDeclined(Customer customer, String reason) {
        Content content = Content.titled("La modifica dei tuoi dati non è stata accettata")
                .paragraph("Il nostro ufficio non ha accettato la modifica di ragione sociale, partita IVA o codice"
                        + " fiscale che hai chiesto: i tuoi dati restano quelli di prima.");
        if (reason != null) {
            content.paragraph("Motivo: " + reason);
        }
        content.button("Vai al tuo profilo", business.siteUrl() + CustomerRoutes.PROFILE);
        return layout.customer(KIND_CHANGE_OUTCOME, customer.getEmail(), "Modifica dei dati non accettata", content);
    }

    private static String orDash(String value) {
        return value == null ? "-" : value;
    }

    private String customerArea() {
        return business.siteUrl() + AuthRoutes.CUSTOMER_AREA;
    }
}
