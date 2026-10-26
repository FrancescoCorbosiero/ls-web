package it.logisticasanchez.web.quotes;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.orders.OrderDraft;
import it.logisticasanchez.web.orders.OrderForm;
import it.logisticasanchez.web.orders.OrderRoutes;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
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
 * «Ordina il preventivo» (ENH-20, ADR 0037), in the customer area: the quote's lines and service
 * as they were priced (not editable), and the fields of an order the quote does not know — the
 * transport document, the packages, the notes and the recipient (the destination's city and CAP
 * filled in). Saving makes the pending order (CUS-04 rules) and marks the quote ordered. Only the
 * customer the quote is for sees it; a quote no longer open shows why.
 */
@Controller
public class QuoteAcceptController {

    private final QuoteService quotes;
    private final OrderForm forms;
    private final CustomerService customers;
    private final FeatureFlags flags;

    public QuoteAcceptController(QuoteService quotes, OrderForm forms, CustomerService customers, FeatureFlags flags) {
        this.quotes = quotes;
        this.forms = forms;
        this.customers = customers;
        this.flags = flags;
    }

    @GetMapping(QuoteRoutes.ACCEPT)
    public String form(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        QuoteView quote = quote(id, principal);
        OrderDraft draft = lines(quote);
        draft.setDetails("", "", quote.accessoryServiceId(), "");
        draft.setRecipient(new OrderDraft.Recipient(
                "", "", "", nullToEmpty(quote.recipientCity()), nullToEmpty(quote.recipientPostalCode()), "", ""));
        return page(model, principal, quote, forms.of(draft), null);
    }

    @PostMapping(QuoteRoutes.ACCEPT)
    public String order(
            @PathVariable("id") long id,
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        QuoteView quote = quote(id, principal);
        // the lines and the service are the quote's, whatever the request carries
        OrderDraft draft = lines(quote);
        draft.setDetails(
                ItalianFormats.trim(form.get("transportDocumentCode")),
                ItalianFormats.trim(form.get("packageCount")),
                quote.accessoryServiceId(),
                ItalianFormats.trim(form.get("notes")));
        draft.setRecipient(new OrderDraft.Recipient(
                ItalianFormats.trim(form.get("name")),
                ItalianFormats.trim(form.get("addressLine")),
                ItalianFormats.trim(form.get("province")),
                ItalianFormats.trim(form.get("city")),
                ItalianFormats.trim(form.get("postalCode")),
                ItalianFormats.trim(form.get("phone")),
                ItalianFormats.trim(form.get("email"))));
        OrderForm.Submission submission = forms.validate(forms.of(draft));
        if (!submission.valid()) {
            return page(model, principal, quote, submission.model(), null);
        }
        OrderView order;
        try {
            order = quotes.accept(id, principal.customerId(), principal.customerUserId(), submission.content());
        } catch (RuleViolationException e) {
            return page(model, principal, quote, submission.model(), e.detail());
        }
        redirect.addFlashAttribute(
                "flash",
                "Ordine " + order.tempCode() + " inviato dal preventivo " + quote.code()
                        + ": lo confermiamo con codice e importo.");
        return "redirect:" + OrderRoutes.LIST + order.displayCode() + "/";
    }

    private String page(Model model, CustomerPrincipal principal, QuoteView quote, OrderForm.Model form, String error) {
        model.addAttribute("email", principal.email());
        model.addAttribute("customer", customers.find(principal.customerId()).orElseThrow());
        model.addAttribute("quote", quote);
        model.addAttribute("open", quote.open(quotes.today()));
        model.addAttribute("closed", quote.open(quotes.today()) ? null : quotes.closedMessage(quote));
        model.addAttribute("form", form);
        model.addAttribute("error", error);
        model.addAttribute("addressBook", null);
        return "quotes/accept";
    }

    /** The customer's own quote, while the flag is on; anything else is a 404. */
    private QuoteView quote(long id, CustomerPrincipal principal) {
        if (!flags.isEnabled(FlagKey.QUOTES_SAVED)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        return quotes.find(id)
                .filter(q -> q.customerId() == principal.customerId())
                .orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    private static OrderDraft lines(QuoteView quote) {
        OrderDraft draft = new OrderDraft();
        for (QuoteView.Line line : quote.lines()) {
            draft.addLine(
                    line.kind(), line.heightCm(), line.lengthCm(), line.widthCm(), line.weightKg(), line.quantity());
        }
        return draft;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
