package it.logisticasanchez.web.support;

import it.logisticasanchez.web.orders.LineKind;
import it.logisticasanchez.web.orders.OrderDraft;
import java.math.BigDecimal;

/** Complete wizard drafts for the tests of the modules that consume orders (back office, reports, privacy). */
public final class OrderTestData {

    private OrderTestData() {}

    /** The chapter 03 §6.3 worked example: two EPAL of 450 kg, one pallet of 300 kg, three parcels of 12 kg. */
    public static OrderDraft draft(String transportDocumentCode, String recipientName, String city, String postalCode) {
        OrderDraft draft = new OrderDraft();
        draft.addLine(LineKind.EPAL, 180, 120, 80, new BigDecimal("450"), 2);
        draft.addLine(LineKind.GENERIC, 100, 120, 80, new BigDecimal("300"), 1);
        draft.addLine(LineKind.GENERIC, 40, 30, 30, new BigDecimal("12"), 3);
        draft.setDetails(transportDocumentCode, "3", 2, "Consegna al mattino");
        draft.setRecipient(new OrderDraft.Recipient(
                recipientName,
                "Via Roma 10",
                "MI",
                city,
                postalCode,
                "+390212345678",
                "ricevimento@destinatario.example"));
        return draft;
    }
}
