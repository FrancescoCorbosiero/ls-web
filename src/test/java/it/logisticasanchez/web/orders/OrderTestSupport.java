package it.logisticasanchez.web.orders;

import java.math.BigDecimal;

/** The chapter 03 §6.3 worked example as a draft, for the orders tests. */
final class OrderTestSupport {

    private OrderTestSupport() {}

    static OrderDraft exampleDraft() {
        OrderDraft draft = new OrderDraft();
        draft.addLine(LineKind.EPAL, 180, 120, 80, new BigDecimal("450"), 2);
        draft.addLine(LineKind.GENERIC, 100, 120, 80, new BigDecimal("300"), 1);
        draft.addLine(LineKind.GENERIC, 40, 30, 30, new BigDecimal("12"), 3);
        draft.setDetails("DDT-2026-0042", "3", 2, "Consegna al mattino");
        draft.setRecipient(new OrderDraft.Recipient(
                "Magazzino Destinatario S.r.l.",
                "Via Roma 10",
                "MI",
                "Milano",
                "20121",
                "+390212345678",
                "ricevimento@destinatario.example"));
        return draft;
    }
}
