package it.logisticasanchez.web.platform.retention;

import java.time.Instant;

/**
 * One retention rule (Q-18, PD-16, chapter 10 §2), owned by the module whose table it cleans
 * and registered simply by being a bean: the {@link RetentionRunner} collects every
 * implementation. {@link #run} is idempotent and returns the number of rows affected; a job
 * that deletes audit rows must open its own transaction with
 * {@code SET LOCAL ls.audit_maintenance = 'on'} (ADR 0020).
 */
public interface RetentionJob {

    /** A stable name for the log, e.g. {@code login-tokens}. */
    String name();

    int run(Instant now);
}
