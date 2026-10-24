package it.logisticasanchez.web.orders;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CUS-04 totals (OP-R5; chapter 03 §6.3): the worked example (1236 kg, 4.53 m³), per-line rounding
 * (10×10×10 → 0,00; 50×40×30 + 20×20×25 → 0,07 with no float artefact), a decimal tie, Italian format.
 */
@Tag("CUS-04")
class OrderTotalsTest {

    @Test
    void theWorkedExampleOfChapter03() {
        OrderDraft draft = new OrderDraft();
        draft.addLine(LineKind.EPAL, 180, 120, 80, new BigDecimal("450"), 2);
        draft.addLine(LineKind.GENERIC, 100, 120, 80, new BigDecimal("300"), 1);
        draft.addLine(LineKind.GENERIC, 40, 30, 30, new BigDecimal("12"), 3);
        assertThat(draft.lines())
                .extracting(OrderDraft.Line::weightTotal)
                .containsExactly(new BigDecimal("900.00"), new BigDecimal("300.00"), new BigDecimal("36.00"));
        assertThat(draft.lines())
                .extracting(OrderDraft.Line::volumeTotal)
                .containsExactly(new BigDecimal("3.46"), new BigDecimal("0.96"), new BigDecimal("0.11"));
        assertThat(draft.totalWeightKg()).isEqualByComparingTo("1236");
        assertThat(draft.totalVolumeM3()).isEqualByComparingTo("4.53");
        assertThat(ItalianFormats.formatNumber(draft.totalWeightKg(), 2)).isEqualTo("1.236,00");
        assertThat(ItalianFormats.formatNumber(draft.totalVolumeM3(), 2)).isEqualTo("4,53");
    }

    @Test
    void volumeIsRoundedPerLineToTwoDecimals() {
        assertThat(OrderTotals.lineVolume(10, 10, 10, 1)).isEqualByComparingTo("0.00");
        assertThat(OrderTotals.lineVolume(10, 10, 10, 9)).isEqualByComparingTo("0.01");
        assertThat(OrderTotals.lineVolume(50, 40, 30, 1)).isEqualByComparingTo("0.06");
        assertThat(OrderTotals.lineVolume(20, 20, 25, 1)).isEqualByComparingTo("0.01");
        // a tie that binary floats cannot represent: 25×25×24 cm = 0.015 m³ → half-up 0.02 (chapter 08 §2.3)
        assertThat(OrderTotals.lineVolume(25, 25, 24, 1)).isEqualByComparingTo("0.02");
    }

    @Test
    void sumsHaveNoFloatArtefactsAndFollowDeletions() {
        OrderDraft draft = new OrderDraft();
        draft.addLine(LineKind.GENERIC, 50, 40, 30, new BigDecimal("10"), 1);
        draft.addLine(LineKind.GENERIC, 20, 20, 25, new BigDecimal("10"), 1);
        assertThat(ItalianFormats.formatNumber(draft.totalVolumeM3(), 2)).isEqualTo("0,07");
        long firstId = draft.lines().get(0).id();
        long secondId = draft.lines().get(1).id();
        assertThat(draft.removeLine(firstId)).isTrue();
        assertThat(draft.removeLine(secondId)).isTrue();
        assertThat(draft.lines()).isEmpty();
        assertThat(ItalianFormats.formatNumber(draft.totalVolumeM3(), 2)).isEqualTo("0,00");
        assertThat(ItalianFormats.formatNumber(draft.totalWeightKg(), 2)).isEqualTo("0,00");
        draft.addLine(LineKind.EPAL, 1, 1, 1, new BigDecimal("1"), 1);
        assertThat(draft.lines().get(0).id()).as("ids never reused (OP-05)").isEqualTo(secondId + 1);
    }

    @Test
    void weightIsUnitWeightTimesQuantity() {
        assertThat(OrderTotals.lineWeight(new BigDecimal("12.5"), 3)).isEqualByComparingTo("37.50");
        assertThat(OrderTotals.lineWeight(new BigDecimal("450"), 2)).isEqualByComparingTo("900.00");
    }

    @Test
    void temporaryCodesKeepTheLegacyMarkerAndAreRandom() {
        String a = OrderService.tempCode();
        String b = OrderService.tempCode();
        assertThat(a).matches("TEMP-[A-Z2-9]{8}");
        assertThat(a).isNotEqualTo(b);
    }
}
