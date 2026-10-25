package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import it.logisticasanchez.web.tracking.TrackingState;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * «I miei ordini» (CUS-05): the home of the customer area — how many orders wait for the office,
 * travel or arrived, the list of the signed-in customer's orders (code, date, recipient, state,
 * tracking, amount once approved) filtered by those groups, and the detail page by code or
 * temporary code with the tracking steps and, while the order is pending, «Modifica» and
 * «Annulla» (CUS-07); another customer's code is a 404. A pending or declined customer sees its
 * status here and no «Nuovo ordine» (CUS-03).
 */
@Controller
public class CustomerOrdersController {

    /** The list's groups ({@code ?stato=}): every order, waiting for the office, travelling, delivered, closed. */
    static final Map<String, Predicate<OrderView>> GROUPS = Map.of(
            "attesa", OrderView::isPending,
            "viaggio", o -> o.isApproved() && o.trackingStateId() != TrackingState.CONSEGNATO,
            "consegnati", o -> o.isApproved() && o.trackingStateId() == TrackingState.CONSEGNATO,
            "chiusi", o -> o.isDeclined() || o.isCancelled());

    private final OrderService orders;
    private final CustomerService customers;
    private final TrackingCatalog catalog;

    public CustomerOrdersController(OrderService orders, CustomerService customers, TrackingCatalog catalog) {
        this.orders = orders;
        this.customers = customers;
        this.catalog = catalog;
    }

    @GetMapping(OrderRoutes.LIST)
    public String list(
            @RequestParam(name = "stato", required = false) String group,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        CustomerView customer = customers.find(principal.customerId()).orElseThrow();
        List<OrderView> all = orders.listForCustomer(principal.customerId());
        String active = group != null && GROUPS.containsKey(group) ? group : "";
        model.addAttribute("email", principal.email());
        model.addAttribute("customer", customer);
        model.addAttribute("allCount", all.size());
        for (Map.Entry<String, Predicate<OrderView>> g : GROUPS.entrySet()) {
            model.addAttribute(
                    "count_" + g.getKey(), all.stream().filter(g.getValue()).count());
        }
        model.addAttribute("group", active);
        model.addAttribute(
                "orders",
                active.isEmpty() ? all : all.stream().filter(GROUPS.get(active)).toList());
        model.addAttribute("selfService", orders.customerSelfService());
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
        model.addAttribute("selfService", orders.customerSelfService());
        model.addAttribute(
                "steps",
                catalog.states().stream()
                        .filter(s -> s.id() != TrackingState.NON_GESTITO)
                        .toList());
        return "orders/detail";
    }
}
