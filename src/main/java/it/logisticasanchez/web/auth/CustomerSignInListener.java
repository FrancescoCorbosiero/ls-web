package it.logisticasanchez.web.auth;

import java.time.Instant;

/**
 * Hook for the {@code customers} module (Phase 2): called inside the sign-in transaction when a
 * customer user has used a sign-in link, so the module can set {@code customer.email_verified_at}
 * and move a {@code PENDING_VERIFICATION} customer on (chapter 08 §2.4 "sign-in link used").
 * Zero or more beans; none exists in Phase 1.
 */
public interface CustomerSignInListener {

    void onSignedIn(long customerUserId, long customerId, Instant at);
}
