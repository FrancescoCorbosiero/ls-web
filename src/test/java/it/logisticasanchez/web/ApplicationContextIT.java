package it.logisticasanchez.web;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** The application context starts against PostgreSQL and Flyway has run on it. */
@SpringBootTest
class ApplicationContextIT extends AbstractPostgresIT {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Test
    void contextStartsWithTheTestProfileOnPostgres() throws SQLException {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(16);
        }
    }

    @Test
    void flywayRanAtStartupAndNothingIsPending() throws SQLException {
        MigrationInfoService info = flyway.info();
        assertThat(info.pending()).as("pending migrations after start-up").isEmpty();
        assertThat(info.current())
                .as("no migration is applied before stage 2 adds V001")
                .isNull();
        // The schema history table is created by the start-up migrate() call even while
        // db/migration holds no migration (stage 2 adds V001); its row count equals the
        // applied migrations plus the baseline row, if any.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select count(*) from flyway_schema_history")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(info.applied().length);
        }
    }

    @Test
    void hibernateValidatesTheSchemaInsteadOfChangingIt() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.clean-disabled", Boolean.class))
                .isTrue();
    }
}
