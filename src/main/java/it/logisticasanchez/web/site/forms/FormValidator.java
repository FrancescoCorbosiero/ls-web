package it.logisticasanchez.web.site.forms;

import it.logisticasanchez.web.platform.config.SiteProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Server-side validation of the quote and contact forms, ported from
 * {@code legacy/ls-home/netlify/functions/form.mjs:24-162} (PUB-06, PUB-07, ADR 0014): same field
 * names, allowed values, labels, check order and error codes ({@code invalid:<field>}). One
 * deliberate change: a value longer than its maximum, or a CAP that is not exactly five digits,
 * is rejected instead of being cut to size (chapter 06 §5.5 "Fix"). PD-04: a decimal comma in
 * the quantity stays accepted unless {@code ls.site.form.quantity-integer-only} is set.
 */
@Component
public class FormValidator {

    static final Map<String, String> SERVICE_LABELS = Map.of(
            "pallet", "Spedizione pallet espresso",
            "groupage", "Groupage / collettame",
            "completo", "Carico completo",
            "magazzino", "Magazzino e logistica");
    static final Map<String, String> PALLET_LABELS = Map.of(
            "eur", "EPAL 120×80 cm",
            "industriale", "Industriale 120×100 cm",
            "mezzo", "Mezzo pallet 80×60 cm",
            "quarto", "Quarto di pallet 60×40 cm",
            "altro", "Altro / fuori misura");
    static final Map<String, String> RECIPIENT_LABELS = Map.of("azienda", "Azienda", "privato", "Privato");
    static final Map<String, String> CONTACT_LABELS =
            Map.of("email", "Email", "telefono", "Telefono", "whatsapp", "WhatsApp");
    static final List<String> EXTRAS = List.of(
            "Sponda idraulica",
            "Consegna su appuntamento",
            "Preavviso al destinatario",
            "Merce fragile o non sovrapponibile");

