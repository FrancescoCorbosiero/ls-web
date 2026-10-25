package it.logisticasanchez.web.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Back-office presentation rules consumed by the {@code backoffice} module (PD-06). */
@Validated
@ConfigurationProperties(prefix = "ls.backoffice")
public record BackofficeProperties(
        /** PD-06: the "In Approvazione" list shows PENDING_APPROVAL only, as today. */
        @DefaultValue("false") boolean showUnverifiedCustomers // PD-06
        ) {}
