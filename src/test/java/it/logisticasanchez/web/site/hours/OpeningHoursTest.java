package it.logisticasanchez.web.site.hours;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ENH-18 (ADR 0042): «Aperto ora» from the weekly hours of the configuration (8–18 Monday to
 * Friday, 8–12 on Saturday, Sunday closed) less the closure days, in Europe/Rome — Saturday noon,
 * a closure day and the daylight-saving changes of 2026 (29 March, 25 October).
 */
@Tag("ENH-18")
class OpeningHoursTest {

    private final OpeningHours hours =
            new OpeningHours(new BusinessProperties.Hours("8:00–18:00", "8:00–12:00", "Lun–Ven 8–18 · Sab 8–12"));

    @Test
    void theWeekdaysOpenFromEightToSixAndSaturdayUntilNoon() {
        assertThat(open("2026-10-19T07:59")).as("Monday before eight").isFalse();
        assertThat(open("2026-10-19T08:00")).isTrue();
        assertThat(open("2026-10-19T17:59")).isTrue();
        assertThat(open("2026-10-19T18:00")).as("Monday at six").isFalse();
        assertThat(open("2026-10-24T11:59")).as("Saturday before noon").isTrue();
        assertThat(open("2026-10-24T12:00")).as("Saturday noon").isFalse();
        assertThat(open("2026-10-25T10:00")).as("Sunday").isFalse();
    }

    @Test
    void aClosureDayIsClosedAllDay() {
        Set<LocalDate> closures = Set.of(LocalDate.parse("2026-12-08"));
        assertThat(hours.isOpen(rome("2026-12-08T10:00"), closures)).isFalse();
        assertThat(hours.isOpen(rome("2026-12-08T10:00"), Set.of())).isTrue();
        assertThat(hours.isOpen(rome("2026-12-09T10:00"), closures)).isTrue();
        assertThat(hours.intervals(LocalDate.parse("2026-12-07"), 3, closures))
                .extracting(i -> i.start().atZone(OpeningHours.ROME).toLocalDate())
                .containsExactly(LocalDate.parse("2026-12-07"), LocalDate.parse("2026-12-09"));
    }

    @Test
    void theDaylightSavingChangesMoveTheInstantsNotTheHours() {
        List<OpeningHours.Interval> spring = hours.intervals(LocalDate.parse("2026-03-27"), 4, Set.of());
        assertThat(spring)
                .containsExactly(
                        new OpeningHours.Interval(
                                Instant.parse("2026-03-27T07:00:00Z"), Instant.parse("2026-03-27T17:00:00Z")),
                        new OpeningHours.Interval(
                                Instant.parse("2026-03-28T07:00:00Z"), Instant.parse("2026-03-28T11:00:00Z")),
                        new OpeningHours.Interval(
                                Instant.parse("2026-03-30T06:00:00Z"), Instant.parse("2026-03-30T16:00:00Z")));
        assertThat(hours.intervals(LocalDate.parse("2026-10-26"), 1, Set.of()))
                .containsExactly(new OpeningHours.Interval(
                        Instant.parse("2026-10-26T07:00:00Z"), Instant.parse("2026-10-26T17:00:00Z")));
        assertThat(hours.isOpen(Instant.parse("2026-10-26T06:30:00Z"), Set.of()))
                .as("07:30 in Rome after the change")
                .isFalse();
        assertThat(hours.isOpen(Instant.parse("2026-03-30T06:30:00Z"), Set.of()))
                .as("08:30 in Rome after the change")
                .isTrue();
    }

    @Test
    void theHoursOfTheConfigurationAreReadOrRefused() {
        OpeningHours other = new OpeningHours(new BusinessProperties.Hours("8.30 - 12.30", "9:00—11:00", "-"));
        assertThat(other.isOpen(rome("2026-10-20T08:30"), Set.of())).isTrue();
        assertThat(other.isOpen(rome("2026-10-20T12:30"), Set.of())).isFalse();
        assertThat(other.isOpen(rome("2026-10-24T10:59"), Set.of())).isTrue();
        assertThatThrownBy(() -> OpeningHours.range("18:00–8:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpeningHours.range("chiuso")).isInstanceOf(IllegalArgumentException.class);
    }

    private boolean open(String romeTime) {
        return hours.isOpen(rome(romeTime), Set.of());
    }

    private static Instant rome(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(OpeningHours.ROME).toInstant();
    }
}