    static final Pattern EMAIL_RE = Pattern.compile("^[^\\s@<>()\",;:]+@[^\\s@<>()\",;:]+\\.[^\\s@<>()\",;:]{2,}$");
    static final Pattern PHONE_RE = Pattern.compile("^[0-9+\\s().\\/-]{6,20}$");
    static final Pattern CAP_RE = Pattern.compile("^\\d{5}$");
    static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    static final DateTimeFormatter ITALIAN_LONG_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALIAN);

    private final boolean quantityIntegerOnly;

    public FormValidator(SiteProperties site) {
        this.quantityIntegerOnly = site.form().quantityIntegerOnly();
    }

    /** The {@code form-name} of the request, if it names a known form. */
    public static Optional<FormSubmission.Form> formOf(String formName) {
        for (FormSubmission.Form form : FormSubmission.Form.values()) {
            if (form.fieldValue().equals(formName)) {
                return Optional.of(form);
            }
        }
        return Optional.empty();
    }

    /** Validates the fields of {@code form}; throws {@link InvalidFormException} on the first failing field. */
    public FormSubmission validate(FormSubmission.Form form, Map<String, String[]> parameters) {
        Fields get = new Fields(parameters);
        return switch (form) {
            case PREVENTIVO -> quote(get);
            case CONTATTI -> contact(get);
        };
    }

    private FormSubmission quote(Fields get) {
        Common c = common(get);
        String servizio = get.value("servizio");
        String tipo = get.value("tipo_pallet");
        String destinatario = get.value("destinatario");
        String preferito = get.value("contatto_preferito");
        String ritiroCap = get.value("ritiro_cap", 5);
        String consegnaCap = get.value("consegna_cap", 5);
        check(SERVICE_LABELS.containsKey(servizio), "servizio");
        check(CAP_RE.matcher(ritiroCap).matches(), "ritiro_cap");
        check(CAP_RE.matcher(consegnaCap).matches(), "consegna_cap");
        String ritiroLocalita = oneLine(get.value("ritiro_localita", 80));
        String consegnaLocalita = oneLine(get.value("consegna_localita", 80));
        check(!ritiroLocalita.isEmpty(), "ritiro_localita");
        check(!consegnaLocalita.isEmpty(), "consegna_localita");
        String ritiro = ritiroCap + " " + ritiroLocalita;
        String consegna = consegnaCap + " " + consegnaLocalita;
        check(RECIPIENT_LABELS.containsKey(destinatario), "destinatario");
        check(PALLET_LABELS.containsKey(tipo), "tipo_pallet");
        BigDecimal numero = number(get.value("numero", 6), 1, 99, "numero", false);
        if (quantityIntegerOnly) {
            check(numero.stripTrailingZeros().scale() <= 0, "numero"); // PD-04
        }
        BigDecimal peso = number(get.value("peso", 8), 1, 40000, "peso", false);
        BigDecimal altezza = number(get.value("altezza", 6), 10, 300, "altezza", true);
        check(c.telefono != null, "telefono");
        check(CONTACT_LABELS.containsKey(preferito), "contatto_preferito");
        List<String> extras = new ArrayList<>();
        for (String extra : get.values("servizi_extra[]")) {
            if (EXTRAS.contains(extra) && !extras.contains(extra)) {
                extras.add(extra);
            }
        }
        String dataRitiro = get.value("data_ritiro", 10);
        LocalDate pickupDate = isoDate(dataRitiro);
        String descrizione = oneLine(get.value("descrizione_merce", 200));
        String azienda = oneLine(get.value("azienda", 120));
        String note = get.value("note", 3000);
        String service = SERVICE_LABELS.get(servizio);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("form-name", "preventivo");
        payload.put("servizio", servizio);
        payload.put("data_ritiro", dataRitiro);
        payload.put("ritiro_cap", ritiroCap);
        payload.put("ritiro_localita", ritiroLocalita);
        payload.put("consegna_cap", consegnaCap);
        payload.put("consegna_localita", consegnaLocalita);
        payload.put("destinatario", destinatario);
        payload.put("tipo_pallet", tipo);
        payload.put("numero", plain(numero));
        payload.put("peso", plain(peso));
        payload.put("altezza", altezza == null ? "" : plain(altezza));
        payload.put("descrizione_merce", descrizione);
        payload.put("servizi_extra[]", extras);
        payload.put("nome", c.nome);
        payload.put("azienda", azienda);
        payload.put("email", c.email);
        payload.put("telefono", c.telefono);
        payload.put("contatto_preferito", preferito);
        payload.put("note", note);
        payload.put("privacy", "accettata");

        List<FormSubmission.Section> sections = List.of(
                new FormSubmission.Section(
                        "Spedizione",
                        List.of(
                                row("Servizio", service),
                                row(
                                        "Data di ritiro",
                                        pickupDate == null ? "Da concordare" : ITALIAN_LONG_DATE.format(pickupDate)),
                                row("Ritiro", ritiro),
                                row("Consegna", consegna),
                                row("Destinatario", RECIPIENT_LABELS.get(destinatario)))),
                new FormSubmission.Section(
                        "Merce",
                        List.of(
                                row("Quantità", plain(numero) + " × " + PALLET_LABELS.get(tipo)),
                                row("Peso totale", plain(peso) + " kg"),
                                row("Altezza", altezza == null ? "" : plain(altezza) + " cm"),
                                row("Descrizione", descrizione),
                                row("Servizi extra", String.join(", ", extras)))),
                new FormSubmission.Section(
                        "Cliente",
                        List.of(
                                row("Nome", c.nome),
                                row("Azienda", azienda),
                                row("Email", c.email),
                                row("Telefono", c.telefono),
                                row("Preferisce", CONTACT_LABELS.get(preferito)),
                                row("Note", note))));
        return new FormSubmission(
                FormSubmission.Form.PREVENTIVO,
                "Richiesta di preventivo: " + service + ", " + ritiro + " → " + consegna,
                "Nuova richiesta di preventivo",
                sections,
                c.nome,
                c.email,
                c.telefono,
                preferito,
                new FormSubmission.Quote(
                        servizio,
                        pickupDate,
                        ritiroCap,
                        ritiroLocalita,
                        consegnaCap,
                        consegnaLocalita,
                        destinatario,
                        tipo,
                        numero,
                        peso,
                        altezza,
                        descrizione.isEmpty() ? null : descrizione,
                        extras,
                        azienda.isEmpty() ? null : azienda,
                        note.isEmpty() ? null : note),
                null,
                payload);
    }

    private FormSubmission contact(Fields get) {
        Common c = common(get);
        String messaggio = get.value("messaggio", 5000);
        check(messaggio.length() >= 10, "messaggio");
        String oggetto = oneLine(get.value("oggetto", 100));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("form-name", "contatti");
        payload.put("nome", c.nome);
        payload.put("email", c.email);
        payload.put("telefono", c.telefono == null ? "" : c.telefono);
        payload.put("oggetto", oggetto);
        payload.put("messaggio", messaggio);
        payload.put("privacy", "accettata");

        List<FormSubmission.Section> sections = List.of(
                new FormSubmission.Section("Messaggio", List.of(row("Oggetto", oggetto), row("Testo", messaggio))),
                new FormSubmission.Section(
                        "Cliente", List.of(row("Nome", c.nome), row("Email", c.email), row("Telefono", c.telefono))));
        return new FormSubmission(
                FormSubmission.Form.CONTATTI,
                "Messaggio dal sito: " + (oggetto.isEmpty() ? "contatto" : oggetto) + " (" + c.nome + ")",
                "Nuovo messaggio dal sito",
                sections,
                c.nome,
                c.email,
                c.telefono,
                null,
                null,
                new FormSubmission.Contact(oggetto.isEmpty() ? null : oggetto, messaggio),
                payload);
    }

    /** {@code contact()} of form.mjs: name, e-mail, optional phone, privacy consent. */
    private Common common(Fields get) {
        String nome = oneLine(get.value("nome", 100));
        String email = get.value("email", 254);
        String telefono = oneLine(get.value("telefono", 30));
        check(!nome.isEmpty(), "nome");
        check(EMAIL_RE.matcher(email).matches(), "email");
        check(telefono.isEmpty() || PHONE_RE.matcher(telefono).matches(), "telefono");
        check("accettata".equals(get.value("privacy")), "privacy");
        return new Common(nome, email, telefono.isEmpty() ? null : telefono);
    }

    private record Common(String nome, String email, String telefono) {}

    private static FormSubmission.Row row(String label, String value) {
        return new FormSubmission.Row(label, value == null ? "" : value);
    }

    private static void check(boolean ok, String field) {
        if (!ok) {
            throw new InvalidFormException(field);
        }
    }

    /** {@code number()} of form.mjs: decimal comma accepted, finite, within [min, max]. */
    static BigDecimal number(String value, int min, int max, String field, boolean optional) {
        if (optional && value.isEmpty()) {
            return null;
        }
        BigDecimal n;
        try {
            n = new BigDecimal(value.replace(',', '.').trim());
        } catch (NumberFormatException e) {
            throw new InvalidFormException(field);
        }
        check(n.compareTo(BigDecimal.valueOf(min)) >= 0 && n.compareTo(BigDecimal.valueOf(max)) <= 0, field);
        return n;
    }

    /** {@code String(n)} of JavaScript for the numbers the form accepts: {@code 2.5}, {@code 650}. */
    static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    /** {@code dateIt()} of form.mjs: a valid {@code YYYY-MM-DD}, else nothing («Da concordare»). */
    static LocalDate isoDate(String value) {
        if (!ISO_DATE.matcher(value).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * {@code reader()} of form.mjs: trimmed, line breaks normalised — and, unlike the legacy, a
     * value longer than {@code max} is refused (ADR 0014). The default maximum is 200.
     */
    static final class Fields {
        private final Map<String, String[]> parameters;

        Fields(Map<String, String[]> parameters) {
            this.parameters = parameters;
        }

        String value(String name) {
            return value(name, 200);
        }

        String value(String name, int max) {
            String[] values = parameters.get(name);
            String raw = values == null || values.length == 0 || values[0] == null ? "" : values[0];
            String normalised = raw.replaceAll("\r\n?", "\n").trim();
            check(normalised.length() <= max, name);
            return normalised;
        }

        List<String> values(String name) {
            String[] values = parameters.get(name);
            return values == null ? List.of() : List.of(values);
        }
    }
}
