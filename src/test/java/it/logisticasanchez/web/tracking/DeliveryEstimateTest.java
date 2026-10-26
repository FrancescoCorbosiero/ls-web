package it.logisticasanchez.web.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ENH-05 (ADR 0034): the indicative delivery day is the zone of the CAP widget (PUB-08: 1, 2 or 3
 * working days) after the pick-up day in Europe/Rome; working days skip weekends and the Italian
 * national holidays (Easter Monday included); no estimate for the places agreed case by case or
 * for a CAP the dataset does not know. Independent of the JVM time zone.
 */
@Tag("ENH-05")
class DeliveryEstimateTest {

    private final DeliveryEstimate estimates = new DeliveryEstimate();

    @ParameterizedTest(name = "picked up {1}, CAP {0} → {2}")
    @CsvSource({
        // Monday 26 October 2026: zone 1 (Milano), zone 2 (Roma), zone 3 (Palermo)
        "20121, 2026-10-26, 2026-10-27",
        "00184, 2026-10-26, 2026-10-28",
        "90121, 2026-10-26, 2026-10-29",
        // Friday and Saturday: the weekend is skipped
        "20121, 2026-10-30, 2026-11-02",
        "20121, 2026-10-31, 2026-11-02",
        "00184, 2026-10-30, 2026-11-03",
        // Christmas: 25 and 26 December, then the weekend
        "20121, 2026-12-24, 2026-12-28",
        "90121, 2026-12-23, 2026-12-29",
        // Easter Monday 2026 is 6 April; 25 April and 1 May on working days of 2025
        "20121, 2026-04-03, 2026-04-07",
        "20121, 2025-04-24, 2025-04-28",
        "20121, 2025-04-30, 2025-05-02",
        // 2 June, 15 August, 1 November, 8 December, 1 and 6 January
        "20121, 2026-06-01, 2026-06-03",
        "20121, 2025-08-14, 2025-08-18",
        "20121, 2027-10-29, 2027-11-02",
        "20121, 2026-12-07, 2026-12-09",
        "20121, 2026-12-31, 2027-01-04",
        "20121, 2027-01-05, 2027-01-07",
        // Mestre is the mainland: zone 1 like the rest of Veneto
        "30171, 2026-10-26, 2026-10-27"
    })
    void theZoneOfTheCapInWorkingDaysAfterThePickUp(String cap, LocalDate pickup, LocalDate expected) {
        assertThat(estimates.estimate(cap, pickup)).contains(expected);
    }

    @ParameterizedTest(name = "no estimate for {0}")
    @CsvSource({
        "80073", // Capri: minor island
        "57037", // Elba
        "30124", // Venice lagoon
        "99999", // unknown prefix
        "123", "''"
    })
    void noEstimateWhereTimesAreAgreedOrTheCapIsUnknown(String cap) {
        assertThat(estimates.estimate(cap, LocalDate.of(2026, 10, 26))).isEmpty();
    }

    @Test
    void easterFollowsTheGregorianComputus() {
        assertThat(DeliveryEstimate.easterSunday(2024)).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(DeliveryEstimate.easterSunday(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(DeliveryEstimate.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(DeliveryEstimate.easterSunday(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
        assertThat(DeliveryEstimate.easterSunday(2038)).isEqualTo(LocalDate.of(2038, 4, 25));
        assertThat(DeliveryEstimate.isWorkingDay(LocalDate.of(2026, 4, 6))).isFalse();
        assertThat(DeliveryEstimate.isWorkingDay(LocalDate.of(2026, 4, 7))).isTrue();
    }

    @Test
    void thePickUpDayIsTheDayInRomeAndADeliveredShipmentShowsItsDay() {
        // 23:30 UTC on Monday 26 October 2026 is already Tuesday in Rome
        assertThat(estimates.estimate("20121", Instant.parse("2026-10-26T23:30:00Z")))
                .contains(LocalDate.of(2026, 10, 28));
        assertThat(estimates.estimate("20121", (Instant) null)).isEmpty();
        Instant approved = Instant.parse("2026-10-26T09:00:00Z");
        assertThat(estimates.deliveryDay(TrackingState.PRESA, "20121", approved, approved))
                .contains(LocalDate.of(2026, 10, 27));
        assertThat(estimates.deliveryDay(3, "20121", approved, Instant.parse("2026-10-27T07:00:00Z")))
                .as("still the estimate on the way")
                .contains(LocalDate.of(2026, 10, 27));
        assertThat(estimates.deliveryDay(
                        TrackingState.CONSEGNATO, "20121", approved, Instant.parse("2026-10-28T22:30:00Z")))
                .as("delivered: the day it happened, in Rome")
                .contains(LocalDate.of(2026, 10, 28));
        assertThat(estimates.deliveryDay(TrackingState.NON_GESTITO, "20121", approved, approved))
                .isEmpty();
    }
}
