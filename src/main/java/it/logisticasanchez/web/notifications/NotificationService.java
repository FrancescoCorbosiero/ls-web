package it.logisticasanchez.web.notifications;

/**
 * The outbox contract (chapter 10 §2 "E-mail", ADR 0015): {@link #enqueue} writes the
 * {@code email_outbox} row in the caller's transaction and returns at once; the worker sends it
 * later with retries. Nothing sends e-mail directly from a controller or a service.
 */
public interface NotificationService {

    /** Queues the e-mail; returns the outbox row id. */
    long enqueue(EmailMessage message);
}
