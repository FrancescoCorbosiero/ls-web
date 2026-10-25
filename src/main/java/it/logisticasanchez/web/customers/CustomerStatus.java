package it.logisticasanchez.web.customers;

/** The customer states of chapter 08 §2.4 (column {@code customer.status}). */
public enum CustomerStatus {
    PENDING_VERIFICATION,
    PENDING_APPROVAL,
    ACTIVE,
    DECLINED;

    /** Only an ACTIVE customer may order (OP-R2, CUS-03). */
    public boolean canOrder() {
        return this == ACTIVE;
    }

    public boolean isPending() {
        return this == PENDING_VERIFICATION || this == PENDING_APPROVAL;
    }
}
