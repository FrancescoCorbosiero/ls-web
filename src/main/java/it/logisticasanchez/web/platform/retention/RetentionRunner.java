package it.logisticasanchez.web.platform.retention;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs every registered {@link RetentionJob} on the {@code ls.retention.cron} schedule; a
 * failing job is logged and does not stop the others. Off in the test profile
 * ({@code ls.retention.enabled=false}); tests call {@link #runAll} or a job directly.
 */
@Component
@ConditionalOnProperty(name = "ls.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionRunner.class);

    private final List<RetentionJob> jobs;
    private final Clock clock;

    public RetentionRunner(List<RetentionJob> jobs, Clock clock) {
        this.jobs = List.copyOf(jobs);
        this.clock = clock;
    }

    public List<RetentionJob> jobs() {
        return jobs;
    }

    @Scheduled(cron = "${ls.retention.cron}")
    public void scheduled() {
        runAll(clock.instant());
    }

    public void runAll(Instant now) {
        for (RetentionJob job : jobs) {
            try {
                int affected = job.run(now);
                log.info("retention job={} affected={}", job.name(), affected);
            } catch (RuntimeException e) {
                log.error("retention job={} failed", job.name(), e);
            }
        }
    }
}
