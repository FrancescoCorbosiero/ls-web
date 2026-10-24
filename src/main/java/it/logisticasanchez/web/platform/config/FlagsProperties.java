package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Feature-flag service settings (ADR 0025): cache period and the test/local overrides. */
@Validated
@ConfigurationProperties(prefix = "ls.flags")
public record FlagsProperties(
        @Min(0) @DefaultValue("30") int cacheSeconds,
        /**
         * {@code ls.flags.override.<key>=true|false} wins over the table for that key; refused in
         * the prod profile. YAML keys with dots need brackets: {@code "[tracking.history]": true}.
         */
        @DefaultValue({}) Map<String, Boolean> override) {}
