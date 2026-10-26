package it.logisticasanchez.web.platform.web;

import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Italian formatting helpers exposed to every template as {@code fmt} (dates in Europe/Rome,
 * decimal comma). The screens show an instant by its time, with the day only when the time alone
 * would be ambiguous (ADR 0044): {@link #when} for cells and timelines, {@link #at} inside a
 * sentence. Documents (PDF, e-mails, the quote page) keep the full date.
 */
public final class Formats {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Clock clock;

    public Formats(Clock clock) {
        this.clock = clock;
    }

    public String date(Instant instant) {
        return ItalianFormats.formatDate(instant);
    }

    public String dateTime(Instant instant) {
        return ItalianFormats.formatDateTime(instant);
    }

    /**
     * An instant for a cell or a timeline, in Europe/Rome: {@code 14:32} today, {@code ieri 14:32},
     * {@code domani 14:32}, {@code 12/10 14:32} in the same year, {@code 12/10/2025 14:32} otherwise;
     * «-» for null.
     */
    public String when(Instant instant) {
        if (instant == null) {
            return "-";
        }
        ZonedDateTime at = instant.atZone(ItalianFormats.ROME);
        String time = TIME.format(at);
        return switch (dayOf(at)) {
            case TODAY -> time;
            case YESTERDAY -> "ieri " + time;
            case TOMORROW -> "domani " + time;
            case THIS_YEAR -> DAY_MONTH.format(at) + " " + time;
            case OTHER_YEAR -> DAY_MONTH_YEAR.format(at) + " " + time;
        };
    }

    /**
     * An instant inside a sentence, where «il» would come before a date: {@code oggi alle 14:32},
     * {@code ieri alle 14:32}, {@code domani alle 14:32}, {@code il 12/10 alle 14:32},
     * {@code il 12/10/2025 alle 14:32}; «-» for null.
     */
    public String at(Instant instant) {
        if (instant == null) {
            return "-";
        }
        ZonedDateTime at = instant.atZone(ItalianFormats.ROME);
        String time = "alle " + TIME.format(at);
        return switch (dayOf(at)) {
            case TODAY -> "oggi " + time;
            case YESTERDAY -> "ieri " + time;
            case TOMORROW -> "domani " + time;
            case THIS_YEAR -> "il " + DAY_MONTH.format(at) + " " + time;
            case OTHER_YEAR -> "il " + DAY_MONTH_YEAR.format(at) + " " + time;
        };
    }

    /** A day as {@code 24/10/2026} (ADR 0035). */
    public String day(LocalDate day) {
        return day == null ? "-" : day.format(DAY_MONTH_YEAR);
    }

    /** A day written out, «giovedì 29 ottobre 2026» (ENH-05). */
    public String longDate(LocalDate day) {
        return ItalianFormats.formatLongDate(day);
    }

    public String number(BigDecimal value, int decimals) {
        return ItalianFormats.formatNumber(value, decimals);
    }

    public String euro(BigDecimal amount) {
        return ItalianFormats.formatEuro(amount);
    }

    private Day dayOf(ZonedDateTime at) {
        LocalDate today = clock.instant().atZone(ItalianFormats.ROME).toLocalDate();
        LocalDate day = at.toLocalDate();
        if (day.equals(today)) {
            return Day.TODAY;
        }
        if (day.equals(today.minusDays(1))) {
            return Day.YESTERDAY;
        }
        if (day.equals(today.plusDays(1))) {
            return Day.TOMORROW;
        }
        return day.getYear() == today.getYear() ? Day.THIS_YEAR : Day.OTHER_YEAR;
    }

    private enum Day {
        TODAY,
        YESTERDAY,
        TOMORROW,
        THIS_YEAR,
        OTHER_YEAR
    }
}
