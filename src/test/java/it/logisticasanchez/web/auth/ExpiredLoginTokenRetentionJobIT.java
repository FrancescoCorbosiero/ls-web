package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * NFR-03 (chapter 10 §2 retention): {@code login_token} rows are deleted 24 hours after they
 * expire — clock-driven: rows expired 25 h before "now" go, rows expired 23 h before stay.
 */
@Tag("NFR-03")
@SpringBootTest
class ExpiredLoginTokenRetentionJobIT extends AbstractPostgresIT {

    @Autowired
    private ExpiredLoginTokenRetentionJob job;

    @Autowired
    private LoginTokenRepository tokens;

    @Test
    void deletesRowsExpiredMoreThan24HoursBeforeNow() {
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        LoginToken old = tokens.save(LoginToken.forRegistration(hash("a"), hash("old"), now.minus(Duration.ofHours(25))));
        LoginToken recent = tokens.save(LoginToken.forRegistration(hash("b"), hash("recent"), now.minus(Duration.ofHours(23))));
        LoginToken live = tokens.save(LoginToken.forRegistration(hash("c"), hash("live"), now.plus(Duration.ofMinutes(10))));

        int deleted = job.run(now);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(tokens.findById(old.getId())).isEmpty();
        assertThat(tokens.findById(recent.getId())).isPresent();
        assertThat(tokens.findById(live.getId())).isPresent();
        assertThat(job.name()).isEqualTo("login-tokens");
        tokens.deleteAll(java.util.List.of(recent, live));
    }

    /** A 64-hex synthetic hash (the column CHECK requires length 64). */
    private static String hash(String seed) {
        String base = Integer.toHexString(seed.hashCode());
        return (base + "0".repeat(64)).substring(0, 64);
    }
}
