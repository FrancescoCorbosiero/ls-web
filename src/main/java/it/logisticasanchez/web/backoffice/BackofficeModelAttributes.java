package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.orders.OrderService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** {@code staff} (name, role) and {@code queue} (records waiting for approval, ADR 0029) for every back-office template. */
@ControllerAdvice(basePackageClasses = BackofficeRoutes.class)
public class BackofficeModelAttributes {

    private final CustomerService customers;
    private final OrderService orders;

    public BackofficeModelAttributes(CustomerService customers, OrderService orders) {
        this.customers = customers;
        this.orders = orders;
    }

    @ModelAttribute("staff")
    public StaffSession staff(@CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return StaffSession.of(authentication);
    }

    @ModelAttribute("queue")
    public WorkQueue queue() {
        return queueNow();
    }

    WorkQueue queueNow() {
        return new WorkQueue(customers.countPendingApproval(), orders.countPendingApproval());
    }
}
