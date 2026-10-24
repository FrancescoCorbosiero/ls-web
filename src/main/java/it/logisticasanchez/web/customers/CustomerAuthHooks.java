package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.CustomerEmailChangedListener;
import it.logisticasanchez.web.auth.CustomerSignInListener;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** The auth module's hooks implemented by the customers module (chapter 08 §2.4 "sign-in link used"; CUS-06). */
@Component
class CustomerAuthHooks implements CustomerSignInListener, CustomerEmailChangedListener {

    private final CustomerService customers;

    CustomerAuthHooks(CustomerService customers) {
        this.customers = customers;
    }

    @Override
    public void onSignedIn(long customerUserId, long customerId, Instant at) {
        customers.emailVerified(customerId, at);
    }

    @Override
    public void onEmailChanged(long customerUserId, long customerId, String oldEmail, String newEmail) {
        customers.emailChanged(customerId, customerUserId, oldEmail, newEmail);
    }
}
