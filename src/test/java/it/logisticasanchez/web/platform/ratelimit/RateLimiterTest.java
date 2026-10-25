package it.logisticasanchez.web.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.platform.config.RateLimitProperties;
import it.logisticasanchez.web.platform.config.RateLimitProperties.Limit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** NFR-02: the token bucket refuses the permit past the limit and refills with time, per subject and key. */
@Tag("NFR-02")
class RateLimiterTest {

    private Instant now = Instant.parse("2026-10-17T10:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private RateLimiter limiter() {
        Limit three = new Limit(3, Duration.ofMinutes(1));
        return new RateLimiter(new RateLimitProperties(three, three, three, three, three, three, three, three), clock);
    }

    @Test
    void refusesThePermitPastTheLimitAndSaysWhenToRetry() {
        RateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(RateLimitKeys.FORM_SUBMIT, "203.0.113.5")
                            .allowed())
                    .isTrue();
        }
        RateLimiter.Decision refused = limiter.tryAcquire(RateLimitKeys.FORM_SUBMIT, "203.0.113.5");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(20));
        assertThatThrownBy(() -> limiter.require(RateLimitKeys.FORM_SUBMIT, "203.0.113.5"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).retryAfterSeconds())
                        .isEqualTo(20));
    }

    @Test
    void subjectsAndKeysAreIndependent() {
        RateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(RateLimitKeys.FORM_SUBMIT, "203.0.113.5");
        }
        assertThat(limiter.tryAcquire(RateLimitKeys.FORM_SUBMIT, "203.0.113.6").allowed())
                .isTrue();
        assertThat(limiter.tryAcquire(RateLimitKeys.TRACKING_LOOKUP, "203.0.113.5")
                        .allowed())
                .isTrue();
    }

    @Test
    void permitsComeBackWithTime() {
        RateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(RateLimitKeys.SIGNIN_LINK_EMAIL, "a@ls.example");
        }
        assertThat(limiter.tryAcquire(RateLimitKeys.SIGNIN_LINK_EMAIL, "a@ls.example")
                        .allowed())
                .isFalse();
        now = now.plusSeconds(20);
        assertThat(limiter.tryAcquire(RateLimitKeys.SIGNIN_LINK_EMAIL, "a@ls.example")
                        .allowed())
                .isTrue();
        assertThat(limiter.tryAcquire(RateLimitKeys.SIGNIN_LINK_EMAIL, "a@ls.example")
                        .allowed())
                .isFalse();
        now = now.plus(Duration.ofMinutes(5));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(RateLimitKeys.SIGNIN_LINK_EMAIL, "a@ls.example")
                            .allowed())
                    .isTrue();
        }
        assertThat(limiter.tryAcquire(RateLimitKeys.SIGNIN_LINK_EMAIL, "a@ls.example")
                        .allowed())
                .isFalse();
    }

    @Test
    void unknownKeysAreAProgrammingErrorAndModulesCanRegisterTheirOwn() {
        RateLimiter limiter = limiter();
        assertThatThrownBy(() -> limiter.tryAcquire("orders.create", "x")).isInstanceOf(IllegalArgumentException.class);
        limiter.register("orders.create", new Limit(1, Duration.ofHours(1)));
        assertThat(limiter.tryAcquire("orders.create", "x").allowed()).isTrue();
        assertThat(limiter.tryAcquire("orders.create", "x").allowed()).isFalse();
    }

    @Test
    void theDefaultLimitsOfTheEightPlatformKeysAreRegistered() {
        RateLimiter limiter =
                new RateLimiter(new RateLimitProperties(null, null, null, null, null, null, null, null), clock);
        assertThat(limiter.limitOf(RateLimitKeys.SIGNIN_LINK_EMAIL)).isEqualTo(new Limit(5, Duration.ofMinutes(15)));
        assertThat(limiter.limitOf(RateLimitKeys.SIGNIN_LINK_IP)).isEqualTo(new Limit(20, Duration.ofMinutes(15)));
        assertThat(limiter.limitOf(RateLimitKeys.SIGNIN_PASSWORD_EMAIL))
                .isEqualTo(new Limit(10, Duration.ofMinutes(15)));
        assertThat(limiter.limitOf(RateLimitKeys.SIGNIN_PASSWORD_IP)).isEqualTo(new Limit(30, Duration.ofMinutes(15)));
        assertThat(limiter.limitOf(RateLimitKeys.STAFF_LOGIN)).isEqualTo(new Limit(20, Duration.ofMinutes(15)));
        assertThat(limiter.limitOf(RateLimitKeys.TRACKING_LOOKUP)).isEqualTo(new Limit(60, Duration.ofMinutes(1)));
        assertThat(limiter.limitOf(RateLimitKeys.FORM_SUBMIT)).isEqualTo(new Limit(10, Duration.ofMinutes(10)));
        assertThat(limiter.limitOf(RateLimitKeys.REGISTRATION)).isEqualTo(new Limit(5, Duration.ofHours(1)));
    }
}
