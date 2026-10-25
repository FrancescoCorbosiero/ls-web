package it.logisticasanchez.web.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A clock the test moves by hand. {@code @Import(MutableClock.Configuration.class)} replaces
 * the application clock bean, so services, workers and jobs all read the same "now".
 */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant now) {
        this.now = Objects.requireNonNull(now, "now");
    }

    /** Starts at the real current time (millisecond precision, as the database stores it). */
    public static MutableClock startingNow() {
        return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    public void set(Instant now) {
        this.now = Objects.requireNonNull(now, "now");
    }

    public void advance(Duration by) {
        this.now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    /** Registers a {@link MutableClock} as the primary {@link Clock} of a test context. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        MutableClock mutableClock() {
            return MutableClock.startingNow();
        }
    }
}
