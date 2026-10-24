package it.logisticasanchez.web.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ADM-09 (ADR 0016): the range is inclusive, {@code from ≤ to}, never in the future, judged in Europe/Rome days. */
@Tag("ADM-09")
class ReportPeriodTest {

    /** 2026-06-30 23:30 UTC is already 1 July in Rome (CEST): "today" is 2026-07-01. */
    private static final Instant NOW = Instant.parse("2026-06-30T23:30:00Z");

    @Test
    void parsesTheFormDatesAndKeepsTheRangeInclusive() {
        ReportPeriod period = ReportPeriod.parse("2026-06-01", "2026-07-01", NOW);
        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(period.to()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(ReportPeriod.italian(period.from())).isEqualTo("01/06/2026");
        assertThat(ReportPeriod.fileName(period.to())).isEqualTo("01_07_2026");
    }

    @Test
    void refusesEmptyMalformedInvertedAndFutureDates() {
        assertThatThrownBy(() -> ReportPeriod.parse("", "2026-06-30", NOW)).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> ReportPeriod.parse("30/06/2026", "2026-06-30", NOW))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("data valida");
        assertThatThrownBy(() -> ReportPeriod.parse("2026-06-30", "2026-06-01", NOW))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("inizio deve precedere");
        assertThatThrownBy(() -> ReportPeriod.parse("2026-06-01", "2026-07-02", NOW))
                .as("2 July is tomorrow in Rome")
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("futuro");
    }

    @Test
    void splitsTypedAddresses() {
        assertThat(ReportService.parseAddresses(
                        " A@Ufficio.example, b@ufficio.example;a@ufficio.example\nc@x.example "))
                .containsExactly("a@ufficio.example", "b@ufficio.example", "c@x.example");
        assertThat(ReportService.parseAddresses(null)).isEmpty();
    }
}
