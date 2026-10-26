package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.customers.AddressBookService;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import java.util.Map;
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
 * CUS-07 (owner decision of 2026-10-25, Q-12; ADR 0028): while the office has not confirmed it,
 * the customer changes its own order with the same form as a new one («Modifica l'ordine») or
 * withdraws it after a confirmation page («Annulla l'ordine»). Each page carries the version of
 * the order it showed; a change meanwhile (the office, another tab) sends the customer back to
 * the current data. An order of another customer is a 404; one that is no longer pending, or a
 * setting that keeps changes with the office, leads back to the detail page. With
 * {@code customers.addressBook} on (ENH-07) the form also offers the address book.
 */
@Controller
public class CustomerOrderChangesController {

    static final String VERSION = "versione";

    private final OrderService orders;
    private final OrderForm forms;
    private final CustomerService customers;
    private final TrackingCatalog catalog;
    private final OrderAddressBook addressBook;

    public CustomerOrderChangesController(
            OrderService orders,
            OrderForm forms,
            CustomerService customers,
            TrackingCatalog catalog,
            OrderAddressBook addressBook) {
        this.orders = orders;
        this.forms = forms;
        this.customers = customers;
        this.catalog = catalog;
        this.addressBook = addressBook;
    }

    @GetMapping(OrderRoutes.EDIT)
    public String edit(
            @PathVariable("codice") String code,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = signedIn(authentication, model);
        OrderView order = own(principal, code);
        if (!changeable(order)) {
            return closed(order, redirect);
        }
        return editPage(model, order, forms.of(order), null);
    }

    @PostMapping(OrderRoutes.EDIT)
    public String update(
            @PathVariable("codice") String code,
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = signedIn(authentication, model);
        OrderView order = own(principal, code);
        if (!changeable(order)) {
            return closed(order, redirect);
        }
        int version = version(form);
        OrderForm.Model typed = forms.read(form);
        switch (addressBook.action(form)) {
            case ADD_LINE -> {
                return editPage(model, order, forms.withRowAdded(typed), version);
            }
            case REMOVE_LINE -> {
                return editPage(model, order, forms.withRowRemoved(typed, forms.removedIndex(form)), version);
            }
            case USE_ADDRESS -> {
                return editPage(model, order, addressBook.use(principal.customerId(), typed), version);
            }
            case SHOW_ADDRESS_BOOK -> {
                return editPage(model, order, typed, version);
            }
            default -> {
                OrderForm.Submission submission = forms.validate(typed);
                if (!submission.valid()) {
                    return editPage(model, order, submission.model(), version);
                }
                try {
                    orders.updateByCustomer(
                            principal.customerId(),
                            principal.customerUserId(),
                            order.displayCode(),
                            version,
                            submission.content());
                } catch (RuleViolationException e) {
                    OrderView current = own(principal, code);
                    if (!changeable(current)) {
                        return closed(current, redirect);
                    }
                    model.addAttribute("error", e.detail());
                    return editPage(model, current, forms.of(current), null);
                }
                redirect.addFlashAttribute(
                        "flash", "Modifiche salvate: il centro logistico vede già l'ordine aggiornato.");
                String notSaved = addressBook.asked(typed)
                        ? addressBook.remember(
                                principal.customerId(),
                                AddressBookService.Actor.customer(principal.customerUserId()),
                                submission.content().recipient())
                        : null;
                if (notSaved != null) {
                    redirect.addFlashAttribute("flashError", notSaved);
                }
                return "redirect:" + OrderRoutes.detail(order.displayCode());
            }
        }
    }

    @GetMapping(OrderRoutes.CANCEL)
    public String cancelPage(
            @PathVariable("codice") String code,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = signedIn(authentication, model);
        OrderView order = own(principal, code);
        if (!changeable(order)) {
            return closed(order, redirect);
        }
        model.addAttribute("order", order);
        model.addAttribute("error", null);
        return "orders/cancel";
    }

    @PostMapping(OrderRoutes.CANCEL)
    public String cancel(
            @PathVariable("codice") String code,
            @RequestParam(name = VERSION, required = false) String versionText,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = signedIn(authentication, model);
        OrderView order = own(principal, code);
        if (!changeable(order)) {
            return closed(order, redirect);
        }
        try {
            orders.cancelByCustomer(
                    principal.customerId(),
                    principal.customerUserId(),
                    order.displayCode(),
                    version(Map.of(VERSION, versionText == null ? "" : versionText)));
        } catch (RuleViolationException e) {
            OrderView current = own(principal, code);
            if (!changeable(current)) {
                return closed(current, redirect);
            }
            model.addAttribute("order", current);
            model.addAttribute("error", e.detail());
            return "orders/cancel";
        }
        redirect.addFlashAttribute(
                "flash", "Ordine " + order.displayCode() + " annullato. Il centro logistico è stato avvisato.");
        return "redirect:" + OrderRoutes.LIST;
    }

    private String editPage(Model model, OrderView order, OrderForm.Model form, Integer version) {
        model.addAttribute("addressBook", addressBook.entries(order.customerId()));
        model.addAttribute("order", order);
        model.addAttribute("form", form);
        model.addAttribute("version", version == null ? order.version() : version);
        model.addAttribute("services", catalog.activeServices());
        model.addAttribute("kinds", LineKind.values());
        model.addAttribute("mode", "edit");
        return "orders/order-form";
    }

    private boolean changeable(OrderView order) {
        return orders.customerSelfService() && order.editableByCustomer();
    }

    /** Back to the detail page, which says why nothing can be changed any more. */
    private static String closed(OrderView order, RedirectAttributes redirect) {
        redirect.addFlashAttribute(
                "flashError",
                order.isPending()
                        ? "Per modificare o annullare l'ordine contatta il centro logistico."
                        : "Il centro logistico ha già gestito l'ordine: non si può più modificare né annullare.");
        return "redirect:" + OrderRoutes.detail(order.displayCode());
    }

    private CustomerPrincipal signedIn(Authentication authentication, Model model) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        model.addAttribute("email", principal.email());
        model.addAttribute("customer", customers.find(principal.customerId()).orElseThrow());
        return principal;
    }

    private OrderView own(CustomerPrincipal principal, String code) {
        return orders.findForCustomer(principal.customerId(), code)
                .orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    /** The version the page was built on; -1 (always stale) when missing or malformed. */
    static int version(Map<String, String> form) {
        String raw = ItalianFormats.trim(form.get(VERSION));
        return raw.matches("^[0-9]{1,9}$") ? Integer.parseInt(raw) : -1;
    }
}
