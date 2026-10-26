package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.customers.AddressBookService;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import jakarta.servlet.http.HttpSession;
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
 * The new order (CUS-04; chapter 03 §6 content and rules, fixes OP-01…07, OP-12…16, OP-23, OP-25;
 * ADR 0029 for the page): one form with the lines as editable rows, the shipment data and the
 * recipient, the totals beside them (live with JavaScript, recomputed on every round trip
 * without it), then the summary («Controlla i dati inseriti») and the final screen with the
 * legacy wording («Logistica Sanchez», Q-37). The draft lives in the session; its idempotency
 * key makes a repeated confirmation harmless (OP-02). Only an ACTIVE customer gets in (CUS-03).
 * ENH-07 (ADR 0033): with {@code customers.addressBook} on, the recipient comes from the address
 * book and can be saved there when the order is sent ({@link OrderAddressBook}); with
 * {@code orders.repeat} on, «Ordina di nuovo» fills the form from one of the customer's orders.
 */
@Controller
public class OrderWizardController {

    static final String SUBMITTED_CODE = "submittedCode";

    private final OrderService orders;
    private final OrderValidator validator;
    private final OrderForm forms;
    private final CustomerService customers;
    private final TrackingCatalog catalog;
    private final OrderAddressBook addressBook;
    private final FeatureFlags flags;

    public OrderWizardController(
            OrderService orders,
            OrderValidator validator,
            OrderForm forms,
            CustomerService customers,
            TrackingCatalog catalog,
            OrderAddressBook addressBook,
            FeatureFlags flags) {
        this.orders = orders;
        this.validator = validator;
        this.forms = forms;
        this.customers = customers;
        this.catalog = catalog;
        this.addressBook = addressBook;
        this.flags = flags;
    }

