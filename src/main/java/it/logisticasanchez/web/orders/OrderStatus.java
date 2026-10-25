package it.logisticasanchez.web.orders;

/**
 * The order states of chapter 08 §2.4 (column {@code shipment_order.status}) plus CANCELLED
 * (V013, ADR 0028): withdrawn by the customer while pending (CUS-07) or by staff (ADM-11).
 */
public enum OrderStatus {
    PENDING,
    APPROVED,
    DECLINED,
    CANCELLED
}
