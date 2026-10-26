package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.customers.CustomerChangeService;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.site.forms.LeadService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** {@code staff} (name, role) and {@code queue} (records waiting for approval, ADR 0029; new website requests, ADR 0036; customers' data changes, ADR 0038) for every back-office template. */
@ControllerAdvice(basePackageClasses = BackofficeRoutes.class)
public class BackofficeModelAttributes {

    private final CustomerService customers;
    private final OrderService orders;
    private final LeadService leads;
    private final CustomerChangeService customerChanges;
    private final FeatureFlags flags;

    public BackofficeModelAttributes(
            CustomerService customers,
            OrderService orders,
            LeadService leads,
            CustomerChangeService customerChanges,
            FeatureFlags flags) {
        this.customers = customers;
        this.orders = orders;
        this.leads = leads;
        this.customerChanges = customerChanges;
        this.flags = flags;
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
        return new WorkQueue(
                customers.countPendingApproval(),
                orders.countPendingApproval(),
                flags.isEnabled(FlagKey.LEADS_INBOX) ? leads.countNew() : null,
                flags.isEnabled(FlagKey.CUSTOMERS_PROFILE_EDIT) ? customerChanges.countPending() : null);
    }
}
