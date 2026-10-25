package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.retention.RetentionJob;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR 0031: a remembered device whose 60 days have passed can no longer sign anyone in, so its
 * row is deleted at the next run (the cookie, if the browser still sends it, is then cleared).
 */
@Component
public class ExpiredRememberedSignInRetentionJob implements RetentionJob {

    private final RememberedSignInRepository devices;

    public ExpiredRememberedSignInRetentionJob(RememberedSignInRepository devices) {
        this.devices = devices;
    }

    @Override
    public String name() {
        return "remembered-sign-ins";
    }

    @Override
    @Transactional
    public int run(Instant now) {
        return devices.deleteExpiredBefore(now);
    }
}
