package it.logisticasanchez.web.tracking;

import java.util.Locale;

/**
 * Normalisation of a shipment code typed by a visitor or sent by a legacy client (TRK-02,
 * chapter 02 §10.2 rule 1): every whitespace removed (the v1 site padded codes with spaces,
 * the new site strips them), then lower-cased when matching is case-insensitive (Q-03, the
 * default). The database side applies the same transformation to the stored code.
 */
public final class ShipmentCode {

    private ShipmentCode() {}

    /** The comparison key, or null when nothing is left (empty or whitespace-only input). */
    public static String normalise(String raw, boolean caseInsensitive) {
        if (raw == null) {
            return null;
        }
        String compact = raw.replaceAll("\\s+", "");
        if (compact.isEmpty()) {
            return null;
        }
        return caseInsensitive ? compact.toLowerCase(Locale.ROOT) : compact;
    }
}
