package it.logisticasanchez.web.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * A device on which a customer user stays signed in (table {@code remembered_sign_in}, ADR
 * 0031): the cookie carries a random token, the row only its SHA-256. The lifetime counts from
 * the sign-in and is not extended by use.
 */
@Entity
@Table(name = "remembered_sign_in")
public class RememberedSignIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_user_id", nullable = false, updatable = false)
    private Long customerUserId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RememberedSignIn() {}

    public RememberedSignIn(long customerUserId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.customerUserId = customerUserId;
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public Long getId() {
        return id;
    }

    public long getCustomerUserId() {
        return customerUserId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public boolean isValidAt(Instant now) {
        return expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        this.lastUsedAt = Objects.requireNonNull(now, "now");
    }
}
