package it.logisticasanchez.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The TEST_DATABASE_* switch of the test support: environment first, container otherwise. */
class TestDatabaseTest {

    @Test
    void usesTheEnvironmentWhenAllThreeVariablesAreSet() {
        var connection = TestDatabase.fromEnvironment(Map.of(
                TestDatabase.URL_VARIABLE, "jdbc:postgresql://localhost:54329/ls_web_test",
                TestDatabase.USER_VARIABLE, "ls",
                TestDatabase.PASSWORD_VARIABLE, "ls"));

        assertThat(connection).isPresent();
        assertThat(connection.get().url()).isEqualTo("jdbc:postgresql://localhost:54329/ls_web_test");
        assertThat(connection.get().username()).isEqualTo("ls");
        assertThat(connection.get().password()).isEqualTo("ls");
        assertThat(connection.get().source()).isEqualTo(TestDatabase.Source.ENVIRONMENT);
    }

    @Test
    void fallsBackToTestcontainersWhenAnyVariableIsMissingOrBlank() {
        assertThat(TestDatabase.fromEnvironment(Map.of())).isEmpty();
        assertThat(TestDatabase.fromEnvironment(Map.of(
                        TestDatabase.URL_VARIABLE, "jdbc:postgresql://localhost:54329/ls_web_test",
                        TestDatabase.USER_VARIABLE, "ls")))
                .isEmpty();
        assertThat(TestDatabase.fromEnvironment(Map.of(
                        TestDatabase.URL_VARIABLE, "  ",
                        TestDatabase.USER_VARIABLE, "ls",
                        TestDatabase.PASSWORD_VARIABLE, "ls")))
                .isEmpty();
    }

    @Test
    void rejectsAUrlThatIsNotPostgres() {
        assertThatThrownBy(() -> TestDatabase.fromEnvironment(Map.of(
                        TestDatabase.URL_VARIABLE, "jdbc:h2:mem:test",
                        TestDatabase.USER_VARIABLE, "ls",
                        TestDatabase.PASSWORD_VARIABLE, "ls")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TestDatabase.URL_VARIABLE);
    }
}
