package it.logisticasanchez.web.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.Application;
import it.logisticasanchez.web.platform.config.NotificationsProperties;
import it.logisticasanchez.web.support.TestDatabase;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * NFR-04 (chapter 10 §3 "Staging e-mail", tested): with the {@code staging} profile and the SES
 * sink the app refuses to start unless a non-empty recipient allow-list is configured; with
 * the directory sink, or with an allow-list, it starts.
 */
@Tag("NFR-04")
class StagingMailGuardIT {

    /**
     * The staging environment as command-line arguments (the highest-precedence source, as the
     * real environment variables would be): the test database, the two required keys, the sink
     * and the allow-list under test.
     */
    private static String[] stagingEnvironment(String sink, String allowList) {
        TestDatabase.Connection database = TestDatabase.get();
        return new String[] {
            "--spring.profiles.active=staging",
            "--spring.datasource.url=" + database.url(),
            "--spring.datasource.username=" + database.username(),
            "--spring.datasource.password=" + database.password(),
            "--spring.datasource.hikari.maximum-pool-size=2",
            "--spring.datasource.hikari.minimum-idle=1",
            "--server.port=0",
            "--logging.structured.format.console=",
            "--ls.security.ip-hash-key=staging-test-key",
            "--ls.notifications.token-encryption-key=dGVzdC1vbmx5LXRva2VuLWtleS1ub3Qtc2VjcmV0LTA=",
            "--ls.notifications.sink=" + sink,
            "--ls.notifications.allow-list=" + allowList,
            "--ls.notifications.worker.enabled=false",
            "--ls.retention.enabled=false"
        };
    }

    private static SpringApplication app() {
        SpringApplication app = new SpringApplication(Application.class);
        app.setWebApplicationType(WebApplicationType.SERVLET); // on port 0: the real chain starts
        return app;
    }

    @Test
    void stagingWithTheSesSinkAndNoAllowListRefusesToStart() {
        assertThatThrownBy(() -> {
                    try (ConfigurableApplicationContext context = app().run(stagingEnvironment("ses", ""))) {
                        // never reached
                    }
                })
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("ls.notifications.allow-list");
    }

    @Test
    void stagingStartsWithTheDirectorySinkOrWithAnAllowList() {
        try (ConfigurableApplicationContext context = app().run(stagingEnvironment("directory", ""))) {
            assertThat(context.getBean(MailSender.class)).isInstanceOf(DirectoryMailSender.class);
            assertThat(context.getBean(RecipientPolicy.class).allows("chiunque@ls.example"))
                    .isTrue();
        }
        // The allow-list path is checked on the policy alone: building an SES client is not needed
        // to prove the guard, and no AWS call is ever made from a test.
        NotificationsProperties ses = new Binder(new MapConfigurationPropertySource(
                        Map.of("ls.notifications.sink", "ses", "ls.notifications.allow-list", "titolare@ls.example")))
                .bindOrCreate("ls.notifications", Bindable.of(NotificationsProperties.class));
        RecipientPolicy policy = new RecipientPolicy(ses, false);
        assertThat(policy.allows("titolare@ls.example")).isTrue();
        assertThat(policy.allows("altro@ls.example")).isFalse();
        NotificationsProperties sesNoList = new Binder(
                        new MapConfigurationPropertySource(Map.of("ls.notifications.sink", "ses")))
                .bindOrCreate("ls.notifications", Bindable.of(NotificationsProperties.class));
        assertThatThrownBy(() -> new RecipientPolicy(sesNoList, false)).isInstanceOf(IllegalStateException.class);
        assertThat(new RecipientPolicy(sesNoList, true).allows("chiunque@ls.example"))
                .as("production delivers to everyone")
                .isTrue();
    }
}
