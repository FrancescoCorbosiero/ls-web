package it.logisticasanchez.web.customers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who the customer is on its documents (ENH-16, ADR 0038): the company or personal name, the VAT
 * number and the tax code, null when empty. The customer changes them only with the office's
 * approval; staff correct them directly (ADM-12).
 */
public record CustomerIdentity(String companyName, String vatNumber, String taxCode) {

    static CustomerIdentity of(RegistrationData data) {
        return new CustomerIdentity(data.companyName(), data.vatNumber(), data.taxCode());
    }

    static CustomerIdentity of(Customer customer) {
        return new CustomerIdentity(customer.getCompanyName(), customer.getVatNumber(), customer.getTaxCode());
    }

    /** The values for the audit details and the export, «-» for an empty one. */
    Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("companyName", companyName);
        values.put("vatNumber", vatNumber == null ? "-" : vatNumber);
        values.put("taxCode", taxCode == null ? "-" : taxCode);
        return values;
    }
}
