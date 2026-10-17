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
import java.util.Locale;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A customer's login identity for the magic-link sign-in (table {@code customer_user}, chapter 08
 * §2.3; CUS-01). The customer itself belongs to the {@code customers} module: only its id is held
 * here (modules never share entities or repositories, chapter 10 §2).
 */
@Entity
@Table(name = "customer_user")
public class CustomerUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 16)
    private CustomerUserRole role = CustomerUserRole.OWNER;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CustomerUser() {}

    public CustomerUser(Long customerId, String email, CustomerUserRole role) {
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.email = normaliseEmail(email);
        this.role = Objects.requireNonNull(role, "role");
    }

    /** E-mails are stored trimmed and lower-case (chapter 08 §2.1 principle 7; database CHECK). */
    public static String normaliseEmail(String email) {
        return Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = normaliseEmail(email);
    }

    public CustomerUserRole getRole() {
        return role;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public void setDisabledAt(Instant disabledAt) {
        this.disabledAt = disabledAt;
    }

    public boolean isDisabled() {
        return disabledAt != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
