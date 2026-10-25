package it.logisticasanchez.web.reports;

import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The inclusive date range of a report in Europe/Rome days (ADM-09, ADR 0016): {@code from ≤ to},
 * neither after today (the legacy had no check and used UTC days, 12c §3.2). Dates arrive as
 * {@code yyyy-MM-dd} from the form's date inputs.
 */
public record ReportPeriod(LocalDate from, LocalDate to) {

    public ReportPeriod {
        if (from == null || to == null) {
            throw new RuleViolationException("Inserisci le date di inizio e di fine.");
        }
        if (from.isAfter(to)) {
            throw new RuleViolationException("La data di inizio deve precedere quella di fine.");
        }
    }

    /** Parses the two form values and refuses a range ending after today in Europe/Rome. */
    public static ReportPeriod parse(String fromText, String toText, Instant now) {
        ReportPeriod period = new ReportPeriod(parseDate(fromText), parseDate(toText));
        LocalDate today = now.atZone(ItalianFormats.ROME).toLocalDate();
        if (period.to().isAfter(today) || period.from().isAfter(today)) {
            throw new RuleViolationException("Le date non possono essere nel futuro.");
        }
        return period;
    }

    private static LocalDate parseDate(String text) {
        String value = ItalianFormats.trim(text);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new RuleViolationException("Inserisci una data valida (anno-mese-giorno).");
        }
    }

    /** {@code 01/06/2026}: the Italian rendering for e-mails and pages. */
    public static String italian(LocalDate date) {
        return String.format("%02d/%02d/%04d", date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    /** {@code 01_06_2026}: the legacy file-name piece (12c §3.4). */
    static String fileName(LocalDate date) {
        return String.format("%02d_%02d_%04d", date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }
}
