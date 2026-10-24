package it.logisticasanchez.web.platform.ratelimit;

import it.logisticasanchez.web.platform.config.RateLimitProperties;
import it.logisticasanchez.web.platform.config.RateLimitProperties.Limit;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * In-memory token-bucket rate limiter keyed by (key, subject) (chapter 10 §4, ADR 0002). Each
 * key is registered with its limit — the six platform keys from {@code ls.ratelimit.*} at
 * start-up, more by modules through {@link #register} — and an unknown key is a programming
 * error. One app instance runs (ADR 0001), so no shared store is needed; buckets idle for a
 * full window are dropped opportunistically.
 */
@Component
public class RateLimiter {

    private static final int CLEANUP_EVERY = 1_000;

    private final Map<String, Limit> limits = new ConcurrentHashMap<>();
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();
    private final Clock clock;

    public RateLimiter(RateLimitProperties properties, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        register(RateLimitKeys.SIGNIN_LINK_EMAIL, properties.signinLinkEmail());
        register(RateLimitKeys.SIGNIN_LINK_IP, properties.signinLinkIp());
        register(RateLimitKeys.STAFF_LOGIN, properties.staffLogin());
        register(RateLimitKeys.TRACKING_LOOKUP, properties.trackingLookup());
        register(RateLimitKeys.FORM_SUBMIT, properties.formSubmit());
        register(RateLimitKeys.REGISTRATION, properties.registration());
    }

    /** Registers (or replaces) the limit of a key; modules call it for keys of their own. */
    public final void register(String key, Limit limit) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(limit, "limit");
        if (limit.window().isZero() || limit.window().isNegative()) {
            throw new IllegalArgumentException("window of " + key + " must be positive");
        }
        limits.put(key, limit);
    }

    public Limit limitOf(String key) {
        Limit limit = limits.get(key);
        if (limit == null) {
            throw new IllegalArgumentException("unregistered rate-limit key: " + key);
        }
        return limit;
    }

    /** Takes one permit for the subject if available. */
    public Decision tryAcquire(String key, String subject) {
        Limit limit = limitOf(key);
        Objects.requireNonNull(subject, "subject");
        if (limit.permits() <= 0) {
            return new Decision(false, limit.window());
        }
        maybeCleanup();
        Bucket bucket = buckets.computeIfAbsent(key + "\u0000" + subject, k -> new Bucket(limit.permits()));
        return bucket.take(limit, clock.instant().toEpochMilli());
    }

    /** Takes one permit or throws the 429 exception of the error model. */
    public void require(String key, String subject) {
        Decision decision = tryAcquire(key, subject);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(key, retryAfterSeconds(decision.retryAfter()));
        }
    }

    /** The {@code Retry-After} value: whole seconds, rounded up, at least 1. */
    static long retryAfterSeconds(Duration retryAfter) {
        long millis = Math.max(0, retryAfter.toMillis());
        return Math.max(1, (millis + 999) / 1000);
    }

    /** Forgets every bucket (tests). */
    public void reset() {
        buckets.clear();
    }

    private void maybeCleanup() {
        if (calls.incrementAndGet() % CLEANUP_EVERY != 0) {
            return;
        }
        long now = clock.instant().toEpochMilli();
        buckets.entrySet().removeIf(entry -> {
            String key = entry.getKey().substring(0, entry.getKey().indexOf('\u0000'));
            Limit limit = limits.get(key);
            return limit == null || entry.getValue().isIdle(now, limit);
        });
    }

    /** Allowed, or the time after which one permit will be available again. */
    public record Decision(boolean allowed, Duration retryAfter) {}

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis = Long.MIN_VALUE;

        Bucket(int capacity) {
            this.tokens = capacity;
        }

        synchronized Decision take(Limit limit, long nowMillis) {
            double perMillis = (double) limit.permits() / limit.window().toMillis();
            if (lastRefillMillis != Long.MIN_VALUE) {
                tokens = Math.min(limit.permits(), tokens + (nowMillis - lastRefillMillis) * perMillis);
            }
            lastRefillMillis = nowMillis;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Decision(true, Duration.ZERO);
            }
            long waitMillis = (long) Math.ceil((1.0 - tokens) / perMillis);
            return new Decision(false, Duration.ofMillis(waitMillis));
        }

        synchronized boolean isIdle(long nowMillis, Limit limit) {
            return nowMillis - lastRefillMillis > limit.window().toMillis() && tokens >= limit.permits() - 1e-9;
        }
    }
}
