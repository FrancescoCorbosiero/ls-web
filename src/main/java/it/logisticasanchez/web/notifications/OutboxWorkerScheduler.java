package it.logisticasanchez.web.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the worker every {@code ls.notifications.worker.interval}; off in tests. */
@Component
@ConditionalOnProperty(name = "ls.notifications.worker.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxWorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorkerScheduler.class);

    private final OutboxWorker worker;

    public OutboxWorkerScheduler(OutboxWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${ls.notifications.worker.interval}", initialDelayString = "${ls.notifications.worker.interval}")
    public void run() {
        try {
            worker.runOnce();
        } catch (RuntimeException e) {
            log.error("outbox worker run failed", e);
        }
    }
}
