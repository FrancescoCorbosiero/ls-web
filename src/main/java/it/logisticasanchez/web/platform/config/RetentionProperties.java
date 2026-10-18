package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Retention periods (Q-18, PD-16, chapter 10 §2) applied by the retention jobs that the
 * {@code platform} scheduler runs; each owning module registers its job (ADR 0024, Phase 2).
 */
@Validated
@ConfigurationProperties(prefix = "ls.retention")
public record RetentionProperties(
        /** The scheduled runner is off in tests; jobs are run directly there. */
        @DefaultValue("true") boolean enabled,
        /** Spring cron of the nightly run (server time zone). */
        @NotBlank @DefaultValue("0 30 3 * * *") String cron,
        @Min(1) @DefaultValue("24") int leadsMonths, // Q-18
        @Min(1) @DefaultValue("10") int shipmentsYears, // Q-18
        @Min(1) @DefaultValue("24") int auditMonths, // Q-18
        @Min(1) @DefaultValue("30") int outboxBodiesDays, // Q-18
        @Min(1) @DefaultValue("30") int ipHashesDays, // Q-18
        @Min(1) @DefaultValue("24") int reportRunsMonths, // PD-16
        /** Chapter 10 §2: login_token rows are deleted 24 hours after they expire. */
        @Min(1) @DefaultValue("24") int loginTokensHoursAfterExpiry) {}
