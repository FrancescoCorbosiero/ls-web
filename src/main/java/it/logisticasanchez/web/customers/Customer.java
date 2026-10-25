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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A registered sender (table {@code customer}, V003; chapter 08 §2.3): the "Ragione
 * Sociale/Nominativo" and address of the legacy portal, the login e-mail (mirrored in
 * {@code customer_user} by the auth module) and the state machine of chapter 08 §2.4, whose
 * transitions are the methods below; each refuses an illegal move with a
 * {@link RuleViolationException}.
 */
@Entity
@Table(name = "customer")
public class Customer {

    static final String SOURCE_CUSTOMER_AREA = "CUSTOMER_AREA";
    static final String SOURCE_BACKOFFICE = "BACKOFFICE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "address_line", nullable = false, length = 200)
    private String addressLine;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "province", nullable = false, length = 60)
    private String province;

    @Column(name = "postal_code", nullable = false, length = 10)
    private String postalCode;

    @Column(name = "phone", nullable = false, length = 30)
    private String phone;

    @Column(name = "vat_number", length = 16)
    private String vatNumber;

    @Column(name = "tax_code", length = 16)
    private String taxCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 24)
    private CustomerStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "declined_by")
    private Long declinedBy;

    @Column(name = "decline_reason")
    private String declineReason;

    @Column(name = "anonymised_at")
    private Instant anonymisedAt;

    @Column(name = "source", nullable = false, length = 16, updatable = false)
    private String source = SOURCE_CUSTOMER_AREA;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    protected Customer() {}

    /** A customer registering from the customer area; verified at once when {@code verifiedAt} is given (REGISTER link). */
    public Customer(String email, RegistrationData data, Instant now, Instant verifiedAt) {
        this.email = Objects.requireNonNull(email, "email");
        this.registeredAt = Objects.requireNonNull(now, "now");
        this.createdAt = now;
        this.emailVerifiedAt = verifiedAt;
        this.status = verifiedAt == null ? CustomerStatus.PENDING_VERIFICATION : CustomerStatus.PENDING_APPROVAL;
        apply(data);
    }

    /**
     * A customer created by staff in the back office (ADM-12, ADR 0028): ACTIVE at once, approved by
     * that staff member, source BACKOFFICE; the e-mail is verified by its first sign-in link (V013).
     */
    static Customer createdByStaff(String email, RegistrationData data, Instant now, long staffUserId) {
        Customer customer = new Customer();
        customer.email = Objects.requireNonNull(email, "email");
        customer.registeredAt = Objects.requireNonNull(now, "now");
        customer.createdAt = now;
        customer.status = CustomerStatus.ACTIVE;
        customer.approvedAt = now;
        customer.approvedBy = staffUserId;
        customer.source = SOURCE_BACKOFFICE;
        customer.apply(data);
        return customer;
    }

    /** Copies the registration fields (used at registration and at a declined customer's re-registration). */
    void apply(RegistrationData data) {
        this.companyName = data.companyName();
        this.addressLine = data.addressLine();
        this.city = data.city();
        this.province = data.province();
        this.postalCode = data.postalCode();
        this.phone = data.phone();
        this.vatNumber = data.vatNumber();
        this.taxCode = data.taxCode();
    }

    /** "Sign-in or verification link used" (chapter 08 §2.4): verifies the e-mail; returns true when the state moved on. */
    boolean verifyEmail(Instant at) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = at;
        }
        if (status == CustomerStatus.PENDING_VERIFICATION) {
            status = CustomerStatus.PENDING_APPROVAL;
            return true;
        }
        return false;
    }

    /** Staff correct the registration data (ADM-12); never the login e-mail (CUS-06) nor an anonymised row. */
    void correct(RegistrationData data) {
        if (anonymisedAt != null) {
            throw new RuleViolationException("I dati di un cliente anonimizzato non si modificano.");
        }
        apply(data);
    }

    /** Staff approve: only a verified customer waiting for approval (Q-28). */
    void approve(long staffUserId, Instant at) {
        if (status != CustomerStatus.PENDING_APPROVAL) {
            throw new RuleViolationException("Il cliente non è in attesa di approvazione.");
        }
        if (emailVerifiedAt == null) {
            throw new RuleViolationException("L'e-mail del cliente non è ancora verificata.");
        }
        status = CustomerStatus.ACTIVE;
        approvedAt = at;
        approvedBy = staffUserId;
        declinedAt = null;
        declinedBy = null;
        declineReason = null;
    }

    /** Staff decline: from either pending state. */
    void decline(long staffUserId, Instant at, String reason) {
        if (!status.isPending()) {
            throw new RuleViolationException("Solo un cliente in attesa può essere rifiutato.");
        }
        status = CustomerStatus.DECLINED;
        declinedAt = at;
        declinedBy = staffUserId;
        declineReason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    /** A declined customer registers again (Q-09): new data, back to pending (approval, since the sign-in proved the mailbox). */
    void registerAgain(RegistrationData data, Instant at) {
        if (status != CustomerStatus.DECLINED) {
            throw new RuleViolationException("Solo un cliente rifiutato può registrarsi di nuovo.");
        }
        apply(data);
        registeredAt = at;
        declinedAt = null;
        declinedBy = null;
        declineReason = null;
        status = emailVerifiedAt == null ? CustomerStatus.PENDING_VERIFICATION : CustomerStatus.PENDING_APPROVAL;
    }

    /**
     * Erasure (NFR-03, ADR 0024): every personal value replaced by a placeholder, the row marked
     * and closed as DECLINED so that nothing can be ordered or approved again; the orders keep
     * their own snapshots. Idempotent.
     */
    void anonymise(String placeholderEmail, Instant at) {
        this.email = Objects.requireNonNull(placeholderEmail, "placeholderEmail");
        this.companyName = "Cliente anonimizzato";
        this.addressLine = "-";
        this.city = "-";
        this.province = "-";
        this.postalCode = "00000";
        this.phone = "-";
        this.vatNumber = null;
        this.taxCode = null;
        this.declineReason = null;
        if (status != CustomerStatus.DECLINED) {
            this.status = CustomerStatus.DECLINED;
            this.declinedAt = at;
            this.declinedBy = null;
        }
        if (anonymisedAt == null) {
            this.anonymisedAt = at;
        }
    }

    void changeEmail(String newEmail) {
        this.email = Objects.requireNonNull(newEmail, "newEmail");
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public String getCity() {
        return city;
    }

    public String getProvince() {
        return province;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getPhone() {
        return phone;
    }

    public String getVatNumber() {
        return vatNumber;
    }

    public String getTaxCode() {
        return taxCode;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public Instant getDeclinedAt() {
        return declinedAt;
    }

    public Long getDeclinedBy() {
        return declinedBy;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public Instant getAnonymisedAt() {
        return anonymisedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getSource() {
        return source;
    }

    public int getVersion() {
        return version;
    }
}
