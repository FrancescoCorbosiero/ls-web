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
 * A one-time set-password link of a staff account (table {@code staff_setup_token}, ADM-01,
 * chapter 08 §3.9, ADR 0003): SHA-256 of the token, 24 hours, consumed once by the {@code POST}
 * of the set-password page.
 */
@Entity
@Table(name = "staff_setup_token")
public class StaffSetupToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_user_id", nullable = false)
    private Long staffUserId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StaffSetupToken() {}

    public StaffSetupToken(Long staffUserId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.staffUserId = Objects.requireNonNull(staffUserId, "staffUserId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public Long getId() {
        return id;
    }

    public Long getStaffUserId() {
        return staffUserId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isUsableAt(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        if (usedAt != null) {
            throw new IllegalStateException("token already used");
        }
        this.usedAt = Objects.requireNonNull(now, "now");
    }
}
