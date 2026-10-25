package it.logisticasanchez.web.notifications;

/** Delivery state of an outbox row (table {@code email_outbox}). */
public enum EmailOutboxStatus {
    /** Waiting for the worker, or for a retry after a failed attempt. */
    QUEUED,
    SENT,
    /** Retries exhausted: an alert is raised (NFR-04). */
    FAILED
}
