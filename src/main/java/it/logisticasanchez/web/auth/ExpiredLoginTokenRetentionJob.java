package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.config.RetentionProperties;
import it.logisticasanchez.web.platform.retention.RetentionJob;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chapter 10 §2: {@code login_token} rows are deleted 24 hours after they expire
 * ({@code ls.retention.login-tokens-hours-after-expiry}). The outbox worker clears its
 * reference to the token once the e-mail is sent or dead; a row still referenced by an
 * undelivered e-mail is left for the next run.
 */
@Component
public class ExpiredLoginTokenRetentionJob implements RetentionJob {

    private final LoginTokenRepository tokens;
    private final RetentionProperties retention;

    public ExpiredLoginTokenRetentionJob(LoginTokenRepository tokens, RetentionProperties retention) {
        this.tokens = tokens;
        this.retention = retention;
    }

    @Override
    public String name() {
        return "login-tokens";
    }

    @Override
    @Transactional
    public int run(Instant now) {
        Instant before = now.minus(Duration.ofHours(retention.loginTokensHoursAfterExpiry()));
        return tokens.deleteExpiredBefore(before);
    }
}
