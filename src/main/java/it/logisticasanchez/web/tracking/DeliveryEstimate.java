package it.logisticasanchez.web.tracking;

import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.site.zones.DeliveryZones;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The indicative delivery day of ENH-05 (ADR 0034): the delivery zone of the recipient's CAP
 * from the dataset of the public CAP widget ({@link DeliveryZones}, PUB-08: zone 1 «24 ore»,
 * zone 2 «48 ore», zone 3 «72 ore») read as 1, 2 or 3 working days after the pick-up day, the
 * Europe/Rome day of the approval (the PRESA state). Working days are Monday to Friday except the
 * Italian national holidays. No estimate for a CAP the dataset does not know, nor for the places
 * whose times «li concordiamo insieme» (minor islands, the Venice lagoon). Indicative only: the
 * price-list transit times are ENH-10's.
 */
@Component
public class DeliveryEstimate {

    /** The national holidays on a fixed date; Easter Monday moves (computed). */
    private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(
            MonthDay.of(1, 1), // Capodanno
            MonthDay.of(1, 6), // Epifania
            MonthDay.of(4, 25), // Liberazione
            MonthDay.of(5, 1), // Festa del lavoro
            MonthDay.of(6, 2), // Festa della Repubblica
            MonthDay.of(8, 15), // Ferragosto
            MonthDay.of(11, 1), // Ognissanti
            MonthDay.of(12, 8), // Immacolata
            MonthDay.of(12, 25), // Natale
            MonthDay.of(12, 26)); // Santo Stefano

    /**
     * The delivery day to show for an approved shipment in this tracking state: the day it was
     * delivered once CONSEGNATO (the last change), the estimate while it travels, nothing while it
     * is «Non gestito».
     */
    public Optional<LocalDate> deliveryDay(
            int trackingStateId, String postalCode, Instant approvedAt, Instant trackingUpdatedAt) {
        if (trackingStateId == TrackingState.CONSEGNATO) {
            return Optional.ofNullable(trackingUpdatedAt)
                    .map(at -> at.atZone(ItalianFormats.ROME).toLocalDate());
        }
        if (trackingStateId == TrackingState.NON_GESTITO) {
            return Optional.empty();
        }
        return estimate(postalCode, approvedAt);
    }

    /** The day of a shipment picked up at {@code pickedUpAt}, or empty when the CAP has no estimate. */
    public Optional<LocalDate> estimate(String postalCode, Instant pickedUpAt) {
        if (pickedUpAt == null) {
            return Optional.empty();
        }
        return estimate(postalCode, pickedUpAt.atZone(ItalianFormats.ROME).toLocalDate());
    }

    /** The day of a shipment picked up on {@code pickupDay}, or empty when the CAP has no estimate. */
    public Optional<LocalDate> estimate(String postalCode, LocalDate pickupDay) {
        return DeliveryZones.lookup(postalCode)
                .filter(DeliveryZones.Result::valid)
                .filter(zone -> zone.special() == null)
                .map(zone -> addWorkingDays(pickupDay, zone.zone()));
    }

    /** {@code days} working days after {@code day} (a non-working {@code day} counts from the next working one). */
    static LocalDate addWorkingDays(LocalDate day, int days) {
        LocalDate result = day;
        int added = 0;
        while (added < days) {
            result = result.plusDays(1);
            if (isWorkingDay(result)) {
                added++;
            }
        }
        return result;
    }

    static boolean isWorkingDay(LocalDate day) {
        DayOfWeek weekday = day.getDayOfWeek();
        return weekday != DayOfWeek.SATURDAY
                && weekday != DayOfWeek.SUNDAY
                && !FIXED_HOLIDAYS.contains(MonthDay.from(day))
                && !day.equals(easterSunday(day.getYear()).plusDays(1));
    }

    /** Easter Sunday of the Gregorian calendar (the anonymous algorithm of Meeus/Jones/Butcher). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
