package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.platform.web.error.RuleViolationException;
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
 * A change of company name, VAT number or tax code asked by the customer from its profile (table
 * {@code customer_change_request}, V019; ENH-16, ADR 0038): PENDING until the office approves or
 * declines it, or the customer takes it back. Each closing move refuses a request that is no
 * longer pending.
 */
@Entity
@Table(name = "customer_change_request")
public class CustomerChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 10)
    private CustomerChangeStatus status;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "vat_number", length = 16)
    private String vatNumber;

    @Column(name = "tax_code", length = 16)
    private String taxCode;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    protected CustomerChangeRequest() {}

    CustomerChangeRequest(long customerId, CustomerIdentity requested, Instant now) {
        this.customerId = customerId;
        this.status = CustomerChangeStatus.PENDING;
        replace(requested, now);
    }

    /** A new save of the profile while the request waits: the values asked for now. */
    void replace(CustomerIdentity requested, Instant now) {
        requirePending();
        this.companyName = Objects.requireNonNull(requested.companyName(), "companyName");
        this.vatNumber = requested.vatNumber();
        this.taxCode = requested.taxCode();
        this.requestedAt = Objects.requireNonNull(now, "now");
    }

    void approve(long staffUserId, Instant at) {
        requirePending();
        status = CustomerChangeStatus.APPROVED;
        closedAt = at;
        decidedBy = staffUserId;
    }

    void decline(long staffUserId, Instant at, String reason) {
        requirePending();
        status = CustomerChangeStatus.DECLINED;
        closedAt = at;
        decidedBy = staffUserId;
        declineReason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    void withdraw(Instant at) {
        requirePending();
        status = CustomerChangeStatus.WITHDRAWN;
        closedAt = at;
    }

    private void requirePending() {
        if (status != CustomerChangeStatus.PENDING) {
            throw new RuleViolationException("La richiesta di modifica non è più in attesa.");
        }
    }

    CustomerIdentity requested() {
        return new CustomerIdentity(companyName, vatNumber, taxCode);
    }

    public Long getId() {
        return id;
    }

    public long getCustomerId() {
        return customerId;
    }

    public CustomerChangeStatus getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getDeclineReason() {
        return declineReason;
    }
}
