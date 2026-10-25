package it.logisticasanchez.web.support;

import java.util.Map;
import java.util.Objects;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The PostgreSQL the integration tests run against.
 *
 * <p>Two paths, both real: when {@code TEST_DATABASE_URL}, {@code TEST_DATABASE_USER} and
 * {@code TEST_DATABASE_PASSWORD} are set (a local instance started by
 * {@code scripts/local-db.sh}, or the service container in CI) they are used as they are and
 * no container is started; otherwise a Testcontainers {@code postgres:16} container is started
 * once for the JVM and stopped by Testcontainers at exit. The container path needs a Docker
 * daemon (CI, the owner's machine); the environment path is the one used where none runs.
 */
public final class TestDatabase {

    public static final String URL_VARIABLE = "TEST_DATABASE_URL";
    public static final String USER_VARIABLE = "TEST_DATABASE_USER";
    public static final String PASSWORD_VARIABLE = "TEST_DATABASE_PASSWORD";
    static final String POSTGRES_IMAGE = "postgres:16";

    private static volatile Connection connection;

    private TestDatabase() {}

    /** JDBC coordinates of the test database, resolving (and starting) it on first use. */
    public static Connection get() {
        Connection current = connection;
        if (current == null) {
            synchronized (TestDatabase.class) {
                current = connection;
                if (current == null) {
                    current = fromEnvironment(System.getenv()).orElseGet(TestDatabase::startContainer);
                    connection = current;
                }
            }
        }
        return current;
    }

    /**
     * The connection described by the environment, if all three variables are set and not
     * blank. Package-private so a test can exercise the parsing without touching the JVM's
     * environment.
     */
    static java.util.Optional<Connection> fromEnvironment(Map<String, String> environment) {
        String url = environment.get(URL_VARIABLE);
        String user = environment.get(USER_VARIABLE);
        String password = environment.get(PASSWORD_VARIABLE);
        if (isBlank(url) || isBlank(user) || isBlank(password)) {
            return java.util.Optional.empty();
        }
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException(URL_VARIABLE + " must be a jdbc:postgresql:// URL, got: " + url);
        }
        return java.util.Optional.of(new Connection(url, user, password, Source.ENVIRONMENT));
    }

    private static Connection startContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("ls_web_test")
                .withUsername("ls")
                .withPassword("ls")
                .withReuse(false);
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "test-database-stop"));
        return new Connection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword(), Source.TESTCONTAINERS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Where the test database came from. */
    public enum Source {
        ENVIRONMENT,
        TESTCONTAINERS
    }

    /** JDBC coordinates of the test database. */
    public record Connection(String url, String username, String password, Source source) {
        public Connection {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(source, "source");
        }
    }
}
