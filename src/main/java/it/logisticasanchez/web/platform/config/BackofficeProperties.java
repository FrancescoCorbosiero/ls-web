package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Back-office presentation rules consumed by the {@code backoffice} module (PD-06; the «Oggi» list of ADR 0035). */
@Validated
@ConfigurationProperties(prefix = "ls.backoffice")
public record BackofficeProperties(
        /** PD-06: the "In Approvazione" list shows PENDING_APPROVAL only, as today. */
        @DefaultValue("false") boolean showUnverifiedCustomers, // PD-06
        /** ADR 0035 «Oggi» (admin.dashboard): a confirmed shipment still on its way after this many days is listed. */
        @DefaultValue("3") @Min(1) @Max(60) int todayTransitDays) {}
