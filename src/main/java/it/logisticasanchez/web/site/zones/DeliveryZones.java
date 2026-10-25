package it.logisticasanchez.web.site.zones;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The CAP → region → delivery-zone dataset of the public site (chapter 06 §5.3, PUB-08), the
 * server-side twin of {@code static/js/eta.js}: the same prefixes, regions, zones and special
 * places, so legends and estimates cannot drift from the widget ({@code DeliveryZonesTest}
 * compares the two). The unified source with the price-list transit times is M2 (ENH-10).
 */
public final class DeliveryZones {

    /** Zone number → label and note, as {@code ZONES} in {@code eta.js}. */
    public static final Map<Integer, Zone> ZONES = Map.of(
            1, new Zone("24 ore", "Nord Italia"),
            2, new Zone("48 ore", "Centro e parte del Sud"),
            3, new Zone("72 ore", "Sud e isole maggiori"));

    /** Region → zone, as {@code REGION_ZONE}. */
    public static final Map<String, Integer> REGION_ZONE = regionZone();

    /** First two digits of the CAP → region, as {@code PREFIX_REGION}. */
    public static final Map<String, String> PREFIX_REGION = prefixRegion();

    /** Minor islands reached by ferry, as {@code MINOR_ISLANDS}. */
    public static final Set<String> MINOR_ISLANDS = Set.of(
            "80071", "80073", "80074", "80075", "80076", "80077", "80079", // Capri, Ischia, Procida
            "57030", "57031", "57032", "57033", "57034", "57036", "57037", "57038", "57039", // Elba, Capraia
            "58012", "91017", "91023", "98055", "07024", "09014", "04027"); // Giglio … Ponza

    /** Venice lagoon (centro storico, Lido, Murano, Burano), as {@code isLagoon}. */
    public static final Pattern LAGOON = Pattern.compile("^301(2[1-6]|3[1-5]|4[12])$");

    public static final String SPECIAL_ISLAND = "isola minore";
    public static final String SPECIAL_LAGOON = "laguna di Venezia";

    private DeliveryZones() {}

    /**
     * {@code lookup} of {@code eta.js}: digits only; not five digits → empty; unknown prefix →
     * an invalid result; else the region, zone, label and special place.
     */
    public static Optional<Result> lookup(String value) {
        String cap = value == null ? "" : value.replaceAll("\\D", "");
        if (cap.length() != 5) {
            return Optional.empty();
        }
        String region = PREFIX_REGION.get(cap.substring(0, 2));
        if (region == null) {
            return Optional.of(new Result(cap, false, null, 0, null, null));
        }
        int zone = REGION_ZONE.get(region);
        String special = MINOR_ISLANDS.contains(cap)
                ? SPECIAL_ISLAND
                : LAGOON.matcher(cap).matches() ? SPECIAL_LAGOON : null;
        return Optional.of(new Result(cap, true, region, zone, ZONES.get(zone).label(), special));
    }

    public record Zone(String label, String note) {}

    /** {@code {cap, valid, region, zone, label, special}} of {@code eta.js}. */
    public record Result(String cap, boolean valid, String region, int zone, String label, String special) {}

    private static Map<String, Integer> regionZone() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String r : new String[] {
            "Lombardia",
            "Piemonte",
            "Valle d'Aosta",
            "Liguria",
            "Veneto",
            "Trentino-Alto Adige",
            "Friuli-Venezia Giulia",
            "Emilia-Romagna"
        }) {
            m.put(r, 1);
        }
        for (String r :
                new String[] {"Toscana", "Umbria", "Marche", "Lazio", "Abruzzo", "Molise", "Campania", "Puglia"}) {
            m.put(r, 2);
        }
        for (String r : new String[] {"Basilicata", "Calabria", "Sicilia", "Sardegna"}) {
            m.put(r, 3);
        }
        return Map.copyOf(m);
    }

    private static Map<String, String> prefixRegion() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "Lazio", "00", "01", "02", "03", "04");
        put(m, "Umbria", "05", "06");
        put(m, "Sardegna", "07", "08", "09");
        put(m, "Piemonte", "10", "12", "13", "14", "15", "28");
        put(m, "Valle d'Aosta", "11");
        put(m, "Liguria", "16", "17", "18", "19");
        put(m, "Lombardia", "20", "21", "22", "23", "24", "25", "26", "27", "46");
        put(m, "Emilia-Romagna", "29", "40", "41", "42", "43", "44", "47", "48");
        put(m, "Veneto", "30", "31", "32", "35", "36", "37", "45");
        put(m, "Friuli-Venezia Giulia", "33", "34");
        put(m, "Trentino-Alto Adige", "38", "39");
        put(m, "Toscana", "50", "51", "52", "53", "54", "55", "56", "57", "58", "59");
        put(m, "Marche", "60", "61", "62", "63");
        put(m, "Abruzzo", "64", "65", "66", "67");
        put(m, "Puglia", "70", "71", "72", "73", "74", "76");
        put(m, "Basilicata", "75", "85");
        put(m, "Campania", "80", "81", "82", "83", "84");
        put(m, "Molise", "86");
        put(m, "Calabria", "87", "88", "89");
        put(m, "Sicilia", "90", "91", "92", "93", "94", "95", "96", "97", "98");
        return Map.copyOf(m);
    }

    private static void put(Map<String, String> m, String region, String... prefixes) {
        for (String prefix : prefixes) {
            m.put(prefix, region);
        }
    }
}
