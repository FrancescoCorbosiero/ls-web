package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.site.forms.LeadService;
import it.logisticasanchez.web.site.forms.LeadStatus;
import it.logisticasanchez.web.site.forms.LeadView;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.MessageSource;
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
 * «Richieste» (ENH-06, flag {@code leads.inbox}, ADR 0036): the quote and contact requests of the
 * website, newest first, by state (the tabs, «Nuove» first) and with a search; the page of one
 * request with its data, the moves of its state machine (chapter 08 §2.4), the customer it belongs
 * to (linked, or found by its e-mail) and the way to «Crea cliente» and «Crea ordine» from it.
 * Every route is a 404 while the flag is off.
 */
@Controller
public class BackofficeLeadsController {

    static final int PAGE_SIZE = 25;

    private final LeadService leads;
    private final CustomerService customers;
    private final OrderService orders;
    private final FeatureFlags flags;
    private final MessageSource messages;

    public BackofficeLeadsController(
            LeadService leads,
            CustomerService customers,
            OrderService orders,
            FeatureFlags flags,
            MessageSource messages) {
        this.leads = leads;
        this.customers = customers;
        this.orders = orders;
        this.flags = flags;
        this.messages = messages;
    }

    @GetMapping(BackofficeRoutes.LEADS)
    public String list(
            @RequestParam(name = "stato", required = false) String status,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "pagina", required = false) String page,
            Model model) {
        requireFlag();
        String tab = status == null ? LeadStatus.NEW.name() : ItalianFormats.trim(status);
        List<LeadStatus> statuses = Arrays.stream(LeadStatus.values())
                .filter(s -> tab.isEmpty() || s.name().equals(tab))
                .toList();
        if (statuses.isEmpty()) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        String q = query == null ? "" : query.strip();
        int number =
                OrderFilters.integer(page).filter(n -> n >= 1).map(n -> n - 1).orElse(0);
        model.addAttribute("tab", tab);
        model.addAttribute("q", q);
        model.addAttribute("statuses", LeadStatus.values());
        model.addAttribute("page", leads.inbox(statuses, q, number, PAGE_SIZE));
        return "backoffice/leads";
    }

    @GetMapping(BackofficeRoutes.LEAD)
    public String detail(@PathVariable("id") long id, Model model) {
        requireFlag();
        LeadView lead = lead(id);
        Optional<CustomerView> linked =
                lead.customerId() == null ? Optional.empty() : customers.find(lead.customerId());
        Optional<CustomerView> sameEmail = linked.isPresent() ? Optional.empty() : customers.findByEmail(lead.email());
        CustomerView customer = linked.or(() -> sameEmail).orElse(null);
        model.addAttribute("lead", lead);
        model.addAttribute("customer", customer);
        model.addAttribute("customerLinked", linked.isPresent());
        model.addAttribute(
                "order",
                lead.convertedOrderId() == null
                        ? null
                        : orders.find(lead.convertedOrderId()).orElse(null));
        model.addAttribute("canCreateCustomer", customer == null && lead.status() != LeadStatus.SPAM);
        model.addAttribute(
                "canCreateOrder",
                lead.status().open() && lead.convertedOrderId() == null && customer != null && customer.canOrder());
        return "backoffice/lead";
    }

    @PostMapping(BackofficeRoutes.LEAD_STATUS)
    public String status(
            @PathVariable("id") long id,
            @RequestParam(name = "stato", required = false) String status,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        requireFlag();
        lead(id);
        LeadStatus target = Arrays.stream(LeadStatus.values())
                .filter(s -> s.name().equals(ItalianFormats.trim(status)))
                .findFirst()
                .orElse(null);
        try {
            if (target == null) {
                throw new RuleViolationException("Scegli il nuovo stato della richiesta.");
            }
            leads.changeStatus(id, StaffSession.of(authentication).requireId(), target);
            redirect.addFlashAttribute(
                    "flash",
                    "Richiesta segnata come «"
                            + messages.getMessage("leads.status." + target.name(), null, Locale.ITALIAN) + "».");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("flashError", e.detail());
        }
        return "redirect:" + BackofficeRoutes.lead(id);
    }

    /** The request belongs to the customer registered with its e-mail. */
    @PostMapping(BackofficeRoutes.LEAD_LINK)
    public String link(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        requireFlag();
        LeadView lead = lead(id);
        Optional<CustomerView> customer = customers.findByEmail(lead.email());
        if (customer.isEmpty()) {
            redirect.addFlashAttribute("flashError", "Nessun cliente registrato con l'e-mail della richiesta.");
        } else {
            leads.linkCustomer(
                    id,
                    StaffSession.of(authentication).requireId(),
                    customer.get().id());
            redirect.addFlashAttribute(
                    "flash", "Richiesta collegata al cliente «" + customer.get().companyName() + "».");
        }
        return "redirect:" + BackofficeRoutes.lead(id);
    }

    private LeadView lead(long id) {
        return leads.find(id).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    private void requireFlag() {
        if (!flags.isEnabled(FlagKey.LEADS_INBOX)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
    }
}
