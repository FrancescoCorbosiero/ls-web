package it.logisticasanchez.web.notifications;

import it.logisticasanchez.web.platform.config.NotificationsProperties;
import it.logisticasanchez.web.platform.config.NotificationsProperties.Sink;
import java.util.List;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The guard of chapter 10 §3 "Staging e-mail": outside production no e-mail reaches a real
 * recipient. With the SES sink outside the {@code prod} profile the app refuses to start unless
 * a non-empty allow-list ({@code ls.notifications.allow-list}) is configured; whenever an
 * allow-list is configured outside production, any recipient not on it is dropped by the worker
 * and logged (by outbox id, never by address). In production every recipient is allowed.
 */
@Component
public class RecipientPolicy {

    private final boolean production;
    private final List<String> allowList;

    @Autowired
    public RecipientPolicy(NotificationsProperties properties, Environment environment) {
        this(properties, environment.matchesProfiles("prod"));
    }

    RecipientPolicy(NotificationsProperties properties, boolean production) {
        this.production = production;
        this.allowList = properties.allowList().stream()
                .map(address -> address.trim().toLowerCase(Locale.ROOT))
                .filter(address -> !address.isEmpty())
                .toList();
        if (!production && properties.sink() == Sink.SES && allowList.isEmpty()) {
            throw new IllegalStateException("Outside production the SES sink needs a recipient allow-list:"
                    + " set ls.notifications.allow-list (LS_NOTIFICATIONS_ALLOW_LIST) or use"
                    + " ls.notifications.sink=directory (chapter 10 §3 \"Staging e-mail\")");
        }
    }

    public boolean allows(String recipient) {
        if (production || allowList.isEmpty()) {
            return true;
        }
        return allowList.contains(recipient.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isProduction() {
        return production;
    }
}
