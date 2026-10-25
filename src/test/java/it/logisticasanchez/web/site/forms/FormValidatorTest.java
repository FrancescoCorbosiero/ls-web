package it.logisticasanchez.web.site.forms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.platform.config.SiteProperties;
import it.logisticasanchez.web.support.Settings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * PUB-06, PUB-07 (ADR 0014): the field rules of chapter 06 §5.5–5.6 in the legacy check order,
 * with the deliberate change that over-long values and malformed CAPs are refused (422) instead
 * of being truncated; the decimal comma of the quantity is kept (PD-04) unless the setting says
 * otherwise.
 */
@Tag("PUB-06")
@Tag("PUB-07")
class FormValidatorTest {

    private final FormValidator validator = new FormValidator(Settings.defaults("ls.site", SiteProperties.class));

    static Map<String, String[]> quote() {
        Map<String, String[]> f = new LinkedHashMap<>();
        put(f, "form-name", "preventivo");
        put(f, "bot-field", "");
        put(f, "servizio", "pallet");
        put(f, "data_ritiro", "2026-10-22");
        put(f, "ritiro_cap", "20831");
        put(f, "ritiro_localita", "Seregno");
        put(f, "consegna_cap", "40121");
        put(f, "consegna_localita", "Bologna");
        put(f, "destinatario", "azienda");
        put(f, "tipo_pallet", "eur");
        put(f, "numero", "2");
        put(f, "peso", "650");
        put(f, "altezza", "140");
        put(f, "descrizione_merce", "Ricambi meccanici");
        f.put("servizi_extra[]", new String[] {"Sponda idraulica", "Preavviso al destinatario", "Inventato"});
        put(f, "nome", "Mario Rossi");
        put(f, "azienda", "Rossi Meccanica S.r.l.");
        put(f, "email", "mario.rossi@rossimeccanica.example");
        put(f, "telefono", "333 000 0000");
        put(f, "contatto_preferito", "whatsapp");
        put(f, "note", "Ritiro preferibilmente al mattino");
        put(f, "privacy", "accettata");
        return f;
    }

    static Map<String, String[]> contact() {
        Map<String, String[]> f = new LinkedHashMap<>();
        put(f, "form-name", "contatti");
        put(f, "bot-field", "");
        put(f, "nome", "Giulia Bianchi");
        put(f, "email", "giulia.bianchi@example.it");
        put(f, "telefono", "");
        put(f, "oggetto", "Informazioni su una spedizione");
        put(f, "messaggio", "Buongiorno, vorrei sapere quando passate a ritirare i due pallet ordinati ieri.");
        put(f, "privacy", "accettata");
        return f;
    }

    static void put(Map<String, String[]> fields, String name, String value) {
        fields.put(name, new String[] {value});
    }

    private static Map<String, String[]> with(Map<String, String[]> base, String name, String value) {
        Map<String, String[]> copy = new HashMap<>(base);
        put(copy, name, value);
        return copy;
    }

