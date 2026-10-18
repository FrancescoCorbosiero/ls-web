package it.logisticasanchez.web.platform.ratelimit;

import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;

/** Raised by {@link RateLimiter#require}; answered as 429 with {@code Retry-After} (ADR 0002). */
public class RateLimitExceededException extends ClientErrorException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String key, long retryAfterSeconds) {
        super(ErrorCode.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
