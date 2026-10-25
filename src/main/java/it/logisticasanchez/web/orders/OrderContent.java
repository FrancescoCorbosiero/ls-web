package it.logisticasanchez.web.orders;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * The validated content of an order — lines, shipment data and recipient — as the order form
 * produces it ({@link OrderForm}) for a new order (CUS-04, ADM-11) or an edit (CUS-07, ADM-11):
 * every value trimmed and normalised by {@link OrderValidator}, the package count a number, the
 * accessory service the stored id (null for «Nessun servizio accessorio»), empty notes null.
 */
public record OrderContent(
        List<OrderDraft.Line> lines,
        String transportDocumentCode,
        Integer packageCount,
        Integer accessoryServiceId,
        String notes,
        OrderDraft.Recipient recipient)
        implements Serializable {

    public OrderContent {
        lines = List.copyOf(lines);
    }

    public BigDecimal totalWeightKg() {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (OrderDraft.Line line : lines) {
            total = total.add(line.weightTotal());
        }
        return total;
    }

    public BigDecimal totalVolumeM3() {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (OrderDraft.Line line : lines) {
            total = total.add(line.volumeTotal());
        }
        return total;
    }
}
