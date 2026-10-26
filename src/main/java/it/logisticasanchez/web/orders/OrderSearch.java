package it.logisticasanchez.web.orders;

import java.time.LocalDate;

/**
 * The filters of the back-office order list (ADM-05): state, tracking state, customer, the
 * submission date range (inclusive, Europe/Rome days) and a text searched in the definitive
 * and temporary codes. Every field is optional ({@code null} = no filter); {@code undelivered}
 * keeps the shipments not yet {@code CONSEGNATO} (the «Spedizioni in corso» of the home, ADR 0029).
 * {@code text} is the free-text search of the office tools (flag {@code admin.dashboard}, ADR
 * 0035): codes, recipient, recipient's city, transport document, customer.
 */
public record OrderSearch(
        OrderStatus status,
        Integer trackingStateId,
        Long customerId,
        LocalDate from,
        LocalDate to,
        String code,
        boolean undelivered,
        String text) {

    public static OrderSearch none() {
        return new OrderSearch(null, null, null, null, null, null, false, null);
    }
}
