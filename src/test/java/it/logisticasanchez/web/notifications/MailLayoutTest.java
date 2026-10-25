package it.logisticasanchez.web.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.notifications.MailLayout.Content;
import it.logisticasanchez.web.notifications.MailLayout.Row;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.support.Settings;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ADR 0015: the shared e-mail shape — brand prefix in the subject, text and HTML bodies from the same blocks, escaping, Reply-To. */
@Tag("EML-02")
@Tag("EML-06")
class MailLayoutTest {

    private final BusinessProperties business = Settings.defaults("ls.business", BusinessProperties.class);
    private final MailLayout layout = new MailLayout(business);

    @Test
    void aCustomerMailCarriesTheBrandTheBlocksAndReplyToTheOffice() {
        Content content = Content.titled("Benvenuto in Logistica Sanchez!")
                .paragraph("Da questo momento <in poi> fai parte dei clienti.")
                .table("Dettaglio", List.of(new Row("Codice", "LS-1"), new Row("Note", null)))
                .button("Area clienti", "https://logisticasanchez.com/area-clienti/");
        EmailMessage mail = layout.customer("EML-03", "cliente@cliente.example", "Conferma nuovo cliente", content);
        assertThat(mail.kind()).isEqualTo("EML-03");
        assertThat(mail.recipient()).isEqualTo("cliente@cliente.example");
        assertThat(mail.subject()).isEqualTo("Logistica Sanchez - Conferma nuovo cliente");
        assertThat(mail.replyTo()).isEqualTo(business.officeEmail());
        assertThat(mail.bodyText())
                .startsWith("Benvenuto in Logistica Sanchez!\n\n")
                .contains("Da questo momento <in poi> fai parte dei clienti.")
                .contains("Dettaglio\n  Codice: LS-1\n  Note: -\n")
                .contains("Area clienti: https://logisticasanchez.com/area-clienti/")
                .contains("rispondi pure a questo messaggio");
        assertThat(mail.bodyHtml())
                .startsWith("<!DOCTYPE html><html lang=\"it\">")
                .contains("&lt;in poi&gt;")
                .doesNotContain("<in poi>")
                .contains("href=\"https://logisticasanchez.com/area-clienti/\"")
                .contains(
                        "<h1 style=\"margin:0 0 16px;font-size:22px;color:#04233b\">Benvenuto in Logistica Sanchez!</h1>");
    }

    @Test
    void anOfficeMailGoesToTheOfficeAddressWithReplyToTheCustomer() {
        EmailMessage mail = layout.office(
                "EML-06", "Nuovo cliente in attesa di conferma", Content.titled("Notifica"), "cliente@cliente.example");
        assertThat(mail.recipient()).isEqualTo(business.officeEmail());
        assertThat(mail.replyTo()).isEqualTo("cliente@cliente.example");
        assertThat(mail.subject()).isEqualTo("Logistica Sanchez - Nuovo cliente in attesa di conferma");
        assertThat(layout.office("EML-06", "x", Content.titled("Notifica"), null)
                        .replyTo())
                .isNull();
    }
}
