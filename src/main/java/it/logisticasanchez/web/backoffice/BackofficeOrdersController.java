package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderStatus;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import java.util.Map;
import java.util.Optional;
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
 * The order screens of the back office (ADM-04…ADM-08; chapter 04 §3.4–3.5, §4.4–4.8 columns and
 * copy; ADR 0017, ADR 0029): «In Approvazione» (temporary codes shown as «-») with «Approva ordine»
 * (fields «Codice» and «Importo Totale (per cliente)», Italian comma) and the decline page, both
 * on the version of the order the page showed (ADR 0028); «Ordini»
 * with server-side pagination, filters (state, tracking, customer, dates) and search by code — or,
 * with the office tools on ({@code admin.dashboard}, ADR 0035), by any text of {@link OrderFilters};
 * the detail with the three legacy sections, the lines grouped with their quantity, the tracking
 * form (current state preselected, any of the six), the internal note beside the customer's
 * read-only note and the history; «Tracking» by code. Rules and e-mails live in
 * {@link OrderService}; a refused rule is shown on the page that sent the form.
 */
@Controller
public class BackofficeOrdersController {

    static final int PAGE_SIZE = 25;

    private final OrderService orders;
    private final CustomerService customers;
    private final TrackingCatalog catalog;

    private final FeatureFlags flags;

    public BackofficeOrdersController(
            OrderService orders, CustomerService customers, TrackingCatalog catalog, FeatureFlags flags) {
        this.orders = orders;
        this.customers = customers;
        this.catalog = catalog;
        this.flags = flags;
    }

    @GetMapping(BackofficeRoutes.ORDERS_PENDING)
    public String pending(Model model) {
        model.addAttribute("orders", orders.pendingApproval());
        return "backoffice/orders-pending";
    }

    @GetMapping(BackofficeRoutes.ORDERS)
    public String list(@RequestParam Map<String, String> params, Model model) {
        boolean officeTools = flags.isEnabled(FlagKey.ADMIN_DASHBOARD); // ADR 0035: free-text search
        OrderFilters filters = OrderFilters.of(params, officeTools);
        int page = integer(params.get("pagina"))
                .filter(n -> n >= 1)
                .map(n -> n - 1)
                .orElse(0);
        model.addAttribute("filters", filters.values());
        model.addAttribute("error", filters.error());
        model.addAttribute("page", orders.search(filters.search(), page, PAGE_SIZE));
        model.addAttribute("freeSearch", officeTools);
        model.addAttribute("exportEnabled", flags.isEnabled(FlagKey.ADMIN_EXPORTS)); // ENH-08
        model.addAttribute("states", catalog.states());
        model.addAttribute("customers", customers.listAll());
        model.addAttribute("statuses", OrderStatus.values());
        return "backoffice/orders";
    }

    @GetMapping(BackofficeRoutes.ORDER)
    public String detail(@PathVariable("id") long id, Model model) {
        return detailPage(model, order(id), null, null);
    }

    @GetMapping(BackofficeRoutes.ORDER_APPROVE)
    public String approvePage(@PathVariable("id") long id, Model model) {
        return approveForm(model, order(id), Map.of(), null);
    }

    @PostMapping(BackofficeRoutes.ORDER_APPROVE)
    public String approve(
            @PathVariable("id") long id,
            @RequestParam(name = "codice", required = false) String code,
            @RequestParam(name = "importo", required = false) String amount,
            @RequestParam(name = "versione", required = false) String version,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        OrderView order = order(id);
        Map<String, String> values =
                Map.of("codice", ItalianFormats.trim(code), "importo", ItalianFormats.trim(amount));
        try {
            OrderView approved = orders.approve(
                    order.id(), StaffSession.of(authentication).requireId(), expectedVersion(version), code, amount);
            redirect.addFlashAttribute("flash", "Ordine approvato con il codice " + approved.code() + ".");
        } catch (RuleViolationException e) {
            return approveForm(model, order(id), values, e.detail());
        }
        return "redirect:" + BackofficeRoutes.ORDERS_PENDING;
    }

    @GetMapping(BackofficeRoutes.ORDER_DECLINE)
    public String declinePage(@PathVariable("id") long id, Model model) {
        model.addAttribute("order", order(id));
        model.addAttribute("reason", "");
        model.addAttribute("error", null);
        return "backoffice/order-decline";
    }

    @PostMapping(BackofficeRoutes.ORDER_DECLINE)
    public String decline(
            @PathVariable("id") long id,
            @RequestParam(name = "motivo", required = false) String reason,
            @RequestParam(name = "versione", required = false) String version,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        OrderView order = order(id);
        try {
            if (reason != null && reason.length() > 500) {
                throw new RuleViolationException("Il motivo può avere al massimo 500 caratteri.");
            }
            orders.decline(order.id(), StaffSession.of(authentication).requireId(), expectedVersion(version), reason);
        } catch (RuleViolationException e) {
            model.addAttribute("order", order(id));
            model.addAttribute("reason", ItalianFormats.trim(reason));
            model.addAttribute("error", e.detail());
            return "backoffice/order-decline";
        }
        redirect.addFlashAttribute("flash", "Ordine " + order.tempCode() + " rifiutato.");
        return "redirect:" + BackofficeRoutes.ORDERS_PENDING;
    }

