package it.logisticasanchez.web.customers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/** A saved recipient of a customer's address book (table {@code customer_address}, V016; ENH-07, ADR 0033). */
@Entity
@Table(name = "customer_address")
public class CustomerAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "address_line", nullable = false, length = 200)
    private String addressLine;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "province", nullable = false, length = 60)
    private String province;

    @Column(name = "postal_code", nullable = false, length = 10)
    private String postalCode;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CustomerAddress() {}

    CustomerAddress(long customerId, AddressView.Fields fields, Instant now) {
        this.customerId = customerId;
        this.createdAt = Objects.requireNonNull(now, "now");
        replace(fields);
    }

    /** New values for every field (already validated by {@link AddressBookService}). */
    void replace(AddressView.Fields fields) {
        this.name = fields.name();
        this.addressLine = fields.addressLine();
        this.city = fields.city();
        this.province = fields.province();
        this.postalCode = fields.postalCode();
        this.phone = fields.phone();
        this.email = fields.email();
    }

    AddressView.Fields fields() {
        return new AddressView.Fields(name, addressLine, province, city, postalCode, phone, email);
    }

    public Long getId() {
        return id;
    }

    public long getCustomerId() {
        return customerId;
    }
}
