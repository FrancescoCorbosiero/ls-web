package it.logisticasanchez.web.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADM-01 (and the synthetic-data rule of CLAUDE.md): no seed data reaches a database outside the
 * {@code local} and {@code test} profiles. The migrations seed only the two enumerations and the
 * feature flags; no Spring SQL initialisation file exists in the main resources; the base,
 * staging and production configuration never enable {@code spring.sql.init}.
 */
@Tag("ADM-01")
class NoSeedDataOutsideLocalAndTestProfilesTest {

    private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");

    private static final Set<String> SEEDED_TABLES = Set.of("tracking_state", "accessory_service", "feature_flag");

    private static final Pattern INSERT_TARGET =
            Pattern.compile("\\binsert\\s+into\\s+(\\w+\\.)?\"?(\\w+)\"?", Pattern.CASE_INSENSITIVE);

    @Test
    void migrationsSeedOnlyTheEnumerationsAndTheFeatureFlags() throws IOException {
        Set<String> targets = new TreeSet<>();
        for (Path migration : NoSeededStaffAccountsTest.migrations()) {
            Matcher matcher = INSERT_TARGET.matcher(Files.readString(migration, StandardCharsets.UTF_8));
            while (matcher.find()) {
                targets.add(matcher.group(2).toLowerCase());
            }
        }
        assertThat(targets).as("tables written by the migrations").isSubsetOf(SEEDED_TABLES);
        assertThat(targets).containsExactlyInAnyOrderElementsOf(SEEDED_TABLES);
    }

    @Test
    void noSpringSqlInitialisationFileExistsInTheMainResources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_RESOURCES)) {
            List<String> seedFiles = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("(data|schema|import)(-[\\w-]+)?\\.sql"))
                    .toList();
            assertThat(seedFiles)
                    .as("Spring SQL initialisation files under src/main/resources")
                    .isEmpty();
        }
    }

    @Test
    void sqlInitialisationIsNotEnabledOutsideLocalAndTest() throws IOException {
        for (String file : List.of("application.yml", "application-staging.yml", "application-prod.yml")) {
            Path path = MAIN_RESOURCES.resolve(file);
            assertThat(path).exists();
            String yaml = Files.readString(path, StandardCharsets.UTF_8);
            assertThat(yaml)
                    .as("%s enables spring.sql.init", file)
                    .doesNotContain("sql:\n")
                    .doesNotContain("init:");
            assertThat(yaml).as("%s names seed data", file).doesNotContainIgnoringCase("data-locations");
        }
    }
}
