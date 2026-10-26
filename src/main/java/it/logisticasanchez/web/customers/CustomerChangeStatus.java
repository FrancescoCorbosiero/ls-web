package it.logisticasanchez.web.customers;

/** The states of a data change request (column {@code customer_change_request.status}, V019; ENH-16, ADR 0038). */
public enum CustomerChangeStatus {
    PENDING,
    APPROVED,
    DECLINED,
    WITHDRAWN
}
