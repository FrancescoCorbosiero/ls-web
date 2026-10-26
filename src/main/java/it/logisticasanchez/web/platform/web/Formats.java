package it.logisticasanchez.web.platform.web;

import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.math.BigDecimal;
import java.time.Instant;

/** Italian formatting helpers exposed to every template as {@code fmt} (dates in Europe/Rome, decimal comma). */
public final class Formats {

    public String date(Instant instant) {
        return ItalianFormats.formatDate(instant);
    }

    public String dateTime(Instant instant) {
        return ItalianFormats.formatDateTime(instant);
    }

    /** A day written out, «giovedì 29 ottobre 2026» (ENH-05). */
    public String longDate(java.time.LocalDate day) {
        return ItalianFormats.formatLongDate(day);
    }

    public String number(BigDecimal value, int decimals) {
        return ItalianFormats.formatNumber(value, decimals);
    }

    public String euro(BigDecimal amount) {
        return ItalianFormats.formatEuro(amount);
    }
}
