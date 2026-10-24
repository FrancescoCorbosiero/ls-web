package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.platform.config.CustomersProperties;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Server-side rules of the registration form (CUS-02; chapter 03 §5.3 corrected by §10
 * OP-08…OP-11 and Q-36): every field trimmed; company or personal name, street, province and
 * city required within the column lengths; CAP exactly five digits; phone required
 * ({@code ls.customers.registration.phone-required}, Q-36) and normalised to E.164; VAT number
 * (11 digits) and tax code (16 characters, or 11 digits) optional (Q-10). Errors are message
 * keys per field, in the legacy check order, so the page shows every problem at once.
 */
@Component
public class RegistrationValidator {

    static final int NAME_MAX = 200;
    static final int ADDRESS_MAX = 200;
    static final int CITY_MAX = 100;
    static final int PROVINCE_MAX = 60;

    private final CustomersProperties.Registration rules;

    public RegistrationValidator(CustomersProperties customers) {
        this.rules = customers.registration();
    }

    /** The validated data, or the field → message-key errors. */
    public Result validate(Map<String, String> input) {
        Map<String, String> errors = new LinkedHashMap<>();
        String companyName = required(input, "companyName", NAME_MAX, errors);
        String addressLine = required(input, "addressLine", ADDRESS_MAX, errors);
        String province = required(input, "province", PROVINCE_MAX, errors);
        String city = required(input, "city", CITY_MAX, errors);
        String postalCode = ItalianFormats.trim(input.get("postalCode"));
        if (postalCode.isEmpty()) {
            errors.put("postalCode", "customers.error.required");
        } else if (!ItalianFormats.isCap(postalCode)) {
            errors.put("postalCode", "customers.error.cap");
        }
        String phone = null;
        String rawPhone = ItalianFormats.trim(input.get("phone"));
        if (rawPhone.isEmpty()) {
            if (rules.phoneRequired()) { // Q-36
                errors.put("phone", "customers.error.required");
            }
        } else {
            Optional<String> normalised = ItalianFormats.normalisePhone(rawPhone);
            if (normalised.isEmpty()) {
                errors.put("phone", "customers.error.phone");
            } else {
                phone = normalised.get();
            }
        }
        String vatNumber =
                ItalianFormats.trim(input.get("vatNumber")).replace(" ", "").toUpperCase(Locale.ROOT);
        if (vatNumber.startsWith("IT")) {
            vatNumber = vatNumber.substring(2);
        }
        if (vatNumber.isEmpty()) {
            vatNumber = null;
            if (rules.vatNumberRequired()) { // Q-10
                errors.put("vatNumber", "customers.error.required");
            }
        } else if (!ItalianFormats.isVatNumber(vatNumber)) {
            errors.put("vatNumber", "customers.error.vatNumber");
        }
        String taxCode =
                ItalianFormats.trim(input.get("taxCode")).replace(" ", "").toUpperCase(Locale.ROOT);
        if (taxCode.isEmpty()) {
            taxCode = null;
            if (rules.taxCodeRequired()) { // Q-10
                errors.put("taxCode", "customers.error.required");
            }
        } else if (!ItalianFormats.isTaxCode(taxCode)) {
            errors.put("taxCode", "customers.error.taxCode");
        }
        if (!errors.isEmpty()) {
            return new Result(null, errors);
        }
        return new Result(
                new RegistrationData(companyName, addressLine, province, city, postalCode, phone, vatNumber, taxCode),
                errors);
    }

    private static String required(Map<String, String> input, String field, int max, Map<String, String> errors) {
        String value = ItalianFormats.trim(input.get(field));
        if (value.isEmpty()) {
            errors.put(field, "customers.error.required");
        } else if (value.length() > max) {
            errors.put(field, "customers.error.tooLong");
        }
        return value;
    }

    /** {@code data} is null when {@code errors} is not empty. */
    public record Result(RegistrationData data, Map<String, String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }
}
