package it.logisticasanchez.web.platform.flags;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An M2 feature flag (table {@code feature_flag}, chapter 10 §2; ADR 0025). One row per key of
 * chapter 09 §M2, seeded off by the migrations; ADMIN users toggle it in the back office.
 */
@Entity
@Table(name = "feature_flag")
public class FeatureFlag {

    @Id
    @Column(name = "key", nullable = false, length = 60)
    private String key;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "requirement_id", nullable = false, length = 8)
    private String requirementId;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected FeatureFlag() {}

    public String getKey() {
        return key;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getRequirementId() {
        return requirementId;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Toggles the flag, recording who did it (an audit entry is written by the service). */
    public void setEnabled(boolean enabled, Long staffUserId, Instant now) {
        this.enabled = enabled;
        this.updatedBy = staffUserId;
        this.updatedAt = now;
    }
}