    @Test
    void aValidQuoteYieldsTheSectionsLabelsAndLeadFieldsOfFormMjs() {
        FormSubmission s = validator.validate(FormSubmission.Form.PREVENTIVO, quote());
        assertThat(s.subject())
                .isEqualTo("Richiesta di preventivo: Spedizione pallet espresso, 20831 Seregno → 40121 Bologna");
        assertThat(s.title()).isEqualTo("Nuova richiesta di preventivo");
        assertThat(s.name()).isEqualTo("Mario Rossi");
        assertThat(s.email()).isEqualTo("mario.rossi@rossimeccanica.example");
        assertThat(s.phone()).isEqualTo("333 000 0000");
        assertThat(s.preferredContact()).isEqualTo("whatsapp");
        assertThat(s.sections())
                .extracting(FormSubmission.Section::heading)
                .containsExactly("Spedizione", "Merce", "Cliente");
        assertThat(s.sections().get(0).rows())
                .extracting(FormSubmission.Row::value)
                .containsExactly(
                        "Spedizione pallet espresso",
                        "giovedì 22 ottobre 2026",
                        "20831 Seregno",
                        "40121 Bologna",
                        "Azienda");
        assertThat(s.sections().get(1).rows())
                .extracting(FormSubmission.Row::value)
                .containsExactly(
                        "2 × EPAL 120×80 cm",
                        "650 kg",
                        "140 cm",
                        "Ricambi meccanici",
                        "Sponda idraulica, Preavviso al destinatario");
        assertThat(s.quote().extras()).containsExactly("Sponda idraulica", "Preavviso al destinatario");
        assertThat(s.quote().pickupDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(s.quote().quantity()).isEqualByComparingTo("2");
        assertThat(s.quote().totalWeightKg()).isEqualByComparingTo("650");
        assertThat(s.quote().maxHeightCm()).isEqualByComparingTo("140");
        assertThat(s.quote().company()).isEqualTo("Rossi Meccanica S.r.l.");
        assertThat(s.payload())
                .containsEntry("servizi_extra[]", List.of("Sponda idraulica", "Preavviso al destinatario"));
        assertThat(s.payload()).containsEntry("numero", "2").containsEntry("form-name", "preventivo");
        assertThat(s.contact()).isNull();
    }

    @Test
    void aValidContactMessageYieldsItsSectionsAndSubject() {
        FormSubmission s = validator.validate(FormSubmission.Form.CONTATTI, contact());
        assertThat(s.subject()).isEqualTo("Messaggio dal sito: Informazioni su una spedizione (Giulia Bianchi)");
        assertThat(s.title()).isEqualTo("Nuovo messaggio dal sito");
        assertThat(s.phone()).isNull();
        assertThat(s.sections()).extracting(FormSubmission.Section::heading).containsExactly("Messaggio", "Cliente");
        assertThat(s.sections().get(1).rows())
                .extracting(FormSubmission.Row::value)
                .containsExactly("Giulia Bianchi", "giulia.bianchi@example.it", "");
        assertThat(s.contact().subject()).isEqualTo("Informazioni su una spedizione");
        assertThat(s.quote()).isNull();
        FormSubmission noSubject = validator.validate(FormSubmission.Form.CONTATTI, with(contact(), "oggetto", ""));
        assertThat(noSubject.subject()).isEqualTo("Messaggio dal sito: contatto (Giulia Bianchi)");
    }

    @ParameterizedTest(name = "{0}={1} → invalid:{2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "nome||nome",
                "email|non-una-mail|email",
                "telefono|12345|telefono",
                "telefono|333 000 0000 000 000 00000|telefono",
                "privacy|no|privacy",
                "servizio|aereo|servizio",
                "ritiro_cap|2083|ritiro_cap",
                "ritiro_cap|208311|ritiro_cap",
                "consegna_cap|4012|consegna_cap",
                "consegna_cap|401234|consegna_cap",
                "consegna_cap|4O121|consegna_cap",
                "ritiro_localita||ritiro_localita",
                "consegna_localita||consegna_localita",
                "destinatario|ente|destinatario",
                "tipo_pallet|gigante|tipo_pallet",
                "numero|0|numero",
                "numero|100|numero",
                "numero|due|numero",
                "peso|0|peso",
                "peso|40001|peso",
                "altezza|9|altezza",
                "altezza|301|altezza",
                "contatto_preferito|fax|contatto_preferito"
            })
    void eachQuoteFieldIsCheckedWithTheLegacyRuleAndNamedInTheError(String field, String value, String expected) {
        assertThatThrownBy(() -> validator.validate(
                        FormSubmission.Form.PREVENTIVO, with(quote(), field, value == null ? "" : value)))
                .isInstanceOf(InvalidFormException.class)
                .extracting(e -> ((InvalidFormException) e).field())
                .isEqualTo(expected);
    }

    @Test
    void theQuoteFormRequiresAPhone() {
        assertThatThrownBy(() -> validator.validate(FormSubmission.Form.PREVENTIVO, with(quote(), "telefono", "")))
                .isInstanceOf(InvalidFormException.class)
                .hasMessage("invalid:telefono");
    }

    @Test
    void theFirstFailingFieldWinsInTheLegacyOrder() {
        Map<String, String[]> f = with(with(quote(), "peso", "0"), "ritiro_cap", "1");
        assertThatThrownBy(() -> validator.validate(FormSubmission.Form.PREVENTIVO, f))
                .hasMessage("invalid:ritiro_cap");
    }

    @ParameterizedTest(name = "{0} of {1} characters → invalid:{0}")
    @CsvSource({
        "nome, 101",
        "email, 255",
        "azienda, 121",
        "ritiro_localita, 81",
        "consegna_localita, 81",
        "descrizione_merce, 201",
        "note, 3001",
        "data_ritiro, 11",
        "numero, 7",
        "peso, 9",
        "altezza, 7"
    })
    void overLongQuoteValuesAreRefusedInsteadOfTruncated(String field, int length) {
        String value = field.equals("email") ? "a".repeat(length - 12) + "@mail.example" : "1".repeat(length);
        assertThatThrownBy(() -> validator.validate(FormSubmission.Form.PREVENTIVO, with(quote(), field, value)))
                .isInstanceOf(InvalidFormException.class)
                .hasMessage("invalid:" + field);
    }

