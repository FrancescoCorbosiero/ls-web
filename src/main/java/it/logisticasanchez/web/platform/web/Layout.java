package it.logisticasanchez.web.platform.web;

import java.time.Clock;
import java.time.Year;

/**
 * Helpers of the shared layout fragments ({@code templates/layout/*.html}), exposed to every
 * template as {@code layout}: the active-menu rule of the static site's {@code tools/sync.mjs}
 * (PUB-02) and the footer year.
 */
public final class Layout {

    private final Clock clock;

    public Layout(Clock clock) {
        this.clock = clock;
    }

    /**
     * The {@code aria-current} value of a navigation link carrying {@code data-nav=key} on the page
     * with key {@code page}: {@code page} for the exact page, {@code true} for an ancestor section
     * ({@code servizi} on {@code servizi/groupage}), null (attribute omitted) otherwise.
     */
    public String ariaCurrent(String page, String key) {
        if (page == null || key == null) {
            return null;
        }
        if (page.equals(key)) {
            return "page";
        }
        return page.startsWith(key + "/") ? "true" : null;
    }

    /** The footer year as text (a number would be formatted with a grouping separator by MessageFormat). */
    public String year() {
        return Integer.toString(Year.now(clock).getValue());
    }
}
