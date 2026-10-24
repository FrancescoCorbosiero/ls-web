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

    /**
     * A JSON string literal (quotes included) for the JSON-LD blocks, rendered with text inlining
     * ({@code [(${layout.json(x)})]}): {@code "}, {@code \\} and control characters escaped, {@code <}
     * as {@code \\u003C} so a value can never close the script; slashes are left alone, unlike
     * Thymeleaf's JavaScript inlining, so the graph stays byte-equal to the static site's.
     */
    public String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '<' -> sb.append("\\u003C");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** The footer year as text (a number would be formatted with a grouping separator by MessageFormat). */
    public String year() {
        return Integer.toString(Year.now(clock).getValue());
    }
}