    @GetMapping(OrderRoutes.NEW)
    public String form(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model) {
        CustomerView customer = customerOrNull(authentication, model);
        if (customer == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        return formPage(model, forms.of(draft(session)), customer);
    }

    /** The form's buttons: «Aggiungi riga», «Elimina» (answered with the page, everything typed kept) and «Continua». */
    @PostMapping(OrderRoutes.NEW)
    public String formAction(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model) {
        CustomerView customer = customerOrNull(authentication, model);
        if (customer == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        OrderForm.Model typed = forms.read(form);
        switch (addressBook.action(form)) {
            case ADD_LINE -> {
                return formPage(model, forms.withRowAdded(typed), customer);
            }
            case REMOVE_LINE -> {
                return formPage(model, forms.withRowRemoved(typed, forms.removedIndex(form)), customer);
            }
            case USE_ADDRESS -> {
                return formPage(model, addressBook.use(customer.id(), typed), customer);
            }
            case SHOW_ADDRESS_BOOK -> {
                return formPage(model, typed, customer);
            }
            default -> {
                OrderForm.Submission submission = forms.validate(typed);
                if (!submission.valid()) {
                    return formPage(model, submission.model(), customer);
                }
                OrderDraft draft = draft(session);
                draft.replace(submission.content());
                draft.setSaveRecipient(addressBook.asked(typed));
                return "redirect:" + OrderRoutes.NEW_REVIEW;
            }
        }
    }

    @GetMapping(OrderRoutes.NEW_REVIEW)
    public String review(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model) {
        if (customerOrNull(authentication, model) == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        OrderDraft draft = draft(session);
        if (!draft.isComplete()) {
            return "redirect:" + OrderRoutes.NEW;
        }
        model.addAttribute("draft", draft);
        model.addAttribute("serviceLabel", serviceLabel(draft.accessoryServiceId()));
        model.addAttribute("warnings", validator.warnings(draft.lines()));
        model.addAttribute(
                "units",
                draft.lines().stream().mapToInt(OrderDraft.Line::quantity).sum());
        model.addAttribute("selfService", orders.customerSelfService());
        model.addAttribute("saveRecipient", draft.saveRecipient() && addressBook.enabled());
        return "orders/wizard-review";
    }

    @PostMapping(OrderRoutes.NEW_REVIEW)
    public String confirm(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        if (customerOrNull(authentication, model) == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        OrderDraft draft = draft(session);
        if (!draft.isComplete()) {
            return "redirect:" + OrderRoutes.NEW;
        }
        OrderView order = orders.submit(principal.customerId(), principal.customerUserId(), draft);
        session.removeAttribute(OrderDraft.SESSION_KEY);
        redirect.addFlashAttribute(SUBMITTED_CODE, order.displayCode());
        String notSaved = draft.saveRecipient()
                ? addressBook.remember(
                        principal.customerId(),
                        AddressBookService.Actor.customer(principal.customerUserId()),
                        draft.recipient())
                : null;
        if (notSaved != null) {
            redirect.addFlashAttribute("flashError", notSaved);
        }
        return "redirect:" + OrderRoutes.NEW_DONE;
    }

    /** The final screen (chapter 03 §6.7 wording, brand corrected): terminal, «Indietro» leads nowhere (OP-02). */
    @GetMapping(OrderRoutes.NEW_DONE)
    public String done(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication, Model model) {
        if (customerOrNull(authentication, model) == null || !model.containsAttribute(SUBMITTED_CODE)) {
            return "redirect:" + OrderRoutes.LIST;
        }
        model.addAttribute("selfService", orders.customerSelfService());
        return "orders/wizard-done";
    }

    /**
     * ENH-07 «Ordina di nuovo» (flag {@code orders.repeat}; a 404 while it is off): the order form
     * filled from one of the customer's orders, whatever its state — lines, accessory service,
     * notes and recipient — with the transport document left to type; a new draft, so a new order.
     */
    @PostMapping(OrderRoutes.REPEAT)
    public String repeat(
            @PathVariable("codice") String code,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model,
            RedirectAttributes redirect) {
        if (!flags.isEnabled(FlagKey.ORDERS_REPEAT)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        OrderView order = orders.findForCustomer(principal.customerId(), code)
                .orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
        if (customerOrNull(authentication, model) == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        session.setAttribute(OrderDraft.SESSION_KEY, OrderDraft.repeatOf(order));
        redirect.addFlashAttribute(
                "flash",
                "Il modulo riporta i dati dell'ordine " + order.displayCode()
                        + ": inserisci il nuovo documento di trasporto e controlla il resto.");
        return "redirect:" + OrderRoutes.NEW;
    }

    private String formPage(Model model, OrderForm.Model form, CustomerView customer) {
        model.addAttribute("addressBook", addressBook.entries(customer.id()));
        model.addAttribute("form", form);
        model.addAttribute("services", catalog.activeServices(form.value("accessoryServiceId")));
        model.addAttribute("kinds", LineKind.values());
        model.addAttribute("mode", "new");
        return "orders/order-form";
    }

    private String serviceLabel(Integer serviceId) {
        if (serviceId == null) {
            return catalog.activeServices().stream()
                    .filter(TrackingCatalog.ServiceView::none)
                    .map(TrackingCatalog.ServiceView::label)
                    .findFirst()
                    .orElse("-");
        }
        return catalog.service(serviceId)
                .map(TrackingCatalog.ServiceView::label)
                .orElse("-");
    }

    /** The signed-in customer when it may order; null (after filling {@code email} and {@code customer}) otherwise. */
    private CustomerView customerOrNull(Authentication authentication, Model model) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        CustomerView customer = customers.find(principal.customerId()).orElseThrow();
        model.addAttribute("email", principal.email());
        model.addAttribute("customer", customer);
        return customer.canOrder() ? customer : null;
    }

    private static OrderDraft draft(HttpSession session) {
        Object existing = session.getAttribute(OrderDraft.SESSION_KEY);
        if (existing instanceof OrderDraft draft) {
            return draft;
        }
        OrderDraft draft = new OrderDraft();
        session.setAttribute(OrderDraft.SESSION_KEY, draft);
        return draft;
    }
}
