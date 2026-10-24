package it.logisticasanchez.web.site.forms;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.notifications.EmailMessage;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.config.SiteProperties;
import it.logisticasanchez.web.support.Settings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * EML-08: the office e-mail of a submission is the one {@code form.mjs} composed — the chapter 06
 * §5.6 plain-text sample verbatim (sent at 09:29 Europe/Rome), subject formats, empty rows and
 * sections omitted, Reply-To = the customer, the HTML buttons with the Italian-mobile WhatsApp rule.
 */
@Tag("EML-08")
class FormMailTest {

    /** 2026-10-16 09:29 in Rome (CEST). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T07:29:00Z"), ZoneOffset.UTC);

    private final BusinessProperties business = Settings.defaults("ls.business", BusinessProperties.class);
    private final FormValidator validator = new FormValidator(Settings.defaults("ls.site", SiteProperties.class));
    private final FormMail mail = new FormMail(business, CLOCK);

    @Test
    void theQuoteEmailIsTheChapter06Sample() {
        EmailMessage m = mail.compose(validator.validate(FormSubmission.Form.PREVENTIVO, FormValidatorTest.quote()));
        assertThat(m.kind()).isEqualTo("EML-08");
        assertThat(m.recipient()).isEqualTo(business.officeEmail());
        assertThat(m.replyTo()).isEqualTo("mario.rossi@rossimeccanica.example");
        assertThat(m.subject())
                .isEqualTo("Richiesta di preventivo: Spedizione pallet espresso, 20831 Seregno → 40121 Bologna");
        assertThat(m.bodyText()).isEqualTo("""
                        Nuova richiesta di preventivo

                        SPEDIZIONE
                        Servizio: Spedizione pallet espresso
                        Data di ritiro: giovedì 22 ottobre 2026
                        Ritiro: 20831 Seregno
                        Consegna: 40121 Bologna
                        Destinatario: Azienda

                        MERCE
                        Quantità: 2 × EPAL 120×80 cm
                        Peso totale: 650 kg
                        Altezza: 140 cm
                        Descrizione: Ricambi meccanici
                        Servizi extra: Sponda idraulica, Preavviso al destinatario

                        CLIENTE
                        Nome: Mario Rossi
                        Azienda: Rossi Meccanica S.r.l.
                        Email: mario.rossi@rossimeccanica.example
                        Telefono: 333 000 0000
                        Preferisce: WhatsApp
                        Note: Ritiro preferibilmente al mattino

                        Inviato dal modulo «Preventivo» di logisticasanchez.com il 16/10/26, 09:29. Rispondi a questa email per scrivere direttamente al cliente.""");
        assertThat(m.bodyHtml())
                .contains("<h1 style=\"margin:0 0 16px;font-size:22px\">Nuova richiesta di preventivo</h1>")
                .contains("Logistica Sanchez · sito web")
                .contains("href=\"mailto:mario.rossi@rossimeccanica.example\"")
                .contains(">Rispondi via email</a>")
                .contains("href=\"tel:3330000000\"")
                .contains(">Chiama 333 000 0000</a>")
                .contains("href=\"https://wa.me/393330000000\"")
                .contains(">Scrivi su WhatsApp</a>")
                .contains(">Ricambi meccanici</td>")
                .contains("Inviato dal modulo «Preventivo» di logisticasanchez.com il 16/10/26, 09:29.");
    }

    @Test
    void theContactEmailOmitsEmptyRowsAndHasNoCallButtonWithoutAPhone() {
        EmailMessage m = mail.compose(validator.validate(FormSubmission.Form.CONTATTI, FormValidatorTest.contact()));
        assertThat(m.subject()).isEqualTo("Messaggio dal sito: Informazioni su una spedizione (Giulia Bianchi)");
        assertThat(m.replyTo()).isEqualTo("giulia.bianchi@example.it");
        assertThat(m.bodyText()).isEqualTo("""
                        Nuovo messaggio dal sito

                        MESSAGGIO
                        Oggetto: Informazioni su una spedizione
                        Testo: Buongiorno, vorrei sapere quando passate a ritirare i due pallet ordinati ieri.

                        CLIENTE
                        Nome: Giulia Bianchi
                        Email: giulia.bianchi@example.it

                        Inviato dal modulo «Contatti» di logisticasanchez.com il 16/10/26, 09:29. Rispondi a questa email per scrivere direttamente al cliente.""");
        assertThat(m.bodyHtml()).doesNotContain("tel:").doesNotContain("wa.me").doesNotContain("Telefono");
    }

    @Test
    void multiLineValuesStartOnTheirOwnLineAndHtmlIsEscaped() {
        Map<String, String[]> f = FormValidatorTest.contact();
        FormValidatorTest.put(f, "messaggio", "Riga uno <b>grassetto</b>\nRiga due & tre");
        EmailMessage m = mail.compose(validator.validate(FormSubmission.Form.CONTATTI, f));
        assertThat(m.bodyText()).contains("Testo:\nRiga uno <b>grassetto</b>\nRiga due & tre");
        assertThat(m.bodyHtml())
                .contains("Riga uno &lt;b&gt;grassetto&lt;/b&gt;\nRiga due &amp; tre")
                .doesNotContain("<b>");
    }

    @Test
    void whatsAppButtonFollowsTheItalianMobileRule() {
        assertThat(FormMail.whatsapp("333 000 0000", "email")).isEqualTo("https://wa.me/393330000000");
        assertThat(FormMail.whatsapp("+39 333 000 0000", "email")).isEqualTo("https://wa.me/393330000000");
        assertThat(FormMail.whatsapp("0039 333 000 0000", "email")).isEqualTo("https://wa.me/393330000000");
        assertThat(FormMail.whatsapp("02 1234 5678", "email")).isEmpty();
        assertThat(FormMail.whatsapp("02 1234 5678", "whatsapp")).isEqualTo("https://wa.me/0212345678");
        assertThat(FormMail.whatsapp("1234567", "whatsapp")).isEmpty();
    }

    @Test
    void theSubjectIsOneLineOfAtMost200Characters() {
        Map<String, String[]> f = FormValidatorTest.contact();
        FormValidatorTest.put(f, "oggetto", "x".repeat(100));
        FormValidatorTest.put(f, "nome", "y".repeat(100));
        EmailMessage m = mail.compose(validator.validate(FormSubmission.Form.CONTATTI, f));
        assertThat(m.subject()).hasSize(200).startsWith("Messaggio dal sito: xxx");
    }
}
