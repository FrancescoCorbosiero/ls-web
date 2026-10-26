package it.logisticasanchez.web.customers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ENH-07 (ADR 0033): an address-book entry follows the recipient rules of the order form (OP-R7). */
@Tag("ENH-07")
class AddressBookServiceTest {

    @Test
    void anEntryIsTrimmedAndNormalisedLikeAnOrdersRecipient() {
        Map<String, String> errors = new LinkedHashMap<>();
        AddressView.Fields fields = AddressBookService.validate(
                Map.of(
                        "name", "  Deposito Nord  ",
                        "addressLine", " Via Milano 1 ",
                        "province", "MB",
                        "city", "Seregno",
                        "postalCode", " 20831 ",
                        "phone", "0362 123456",
                        "email", " Merci@Deposito.example "),
                errors);
        assertThat(errors).isEmpty();
        assertThat(fields)
                .isEqualTo(new AddressView.Fields(
                        "Deposito Nord",
                        "Via Milano 1",
                        "MB",
                        "Seregno",
                        "20831",
                        "+390362123456",
                        "merci@deposito.example"));
    }

    @Test
    void theRequiredFieldsTheCapAndTheOptionalContactsAreChecked() {
        Map<String, String> errors = new LinkedHashMap<>();
        AddressBookService.validate(
                Map.of(
                        "name", "x".repeat(201),
                        "addressLine", "",
                        "province", "MI",
                        "city", "Milano",
                        "postalCode", "2012",
                        "phone", "chiamami",
                        "email", "non-un-indirizzo"),
                errors);
        assertThat(errors)
                .containsEntry("name", "orders.error.tooLong")
                .containsEntry("addressLine", "orders.error.required")
                .containsEntry("postalCode", "orders.error.cap")
                .containsEntry("phone", "orders.error.phone")
                .containsEntry("email", "orders.error.email")
                .doesNotContainKeys("province", "city");
        Map<String, String> optional = new LinkedHashMap<>();
        AddressView.Fields fields = AddressBookService.validate(
                Map.of(
                        "name",
                        "Ritiro",
                        "addressLine",
                        "Via Po 2",
                        "province",
                        "TO",
                        "city",
                        "Torino",
                        "postalCode",
                        "10121"),
                optional);
        assertThat(optional).isEmpty();
        assertThat(fields.phone()).isNull();
        assertThat(fields.email()).isNull();
    }
}
