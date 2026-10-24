package it.logisticasanchez.web.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Tracking rules consumed by the {@code tracking} module (Q-03, Q-38). */
@Validated
@ConfigurationProperties(prefix = "ls.tracking")
public record TrackingProperties(
        @DefaultValue Match match,
        /** Q-38: temporary codes of pending orders answer «Non gestito», like unknown codes. */
        @DefaultValue("false") boolean tempCodesVisible // Q-38
        ) {

    /** Q-03: matching trimmed, inner spaces removed, case-insensitive. */
    public record Match(@DefaultValue("true") boolean caseInsensitive // Q-03
            ) {}
}
