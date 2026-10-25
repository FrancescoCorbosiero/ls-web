package it.logisticasanchez.web.customers;

import java.time.Instant;

/** A customer as the other modules and the templates see it (never the entity). */
public record CustomerView(
        long id,
        String email,
        CustomerStatus status,
        String companyName,
        String addressLine,
        String province,
        String city,
        String postalCode,
        String phone,
        String vatNumber,
        String taxCode,
        Instant registeredAt,
        Instant emailVerifiedAt,
        Instant approvedAt,
        Instant declinedAt,
        String declineReason,
        Instant anonymisedAt,
        String source,
        int version) {

    static CustomerView of(Customer c) {
        return new CustomerView(
                c.getId(),
                c.getEmail(),
                c.getStatus(),
                c.getCompanyName(),
                c.getAddressLine(),
                c.getProvince(),
                c.getCity(),
                c.getPostalCode(),
                c.getPhone(),
                c.getVatNumber(),
                c.getTaxCode(),
                c.getRegisteredAt(),
                c.getEmailVerifiedAt(),
                c.getApprovedAt(),
                c.getDeclinedAt(),
                c.getDeclineReason(),
                c.getAnonymisedAt(),
                c.getSource(),
                c.getVersion());
    }

    /** ADM-12: created by staff in the back office. */
    public boolean createdByStaff() {
        return Customer.SOURCE_BACKOFFICE.equals(source);
    }

    public boolean emailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean anonymised() {
        return anonymisedAt != null;
    }

    public boolean canOrder() {
        return status.canOrder();
    }

    /** «Via, CAP Città (Provincia)». */
    public String addressOneLine() {
        return addressLine + ", " + postalCode + " " + city + " (" + province + ")";
    }
}
