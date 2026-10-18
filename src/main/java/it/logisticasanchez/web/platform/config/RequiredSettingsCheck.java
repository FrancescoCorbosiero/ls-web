package it.logisticasanchez.web.platform.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail fast in staging and production (SEC-20: the legacy ran with an inert profile file and
 * silent defaults). Every value that has no safe default must come from the environment; the
 * message names the variable to set.
 */
@Component
@Profile({"staging", "prod"})
public class RequiredSettingsCheck implements InitializingBean {

    private final SecurityProperties security;
    private final NotificationsProperties notifications;
    private final FlagsProperties flags;
    private final boolean production;

    public RequiredSettingsCheck(
            SecurityProperties security,
            NotificationsProperties notifications,
            FlagsProperties flags,
            org.springframework.core.env.Environment environment) {
        this.security = security;
        this.notifications = notifications;
        this.flags = flags;
        this.production = environment.matchesProfiles("prod");
    }

    @Override
    public void afterPropertiesSet() {
        List<String> missing = check(security, notifications, flags, production);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing or invalid required settings: " + String.join("; ", missing));
        }
    }

    /** The problems found, empty when the configuration is complete. Package-visible for the unit test. */
    static List<String> check(
            SecurityProperties security, NotificationsProperties notifications, FlagsProperties flags, boolean prod) {
        List<String> problems = new ArrayList<>();
        if (security.ipHashKey().isBlank()) {
            problems.add("ls.security.ip-hash-key (LS_SECURITY_IP_HASH_KEY) is required");
        }
        if (notifications.tokenEncryptionKey().isBlank()) {
            problems.add("ls.notifications.token-encryption-key (LS_NOTIFICATIONS_TOKEN_ENCRYPTION_KEY) is required");
        }
        if (!security.proxy().trustForwardedHeaders()) {
            problems.add("ls.security.proxy.trust-forwarded-headers must be true behind the reverse proxy");
        }
        if (prod && notifications.sink() != NotificationsProperties.Sink.SES) {
            problems.add("ls.notifications.sink must be ses in production");
        }
        if (prod && !flags.override().isEmpty()) {
            problems.add("ls.flags.override.* is refused in production (ADR 0025)");
        }
        return problems;
    }
}
