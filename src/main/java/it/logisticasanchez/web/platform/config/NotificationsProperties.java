package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * E-mail settings (chapter 10 §2 "E-mail" and §3 "Staging e-mail"; ADR 0015): the delivery
 * sink, the recipient allow-list outside production, the sender identity, the token-encryption
 * key and the outbox worker.
 */
@Validated
@ConfigurationProperties(prefix = "ls.notifications")
public record NotificationsProperties(
        /** Where the worker delivers: SES (staging/prod), a directory of .eml files (local), memory (tests). */
        @NotNull @DefaultValue("directory") Sink sink,
        /**
         * Outside production only these recipients receive e-mail; any other is dropped and
         * logged. Required with the SES sink outside production (the app refuses to start).
         */
        @DefaultValue({}) List<String> allowList,
        @DefaultValue CustomerConfirmations customerConfirmations,
        /** Sender identity on the verified domain (chapter 10 §2). */
        @NotBlank @DefaultValue("noreply@logisticasanchez.com")
        String fromAddress,

        @NotBlank @DefaultValue("Logistica Sanchez") String fromName,
        /**
         * Base64 of 32 random bytes (AES-256-GCM) for the raw one-time tokens held in the outbox
         * ({@code LS_TOKEN_ENCRYPTION_KEY}); required in staging and production.
         */
        @DefaultValue("") String tokenEncryptionKey,
        @DefaultValue Directory directory,
        @DefaultValue Ses ses,
        @DefaultValue Worker worker) {

    public enum Sink {
        SES,
        DIRECTORY,
        MEMORY
    }

    /** Q-07: EML-02 and EML-04 (customer confirmations) are sent. */
    public record CustomerConfirmations(
            @DefaultValue("true") boolean enabled // Q-07
            ) {}

    /** The directory sink writes one {@code .eml} file per delivery. */
    public record Directory(
            @NotBlank @DefaultValue(".local/mail") String path) {}

    /** SESv2 in the region where the domain identity lives (ADR 0001); credentials from the environment. */
    public record Ses(@NotBlank @DefaultValue("eu-north-1") String region) {}

    /** Outbox worker: batch, exponential backoff and the dead threshold (chapter 10 §2). */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("10s") Duration interval,
            @Min(1) @DefaultValue("20") int batchSize,
            @Min(1) @DefaultValue("8") int maxAttempts,
            @NotNull @DefaultValue("30s") Duration backoffBase,
            @NotNull @DefaultValue("1h") Duration backoffMax) {}
}
