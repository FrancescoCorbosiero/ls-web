package it.logisticasanchez.web.notifications;

/**
 * The outbox contract (chapter 10 §2 "E-mail", ADR 0015): {@link #enqueue} writes the
 * {@code email_outbox} row in the caller's transaction and returns at once; the worker sends it
 * later with retries. Nothing sends e-mail directly from a controller or a service.
 */
public interface NotificationService {

    /** Queues the e-mail; returns the outbox row id. */
    long enqueue(EmailMessage message);

    /**
     * Erasure (NFR-03, ADR 0024): every outbox row addressed to, replying to, or mentioning
     * {@code email} in its bodies loses its bodies and its one-time token, the address is
     * replaced by {@code placeholder} where it is the recipient or the Reply-To, and a row still
     * queued is marked FAILED so that nothing about the erased person is ever sent.
     * Returns the number of rows touched.
     */
    int eraseRecipient(String email, String placeholder);
}
