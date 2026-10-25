package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * «I miei ordini» (CUS-05): the home of the customer area with the signed-in customer's orders —
 * code («-» replaced by the temporary code while pending), date, recipient, state, tracking,
 * amount once approved — and the detail page by code or temporary code; another customer's code
 * is a 404. A pending or declined customer sees its status here and no «Nuovo ordine» (CUS-03).
 */
@Controller
public class CustomerOrdersController {

    private final OrderService orders;
    private final CustomerService customers;

    public CustomerOrdersController(OrderService orders, CustomerService customers) {
        this.orders = orders;
        this.customers = customers;
    }

    @GetMapping(OrderRoutes.LIST)
    public String list(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication, Model model) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        CustomerView customer = customers.find(principal.customerId()).orElseThrow();
        model.addAttribute("email", principal.email());
        model.addAttribute("customer", customer);
        model.addAttribute("orders", orders.listForCustomer(principal.customerId()));
        return "orders/list";
    }

    @GetMapping(OrderRoutes.DETAIL)
    public String detail(
            @PathVariable("codice") String code,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        OrderView order = orders.findForCustomer(principal.customerId(), code)
                .orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
        CustomerView customer = customers.find(principal.customerId()).orElseThrow();
        model.addAttribute("email", principal.email());
        model.addAttribute("customer", customer);
        model.addAttribute("order", order);
        return "orders/detail";
    }
}
