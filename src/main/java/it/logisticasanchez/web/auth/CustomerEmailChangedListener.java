package it.logisticasanchez.web.auth;

/**
 * Hook for the {@code customers} module: called inside the transaction that applies a confirmed
 * e-mail change (CUS-06) so the module can update {@code customer.email} and write its audit
 * entry. Zero or more beans.
 */
public interface CustomerEmailChangedListener {

    void onEmailChanged(long customerUserId, long customerId, String oldEmail, String newEmail);
}
