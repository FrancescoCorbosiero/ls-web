package it.logisticasanchez.web.platform.retention;

import it.logisticasanchez.web.platform.config.RetentionProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Runs every registered {@link RetentionJob} on the {@code ls.retention.cron} schedule (Q-18,
 * PD-16, chapter 10 §2); a failing job is logged and does not stop the others. The scheduled
 * task is registered only when {@code ls.retention.enabled} is true (off in the test profile,
 * where tests call {@link #runAll} or a job directly).
 */
@Component
public class RetentionRunner implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RetentionRunner.class);

    private final List<RetentionJob> jobs;
    private final RetentionProperties settings;
    private final Clock clock;

    public RetentionRunner(List<RetentionJob> jobs, RetentionProperties settings, Clock clock) {
        this.jobs = List.copyOf(jobs);
        this.settings = settings;
        this.clock = clock;
    }

    /** The registered jobs, in bean order. */
    public List<RetentionJob> jobs() {
        return jobs;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (settings.enabled()) {
            registrar.addTriggerTask(() -> runAll(clock.instant()), new CronTrigger(settings.cron()));
            log.info("retention runner scheduled with cron '{}' for {} job(s)", settings.cron(), jobs.size());
        } else {
            log.info("retention runner disabled (ls.retention.enabled=false)");
        }
    }

    /** Runs every job once with the given "now"; returns the rows affected per job name. */
    public java.util.Map<String, Integer> runAll(Instant now) {
        java.util.Map<String, Integer> affectedByJob = new java.util.LinkedHashMap<>();
        for (RetentionJob job : jobs) {
            try {
                int affected = job.run(now);
                affectedByJob.put(job.name(), affected);
                log.info("retention job={} affected={}", job.name(), affected);
            } catch (RuntimeException e) {
                log.error("retention job={} failed", job.name(), e);
            }
        }
        return affectedByJob;
    }
}
