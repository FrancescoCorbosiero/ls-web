package it.logisticasanchez.web.site.zones;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * PUB-08: the server-side CAP dataset answers the cases of chapter 06 §5.3 exactly like
 * {@code eta.js}, and its tables are the ones of {@code static/js/eta.js} (parsed from the file),
 * so the two cannot drift.
 */
@Tag("PUB-08")
class DeliveryZonesTest {

    @ParameterizedTest(name = "{0} → {1}, zone {2}, {3}")
    @CsvSource({
        "20831, Lombardia, 1, 24 ore",
        "40121, Emilia-Romagna, 1, 24 ore",
        "11100, Valle d'Aosta, 1, 24 ore",
        "00118, Lazio, 2, 48 ore",
        "70121, Puglia, 2, 48 ore",
        "80133, Campania, 2, 48 ore",
        "75100, Basilicata, 3, 72 ore",
        "90133, Sicilia, 3, 72 ore",
        "09124, Sardegna, 3, 72 ore",
        "30171, Veneto, 1, 24 ore",
        "47890, Emilia-Romagna, 1, 24 ore",
        "00120, Lazio, 2, 48 ore",
        "' 20 831 ', Lombardia, 1, 24 ore"
    })
    void regularCapsGiveRegionZoneAndLabel(String cap, String region, int zone, String label) {
        DeliveryZones.Result result = DeliveryZones.lookup(cap).orElseThrow();
        assertThat(result.valid()).isTrue();
        assertThat(result.region()).isEqualTo(region);
        assertThat(result.zone()).isEqualTo(zone);
        assertThat(result.label()).isEqualTo(label);
        assertThat(result.special()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"80073", "57037", "91017", "07024"})
    void minorIslandsAreSpecial(String cap) {
        DeliveryZones.Result result = DeliveryZones.lookup(cap).orElseThrow();
        assertThat(result.valid()).isTrue();
        assertThat(result.special()).isEqualTo(DeliveryZones.SPECIAL_ISLAND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"30124", "30126", "30141", "30142"})
    void theVeniceLagoonIsSpecial(String cap) {
        DeliveryZones.Result result = DeliveryZones.lookup(cap).orElseThrow();
        assertThat(result.region()).isEqualTo("Veneto");
        assertThat(result.special()).isEqualTo(DeliveryZones.SPECIAL_LAGOON);
    }

    @ParameterizedTest
    @ValueSource(strings = {"49000", "68000", "99999"})
    void unknownPrefixesAreInvalid(String cap) {
        DeliveryZones.Result result = DeliveryZones.lookup(cap).orElseThrow();
        assertThat(result.valid()).isFalse();
        assertThat(result.cap()).isEqualTo(cap);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2083", "2O831", ""})
    void fewerThanFiveDigitsGiveNoResult(String cap) {
        assertThat(DeliveryZones.lookup(cap)).isEmpty();
    }

    @Test
    void nullGivesNoResult() {
        assertThat(DeliveryZones.lookup(null)).isEmpty();
    }

    @Test
    void theDatasetEqualsEtaJs() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/eta.js"), StandardCharsets.UTF_8);

        Map<String, String> prefixes = new LinkedHashMap<>();
        Matcher m = Pattern.compile("'(\\d{2})':\\s*(?:'([^']*)'|\"([^\"]*)\")")
                .matcher(block(js, "const PREFIX_REGION = {", "};"));
        while (m.find()) {
            prefixes.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
        }
        assertThat(prefixes).hasSize(93).isEqualTo(DeliveryZones.PREFIX_REGION);

        Map<String, Integer> regionZone = new LinkedHashMap<>();
        m = Pattern.compile("(?:'([^']+)'|\"([^\"]+)\"|([A-Za-z]+)):\\s*(\\d)")
                .matcher(block(js, "export const REGION_ZONE = {", "};"));
        while (m.find()) {
            String region = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            regionZone.put(region, Integer.parseInt(m.group(4)));
        }
        assertThat(regionZone).hasSize(20).isEqualTo(DeliveryZones.REGION_ZONE);

        Set<String> islands = new TreeSet<>();
        m = Pattern.compile("'(\\d{5})'").matcher(block(js, "const MINOR_ISLANDS = new Set([", "]);"));
        while (m.find()) {
            islands.add(m.group(1));
        }
        assertThat(islands).hasSize(23).isEqualTo(new TreeSet<>(DeliveryZones.MINOR_ISLANDS));

        assertThat(js).contains("/^301(2[1-6]|3[1-5]|4[12])$/");
        assertThat(DeliveryZones.LAGOON.pattern()).isEqualTo("^301(2[1-6]|3[1-5]|4[12])$");

        for (var zone : DeliveryZones.ZONES.entrySet()) {
            assertThat(js)
                    .contains(zone.getKey() + ": { label: '" + zone.getValue().label() + "', note: '"
                            + zone.getValue().note() + "' }");
        }
    }

    private static String block(String js, String start, String end) {
        int from = js.indexOf(start);
        assertThat(from).as("found %s", start).isGreaterThanOrEqualTo(0);
        int to = js.indexOf(end, from);
        return js.substring(from + start.length(), to);
    }
}
