package it.logisticasanchez.web.reports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A report requested by staff (table {@code report_run}, V007; ADM-09): one customer, an
 * inclusive date range in Europe/Rome days, the filter, who received the e-mail and how many
 * orders the report held. The PDF is not stored: the download regenerates it (ADR 0016).
 */
@Entity
@Table(name = "report_run")
public class ReportRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private long requestedBy;

    @Column(name = "date_from", nullable = false, updatable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false, updatable = false)
    private LocalDate dateTo;

    @Column(name = "only_delivered", nullable = false, updatable = false)
    private boolean onlyDelivered;

    @Column(name = "send_to_customer", nullable = false, updatable = false)
    private boolean sendToCustomer;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recipients", nullable = false, columnDefinition = "text[]")
    private String[] recipients;

    @Column(name = "row_count", nullable = false, updatable = false)
    private int rowCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReportRun() {}

    ReportRun(
            long customerId,
            long requestedBy,
            LocalDate dateFrom,
            LocalDate dateTo,
            boolean onlyDelivered,
            boolean sendToCustomer,
            List<String> recipients,
            int rowCount,
            Instant now) {
        this.customerId = customerId;
        this.requestedBy = requestedBy;
        this.dateFrom = Objects.requireNonNull(dateFrom, "dateFrom");
        this.dateTo = Objects.requireNonNull(dateTo, "dateTo");
        this.onlyDelivered = onlyDelivered;
        this.sendToCustomer = sendToCustomer;
        this.recipients = recipients.toArray(String[]::new);
        this.rowCount = rowCount;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    /** Erasure (NFR-03): the erased address replaced in the recipient list. */
    void replaceRecipient(String email, String placeholder) {
        for (int i = 0; i < recipients.length; i++) {
            if (recipients[i].equalsIgnoreCase(email)) {
                recipients[i] = placeholder;
            }
        }
    }

    public Long getId() {
        return id;
    }

    public long getCustomerId() {
        return customerId;
    }

    public long getRequestedBy() {
        return requestedBy;
    }

    public LocalDate getDateFrom() {
        return dateFrom;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }

    public boolean isOnlyDelivered() {
        return onlyDelivered;
    }

    public boolean isSendToCustomer() {
        return sendToCustomer;
    }

    public List<String> getRecipients() {
        return List.of(recipients);
    }

    public int getRowCount() {
        return rowCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
