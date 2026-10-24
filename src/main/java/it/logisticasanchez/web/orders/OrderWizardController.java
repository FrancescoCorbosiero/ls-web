package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The new-order wizard (CUS-04; chapter 03 §6 copy, fixes OP-01…07, OP-12…16, OP-23, OP-25): the
 * lines and order fields («Inserisci i dati dell'ordine», «Aggiungi Pallet/Colli», live totals
 * recomputed from the lines), the recipient («Inserisci i dati del destinatario»), the summary,
 * the confirmation and the final screen with the legacy wording («Logistica Sanchez», Q-37). The
 * draft lives in the session; its idempotency key makes a repeated confirmation harmless. Only
 * an ACTIVE customer gets in (CUS-03). Server-rendered forms: everything works without JavaScript.
 */
@Controller
public class OrderWizardController {

    static final String SUBMITTED_CODE = "submittedCode";

    private final OrderService orders;
    private final OrderValidator validator;
    private final CustomerService customers;
    private final TrackingCatalog catalog;

    public OrderWizardController(
            OrderService orders, OrderValidator validator, CustomerService customers, TrackingCatalog catalog) {
        this.orders = orders;
        this.validator = validator;
        this.customers = customers;
        this.catalog = catalog;
    }

    @GetMapping(OrderRoutes.NEW)
    public String lines(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model) {
        CustomerView customer = customerOrNull(authentication, model);
        if (customer == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        OrderDraft draft = draft(session);
        return linesPage(model, draft, Map.of(), Map.of(), detailValues(draft), Map.of());
    }

    @PostMapping(OrderRoutes.NEW)
    public String linesAction(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model) {
        CustomerView customer = customerOrNull(authentication, model);
        if (customer == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        OrderDraft draft = draft(session);
        String action = form.getOrDefault("action", "next");
        // the order fields travel with every action so that nothing typed is lost
        Map<String, String> details = new LinkedHashMap<>();
        for (String field : List.of("transportDocumentCode", "packageCount", "accessoryServiceId", "notes")) {
            details.put(field, ItalianFormats.trim(form.get(field)));
        }
        if (validator.isService(details.get("accessoryServiceId"))) {
            draft.setDetails(
                    details.get("transportDocumentCode"),
                    details.get("packageCount"),
                    validator.storedServiceId(details.get("accessoryServiceId")),
                    details.get("notes"));
        }
        switch (action) {
            case "addLine" -> {
                OrderValidator.LineResult result = validator.line(form);
                if (!result.valid()) {
                    Map<String, String> lineValues = new LinkedHashMap<>();
                    for (String field : List.of("kind", "heightCm", "lengthCm", "widthCm", "weightKg", "quantity")) {
                        lineValues.put(field, ItalianFormats.trim(form.get(field)));
                    }
                    return linesPage(model, draft, lineValues, result.errors(), details, Map.of());
                }
                OrderDraft.Line line = result.line();
                draft.addLine(
                        line.kind(),
                        line.heightCm(),
                        line.lengthCm(),
                        line.widthCm(),
                        line.weightKg(),
                        line.quantity());
                return "redirect:" + OrderRoutes.NEW + "#righe";
            }
            case "removeLine" -> {
                String id = form.getOrDefault("lineId", "");
                if (id.matches("^[0-9]{1,9}$")) {
                    draft.removeLine(Long.parseLong(id));
                }
                return "redirect:" + OrderRoutes.NEW + "#righe";
            }
            default -> {
                Map<String, String> errors = validator.details(details, draft.lines());
                if (!errors.isEmpty()) {
                    return linesPage(model, draft, Map.of(), Map.of(), details, errors);
                }
                return "redirect:" + OrderRoutes.NEW_RECIPIENT;
            }
        }
    }

    @GetMapping(OrderRoutes.NEW_RECIPIENT)
    public String recipient(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model) {
        CustomerView customer = customerOrNull(authentication, model);
        if (customer == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        OrderDraft draft = draft(session);
        if (draft.lines().isEmpty()) {
            return "redirect:" + OrderRoutes.NEW;
        }
        Map<String, String> values = new LinkedHashMap<>();
        OrderDraft.Recipient r = draft.recipient();
        if (r != null) {
            values.put("name", r.name());
            values.put("addressLine", r.addressLine());
            values.put("province", r.province());
            values.put("city", r.city());
            values.put("postalCode", r.postalCode());
            values.put("phone", r.phone() == null ? "" : r.phone());
            values.put("email", r.email() == null ? "" : r.email());
        }
        model.addAttribute("values", values);
        model.addAttribute("errors", Map.of());
        return "orders/wizard-recipient";
    }

    @PostMapping(OrderRoutes.NEW_RECIPIENT)
    public String recipientSubmit(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model) {
        CustomerView customer = customerOrNull(authentication, model);
        if (customer == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        OrderDraft draft = draft(session);
        OrderValidator.RecipientResult result = validator.recipient(form);
        if (!result.valid()) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String field : List.of("name", "addressLine", "province", "city", "postalCode", "phone", "email")) {
                values.put(field, ItalianFormats.trim(form.get(field)));
            }
            model.addAttribute("values", values);
            model.addAttribute("errors", result.errors());
            return "orders/wizard-recipient";
        }
        draft.setRecipient(result.recipient());
        return "redirect:" + OrderRoutes.NEW_REVIEW;
    }

    @GetMapping(OrderRoutes.NEW_REVIEW)
    public String review(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model) {
        CustomerView customer = customerOrNull(authentication, model);
        if (customer == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        OrderDraft draft = draft(session);
        if (!draft.isComplete()) {
            return "redirect:" + (draft.lines().isEmpty() ? OrderRoutes.NEW : OrderRoutes.NEW_RECIPIENT);
        }
        model.addAttribute("draft", draft);
        model.addAttribute("serviceLabel", serviceLabel(draft.accessoryServiceId()));
        model.addAttribute("warnings", validator.warnings(draft.lines()));
        return "orders/wizard-review";
    }

    @PostMapping(OrderRoutes.NEW_REVIEW)
    public String confirm(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpSession session,
            Model model,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        CustomerView customer = customerOrNull(authentication, model);
        if (customer == null) {
            return "redirect:" + OrderRoutes.LIST;
        }
        OrderDraft draft = draft(session);
        if (!draft.isComplete()) {
            return "redirect:" + OrderRoutes.NEW;
        }
        OrderView order = orders.submit(principal.customerId(), principal.customerUserId(), draft);
        session.removeAttribute(OrderDraft.SESSION_KEY);
        redirect.addFlashAttribute(SUBMITTED_CODE, order.displayCode());
        return "redirect:" + OrderRoutes.NEW_DONE;
    }

    /** The final screen (chapter 03 §6.7 wording, brand corrected): terminal, «Indietro» leads nowhere (OP-02). */
    @GetMapping(OrderRoutes.NEW_DONE)
    public String done(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication, Model model) {
        if (customerOrNull(authentication, model) == null || !model.containsAttribute(SUBMITTED_CODE)) {
            return "redirect:" + OrderRoutes.LIST;
        }
        return "orders/wizard-done";
    }

    private String linesPage(
            Model model,
            OrderDraft draft,
            Map<String, String> lineValues,
            Map<String, String> lineErrors,
            Map<String, String> details,
            Map<String, String> errors) {
        model.addAttribute("draft", draft);
        model.addAttribute("lineValues", lineValues);
        model.addAttribute("lineErrors", lineErrors);
        model.addAttribute("values", details);
        model.addAttribute("errors", errors);
        model.addAttribute("services", catalog.activeServices());
        model.addAttribute("kinds", LineKind.values());
        return "orders/wizard-lines";
    }

    private static Map<String, String> detailValues(OrderDraft draft) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("transportDocumentCode", draft.transportDocumentCode());
        values.put("packageCount", draft.packageCount());
        values.put(
                "accessoryServiceId",
                draft.accessoryServiceId() == null
                        ? ""
                        : draft.accessoryServiceId().toString());
        values.put("notes", draft.notes());
        return values;
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
