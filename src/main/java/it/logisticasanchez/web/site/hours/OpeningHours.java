package it.logisticasanchez.web.site.hours;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the office is open (ENH-18, ADR 0042): the weekly hours of the configuration (PUB-02,
 * {@code ls.business.hours}: Monday to Friday, Saturday; Sunday closed) less the closure days, as
 * instants of Europe/Rome — so a day after a daylight-saving change opens at the right instant.
 */
public final class OpeningHours {

    public static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final Pattern RANGE =
            Pattern.compile("^\\s*(\\d{1,2})[:.](\\d{2})\\s*[–—-]\\s*(\\d{1,2})[:.](\\d{2})\\s*$");

    /** One opening, from {@code start} (included) to {@code end} (excluded). */
    public record Interval(Instant start, Instant end) {

        public boolean contains(Instant instant) {
            return !instant.isBefore(start) && instant.isBefore(end);
        }
    }

    private record Range(LocalTime opens, LocalTime closes) {}

    private final Range weekdays;
    private final Range saturday;

    public OpeningHours(BusinessProperties.Hours hours) {
        this.weekdays = range(hours.weekdays());
        this.saturday = range(hours.saturday());
    }

    /** The openings of {@code days} days from {@code from} (a Rome day), closure days left out. */
    public List<Interval> intervals(LocalDate from, int days, Set<LocalDate> closures) {
        List<Interval> intervals = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate day = from.plusDays(i);
            Range range = rangeOf(day.getDayOfWeek());
            if (range == null || closures.contains(day)) {
                continue;
            }
            ZonedDateTime start = day.atTime(range.opens()).atZone(ROME);
            ZonedDateTime end = day.atTime(range.closes()).atZone(ROME);
            intervals.add(new Interval(start.toInstant(), end.toInstant()));
        }
        return intervals;
    }

    /** True when {@code now} falls in an opening of its Rome day. */
    public boolean isOpen(Instant now, Set<LocalDate> closures) {
        LocalDate today = now.atZone(ROME).toLocalDate();
        return intervals(today, 1, closures).stream().anyMatch(i -> i.contains(now));
    }

    private Range rangeOf(DayOfWeek day) {
        return switch (day) {
            case SATURDAY -> saturday;
            case SUNDAY -> null;
            default -> weekdays;
        };
    }

    /** «8:00–18:00» (en or em dash, hyphen; a point for the colon is accepted). */
    static Range range(String text) {
        Matcher m = RANGE.matcher(text == null ? "" : text);
        if (!m.matches()) {
            throw new IllegalArgumentException("Orario non valido: " + text + " (atteso per esempio 8:00–18:00)");
        }
        LocalTime opens = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        LocalTime closes = LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
        if (!opens.isBefore(closes)) {
            throw new IllegalArgumentException(
                    "Orario non valido: " + text + " (l'apertura viene prima della chiusura)");
        }
        return new Range(opens, closes);
    }
}
