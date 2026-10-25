package it.logisticasanchez.web.platform.flags;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every M2 flag key of chapter 09 §M2 exists as a {@code feature_flag} row and is off (ADR 0025).
 * Untagged on purpose: the ENH IDs turn green only when Phase 5 builds the features.
 */
@SpringBootTest
class FeatureFlagSeedIT extends AbstractPostgresIT {

    /** `| ENH-05 | `tracking.history`, `tracking.notifyCustomer` | …` rows of the M2 table. */
    private static final Pattern FLAG_ROW = Pattern.compile("^\\| (ENH-\\d{2}) \\| ([^|]+) \\|");

    private static final Pattern FLAG_KEY = Pattern.compile("`([a-zA-Z0-9.]+)`");

    @Autowired
    private FeatureFlagRepository flags;

    @Test
    void everyFlagKeyOfChapter09IsSeededOffAndNothingElseExists() throws IOException {
        List<String[]> expected = flagsOfChapter09();
        assertThat(expected)
                .as("flag keys parsed from docs/brief/09-features.md §M2")
                .hasSize(25);

        List<FeatureFlag> rows = flags.findAll();
        assertThat(rows.stream().map(FeatureFlag::getKey))
                .containsExactlyInAnyOrderElementsOf(
                        expected.stream().map(pair -> pair[1]).toList());
        assertThat(rows).allSatisfy(flag -> {
            assertThat(flag.isEnabled()).as("%s is off", flag.getKey()).isFalse();
            assertThat(flag.getUpdatedBy())
                    .as("%s was never toggled", flag.getKey())
                    .isNull();
            assertThat(flag.getUpdatedAt()).isNotNull();
        });
        for (String[] pair : expected) {
            FeatureFlag flag = flags.findById(pair[1]).orElseThrow();
            assertThat(flag.getRequirementId()).as("requirement of %s", pair[1]).isEqualTo(pair[0]);
        }
    }

    /** {requirement id, flag key} pairs read from the M2 table of chapter 09. */
    private static List<String[]> flagsOfChapter09() throws IOException {
        Path chapter = Path.of("docs", "brief", "09-features.md");
        List<String> lines = Files.readAllLines(chapter, StandardCharsets.UTF_8);
        int start = lines.indexOf("## M2 · Safe enhancements");
        int end = lines.indexOf("## Later · Experimental backlog (do not build now)");
        assertThat(start).as("M2 section of %s", chapter).isPositive();
        assertThat(end).isGreaterThan(start);
        return lines.subList(start, end).stream()
                .map(FLAG_ROW::matcher)
                .filter(Matcher::find)
                .flatMap(row -> {
                    Matcher keys = FLAG_KEY.matcher(row.group(2));
                    Stream.Builder<String[]> pairs = Stream.builder();
                    while (keys.find()) {
                        pairs.add(new String[] {row.group(1), keys.group(1)});
                    }
                    return pairs.build();
                })
                .toList();
    }
}
