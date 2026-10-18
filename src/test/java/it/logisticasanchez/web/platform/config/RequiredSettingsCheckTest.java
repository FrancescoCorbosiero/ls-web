package it.logisticasanchez.web.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Staging and production fail fast on missing required settings (SEC-20 not reproduced). */
class RequiredSettingsCheckTest {

    private static <T> T bind(String prefix, Class<T> type, Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate(prefix, Bindable.of(type));
    }

    @Test
    void anEmptyEnvironmentNamesEveryMissingVariable() {
        SecurityProperties security = bind("ls.security", SecurityProperties.class, Map.of());
        NotificationsProperties notifications = bind("ls.notifications", NotificationsProperties.class, Map.of());
        FlagsProperties flags = bind("ls.flags", FlagsProperties.class, Map.of());

        List<String> problems = RequiredSettingsCheck.check(security, notifications, flags, true);

        assertThat(problems)
                .anyMatch(p -> p.contains("LS_SECURITY_IP_HASH_KEY"))
                .anyMatch(p -> p.contains("LS_NOTIFICATIONS_TOKEN_ENCRYPTION_KEY"))
                .anyMatch(p -> p.contains("trust-forwarded-headers"))
                .anyMatch(p -> p.contains("sink must be ses"));
    }

    @Test
    void aCompleteProductionEnvironmentPassesAndOverridesAreRefused() {
        SecurityProperties security = bind(
                "ls.security",
                SecurityProperties.class,
                Map.of("ls.security.ip-hash-key", "k", "ls.security.proxy.trust-forwarded-headers", "true"));
        NotificationsProperties notifications = bind(
                "ls.notifications",
                NotificationsProperties.class,
                Map.of("ls.notifications.token-encryption-key", "k", "ls.notifications.sink", "ses"));
        FlagsProperties none = bind("ls.flags", FlagsProperties.class, Map.of());
        FlagsProperties overridden = bind("ls.flags", FlagsProperties.class, Map.of("ls.flags.override.[site.openNow]", "true"));

        assertThat(RequiredSettingsCheck.check(security, notifications, none, true)).isEmpty();
        assertThat(RequiredSettingsCheck.check(security, notifications, overridden, true))
                .singleElement()
                .asString()
                .contains("ADR 0025");
        // Staging accepts the directory sink and overrides.
        NotificationsProperties staging = bind(
                "ls.notifications",
                NotificationsProperties.class,
                Map.of("ls.notifications.token-encryption-key", "k", "ls.notifications.sink", "directory"));
        assertThat(RequiredSettingsCheck.check(security, staging, overridden, false)).isEmpty();
    }
}
