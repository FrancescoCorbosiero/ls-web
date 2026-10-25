package it.logisticasanchez.web.platform.validation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Italian formats shared by the customer area (CUS-02, CUS-04; chapter 03 §10 OP-09…OP-11
 * "Fix"): a CAP is exactly five digits; a phone is an Italian number or an E.164 one, normalised
 * to digits with the leading {@code +}; e-mails are trimmed and lower-cased; VAT numbers have
 * eleven digits and tax codes sixteen letters and digits (or eleven digits for a company);
 * amounts and quantities are shown with the decimal comma and parsed accepting either separator.
 */
public final class ItalianFormats {

    private static final Pattern CAP = Pattern.compile("^[0-9]{5}$");
    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@<>()\",;:]+@[^\\s@<>()\",;:]+\\.[^\\s@<>()\",;:]{2,}$");
    private static final Pattern PHONE_CHARS = Pattern.compile("^\\+?[0-9][0-9 .()/-]*$");
    private static final Pattern VAT = Pattern.compile("^[0-9]{11}$");
    private static final Pattern TAX_CODE = Pattern.compile("^([A-Z0-9]{16}|[0-9]{11})$");

    /** Dates and times are shown in Europe/Rome (chapter 10 §2). */
    public static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ITALIAN);

    private ItalianFormats() {}

    /** {@code 24/10/2026}, in Europe/Rome; «-» for null. */
    public static String formatDate(Instant instant) {
        return instant == null ? "-" : DATE.format(instant.atZone(ROME));
    }

    /** {@code 24/10/2026 15:30}, in Europe/Rome; «-» for null. */
    public static String formatDateTime(Instant instant) {
        return instant == null ? "-" : DATE_TIME.format(instant.atZone(ROME));
    }

    public static String trim(String value) {
        return value == null ? "" : value.strip();
    }

    /** Trimmed and lower-cased (OP-10, OP-11). */
    public static String normaliseEmail(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    public static boolean isEmail(String value) {
        return value != null && value.length() <= 254 && EMAIL.matcher(value).matches();
    }

    public static boolean isCap(String value) {
        return value != null && CAP.matcher(value).matches();
    }

    /**
     * The E.164-like form of an Italian or international phone number: spaces, dots, dashes,
     * slashes and parentheses removed; a leading {@code 00} becomes {@code +}; a number without
     * a prefix gets {@code +39}. Empty when the value is not a phone number (letters, too few or
     * too many digits: 6–15 digits after the prefix).
     */
    public static Optional<String> normalisePhone(String value) {
        String raw = trim(value);
        if (raw.isEmpty() || raw.length() > 30 || !PHONE_CHARS.matcher(raw).matches()) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("[^0-9+]", "");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        if (!digits.startsWith("+")) {
            digits = "+39" + digits;
        }
        if (digits.indexOf('+', 1) >= 0) {
            return Optional.empty();
        }
        String national = digits.substring(1);
        if (national.length() < 8 || national.length() > 15) {
            return Optional.empty();
        }
        return Optional.of(digits);
    }

    public static boolean isVatNumber(String value) {
        return value != null && VAT.matcher(value).matches();
    }

    public static boolean isTaxCode(String value) {
        return value != null && TAX_CODE.matcher(value.toUpperCase(Locale.ROOT)).matches();
    }

    /** A decimal typed the Italian way ({@code 180,50}) or with a point ({@code 180.50}); empty when not a number. */
    public static Optional<BigDecimal> parseDecimal(String value) {
        String raw = trim(value).replace("€", "").replace(" ", "").replace(" ", "");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        int comma = raw.lastIndexOf(',');
        int point = raw.lastIndexOf('.');
        if (comma >= 0 && point >= 0) {
            // both present: the last one is the decimal separator, the other the thousands separator
            if (comma > point) {
                raw = raw.replace(".", "").replace(',', '.');
            } else {
                raw = raw.replace(",", "");
            }
        } else if (comma >= 0) {
            raw = raw.replace(',', '.');
        }
        if (!raw.matches("^-?[0-9]+(\\.[0-9]+)?$")) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(raw));
    }

    /** A positive integer ({@code ^[0-9]+$}, OP-R4 "integers ≥ 1"); empty otherwise. */
    public static Optional<Integer> parsePositiveInteger(String value) {
        String raw = trim(value);
        if (!raw.matches("^[0-9]{1,9}$")) {
            return Optional.empty();
        }
        int parsed = Integer.parseInt(raw);
        return parsed >= 1 ? Optional.of(parsed) : Optional.empty();
    }

    /** {@code 1.236,00}: the Italian rendering with the given decimals (grouping dot, decimal comma). */
    public static String formatNumber(BigDecimal value, int decimals) {
        if (value == null) {
            return "-";
        }
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ITALIAN);
        DecimalFormat format = new DecimalFormat(decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals), symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value.setScale(decimals, RoundingMode.HALF_UP));
    }

    /** {@code 180,50 €}. */
    public static String formatEuro(BigDecimal amount) {
        return amount == null ? "-" : formatNumber(amount, 2) + " €";
    }
}
