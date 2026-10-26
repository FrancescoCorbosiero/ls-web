package it.logisticasanchez.web.quotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ENH-20 (ADR 0037): the link of a saved quote is unguessable and only its SHA-256 is kept. */
@Tag("ENH-20")
class QuoteTokensTest {

    @Test
    void aTokenIs256RandomBitsInUrlSafeBase64() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = QuoteTokens.newToken();
            assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
            assertThat(QuoteTokens.wellFormed(token)).isTrue();
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    void onlyTheHashIsStoredAndOnlyWellFormedTokensAreLookedUp() {
        String token = QuoteTokens.newToken();
        assertThat(QuoteTokens.hash(token)).matches("^[0-9a-f]{64}$").isNotEqualTo(token);
        assertThat(QuoteTokens.hash(token)).isEqualTo(QuoteTokens.hash(token));
        assertThat(QuoteTokens.wellFormed(null)).isFalse();
        assertThat(QuoteTokens.wellFormed("abc")).isFalse();
        assertThat(QuoteTokens.wellFormed(token + "x")).isFalse();
        assertThat(QuoteTokens.wellFormed(token.substring(1) + "/")).isFalse();
    }

    @Test
    void theReferenceIsPrvAndTheNumber() {
        assertThat(QuoteView.code(7)).isEqualTo("PRV-00007");
        assertThat(QuoteView.code(123456)).isEqualTo("PRV-123456");
    }
}
