package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.AuthRoutes;
import it.logisticasanchez.web.notifications.EmailMessage;
import it.logisticasanchez.web.notifications.MailLayout;
import it.logisticasanchez.web.notifications.MailLayout.Content;
import it.logisticasanchez.web.notifications.MailLayout.Row;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The e-mails of the customers module (chapter 09 "E-mails"; wording of 12c §1 through the
 * Phase 0 reader notes, brand «Logistica Sanchez» per Q-37, defects of 12c §1.6 dropped):
 * EML-02 registration received, EML-03 account approved / declined, EML-06 new registration
 * to the office (once, when the e-mail is verified).
 */
@Component
public class CustomerMails {

    static final String KIND_REGISTRATION_RECEIVED = "EML-02";
    static final String KIND_ACCOUNT_OUTCOME = "EML-03";
    static final String KIND_OFFICE_NOTIFICATION = "EML-06";

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

    private String customerArea() {
        return business.siteUrl() + AuthRoutes.CUSTOMER_AREA;
    }
}
