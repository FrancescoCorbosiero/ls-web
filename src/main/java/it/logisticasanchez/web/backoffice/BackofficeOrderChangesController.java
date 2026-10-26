package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.customers.AddressBookService;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.orders.LineKind;
import it.logisticasanchez.web.orders.OrderAddressBook;
import it.logisticasanchez.web.orders.OrderForm;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * ADM-11 (owner decision of 2026-10-25; ADR 0028): staff enter an order for a customer («Nuovo
 * ordine», the customer's order form plus the choice of the sender), change one («Modifica») —
 * a pending order for every staff member, an approved one only as ADMIN, who may also correct
 * the definitive code and the amount — and cancel one («Annulla ordine», with an optional
 * reason). Every page carries the version of the order it showed: a change meanwhile (the
 * customer, a colleague) brings the current data back with a message. Rules, audit and the
 * absence of e-mails live in {@link OrderService}. ENH-07 (ADR 0033): with
 * {@code customers.addressBook} on, the form offers the chosen customer's address book and can
 * save the recipient there; with {@code orders.repeat} on, «Ripeti ordine» opens «Nuovo ordine»
 * filled from an existing order ({@code ?ripeti=<id>}).
 */
@Controller
public class BackofficeOrderChangesController {

    static final String VERSION = "versione";
    static final String FORM_KEY = "chiave";

    private final OrderService orders;
    private final OrderForm forms;
    private final CustomerService customers;
    private final TrackingCatalog catalog;
    private final OrderAddressBook addressBook;
    private final FeatureFlags flags;

    public BackofficeOrderChangesController(
            OrderService orders,
            OrderForm forms,
            CustomerService customers,
            TrackingCatalog catalog,
            OrderAddressBook addressBook,
            FeatureFlags flags) {
        this.orders = orders;
        this.forms = forms;
        this.customers = customers;
        this.catalog = catalog;
        this.addressBook = addressBook;
        this.flags = flags;
    }

    @GetMapping(BackofficeRoutes.ORDER_NEW)
    public String newOrder(
            @RequestParam(name = "cliente", required = false) String customer,
            @RequestParam(name = "ripeti", required = false) String repeat,
            Model model) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("cliente", id(customer) == null ? "" : id(customer).toString());
        OrderForm.Model form = forms.blank();
        // ENH-07 «Ripeti ordine»: the same customer and data, a new transport document to type
        Long repeated = flags.isEnabled(FlagKey.ORDERS_REPEAT) ? id(repeat) : null;
        Optional<OrderView> source = repeated == null ? Optional.empty() : orders.find(repeated);
        if (source.isPresent()) {
            form = forms.repeat(source.get());
            extra.put("cliente", Long.toString(source.get().customerId()));
            model.addAttribute(
                    "flash",
                    "Il modulo riporta i dati dell'ordine " + source.get().displayCode()
                            + ": inserisci il nuovo documento di trasporto e controlla il resto.");
        }
        return formPage(model, null, form, extra, null, UUID.randomUUID().toString(), null);
    }

    @PostMapping(BackofficeRoutes.ORDER_NEW)
    public String create(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        Map<String, String> extra = Map.of("cliente", ItalianFormats.trim(form.get("cliente")));
        String key = formKey(form);
        OrderForm.Model typed = forms.read(form);
        switch (addressBook.action(form)) {
            case ADD_LINE -> {
                return formPage(model, null, forms.withRowAdded(typed), extra, null, key, null);
            }
            case REMOVE_LINE -> {
                return formPage(
                        model, null, forms.withRowRemoved(typed, forms.removedIndex(form)), extra, null, key, null);
            }
            case USE_ADDRESS -> {
                return formPage(model, null, addressBook.use(id(extra.get("cliente")), typed), extra, null, key, null);
            }
            case SHOW_ADDRESS_BOOK -> {
                return formPage(model, null, typed, extra, null, key, null);
            }
            default -> {
                OrderForm.Submission submission = forms.validate(typed);
                Long customerId = id(extra.get("cliente"));
                if (!submission.valid() || customerId == null) {
                    model.addAttribute(
                            "customerError", customerId == null ? "backoffice.orderForm.customerRequired" : null);
                    return formPage(model, null, submission.model(), extra, null, key, null);
                }
                OrderView created;
                try {
                    created = orders.createByStaff(
                            StaffSession.of(authentication).requireId(),
                            customerId,
                            UUID.fromString(key),
                            submission.content());
                } catch (RuleViolationException e) {
                    return formPage(model, null, submission.model(), extra, null, key, e.detail());
                }
                redirect.addFlashAttribute(
                        "flash",
                        "Ordine inserito con il codice provvisorio " + created.tempCode()
                                + ": controlla i dati e approvalo con codice e importo.");
                rememberIfAsked(typed, customerId, authentication, submission, redirect);
                return "redirect:" + BackofficeRoutes.order(created.id());
            }
        }
    }

    @GetMapping(BackofficeRoutes.ORDER_EDIT)
    public String edit(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        OrderView order = order(id);
        StaffSession staff = StaffSession.of(authentication);
        if (!changeable(order, staff)) {
            return closed(order, staff, redirect);
        }
        return formPage(model, order, forms.of(order), approval(order), order.version(), null, null);
    }

    @PostMapping(BackofficeRoutes.ORDER_EDIT)
    public String update(
            @PathVariable("id") long id,
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        OrderView order = order(id);
        StaffSession staff = StaffSession.of(authentication);
        if (!changeable(order, staff)) {
            return closed(order, staff, redirect);
        }
        int version = version(form.get(VERSION));
        Map<String, String> extra = Map.of(
                "codice", ItalianFormats.trim(form.get("codice")), "importo", ItalianFormats.trim(form.get("importo")));
        OrderForm.Model typed = forms.read(form);
        switch (addressBook.action(form)) {
            case ADD_LINE -> {
                return formPage(model, order, forms.withRowAdded(typed), extra, version, null, null);
            }
            case USE_ADDRESS -> {
                return formPage(model, order, addressBook.use(order.customerId(), typed), extra, version, null, null);
            }
            case SHOW_ADDRESS_BOOK -> {
                return formPage(model, order, typed, extra, version, null, null);
            }
            case REMOVE_LINE -> {
                return formPage(
                        model,
                        order,
                        forms.withRowRemoved(typed, forms.removedIndex(form)),
                        extra,
                        version,
                        null,
                        null);
            }
            default -> {
                OrderForm.Submission submission = forms.validate(typed);
                if (!submission.valid()) {
                    return formPage(model, order, submission.model(), extra, version, null, null);
                }
                try {
                    orders.updateByStaff(
                            order.id(),
                            staff.requireId(),
                            staff.admin(),
                            version,
                            submission.content(),
                            order.isApproved()
                                    ? new OrderService.ApprovalCorrection(extra.get("codice"), extra.get("importo"))
                                    : null);
                } catch (RuleViolationException e) {
                    OrderView current = order(id);
                    if (!changeable(current, staff)) {
                        return closed(current, staff, redirect);
                    }
                    if (current.version() != version) {
                        return formPage(
                                model,
                                current,
                                forms.of(current),
                                approval(current),
                                current.version(),
                                null,
                                e.detail());
                    }
                    return formPage(model, current, submission.model(), extra, version, null, e.detail());
                }
                redirect.addFlashAttribute("flash", "Ordine aggiornato.");
                rememberIfAsked(typed, order.customerId(), authentication, submission, redirect);
                return "redirect:" + BackofficeRoutes.order(order.id());
            }
        }
    }

    @GetMapping(BackofficeRoutes.ORDER_CANCEL)
    public String cancelPage(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        OrderView order = order(id);
        StaffSession staff = StaffSession.of(authentication);
        if (!changeable(order, staff)) {
            return closed(order, staff, redirect);
        }
        return cancelForm(model, order, "", null);
    }

    @PostMapping(BackofficeRoutes.ORDER_CANCEL)
    public String cancel(
            @PathVariable("id") long id,
            @RequestParam(name = "motivo", required = false) String reason,
            @RequestParam(name = VERSION, required = false) String version,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        OrderView order = order(id);
        StaffSession staff = StaffSession.of(authentication);
        if (!changeable(order, staff)) {
            return closed(order, staff, redirect);
        }
        try {
            orders.cancelByStaff(order.id(), staff.requireId(), staff.admin(), version(version), reason);
        } catch (RuleViolationException e) {
            OrderView current = order(id);
            if (!changeable(current, staff)) {
                return closed(current, staff, redirect);
            }
            return cancelForm(model, current, ItalianFormats.trim(reason), e.detail());
        }
        redirect.addFlashAttribute("flash", "Ordine " + order.displayCode() + " annullato.");
        return "redirect:" + BackofficeRoutes.order(order.id());
    }

    private String formPage(
            Model model,
            OrderView order,
            OrderForm.Model form,
            Map<String, String> extra,
            Integer version,
            String formKey,
            String error) {
        model.addAttribute("order", order);
        model.addAttribute("form", form);
        model.addAttribute("extra", extra);
        model.addAttribute("version", version);
        model.addAttribute("formKey", formKey);
        model.addAttribute("error", error);
        if (!model.containsAttribute("customerError")) {
            model.addAttribute("customerError", null);
        }
        model.addAttribute("services", catalog.activeServices());
        model.addAttribute("kinds", LineKind.values());
        Long bookOwner = order == null ? id(extra.get("cliente")) : Long.valueOf(order.customerId());
        model.addAttribute("addressBook", addressBook.entries(bookOwner));
        model.addAttribute("addressBookForStaff", true);
        model.addAttribute("addressBookEnabled", addressBook.enabled());
        if (order == null) {
            model.addAttribute(
                    "customers",
                    customers.listAll().stream().filter(CustomerView::canOrder).toList());
        } else {
            model.addAttribute("customer", customers.find(order.customerId()).orElse(null));
        }
        return "backoffice/order-form";
    }

    /** ENH-07 «Salva il destinatario nella rubrica del cliente», after the order was saved. */
    private void rememberIfAsked(
            OrderForm.Model typed,
            long customerId,
            Authentication authentication,
            OrderForm.Submission submission,
            RedirectAttributes redirect) {
        if (!addressBook.asked(typed)) {
            return;
        }
        String notSaved = addressBook.remember(
                customerId,
                AddressBookService.Actor.staff(StaffSession.of(authentication).requireId()),
                submission.content().recipient());
        if (notSaved != null) {
            redirect.addFlashAttribute("flashError", notSaved);
        }
    }

    private String cancelForm(Model model, OrderView order, String reason, String error) {
        model.addAttribute("order", order);
        model.addAttribute("reason", reason);
        model.addAttribute("error", error);
        return "backoffice/order-cancel";
    }

    /** A pending order for every staff member, an approved one for an ADMIN (chapter 08 §2.4). */
    static boolean changeable(OrderView order, StaffSession staff) {
        return order.isPending() || (order.isApproved() && staff.admin());
    }

    private static String closed(OrderView order, StaffSession staff, RedirectAttributes redirect) {
        redirect.addFlashAttribute(
                "flashError",
                order.isApproved() && !staff.admin()
                        ? "Solo un amministratore può modificare o annullare un ordine già confermato."
                        : "L'ordine è chiuso: non si può più modificare né annullare.");
        return "redirect:" + BackofficeRoutes.order(order.id());
    }

    private static Map<String, String> approval(OrderView order) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("codice", order.code() == null ? "" : order.code());
        values.put(
                "importo",
                order.amount() == null
                        ? ""
                        : ItalianFormats.formatNumber(order.amount(), 2).replace(".", ""));
        return values;
    }

    private OrderView order(long id) {
        return orders.find(id).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    private static Long id(String value) {
        String raw = ItalianFormats.trim(value);
        return raw.matches("^[0-9]{1,18}$") ? Long.valueOf(raw) : null;
    }

    private static String formKey(Map<String, String> form) {
        String raw = ItalianFormats.trim(form.get(FORM_KEY));
        try {
            return UUID.fromString(raw).toString();
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID().toString();
        }
    }

    /** The version a page was built on; -1 (always stale) when missing or malformed. */
    static int version(String value) {
        String raw = ItalianFormats.trim(value);
        return raw.matches("^[0-9]{1,9}$") ? Integer.parseInt(raw) : -1;
    }
}