    @PostMapping(BackofficeRoutes.ORDER_TRACKING)
    public String setTracking(
            @PathVariable("id") long id,
            @RequestParam(name = "stato", required = false) String stateId,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        OrderView order = order(id);
        try {
            int state = integer(stateId).orElseThrow(() -> new RuleViolationException("Scegli uno stato di tracking."));
            OrderView updated = orders.setTrackingState(
                    order.id(), StaffSession.of(authentication).requireId(), state);
            redirect.addFlashAttribute("flash", "Tracking impostato su «" + updated.trackingLabel() + "».");
        } catch (RuleViolationException e) {
            return detailPage(model, order, e.detail(), null);
        }
        return "redirect:" + BackofficeRoutes.order(order.id());
    }

    @PostMapping(BackofficeRoutes.ORDER_NOTE)
    public String setNote(
            @PathVariable("id") long id,
            @RequestParam(name = "nota", required = false) String note,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        OrderView order = order(id);
        try {
            orders.setInternalNote(order.id(), StaffSession.of(authentication).requireId(), note);
        } catch (RuleViolationException e) {
            return detailPage(model, order, null, e.detail());
        }
        redirect.addFlashAttribute("flash", "Note aggiornate.");
        return "redirect:" + BackofficeRoutes.order(order.id());
    }

    /** «Imposta tracking» by code (chapter 04 §4.6 as a page): the form, the order found and its current state. */
    @GetMapping(BackofficeRoutes.TRACKING)
    public String trackingPage(@RequestParam(name = "codice", required = false) String code, Model model) {
        return trackingForm(model, code, null);
    }

    @PostMapping(BackofficeRoutes.TRACKING)
    public String trackingSet(
            @RequestParam(name = "codice", required = false) String code,
            @RequestParam(name = "stato", required = false) String stateId,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        Optional<OrderView> found = orders.findApprovedByCode(code);
        if (found.isEmpty()) {
            return trackingForm(model, code, "Nessun ordine approvato con questo codice.");
        }
        try {
            int state = integer(stateId).orElseThrow(() -> new RuleViolationException("Scegli uno stato di tracking."));
            OrderView updated = orders.setTrackingState(
                    found.get().id(), StaffSession.of(authentication).requireId(), state);
            redirect.addFlashAttribute(
                    "flash", "Tracking di " + updated.code() + " impostato su «" + updated.trackingLabel() + "».");
        } catch (RuleViolationException e) {
            return trackingForm(model, code, e.detail());
        }
        return "redirect:" + BackofficeRoutes.TRACKING + "?codice=" + ItalianFormats.trim(code);
    }

    private String trackingForm(Model model, String code, String error) {
        String typed = ItalianFormats.trim(code);
        model.addAttribute("codice", typed);
        model.addAttribute(
                "order",
                typed.isEmpty() ? null : orders.findApprovedByCode(typed).orElse(null));
        model.addAttribute("notFound", !typed.isEmpty() && model.getAttribute("order") == null && error == null);
        model.addAttribute("states", catalog.states());
        model.addAttribute("error", error);
        // ENH-15 (ADR 0035): the bulk form, only while orders.bulkTracking is on
        model.addAttribute("bulkEnabled", flags.isEnabled(FlagKey.ORDERS_BULK_TRACKING));
        model.addAttribute("bulkCodes", "");
        model.addAttribute("bulkState", "");
        model.addAttribute("bulkError", null);
        model.addAttribute("bulk", null);
        return "backoffice/tracking";
    }

    private String detailPage(Model model, OrderView order, String trackingError, String noteError) {
        model.addAttribute("order", order);
        model.addAttribute("changeable", BackofficeOrderChangesController.changeable(order, (StaffSession)
                model.getAttribute("staff")));
        model.addAttribute("customer", customers.find(order.customerId()).orElse(null));
        model.addAttribute("note", orders.internalNote(order.id()).orElse(""));
        model.addAttribute("history", orders.trackingHistory(order.id()));
        model.addAttribute("states", catalog.states());
        model.addAttribute("trackingError", trackingError);
        model.addAttribute("noteError", noteError);
        model.addAttribute("repeatEnabled", flags.isEnabled(FlagKey.ORDERS_REPEAT)); // ENH-07
        return "backoffice/order";
    }

    private String approveForm(Model model, OrderView order, Map<String, String> values, String error) {
        model.addAttribute("order", order);
        model.addAttribute("values", values);
        model.addAttribute("error", error);
        return "backoffice/order-approve";
    }

    private OrderView order(long id) {
        return orders.find(id).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    /** The version an approve or decline page was built on (ADR 0028): null when the form sent none, -1 when malformed. */
    private static Integer expectedVersion(String value) {
        return value == null ? null : BackofficeOrderChangesController.version(value);
    }

    private static Optional<Integer> integer(String value) {
        String raw = ItalianFormats.trim(value);
        return raw.matches("^[0-9]{1,9}$") ? Optional.of(Integer.parseInt(raw)) : Optional.empty();
    }
}
