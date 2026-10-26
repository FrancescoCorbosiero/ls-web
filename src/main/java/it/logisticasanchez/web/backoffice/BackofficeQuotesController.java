package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.orders.LineKind;
import it.logisticasanchez.web.orders.OrderForm;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.quotes.QuotePdf;
import it.logisticasanchez.web.quotes.QuoteService;
import it.logisticasanchez.web.quotes.QuoteView;
import it.logisticasanchez.web.site.forms.LeadService;
import it.logisticasanchez.web.site.forms.LeadView;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Saved quotes in the back office (ENH-20, flag {@code quotes.saved}, ADR 0037): «Prepara
 * preventivo» on a website request whose customer is linked — the lines (the order form's rows,
 * filled from the request), the amount, the last valid day (30 days by default), the accessory
 * service, the destination and a note for the customer; «Invia» saves it and e-mails the link.
 * «Ritira» takes back a quote not yet ordered; staff download its PDF. A 404 while the flag is off.
 */
@Controller
public class BackofficeQuotesController {

    /** The validity «Prepara preventivo» proposes. */
    static final int DEFAULT_VALID_DAYS = 30;

    static final List<String> FIELDS =
            List.of("amount", "validUntil", "accessoryServiceId", "recipientCity", "recipientPostalCode", "note");

    private final QuoteService quotes;
    private final QuotePdf pdf;
    private final LeadService leads;
    private final CustomerService customers;
    private final OrderForm forms;
    private final TrackingCatalog catalog;
    private final FeatureFlags flags;

    public BackofficeQuotesController(
            QuoteService quotes,
            QuotePdf pdf,
            LeadService leads,
            CustomerService customers,
            OrderForm forms,
            TrackingCatalog catalog,
            FeatureFlags flags) {
        this.quotes = quotes;
        this.pdf = pdf;
        this.leads = leads;
        this.customers = customers;
        this.forms = forms;
        this.catalog = catalog;
        this.flags = flags;
    }

    @GetMapping(BackofficeRoutes.LEAD_QUOTE)
    public String form(@PathVariable("id") long id, Model model) {
        requireFlag();
        LeadView lead = lead(id);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("amount", "");
        values.put("validUntil", quotes.today().plusDays(DEFAULT_VALID_DAYS).toString());
        Integer service = LeadDrafts.accessoryService(lead, catalog.activeServices());
        values.put("accessoryServiceId", service == null ? "" : service.toString());
        values.put("recipientCity", lead.deliveryCity() == null ? "" : lead.deliveryCity());
        values.put("recipientPostalCode", lead.deliveryPostalCode() == null ? "" : lead.deliveryPostalCode());
        values.put("note", "");
        return page(model, lead, forms.prefilled(LeadDrafts.line(lead), null, null, null), values, null);
    }

    @PostMapping(BackofficeRoutes.LEAD_QUOTE)
    public String save(
            @PathVariable("id") long id,
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        requireFlag();
        LeadView lead = lead(id);
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : FIELDS) {
            values.put(field, ItalianFormats.trim(form.get(field)));
        }
        OrderForm.Model typed = forms.read(form);
        switch (forms.action(form)) {
            case ADD_LINE -> {
                return page(model, lead, forms.withRowAdded(typed), values, null);
            }
            case REMOVE_LINE -> {
                return page(model, lead, forms.withRowRemoved(typed, forms.removedIndex(form)), values, null);
            }
            default -> {
                OrderForm.LinesSubmission lines = forms.validateLines(typed);
                if (!lines.valid()) {
                    return page(model, lead, lines.model(), values, null);
                }
                QuoteView quote;
                try {
                    quote = quotes.create(
                            id,
                            StaffSession.of(authentication).requireId(),
                            new QuoteService.Draft(
                                    lines.lines(),
                                    values.get("amount"),
                                    date(values.get("validUntil")),
                                    OrderFilters.integer(values.get("accessoryServiceId"))
                                            .orElse(null),
                                    values.get("recipientCity"),
                                    values.get("recipientPostalCode"),
                                    values.get("note")));
                } catch (RuleViolationException e) {
                    return page(model, lead, lines.model(), values, e.detail());
                }
                CustomerView customer = customers.find(quote.customerId()).orElseThrow();
                redirect.addFlashAttribute(
                        "flash",
                        "Preventivo " + quote.code() + " inviato a " + customer.email()
                                + ": il cliente lo trova nel link dell'e-mail e in «I miei ordini».");
                return "redirect:" + BackofficeRoutes.lead(id);
            }
        }
    }

    @PostMapping(BackofficeRoutes.QUOTE_WITHDRAW)
    public String withdraw(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        requireFlag();
        QuoteView quote = quote(id);
        try {
            quotes.withdraw(id, StaffSession.of(authentication).requireId());
            redirect.addFlashAttribute(
                    "flash", "Preventivo " + quote.code() + " ritirato: il link ora dice che non è più valido.");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("flashError", e.detail());
        }
        return "redirect:" + BackofficeRoutes.lead(quote.leadId());
    }

    @GetMapping(BackofficeRoutes.QUOTE_PDF)
    public ResponseEntity<byte[]> pdf(@PathVariable("id") long id) {
        requireFlag();
        QuoteView quote = quote(id);
        CustomerView customer = customers.find(quote.customerId()).orElseThrow();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename("preventivo-" + quote.code() + ".pdf")
                                .build()
                                .toString())
                .body(pdf.render(quote, customer));
    }

    private String page(Model model, LeadView lead, OrderForm.Model form, Map<String, String> values, String error) {
        model.addAttribute("lead", lead);
        model.addAttribute(
                "customer",
                lead.customerId() == null
                        ? null
                        : customers.find(lead.customerId()).orElse(null));
        model.addAttribute("form", form);
        model.addAttribute("values", values);
        model.addAttribute("error", error);
        model.addAttribute("services", catalog.activeServices());
        model.addAttribute("kinds", LineKind.values());
        return "backoffice/quote-form";
    }

    private static LocalDate date(String value) {
        try {
            return value == null || value.isEmpty() ? null : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private LeadView lead(long id) {
        return leads.find(id).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    private QuoteView quote(long id) {
        return quotes.find(id).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    private void requireFlag() {
        if (!flags.isEnabled(FlagKey.QUOTES_SAVED) || !flags.isEnabled(FlagKey.LEADS_INBOX)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
    }
}
