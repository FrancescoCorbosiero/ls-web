package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Security settings of chapter 10 §2 and §4: session lifetimes, token lifetimes, staff lock-out
 * (PD-10), proxy trust, HSTS and the IP-hash key. Every lifetime is a seam the owner may change
 * (chapter 10 §2 "the owner may change these").
 */
@Validated
@ConfigurationProperties(prefix = "ls.security")
public record SecurityProperties(
        @DefaultValue Sessions sessions,
        @DefaultValue Tokens tokens,
        @DefaultValue Staff staff,
        @DefaultValue Proxy proxy,
        @DefaultValue Hsts hsts,
        /**
         * HMAC-SHA-256 key for IP hashes ({@code audit_log.ip_hash}, {@code
         * login_token.requested_ip_hash}; chapter 10 §2). Required in staging and production
         * ({@code LS_SECURITY_IP_HASH_KEY}); the local and test profiles set a non-secret value.
         */
        @DefaultValue("") String ipHashKey) {

    /** Idle and absolute session timeouts (chapter 10 §2; PD-10 notes they are the chapter's figures). */
    public record Sessions(
            @DefaultValue CustomerSession customer,
            @DefaultValue StaffSession staff) {}

    /** One session class: idle (no request) and absolute (since authentication) limits. */
    public interface Window {
        Duration idle();

        Duration absolute();
    }

    /**
     * Customers: 60 minutes idle, 12 hours absolute (chapter 10 §2); after a sign-in link the
     * device stays signed in for {@code remember} (owner decision of 2026-10-25, PD-10, ADR 0031:
     * 60 days; zero turns it off): a cookie restores the session once these limits end it.
     */
    public record CustomerSession(
            @NotNull @DefaultValue("60m") Duration idle,
            @NotNull @DefaultValue("12h") Duration absolute,
            @NotNull @DefaultValue("60d") Duration remember)
            implements Window {}

    /** Staff: 30 minutes idle, 10 hours absolute (chapter 10 §2). */
    public record StaffSession(
            @NotNull @DefaultValue("30m") Duration idle,
            @NotNull @DefaultValue("10h") Duration absolute) implements Window {}

    /** One-time link lifetimes (chapter 10 §2 "Tokens"). */
    public record Tokens(
            @NotNull @DefaultValue("15m") Duration signIn,
            @NotNull @DefaultValue("15m") Duration verifyEmail,
            @NotNull @DefaultValue("24h") Duration registration,
            @NotNull @DefaultValue("15m") Duration changeEmail) {}

    public record Staff(
            @DefaultValue Lockout lockout, @DefaultValue Totp totp) {}

    /** PD-10: lock the account after {@code threshold} failed logins for {@code duration}. */
    public record Lockout(
            @Min(1) @DefaultValue("10") int threshold, // PD-10
            @NotNull @DefaultValue("15m") Duration duration // PD-10
            ) {}

    /** TOTP for staff is M2 (ENH-09, flag {@code security.adminTotp}); this seam stays off in M1. */
    public record Totp(@DefaultValue("false") boolean enabled // Q-17
            ) {}

    /**
     * Forwarded-header trust (chapter 10 §4): exactly one proxy hop. When true, the scheme,
     * host and client IP come from the {@code X-Forwarded-*} headers written by the proxy, the
     * client IP being the last {@code X-Forwarded-For} value; when false (local, test) the
     * socket address is the client.
     */
    public record Proxy(@DefaultValue("false") boolean trustForwardedHeaders) {}

    /** HSTS is sent only where TLS terminates for real (production profile). */
    public record Hsts(@DefaultValue("false") boolean enabled) {}
}
