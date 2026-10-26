package it.logisticasanchez.web.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR 0044: the screens show an instant by its Europe/Rome time, with the day only when the time
 * alone would be ambiguous — whatever the JVM time zone.
 */
@Tag("CUS-05")
@Tag("ADM-05")
class FormatsTest {

    /** 10:00 in Rome on Tuesday 27 October 2026 (CET, UTC+1), with the clock in UTC. */
    private final Formats fmt = new Formats(Clock.fixed(Instant.parse("2026-10-27T09:00:00Z"), ZoneOffset.UTC));

    @Test
    void aCellShowsTheTimeAndTheDayOnlyWhenNeeded() {
        assertThat(fmt.when(Instant.parse("2026-10-27T13:32:00Z"))).isEqualTo("14:32");
        assertThat(fmt.when(Instant.parse("2026-10-26T23:30:00Z")))
                .as("00:30 in Rome is today, though yesterday in UTC")
                .isEqualTo("00:30");
        assertThat(fmt.when(Instant.parse("2026-10-26T22:59:00Z"))).isEqualTo("ieri 23:59");
        assertThat(fmt.when(Instant.parse("2026-10-28T07:00:00Z"))).isEqualTo("domani 08:00");
        assertThat(fmt.when(Instant.parse("2026-10-12T12:32:00Z")))
                .as("summer time on 12 October")
                .isEqualTo("12/10 14:32");
        assertThat(fmt.when(Instant.parse("2025-12-31T08:05:00Z"))).isEqualTo("31/12/2025 09:05");
        assertThat(fmt.when(null)).isEqualTo("-");
    }

    @Test
    void aSentenceSaysTheDayAndTheTime() {
        assertThat(fmt.at(Instant.parse("2026-10-27T13:32:00Z"))).isEqualTo("oggi alle 14:32");
        assertThat(fmt.at(Instant.parse("2026-10-26T08:10:00Z"))).isEqualTo("ieri alle 09:10");
        assertThat(fmt.at(Instant.parse("2026-10-28T07:00:00Z"))).isEqualTo("domani alle 08:00");
        assertThat(fmt.at(Instant.parse("2026-10-12T12:32:00Z"))).isEqualTo("il 12/10 alle 14:32");
        assertThat(fmt.at(Instant.parse("2025-12-31T08:05:00Z"))).isEqualTo("il 31/12/2025 alle 09:05");
        assertThat(fmt.at(null)).isEqualTo("-");
    }

    @Test
    void theFullDateStaysAvailableForTitlesAndDocuments() {
        assertThat(fmt.dateTime(Instant.parse("2026-10-27T13:32:00Z"))).isEqualTo("27/10/2026 14:32");
        assertThat(fmt.date(Instant.parse("2026-10-27T13:32:00Z"))).isEqualTo("27/10/2026");
    }
}
