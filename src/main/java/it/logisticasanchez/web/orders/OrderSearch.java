package it.logisticasanchez.web.orders;

import java.time.LocalDate;

/**
 * The filters of the back-office order list (ADM-05): state, tracking state, customer, the
 * submission date range (inclusive, Europe/Rome days) and a text searched in the definitive
 * and temporary codes. Every field is optional ({@code null} = no filter).
 */
public record OrderSearch(
        OrderStatus status, Integer trackingStateId, Long customerId, LocalDate from, LocalDate to, String code) {

    public static OrderSearch none() {
        return new OrderSearch(null, null, null, null, null, null);
    }
}
