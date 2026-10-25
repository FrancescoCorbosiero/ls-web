package it.logisticasanchez.web.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A queued e-mail (table {@code email_outbox}, chapter 10 §2). Written in the business
 * transaction, sent by the worker with retries; the bodies are purged 30 days after delivery
 * (Q-18). For one-time links the raw token is held only encrypted and wiped after delivery.
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    @Column(name = "recipient", nullable = false, length = 254)
    private String recipient;

    @Column(name = "reply_to", length = 254)
    private String replyTo;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body_text")
    private String bodyText;

    @Column(name = "body_html")
    private String bodyHtml;

    @Column(name = "login_token_id")
    private Long loginTokenId;

    @Column(name = "token_ciphertext")
    private byte[] tokenCiphertext;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 12)
    private EmailOutboxStatus status = EmailOutboxStatus.QUEUED;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "provider_message_id", length = 120)
    private String providerMessageId;

    @Column(name = "bodies_purged_at")
    private Instant bodiesPurgedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EmailOutbox() {}

    /**
     * Queues an e-mail of the catalogue ({@code kind} is an {@code EML-0x} id of chapter 09) with
     * both bodies rendered.
     */
    public EmailOutbox(String kind, String recipient, String subject, String bodyText, String bodyHtml) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.recipient = Objects.requireNonNull(recipient, "recipient");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.bodyText = Objects.requireNonNull(bodyText, "bodyText");
        this.bodyHtml = bodyHtml;
    }

    public Long getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }

    public String getSubject() {
        return subject;
    }

    public String getBodyText() {
        return bodyText;
    }

    public String getBodyHtml() {
        return bodyHtml;
    }

    public Long getLoginTokenId() {
        return loginTokenId;
    }

    public byte[] getTokenCiphertext() {
        return tokenCiphertext;
    }

    /** Stamps creation and first attempt with the application clock (set by the service). */
    void queuedAt(Instant now) {
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
        this.nextAttemptAt = now;
    }

    /**
     * Attaches the one-time link this e-mail carries, the raw token only encrypted; the
     * {@code login_token} reference is null for a staff set-password link (V010).
     */
    public void attachToken(Long loginTokenId, byte[] tokenCiphertext) {
        this.loginTokenId = loginTokenId;
        this.tokenCiphertext = Objects.requireNonNull(tokenCiphertext, "tokenCiphertext");
    }

    public EmailOutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public Instant getBodiesPurgedAt() {
        return bodiesPurgedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Records a successful delivery and wipes the token ciphertext and reference. */
    public void markSent(Instant now, String providerMessageId) {
        this.status = EmailOutboxStatus.SENT;
        this.sentAt = Objects.requireNonNull(now, "now");
        this.providerMessageId = providerMessageId;
        this.tokenCiphertext = null;
        this.loginTokenId = null;
        this.attempts++;
        this.updatedAt = now;
    }

    /** Records a failed attempt: a retry at {@code retryAt}, or FAILED when none is left. */
    public void markAttemptFailed(Instant now, String error, Instant retryAt) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        this.updatedAt = now;
        if (retryAt == null) {
            this.status = EmailOutboxStatus.FAILED;
            this.tokenCiphertext = null;
            this.loginTokenId = null;
        } else {
            this.nextAttemptAt = retryAt;
        }
    }

    /**
     * Erasure (NFR-03): the erased address, wherever it appears (recipient, Reply-To), is
     * replaced by the placeholder; bodies and token are removed; a row still QUEUED becomes
     * FAILED (it would otherwise reach the erased person, and V007 requires a body on a queued row).
     */
    public void erase(String email, String placeholder, Instant now) {
        if (recipient.equalsIgnoreCase(email)) {
            this.recipient = placeholder;
        }
        if (replyTo != null && replyTo.equalsIgnoreCase(email)) {
            this.replyTo = placeholder;
        }
        this.loginTokenId = null;
        if (status == EmailOutboxStatus.QUEUED) {
            this.status = EmailOutboxStatus.FAILED;
            this.lastError = "erased";
        }
        purgeBodies(now);
    }

    /** Retention (Q-18): removes the bodies and the token, keeps the metadata. */
    public void purgeBodies(Instant now) {
        this.bodyText = null;
        this.bodyHtml = null;
        this.tokenCiphertext = null;
        this.bodiesPurgedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }
}