    @ParameterizedTest(name = "{0} of {1} characters → invalid:{0}")
    @CsvSource({"messaggio, 5001", "oggetto, 101", "nome, 101", "telefono, 31"})
    void overLongContactValuesAreRefused(String field, int length) {
        assertThatThrownBy(() ->
                        validator.validate(FormSubmission.Form.CONTATTI, with(contact(), field, "x".repeat(length))))
                .isInstanceOf(InvalidFormException.class)
                .hasMessage("invalid:" + field);
    }

    @Test
    void shortMessagesAndBadContactPhonesAreRefused() {
        assertThatThrownBy(
                        () -> validator.validate(FormSubmission.Form.CONTATTI, with(contact(), "messaggio", "corto")))
                .hasMessage("invalid:messaggio");
        assertThatThrownBy(() -> validator.validate(FormSubmission.Form.CONTATTI, with(contact(), "telefono", "abc")))
                .hasMessage("invalid:telefono");
        // a phone of 21–30 characters was refused by the legacy too (plan D-38)
        assertThatThrownBy(() -> validator.validate(
                        FormSubmission.Form.CONTATTI, with(contact(), "telefono", "+39 333 000 0000 000 00")))
                .hasMessage("invalid:telefono");
    }

    @Test
    void theDecimalCommaInTheQuantityIsAcceptedUnlessTheSettingForbidsIt() {
        FormSubmission s = validator.validate(FormSubmission.Form.PREVENTIVO, with(quote(), "numero", "2,5"));
        assertThat(s.quote().quantity()).isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(s.sections().get(1).rows().get(0).value()).isEqualTo("2.5 × EPAL 120×80 cm");

        FormValidator integerOnly = new FormValidator(
                Settings.with("ls.site", SiteProperties.class, Map.of("ls.site.form.quantity-integer-only", "true")));
        assertThatThrownBy(() -> integerOnly.validate(FormSubmission.Form.PREVENTIVO, with(quote(), "numero", "2,5")))
                .hasMessage("invalid:numero");
        assertThat(integerOnly
                        .validate(FormSubmission.Form.PREVENTIVO, quote())
                        .quote()
                        .quantity())
                .isEqualByComparingTo("2");
    }

    @Test
    void optionalFieldsMayBeEmptyAndAnUnknownDateMeansToBeAgreed() {
        Map<String, String[]> f =
                with(with(with(with(quote(), "altezza", ""), "data_ritiro", "domani"), "azienda", ""), "note", "");
        f.remove("servizi_extra[]");
        f.remove("descrizione_merce");
        FormSubmission s = validator.validate(FormSubmission.Form.PREVENTIVO, f);
        assertThat(s.quote().maxHeightCm()).isNull();
        assertThat(s.quote().pickupDate()).isNull();
        assertThat(s.quote().extras()).isEmpty();
        assertThat(s.quote().company()).isNull();
        assertThat(s.sections().get(0).rows().get(1).value()).isEqualTo("Da concordare");
        assertThat(s.sections().get(1).rows().get(2).value()).isEmpty();
        // past dates are accepted, as in the legacy
        assertThat(validator
                        .validate(FormSubmission.Form.PREVENTIVO, with(quote(), "data_ritiro", "2020-01-01"))
                        .quote()
                        .pickupDate())
                .isEqualTo(LocalDate.of(2020, 1, 1));
    }

    @Test
    void whitespaceIsCollapsedInOneLineFieldsAndKeptInNotes() {
        Map<String, String[]> f = with(with(quote(), "nome", "  Mario \n Rossi  "), "note", "riga 1\r\nriga 2");
        FormSubmission s = validator.validate(FormSubmission.Form.PREVENTIVO, f);
        assertThat(s.name()).isEqualTo("Mario Rossi");
        assertThat(s.quote().notes()).isEqualTo("riga 1\nriga 2");
    }

    @Test
    void formNamesAreRecognisedExactly() {
        assertThat(FormValidator.formOf("preventivo")).contains(FormSubmission.Form.PREVENTIVO);
        assertThat(FormValidator.formOf("contatti")).contains(FormSubmission.Form.CONTATTI);
        assertThat(FormValidator.formOf("altro")).isEmpty();
        assertThat(FormValidator.formOf("")).isEmpty();
    }
}
