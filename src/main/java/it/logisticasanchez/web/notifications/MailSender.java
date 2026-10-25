package it.logisticasanchez.web.notifications;

/**
 * The delivery port of the outbox worker (ADR 0015). Adapters: {@link SesV2MailSender}
 * (staging/prod), {@link DirectoryMailSender} (local, {@code .eml} files),
 * {@link InMemoryMailSender} (tests). Chosen by {@code ls.notifications.sink}.
 */
public interface MailSender {

    /** Sends and returns the provider's message id (may be null). Throws on failure. */
    String send(OutboundEmail email) throws MailSendException;

    /** A delivery failure; the worker retries with backoff. */
    class MailSendException extends Exception {
        public MailSendException(String message, Throwable cause) {
            super(message, cause);
        }

        public MailSendException(String message) {
            super(message);
        }
    }
}
