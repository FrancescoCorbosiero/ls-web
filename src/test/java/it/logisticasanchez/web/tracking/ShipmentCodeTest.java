package it.logisticasanchez.web.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** TRK-02: code normalisation — trimmed, every space removed, case rule as the Q-03 seam says. */
@Tag("TRK-02")
class ShipmentCodeTest {

    @Test
    void spacesAreRemovedAndCaseFoldedByDefault() {
        assertThat(ShipmentCode.normalise(" ABC123 ", true)).isEqualTo("abc123");
        assertThat(ShipmentCode.normalise("abc123", true)).isEqualTo("abc123");
        assertThat(ShipmentCode.normalise("A B C 123", true)).isEqualTo("abc123");
        assertThat(ShipmentCode.normalise("\tLS-2024-0118\n", true)).isEqualTo("ls-2024-0118");
    }

    @Test
    void theCaseSensitiveSeamKeepsTheCase() {
        assertThat(ShipmentCode.normalise("  ls-2024-0118 ", false)).isEqualTo("ls-2024-0118");
        assertThat(ShipmentCode.normalise("A B C", false)).isEqualTo("ABC");
    }

    @Test
    void nothingLeftMeansNoLookup() {
        assertThat(ShipmentCode.normalise("", true)).isNull();
        assertThat(ShipmentCode.normalise("   ", true)).isNull();
        assertThat(ShipmentCode.normalise(null, true)).isNull();
    }
}
