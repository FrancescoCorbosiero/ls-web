package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADM-01 (chapter 10 §4): staff passwords have at least 12 characters and are not in the bundled
 * common-password list, compared case-insensitively; anything else is accepted.
 */
@Tag("ADM-01")
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void theBundledListHoldsAFewThousandCommonPasswordsIncludingLongOnes() {
        assertThat(policy.commonPasswords()).isGreaterThan(10_000);
        assertThat(policy.check("passwordpassword")).contains(PasswordPolicy.Violation.COMMON);
        assertThat(policy.check("1234567890123")).contains(PasswordPolicy.Violation.COMMON);
        assertThat(policy.check("PasswordPassword")).as("case-insensitive").contains(PasswordPolicy.Violation.COMMON);
        assertThat(policy.check("  passwordpassword  ")).as("trimmed").contains(PasswordPolicy.Violation.COMMON);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "password", "12345678901", "qwerty12345", "           "})
    void shorterThanTwelveCharactersIsRefusedBeforeAnythingElse(String password) {
        assertThat(policy.check(password)).contains(PasswordPolicy.Violation.TOO_SHORT);
        assertThat(policy.isAcceptable(password)).isFalse();
    }

    @Test
    void nullIsTooShort() {
        assertThat(policy.check(null)).contains(PasswordPolicy.Violation.TOO_SHORT);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"correct-horse-battery-staple", "Seregno pallet espresso 2026", "ab12CD34ef56", "üaaaaaaaaaaa"})
    void twelveOrMoreCharactersNotInTheListAreAccepted(String password) {
        assertThat(policy.check(password)).isEmpty();
        assertThat(policy.isAcceptable(password)).isTrue();
    }

    @Test
    void violationsCarryTheMessageKeysOfTheBundle() {
        assertThat(PasswordPolicy.Violation.TOO_SHORT.messageKey()).isEqualTo("auth.password.tooShort");
        assertThat(PasswordPolicy.Violation.COMMON.messageKey()).isEqualTo("auth.password.common");
    }
}
