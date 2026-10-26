package it.logisticasanchez.web.customers;

/**
 * A saved recipient as the other modules and the templates see it (ENH-07): the recipient fields
 * of an order (OP-R7), phone and e-mail null when empty.
 */
public record AddressView(long id, Fields fields) {

    public String name() {
        return fields.name();
    }

    public String addressLine() {
        return fields.addressLine();
    }

    public String province() {
        return fields.province();
    }

    public String city() {
        return fields.city();
    }

    public String postalCode() {
        return fields.postalCode();
    }

    public String phone() {
        return fields.phone();
    }

    public String email() {
        return fields.email();
    }

    /** «Nome – Città (Provincia)»: the choice in the order form's list. */
    public String label() {
        return fields.name() + " – " + fields.city() + " (" + fields.province() + ")";
    }

    /** «Via, CAP Città (Provincia)». */
    public String addressOneLine() {
        return fields.addressLine() + ", " + fields.postalCode() + " " + fields.city() + " (" + fields.province() + ")";
    }

    /** The values of an entry, trimmed and validated. */
    public record Fields(
            String name,
            String addressLine,
            String province,
            String city,
            String postalCode,
            String phone,
            String email) {}
}
