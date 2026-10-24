package it.logisticasanchez.web.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** CUS-02 / CUS-04 (chapter 03 §10 OP-09…OP-11 fixes): CAP, phone, e-mail, VAT, decimal and date formats. */
@Tag("CUS-02")
@Tag("CUS-04")
class ItalianFormatsTest {

    @ParameterizedTest
    @ValueSource(strings = {"20831", "00100"})
    void aCapIsExactlyFiveDigits(String cap) {
        assertThat(ItalianFormats.isCap(cap)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20831-1234", " 20851", "2083", "208311", "2083a", ""})
    void anythingElseIsNotACap(String cap) {
        assertThat(ItalianFormats.isCap(cap)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "3292396962, +393292396962",
        "'+39 329 239 6962', +393292396962",
        "'0362 123456', +390362123456",
        "'02 12345678', +390212345678",
        "0362123456, +390362123456",
        "'+39 0362 123456', +390362123456",
        "'0039 329 239 6962', +393292396962",
        "'+1 (555) 123-4567', +15551234567",
        "'  329-239.6962 ', +393292396962"
    })
    void italianAndInternationalPhonesAreNormalisedToE164(String typed, String expected) {
        assertThat(ItalianFormats.normalisePhone(typed)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "telefono", "12345", "+39 329 abc 6962", "+39+329239696", "12345678901234567"})
    void aValueThatIsNotAPhoneIsRefused(String typed) {
        assertThat(ItalianFormats.normalisePhone(typed)).isEmpty();
    }

    @Test
    void emailsAreTrimmedAndLowerCased() {
        assertThat(ItalianFormats.normaliseEmail("  Mario@Azienda.example ")).isEqualTo("mario@azienda.example");
        assertThat(ItalianFormats.isEmail("mario@azienda.example")).isTrue();
        assertThat(ItalianFormats.isEmail("mario@azienda")).isFalse();
        assertThat(ItalianFormats.isEmail("mario azienda@example.com")).isFalse();
    }

    @Test
    void vatNumbersAndTaxCodes() {
        assertThat(ItalianFormats.isVatNumber("13113080967")).isTrue();
        assertThat(ItalianFormats.isVatNumber("1311308096")).isFalse();
        assertThat(ItalianFormats.isTaxCode("RSSMRA80A01F205X")).isTrue();
        assertThat(ItalianFormats.isTaxCode("rssmra80a01f205x")).isTrue();
        assertThat(ItalianFormats.isTaxCode("13113080967")).isTrue();
        assertThat(ItalianFormats.isTaxCode("RSSMRA80A01")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "'180,50', 180.50",
        "180.50, 180.50",
        "180, 180",
        "'1.234,56', 1234.56",
        "'1,234.56', 1234.56",
        "'€ 180,5', 180.5",
        "' 42 ', 42"
    })
    void decimalsAcceptTheItalianCommaAndThePoint(String typed, String expected) {
        assertThat(ItalianFormats.parseDecimal(typed)).contains(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "12,5,3", "12..5", "-"})
    void aValueThatIsNotADecimalIsRefused(String typed) {
        assertThat(ItalianFormats.parseDecimal(typed)).isEmpty();
    }

    @Test
    void positiveIntegersOnly() {
        assertThat(ItalianFormats.parsePositiveInteger("12")).contains(12);
        assertThat(ItalianFormats.parsePositiveInteger("007")).contains(7);
        assertThat(ItalianFormats.parsePositiveInteger("0")).isEmpty();
        assertThat(ItalianFormats.parsePositiveInteger("12,5")).isEmpty();
        assertThat(ItalianFormats.parsePositiveInteger("-3")).isEmpty();
        assertThat(ItalianFormats.parsePositiveInteger("")).isEmpty();
    }

    @Test
    void italianRendering() {
        assertThat(ItalianFormats.formatNumber(new BigDecimal("1236"), 2)).isEqualTo("1.236,00");
        assertThat(ItalianFormats.formatNumber(new BigDecimal("4.53"), 2)).isEqualTo("4,53");
        assertThat(ItalianFormats.formatNumber(new BigDecimal("450.00"), 0)).isEqualTo("450");
        assertThat(ItalianFormats.formatEuro(new BigDecimal("180.5"))).isEqualTo("180,50 €");
        assertThat(ItalianFormats.formatEuro(null)).isEqualTo("-");
        assertThat(ItalianFormats.formatDateTime(Instant.parse("2026-07-01T10:30:00Z")))
                .isEqualTo("01/07/2026 12:30");
        assertThat(ItalianFormats.formatDate(Instant.parse("2026-01-15T23:30:00Z")))
                .isEqualTo("16/01/2026");
    }
}
