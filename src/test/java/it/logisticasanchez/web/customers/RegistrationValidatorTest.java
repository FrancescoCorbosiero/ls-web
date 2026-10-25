package it.logisticasanchez.web.customers;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.platform.config.CustomersProperties;
import it.logisticasanchez.web.support.Settings;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CUS-02 validators (chapter 03 §5.3 corrected by §10 OP-08…OP-11, Q-10, Q-36): every field trimmed,
 * CAP five digits, phone required and normalised, VAT and tax code optional, lengths, every error at once.
 */
@Tag("CUS-02")
class RegistrationValidatorTest {

    private final RegistrationValidator validator =
            new RegistrationValidator(Settings.defaults("ls.customers", CustomersProperties.class));

    private static Map<String, String> valid() {
        Map<String, String> m = new HashMap<>();
        m.put("companyName", "  Ditta Esempio S.r.l. ");
        m.put("addressLine", "Via di Prova 1");
        m.put("province", "MB");
        m.put("city", "Seregno");
        m.put("postalCode", "20831");
        m.put("phone", "0362 123456");
        m.put("vatNumber", " IT 13113080967 ");
        m.put("taxCode", "");
        return m;
    }

    @Test
    void aValidFormIsTrimmedAndNormalised() {
        RegistrationValidator.Result result = validator.validate(valid());
        assertThat(result.valid()).isTrue();
        assertThat(result.data())
                .isEqualTo(new RegistrationData(
                        "Ditta Esempio S.r.l.",
                        "Via di Prova 1",
                        "MB",
                        "Seregno",
                        "20831",
                        "+390362123456",
                        "13113080967",
                        null));
    }

    @Test
    void everyMissingFieldIsReportedAtOnce() {
        Map<String, String> m = valid();
        m.put("companyName", "   ");
        m.put("addressLine", "");
        m.put("province", null);
        m.put("city", "");
        m.put("postalCode", "");
        m.put("phone", "");
        RegistrationValidator.Result result = validator.validate(m);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors().keySet())
                .containsExactly("companyName", "addressLine", "province", "city", "postalCode", "phone");
        assertThat(result.errors().values()).containsOnly("customers.error.required");
    }

    @Test
    void capMustBeFiveDigitsAndPhoneMustBeAPhone() {
        Map<String, String> m = valid();
        m.put("postalCode", "20831-1234");
        m.put("phone", "telefono");
        RegistrationValidator.Result result = validator.validate(m);
        assertThat(result.errors())
                .containsEntry("postalCode", "customers.error.cap")
                .containsEntry("phone", "customers.error.phone")
                .hasSize(2);
    }

    @Test
    void phoneIsRequiredByDefaultAndOptionalWhenTheSettingSaysSo() {
        Map<String, String> m = valid();
        m.put("phone", "");
        assertThat(validator.validate(m).errors()).containsEntry("phone", "customers.error.required");
        RegistrationValidator lenient = new RegistrationValidator(Settings.with(
                "ls.customers",
                CustomersProperties.class,
                Map.of("ls.customers.registration.phone-required", "false")));
        RegistrationValidator.Result result = lenient.validate(m);
        assertThat(result.valid()).isTrue();
        assertThat(result.data().phone()).isNull();
    }

    @Test
    void vatNumberAndTaxCodeAreOptionalButCheckedWhenGiven() {
        Map<String, String> m = valid();
        m.put("vatNumber", "");
        m.put("taxCode", "rssmra80a01f205x");
        RegistrationValidator.Result result = validator.validate(m);
        assertThat(result.valid()).isTrue();
        assertThat(result.data().vatNumber()).isNull();
        assertThat(result.data().taxCode()).isEqualTo("RSSMRA80A01F205X");
        m.put("vatNumber", "1234");
        m.put("taxCode", "ABC");
        assertThat(validator.validate(m).errors())
                .containsEntry("vatNumber", "customers.error.vatNumber")
                .containsEntry("taxCode", "customers.error.taxCode");
    }

    @Test
    void overLongValuesAreRefused() {
        Map<String, String> m = valid();
        m.put("companyName", "x".repeat(201));
        m.put("city", "y".repeat(101));
        assertThat(validator.validate(m).errors())
                .containsEntry("companyName", "customers.error.tooLong")
                .containsEntry("city", "customers.error.tooLong");
    }
}
