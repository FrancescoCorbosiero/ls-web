package it.logisticasanchez.web.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADM-01: no staff account is created by a migration. The test parses every Flyway migration
 * and fails on any statement that writes {@code staff_user} (INSERT, COPY, UPDATE or a function
 * body that does), whatever the casing or the schema qualification.
 */
@Tag("ADM-01")
class NoSeededStaffAccountsTest {

    static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    private static final Pattern WRITES_STAFF_USER = Pattern.compile(
            "\\b(insert\\s+into|copy|update|merge\\s+into)\\s+(\\w+\\.)?\"?staff_user\"?\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void noMigrationInsertsIntoStaffUser() throws IOException {
        List<Path> migrations = migrations();
        assertThat(migrations).as("Flyway migrations").isNotEmpty();
        for (Path migration : migrations) {
            String sql = Files.readString(migration, StandardCharsets.UTF_8);
            assertThat(WRITES_STAFF_USER.matcher(sql).find())
                    .as("%s writes staff_user (ADM-01 forbids seeded staff accounts)", migration.getFileName())
                    .isFalse();
        }
    }

    static List<Path> migrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted()
                    .toList();
        }
    }
}
