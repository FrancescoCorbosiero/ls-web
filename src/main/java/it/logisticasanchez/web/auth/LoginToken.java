package it.logisticasanchez.web.auth;

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
 * A one-time link of the customer area (table {@code login_token}, chapter 08 §2.3; CUS-01,
 * chapter 10 §2). Only the SHA-256 hash of the token is stored; the row is consumed once by the
 * CSRF-protected {@code POST} of the confirmation page. {@code REGISTER} links carry no customer
 * user but the HMAC of the unknown e-mail.
 */
@Entity
@Table(name = "login_token")
public class LoginToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_user_id")
    private Long customerUserId;

    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "purpose", nullable = false, length = 16)
    private LoginTokenPurpose purpose;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "requested_ip_hash", length = 64)
    private String requestedIpHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LoginToken() {}

    /** A token for a known customer user (every purpose but {@code REGISTER}), created now. */
    public static LoginToken forCustomerUser(
            Long customerUserId, LoginTokenPurpose purpose, String tokenHash, Instant expiresAt) {
        return forCustomerUser(customerUserId, purpose, tokenHash, expiresAt, Instant.now());
    }

    /** A token for a known customer user, stamped with the application clock. */
    public static LoginToken forCustomerUser(
            Long customerUserId, LoginTokenPurpose purpose, String tokenHash, Instant expiresAt, Instant createdAt) {
        if (purpose == LoginTokenPurpose.REGISTER) {
            throw new IllegalArgumentException("REGISTER tokens carry an e-mail hash, not a customer user");
        }
        LoginToken token = new LoginToken(purpose, tokenHash, expiresAt, createdAt);
        token.customerUserId = Objects.requireNonNull(customerUserId, "customerUserId");
        return token;
    }

    /** A {@code REGISTER} token for an unknown e-mail, identified only by its HMAC, created now. */
    public static LoginToken forRegistration(String emailHash, String tokenHash, Instant expiresAt) {
        return forRegistration(emailHash, tokenHash, expiresAt, Instant.now());
    }

    /** A {@code REGISTER} token stamped with the application clock. */
    public static LoginToken forRegistration(String emailHash, String tokenHash, Instant expiresAt, Instant createdAt) {
        LoginToken token = new LoginToken(LoginTokenPurpose.REGISTER, tokenHash, expiresAt, createdAt);
        token.emailHash = Objects.requireNonNull(emailHash, "emailHash");
        return token;
    }

    private LoginToken(LoginTokenPurpose purpose, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerUserId() {
        return customerUserId;
    }

    public String getEmailHash() {
        return emailHash;
    }

    public LoginTokenPurpose getPurpose() {
        return purpose;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public String getRequestedIpHash() {
        return requestedIpHash;
    }

    public void setRequestedIpHash(String requestedIpHash) {
        this.requestedIpHash = requestedIpHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** True while the token can still be consumed. */
    public boolean isUsableAt(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    /** Marks the token used; the caller checks {@link #isUsableAt(Instant)} first. */
    public void markUsed(Instant now) {
        if (usedAt != null) {
            throw new IllegalStateException("token already used");
        }
        this.usedAt = Objects.requireNonNull(now, "now");
    }
}
