package it.logisticasanchez.web.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The Flyway migrations apply to a clean database, are idempotent, and the audit log they create
 * is append-only (ADR 0020).
 */
@SpringBootTest
class MigrationsIT extends AbstractPostgresIT {

    /** A schema of its own, so the test starts from nothing whatever the app's schema holds. */
    private static final String CLEAN_SCHEMA = "migrations_it";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway applicationFlyway;

    @Test
    void allMigrationsApplyToACleanDatabaseAndRunningAgainIsANoOp() throws SQLException {
        dropSchema();
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(CLEAN_SCHEMA)
                    .locations("classpath:db/migration")
                    .load();

            MigrateResult first = flyway.migrate();
            MigrationInfo[] applied = Arrays.stream(flyway.info().applied())
                    .filter(info -> info.getVersion() != null)
                    .toArray(MigrationInfo[]::new);
            assertThat(first.success).isTrue();
            assertThat(first.migrationsExecuted)
                    .as("versioned migrations applied to a clean schema")
                    .isEqualTo(17);
            assertThat(applied).hasSize(17);
            assertThat(applied[0].getVersion().getVersion()).isEqualTo("001");
            assertThat(applied[16].getVersion().getVersion()).isEqualTo("017");
            assertThat(flyway.info().pending()).isEmpty();

            MigrateResult second = flyway.migrate();
            assertThat(second.migrationsExecuted)
                    .as("a second run applies nothing")
                    .isZero();
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

            assertThat(countRows(CLEAN_SCHEMA + ".tracking_state")).isEqualTo(6);
            assertThat(countRows(CLEAN_SCHEMA + ".accessory_service")).isEqualTo(6);
            assertThat(countRows(CLEAN_SCHEMA + ".feature_flag")).isEqualTo(25);
            assertThat(countRows(CLEAN_SCHEMA + ".staff_user")).isZero();
            assertThat(countRows(CLEAN_SCHEMA + ".customer")).isZero();
            assertThat(countRows(CLEAN_SCHEMA + ".shipment_order")).isZero();
        } finally {
            dropSchema();
        }
    }

    @Test
    void theApplicationSchemaIsAtTheLatestVersionAndValid() {
        assertThat(applicationFlyway.info().pending()).isEmpty();
        assertThat(applicationFlyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @Tag("NFR-02")
    void auditLogAndItsDetailAcceptInsertsButRefuseUpdatesAndDeletes() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                long id;
                try (ResultSet rows = statement.executeQuery(
                        "insert into audit_log (actor_type, actor_id, action, entity_type, entity_id, entity_ref)"
                                + " values ('SYSTEM', null, 'test.audit.immutability', 'test', 1, 'TEST-0001') returning id")) {
                    assertThat(rows.next()).isTrue();
                    id = rows.getLong(1);
                }
                statement.executeUpdate(
                        "insert into audit_log_detail (audit_log_id, before, after) values (" + id + ", '{}', '{}')");

                refused(connection, "update audit_log set action = 'test.changed' where id = " + id, "UPDATE refused");
                refused(connection, "delete from audit_log where id = " + id, "DELETE refused");
                refused(
                        connection,
                        "update audit_log_detail set after = '{\"x\":1}' where audit_log_id = " + id,
                        "UPDATE refused");
                refused(connection, "delete from audit_log_detail where audit_log_id = " + id, "DELETE refused");

                // The retention job and the erasure tool delete inside a maintenance transaction
                // (ADR 0020); an UPDATE stays refused even there.
                statement.execute("set local ls.audit_maintenance = 'on'");
                refused(connection, "update audit_log set action = 'test.changed' where id = " + id, "UPDATE refused");
                assertThat(statement.executeUpdate("delete from audit_log_detail where audit_log_id = " + id))
                        .isEqualTo(1);
                assertThat(statement.executeUpdate("delete from audit_log where id = " + id))
                        .isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void refused(Connection connection, String sql, String expectedMessage) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(sql))
                    .as(sql)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("audit_log_immutable")
                    .hasMessageContaining(expectedMessage);
        } finally {
            connection.rollback(savepoint);
        }
    }

    private long countRows(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void dropSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + CLEAN_SCHEMA + " cascade");
        }
    }
}
