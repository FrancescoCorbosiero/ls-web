package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Compatibility API settings consumed by the {@code tracking} module (API-01…API-03, ADR 0010,
 * ADR 0011): the hosts that answer old {@code /mono/**} links, the CORS origins of the tracking
 * endpoint and the JSON strictness.
 */
@Validated
@ConfigurationProperties(prefix = "ls.compat")
public record CompatProperties(
        /** Hosts on which old {@code /mono/**} links are answered by the Italian page (Q-30, Q-50). */
        @NotEmpty @DefaultValue({"logisticasanchez.com", "app.logisticasanchez.com"}) List<String> hosts, // Q-30
        @DefaultValue Cors cors,
        /** PD-09: unknown JSON fields on API-01 are ignored (the legacy answered 500). */
        @DefaultValue("false") boolean strictJson // PD-09
        ) {

    /** Q-39: exact HTTPS origins only, {@code POST, OPTIONS}, {@code Content-Type}, no credentials. */
    public record Cors(@NotEmpty @DefaultValue("https://logisticasanchez.com") List<String> allowedOrigins // Q-39
            ) {}
}
