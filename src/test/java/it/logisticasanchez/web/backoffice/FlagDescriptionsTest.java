package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.platform.flags.FlagKey;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The flags table of «Impostazioni» (ADR 0025): every M2 flag has a one-line Italian description
 * in the bundle (and no description names a flag that does not exist); the built flags come
 * first, each group in requirement order; a flag not built yet offers its toggle only to be
 * turned off.
 */
@Tag("NFR-02")
class FlagDescriptionsTest {

    private static final Path BUNDLE = Path.of("src", "main", "resources", "messages.properties");
    private static final String PREFIX = "backoffice.settings.flag.";

    @Test
    void everyFlagHasAOneLineDescriptionAndNoDescriptionIsLeftOver() throws IOException {
        Properties bundle = new Properties();
        try (var reader = new InputStreamReader(Files.newInputStream(BUNDLE), StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }
        for (FlagKey key : FlagKey.values()) {
            assertThat(bundle.getProperty(PREFIX + key.key()))
                    .as("description of %s", key.key())
                    .isNotBlank()
                    .doesNotContain("\n")
                    .hasSizeLessThanOrEqualTo(120);
        }
        assertThat(bundle.stringPropertyNames().stream().filter(name -> name.startsWith(PREFIX)))
                .as("descriptions of flags that do not exist")
                .allSatisfy(name -> assertThat(FlagKey.ofKey(name.substring(PREFIX.length())))
                        .as(name)
                        .isPresent());
    }

    @Test
    void builtFlagsComeFirstAndAFlagNotBuiltCanOnlyBeTurnedOff() {
        Map<FlagKey, Boolean> values = new EnumMap<>(FlagKey.class);
        for (FlagKey key : FlagKey.values()) {
            values.put(key, false);
        }
        FlagKey notBuilt = Arrays.stream(FlagKey.values())
                .filter(k -> !k.built())
                .findFirst()
                .orElseThrow();
        values.put(notBuilt, true);

        List<BackofficeSettingsController.FlagRow> rows = BackofficeSettingsController.flagRows(values);

        assertThat(rows.stream().map(BackofficeSettingsController.FlagRow::key))
                .containsExactlyElementsOf(Stream.concat(
                                Arrays.stream(FlagKey.values()).filter(FlagKey::built),
                                Arrays.stream(FlagKey.values()).filter(k -> !k.built()))
                        .toList());
        assertThat(rows)
                .allSatisfy(row -> assertThat(row.toggleable())
                        .as("toggle of %s", row.key().key())
                        .isEqualTo(row.built() || row.key() == notBuilt));
    }
}
