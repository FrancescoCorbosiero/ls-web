package it.logisticasanchez.web.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The legacy totals of the order wizard (OP-R5; chapter 03 §6.3), in decimal arithmetic (OP-06
 * fix: no float artefacts): line weight = peso × quantità; line volume =
 * round₂(altezza × lunghezza × larghezza / 1 000 000 × quantità), rounded half-up **per line**
 * (a 10×10×10 cm box counts 0,00); the order totals are the sums of the lines.
 */
public final class OrderTotals {

    private static final BigDecimal CUBIC_CM_PER_M3 = new BigDecimal("1000000");

    private OrderTotals() {}

    public static BigDecimal lineWeight(BigDecimal unitWeightKg, int quantity) {
        return unitWeightKg.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal lineVolume(int heightCm, int lengthCm, int widthCm, int quantity) {
        BigDecimal cubicCm = BigDecimal.valueOf(heightCm)
                .multiply(BigDecimal.valueOf(lengthCm))
                .multiply(BigDecimal.valueOf(widthCm))
                .multiply(BigDecimal.valueOf(quantity));
        return cubicCm.divide(CUBIC_CM_PER_M3, 2, RoundingMode.HALF_UP);
    }
}
