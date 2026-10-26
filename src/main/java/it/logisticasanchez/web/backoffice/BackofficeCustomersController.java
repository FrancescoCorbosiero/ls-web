package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The customer screens of the back office (ADM-02, ADM-03; chapter 04 §3.2–3.3 columns and copy;
 * ADR 0017): «In Approvazione» with approve/decline behind a named confirmation page («Sei sicuro
 * di voler procedere?»), «Registrati» newest first with search and pagination, the customer page
 * with its orders. Rules and e-mails live in {@link CustomerService}; a refused action
 * (unverified customer, Q-28) is shown on the confirmation page.
 */
@Controller
public class BackofficeCustomersController {

    static final int PAGE_SIZE = 25;

    private final CustomerService customers;
    private final OrderService orders;
    private final FeatureFlags flags;

    public BackofficeCustomersController(CustomerService customers, OrderService orders, FeatureFlags flags) {
        this.customers = customers;
        this.orders = orders;
        this.flags = flags;
    }

    @GetMapping(BackofficeRoutes.CUSTOMERS_PENDING)
    public String pending(Model model) {
        model.addAttribute("customers", customers.pendingApproval());
        return "backoffice/customers-pending";
    }

    @GetMapping(BackofficeRoutes.CUSTOMERS)
    public String registered(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "pagina", required = false) Integer pageNumber,
            Model model) {
        int page = pageNumber == null || pageNumber < 1 ? 0 : pageNumber - 1;
        Page<CustomerView> result = customers.search(query, page, PAGE_SIZE);
        model.addAttribute("q", query == null ? "" : query.strip());
        model.addAttribute("page", result);
        model.addAttribute("exportEnabled", flags.isEnabled(FlagKey.ADMIN_EXPORTS)); // ENH-08, ADR 0035
        return "backoffice/customers";
    }

    @GetMapping(BackofficeRoutes.CUSTOMER)
    public String detail(@PathVariable("id") long id, Model model) {
        CustomerView customer = customer(id);
        model.addAttribute("customer", customer);
        model.addAttribute("orders", orders.listForCustomer(customer.id()));
        return "backoffice/customer";
    }

    @GetMapping(BackofficeRoutes.CUSTOMER_APPROVE)
    public String approvePage(@PathVariable("id") long id, Model model) {
        return confirmPage(model, customer(id), "approve", null);
    }

    @PostMapping(BackofficeRoutes.CUSTOMER_APPROVE)
    public String approve(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerView customer = customer(id);
        try {
            customers.approve(customer.id(), StaffSession.of(authentication).requireId());
        } catch (RuleViolationException e) {
            return confirmPage(model, customer, "approve", e.detail());
        }
        redirect.addFlashAttribute("flash", "Cliente «" + customer.companyName() + "» approvato.");
        return "redirect:" + BackofficeRoutes.CUSTOMERS_PENDING;
    }

    @GetMapping(BackofficeRoutes.CUSTOMER_DECLINE)
    public String declinePage(@PathVariable("id") long id, Model model) {
        return confirmPage(model, customer(id), "decline", null);
    }

    @PostMapping(BackofficeRoutes.CUSTOMER_DECLINE)
    public String decline(
            @PathVariable("id") long id,
            @RequestParam(name = "motivo", required = false) String reason,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerView customer = customer(id);
        if (reason != null && reason.length() > 500) {
            return confirmPage(model, customer, "decline", "Il motivo può avere al massimo 500 caratteri.");
        }
        try {
            customers.decline(customer.id(), StaffSession.of(authentication).requireId(), reason);
        } catch (RuleViolationException e) {
            return confirmPage(model, customer, "decline", e.detail());
        }
        redirect.addFlashAttribute("flash", "Cliente «" + customer.companyName() + "» rifiutato.");
        return "redirect:" + BackofficeRoutes.CUSTOMERS_PENDING;
    }

    private CustomerView customer(long id) {
        return customers.find(id).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    private static String confirmPage(Model model, CustomerView customer, String action, String error) {
        model.addAttribute("customer", customer);
        model.addAttribute("action", action);
        model.addAttribute("error", error);
        return "backoffice/customer-confirm";
    }
}
