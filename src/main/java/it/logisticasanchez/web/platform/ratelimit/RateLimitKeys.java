package it.logisticasanchez.web.platform.ratelimit;

/**
 * The rate-limit keys registered by {@code platform} (plan §4.3). A module acquires a permit
 * with {@code rateLimiter.require(key, subject)}; the subject is a client IP or a normalised
 * e-mail, never logged.
 */
public final class RateLimitKeys {

    /** CUS-01: sign-in link requests, subject = normalised e-mail. */
    public static final String SIGNIN_LINK_EMAIL = "signin.link.email";
    /** CUS-01: sign-in link requests, subject = client IP. */
    public static final String SIGNIN_LINK_IP = "signin.link.ip";
    /** CUS-01 (ADR 0032): password sign-in attempts, subject = normalised e-mail. */
    public static final String SIGNIN_PASSWORD_EMAIL = "signin.password.email";
    /** CUS-01 (ADR 0032): password sign-in attempts, subject = client IP. */
    public static final String SIGNIN_PASSWORD_IP = "signin.password.ip";
    /** ADM-01: staff login attempts, subject = client IP. */
    public static final String STAFF_LOGIN = "staff.login";
    /** TRK-04: tracking lookups, subject = client IP. */
    public static final String TRACKING_LOOKUP = "tracking.lookup";
    /** PUB-06/07: public form submissions, subject = client IP. */
    public static final String FORM_SUBMIT = "form.submit";
    /** CUS-02: registrations, subject = client IP. */
    public static final String REGISTRATION = "registration";

    private RateLimitKeys() {}
}
