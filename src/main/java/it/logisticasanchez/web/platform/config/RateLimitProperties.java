package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Rate limits (chapter 10 §4; plan §4.3): one entry per registered key, {@code permits} per
 * sliding {@code window} for one subject (an IP or a normalised e-mail). The keys are
 * registered by {@code platform} at start-up; modules consume them by name.
 */
@Validated
@ConfigurationProperties(prefix = "ls.ratelimit")
public record RateLimitProperties(
        /** CUS-01: sign-in link requests per normalised e-mail. */
        @DefaultValue Limit signinLinkEmail,
        /** CUS-01: sign-in link requests per client IP. */
        @DefaultValue Limit signinLinkIp,
        /** ADM-01: staff login attempts per client IP (the per-account lock-out is PD-10). */
        @DefaultValue Limit staffLogin,
        /** TRK-04: tracking lookups per client IP. */
        @DefaultValue Limit trackingLookup,
        /** PUB-06/PUB-07: public form submissions per client IP. */
        @DefaultValue Limit formSubmit,
        /** CUS-02: registrations per client IP. */
        @DefaultValue Limit registration) {

    public RateLimitProperties {
        signinLinkEmail = or(signinLinkEmail, 5, Duration.ofMinutes(15));
        signinLinkIp = or(signinLinkIp, 20, Duration.ofMinutes(15));
        staffLogin = or(staffLogin, 20, Duration.ofMinutes(15));
        trackingLookup = or(trackingLookup, 60, Duration.ofMinutes(1));
        formSubmit = or(formSubmit, 10, Duration.ofMinutes(10));
        registration = or(registration, 5, Duration.ofHours(1));
    }

    private static Limit or(Limit value, int permits, Duration window) {
        return value == null || (value.permits() == 0 && value.window() == null) ? new Limit(permits, window) : value;
    }

    /** {@code permits} requests per {@code window}; refilled continuously (token bucket). */
    public record Limit(@Min(0) int permits, @NotNull Duration window) {}
}
