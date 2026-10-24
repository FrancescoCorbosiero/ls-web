package it.logisticasanchez.web.notifications;

import java.util.Objects;

/**
 * What a module hands to {@link NotificationService}: the catalogue id (EML-01…EML-09), the
 * recipient, the subject and both rendered bodies. For one-time links the bodies contain
 * {@link #TOKEN_PLACEHOLDER} where the raw token goes; the raw token itself travels only in
 * {@link TokenLink} and is stored encrypted (chapter 10 §2).
 */
public record EmailMessage(
        String kind,
        String recipient,
        String subject,
        String bodyText,
        String bodyHtml,
        String replyTo,
        TokenLink tokenLink) {

    /** Replaced by the raw token at send time, in the text and HTML bodies. */
    public static final String TOKEN_PLACEHOLDER = "{{token}}";

    public EmailMessage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(bodyText, "bodyText");
        if (!recipient.contains("@") || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient is not an e-mail address");
        }
    }

    public static EmailMessage of(String kind, String recipient, String subject, String bodyText, String bodyHtml) {
        return new EmailMessage(kind, recipient, subject, bodyText, bodyHtml, null, null);
    }

    public EmailMessage withReplyTo(String address) {
        return new EmailMessage(kind, recipient, subject, bodyText, bodyHtml, address, tokenLink);
    }

    public EmailMessage withTokenLink(long loginTokenId, String rawToken) {
        return new EmailMessage(
                kind, recipient, subject, bodyText, bodyHtml, replyTo, new TokenLink(loginTokenId, rawToken));
    }

    /**
     * A one-time secret that references no {@code login_token} row (the staff set-password link,
     * whose row is {@code staff_setup_token}): stored encrypted and substituted like a token.
     */
    public EmailMessage withSecret(String rawToken) {
        return new EmailMessage(kind, recipient, subject, bodyText, bodyHtml, replyTo, new TokenLink(null, rawToken));
    }

    /**
     * The one-time link this e-mail carries: the raw token and, for customer links, the
     * {@code login_token} row it belongs to (null for a staff set-password link).
     */
    public record TokenLink(Long loginTokenId, String rawToken) {
        public TokenLink {
            Objects.requireNonNull(rawToken, "rawToken");
        }
    }
}
