package it.logisticasanchez.web.reports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/** An allow-listed extra recipient of report e-mails (table {@code report_recipient}, V012; ADM-09, Q-08). */
@Entity
@Table(name = "report_recipient")
public class ReportRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 254, updatable = false)
    private String email;

    @Column(name = "label", length = 100)
    private String label;

    @Column(name = "added_by", nullable = false, updatable = false)
    private long addedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReportRecipient() {}

    ReportRecipient(String email, String label, long addedBy, Instant now) {
        this.email = Objects.requireNonNull(email, "email");
        this.label = label;
        this.addedBy = addedBy;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getLabel() {
        return label;
    }

    public long getAddedBy() {
        return addedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
