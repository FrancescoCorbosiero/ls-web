package it.logisticasanchez.web.customers;

/**
 * The validated, normalised fields of the registration form (OP-R3; Q-10 VAT number and tax code
 * optional): every value trimmed, the CAP five digits, the phone in E.164 form.
 */
public record RegistrationData(
        String companyName,
        String addressLine,
        String province,
        String city,
        String postalCode,
        String phone,
        String vatNumber,
        String taxCode) {}
