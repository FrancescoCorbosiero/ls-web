package it.logisticasanchez.web.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PUB-02: the {@code aria-current} rule of the static site's {@code tools/sync.mjs} on the nav
 * keys of the partials — {@code page} for the exact page, {@code true} for an ancestor section,
 * nothing otherwise — and the footer year as plain digits.
 */
@Tag("PUB-02")
class LayoutTest {

    /** Every data-nav key of the header, drawer and top bar. */
    static final List<String> NAV_KEYS = List.of(
            "home",
            "servizi",
            "servizi/spedizione-pallet",
            "servizi/groupage",
            "servizi/carico-completo",
            "servizi/magazzino",
            "zone",
            "chi-siamo",
            "blog",
            "faq",
            "contatti",
            "tracking",
            "preventivo",
            "area-clienti");

    private final Layout layout = new Layout(Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void theExactPageIsCurrentAndNothingElseOnATopLevelPage() {
        for (String page : NAV_KEYS) {
            for (String key : NAV_KEYS) {
                String expected = page.equals(key) ? "page" : page.startsWith(key + "/") ? "true" : null;
                assertThat(layout.ariaCurrent(page, key))
                        .as("page %s, link %s", page, key)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void theParentSectionIsTrueOnASubPage() {
        assertThat(layout.ariaCurrent("servizi/groupage", "servizi")).isEqualTo("true");
        assertThat(layout.ariaCurrent("servizi/groupage", "servizi/groupage")).isEqualTo("page");
        assertThat(layout.ariaCurrent("servizi/groupage", "servizi/magazzino")).isNull();
        assertThat(layout.ariaCurrent("zone/milano", "zone")).isEqualTo("true");
        assertThat(layout.ariaCurrent("servizi", "servizi/groupage")).isNull();
    }

    @Test
    void aPageWithoutAKeyMarksNothing() {
        assertThat(layout.ariaCurrent(null, "servizi")).isNull();
        assertThat(layout.ariaCurrent("", "servizi")).isNull();
        assertThat(layout.ariaCurrent("servizi", null)).isNull();
    }

    @Test
    void theYearIsPlainDigits() {
        assertThat(layout.year()).isEqualTo("2026");
    }
}
