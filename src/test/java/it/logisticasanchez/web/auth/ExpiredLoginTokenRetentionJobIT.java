package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * NFR-03 (chapter 10 §2 retention): {@code login_token} rows are deleted 24 hours after they
 * expire — clock-driven: rows expired 25 h before "now" go, rows expired 23 h before stay, live
 * rows stay. The rows are inserted with explicit timestamps (the table requires
 * {@code expires_at > created_at}, so an expired row must have been created earlier).
 */
@Tag("NFR-03")
@SpringBootTest
class ExpiredLoginTokenRetentionJobIT extends AbstractPostgresIT {

    @Autowired
    private ExpiredLoginTokenRetentionJob job;

    @Autowired
    private LoginTokenRepository tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void deletesRowsExpiredMoreThan24HoursBeforeNow() {
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        long old = insert("old", now.minus(Duration.ofHours(25)));
        long recent = insert("recent", now.minus(Duration.ofHours(23)));
        long live = insert("live", now.plus(Duration.ofMinutes(10)));

        int deleted = job.run(now);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(tokens.findById(old)).isEmpty();
        assertThat(tokens.findById(recent)).isPresent();
        assertThat(tokens.findById(live)).isPresent();
        assertThat(job.name()).isEqualTo("login-tokens");
        jdbc.update("delete from login_token where id in (?, ?)", recent, live);
    }

    /** A synthetic REGISTER token created 30 days before it expires. */
    private long insert(String seed, Instant expiresAt) {
        Instant createdAt = expiresAt.minus(Duration.ofDays(30));
        return jdbc.queryForObject(
                "insert into login_token (email_hash, purpose, token_hash, expires_at, created_at)"
                        + " values (?, 'REGISTER', ?, ?, ?) returning id",
                Long.class,
                hash("email-" + seed),
                hash("token-" + seed),
                Timestamp.from(expiresAt),
                Timestamp.from(createdAt));
    }

    /** A 64-hex synthetic hash (the column CHECK requires length 64). */
    private static String hash(String seed) {
        String base = Integer.toHexString(seed.hashCode());
        return (base + "0".repeat(64)).substring(0, 64);
    }
}
