package it.logisticasanchez.web.orders;

/** The order states of chapter 08 §2.4 (column {@code shipment_order.status}). */
public enum OrderStatus {
    PENDING,
    APPROVED,
    DECLINED
}
