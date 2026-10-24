package it.logisticasanchez.web.platform.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Report delivery rules consumed by the {@code reports} module (Q-08, PD-11). */
@Validated
@ConfigurationProperties(prefix = "ls.reports")
public record ReportsProperties(
        @DefaultValue Recipients recipients,
        @DefaultValue("true") boolean customerCanReceive // Q-08
        ) {

    /**
     * Q-08: recipients are the office address plus an ADMIN-managed allow-list; an empty list
     * means the office address alone ({@code ls.business.office-email}).
     */
    public record Recipients(@DefaultValue({}) List<String> allowList // Q-08
            ) {}
}
