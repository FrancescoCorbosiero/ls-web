package it.logisticasanchez.web.schema;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The migrated schema matches the M1 target model: every table and column of chapter 08 §2.3
 * (amended by 12b §5.4, plan §4.4 and Q-55) exists with the expected type and nullability, and
 * every CHECK constraint, unique constraint and unique index listed in ADR 0020 exists. The
 * expectations are the text files under {@code src/test/resources/schema/}, maintained by hand
 * with the migrations.
 */
@SpringBootTest
class SchemaConformanceIT extends AbstractPostgresIT {

    private static final Set<String> M1_TABLES = Set.of(
            "staff_user",
            "staff_setup_token",
            "customer",
            "customer_pending_registration",
            "customer_user",
            "customer_address",
            "customer_change_request",
            "quote",
            "quote_line",
            "site_notice",
            "office_closure",
            "login_token",
            "remembered_sign_in",
            "tracking_state",
            "accessory_service",
            "shipment_order",
            "order_line",
            "order_internal_note",
            "tracking_event",
            "lead",
            "email_outbox",
            "report_run",
            "report_recipient",
            "feature_flag",
            "audit_log",
            "audit_log_detail");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyTableOfTheTargetModelExistsAndNoLegacyColumnDoes() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = current_schema() and table_type = 'BASE TABLE'",
                String.class);
        assertThat(tables).containsAll(M1_TABLES);
        assertThat(tables)
                .as("no migration bookkeeping tables (Q-55)")
                .doesNotContain("legacy_key_map", "import_run", "import_issue");

        List<String> columns = jdbc.queryForList(
                "select table_name || '.' || column_name from information_schema.columns"
                        + " where table_schema = current_schema()",
                String.class);
        assertThat(columns)
                .as("no legacy_*, *_raw, legacy_id, legacy_json or data_quality column (Q-55)")
                .noneMatch(column -> column.matches(".*\\.(legacy_.*|.*_raw|data_quality)$"));
    }

    @Test
    void everyExpectedColumnExistsWithItsTypeAndNullability() {
        Map<String, String> actual = jdbc
                .queryForList("select table_name, column_name, udt_name, is_nullable from information_schema.columns"
                        + " where table_schema = current_schema()")
                .stream()
                .collect(Collectors.toMap(
                        row -> row.get("table_name") + " " + row.get("column_name"),
                        row -> row.get("udt_name") + " "
                                + ("YES".equals(row.get("is_nullable")) ? "NULL" : "NOT NULL")));

        List<String> expected = expectation("expected-columns.txt");
        assertThat(expected).hasSizeGreaterThan(200);
        for (String line : expected) {
            String[] parts = line.split(" ", 3);
            String column = parts[0] + " " + parts[1];
            assertThat(actual).as("column %s exists", column).containsKey(column);
            assertThat(actual.get(column))
                    .as("type and nullability of %s", column)
                    .isEqualTo(parts[2]);
        }

        Set<String> expectedColumns = expected.stream()
                .map(line -> line.split(" ", 3)[0] + " " + line.split(" ", 3)[1])
                .collect(Collectors.toSet());
        Set<String> m1Columns = actual.keySet().stream()
                .filter(column -> M1_TABLES.contains(column.split(" ")[0]))
                .collect(Collectors.toSet());
        assertThat(m1Columns)
                .as("every column of an M1 table is listed in expected-columns.txt")
                .containsExactlyInAnyOrderElementsOf(expectedColumns);
    }

    @Test
    void everyConstraintAndUniqueIndexOfAdr0020Exists() {
        Set<String> constraints = jdbc
                .queryForList(
                        "select c.conrelid::regclass::text || ' ' || c.conname from pg_constraint c"
                                + " where c.connamespace = current_schema()::regnamespace and c.contype in ('c', 'u')",
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        Set<String> uniqueIndexes = jdbc
                .queryForList(
                        "select tablename || ' ' || indexname from pg_indexes"
                                + " where schemaname = current_schema() and indexdef like 'CREATE UNIQUE INDEX%'",
                        String.class)
                .stream()
                .collect(Collectors.toSet());

        for (String line : expectation("expected-constraints.txt")) {
            String name = line.split(" ", 2)[1];
            if (name.startsWith("ux_")) {
                assertThat(uniqueIndexes).as("unique index %s", line).contains(line);
            } else {
                assertThat(constraints).as("constraint %s", line).contains(line);
            }
        }
        assertThat(jdbc.queryForList(
                        "select tgname from pg_trigger where tgname in"
                                + " ('trg_audit_log_immutable', 'trg_audit_log_detail_immutable') and not tgisinternal",
                        String.class))
                .containsExactlyInAnyOrder("trg_audit_log_immutable", "trg_audit_log_detail_immutable");
    }

    @Test
    void everyTableCarriesAComment() {
        List<String> withoutComment = jdbc.queryForList(
                "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                        + " where n.nspname = current_schema() and c.relkind = 'r'"
                        + " and c.relname <> 'flyway_schema_history' and obj_description(c.oid, 'pg_class') is null",
                String.class);
        assertThat(withoutComment).as("tables without COMMENT ON").isEmpty();
    }

    private static List<String> expectation(String file) {
        try {
            return Files.readAllLines(Path.of("src", "test", "resources", "schema", file), StandardCharsets.UTF_8)
                    .stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
