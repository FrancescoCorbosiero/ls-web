package it.logisticasanchez.web.backoffice;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A CSV file for a spreadsheet (ENH-08, ADR 0035; chapter 10 §4 «cells as text»): UTF-8 with a
 * byte-order mark and semicolons, as Excel in Italian reads it; every cell quoted; a value that a
 * spreadsheet would read as a formula — it starts with {@code =}, {@code +}, {@code -}, {@code @},
 * a tab or a carriage return — gets a leading apostrophe, so it stays text.
 */
final class CsvFile {

    private static final char SEPARATOR = ';';
    private static final Pattern FORMULA = Pattern.compile("^[=+\\-@\t\r]");

    private final StringBuilder text = new StringBuilder("\uFEFF");

    CsvFile row(List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                text.append(SEPARATOR);
            }
            text.append('"').append(cell(cells.get(i)).replace("\"", "\"\"")).append('"');
        }
        text.append("\r\n");
        return this;
    }

    /** The value as a text cell: null is empty, a formula-like value gets an apostrophe. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        return FORMULA.matcher(value).find() ? "'" + value : value;
    }

    byte[] bytes() {
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
