package it.logisticasanchez.web.notifications;

import it.logisticasanchez.web.platform.config.NotificationsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link OutboxWorker} every {@code ls.notifications.worker.interval} (fixed delay,
 * so two runs never overlap). Registered only when {@code ls.notifications.worker.enabled} is
 * true: off in the test profile, where tests call {@link OutboxWorker#runOnce()} directly.
 */
@Component
public class OutboxWorkerScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorkerScheduler.class);

    private final OutboxWorker worker;
    private final NotificationsProperties.Worker settings;

    public OutboxWorkerScheduler(OutboxWorker worker, NotificationsProperties properties) {
        this.worker = worker;
        this.settings = properties.worker();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!settings.enabled()) {
            log.info("outbox worker disabled (ls.notifications.worker.enabled=false)");
            return;
        }
        registrar.addFixedDelayTask(this::run, settings.interval());
        log.info("outbox worker scheduled every {}", settings.interval());
    }

    void run() {
        try {
            worker.runOnce();
        } catch (RuntimeException e) {
            log.error("outbox worker run failed", e);
        }
    }
}
