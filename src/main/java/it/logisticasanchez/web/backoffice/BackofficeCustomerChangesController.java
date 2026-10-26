package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.customers.RegistrationValidator;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.site.forms.LeadService;
import it.logisticasanchez.web.site.forms.LeadView;
import java.util.LinkedHashMap;
import java.util.List;
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
 * ADM-12 (owner decision of 2026-10-25; ADR 0028): staff create a customer who asked by phone or
 * e-mail («Nuovo cliente»: active at once, optionally with the welcome e-mail EML-03) and correct
 * a customer's registration data («Modifica dati») with the rules of the registration form
 * (CUS-02, {@link RegistrationValidator}). The login e-mail is never changed here (CUS-06); an
 * anonymised customer cannot be edited; the page carries the version it showed. ENH-06 (ADR 0036):
 * with {@code leads.inbox} on, «Crea cliente» of a website request opens «Nuovo cliente» filled
 * from it ({@code ?richiesta=<id>}) and the new customer is linked to the request.
 */
@Controller
public class BackofficeCustomerChangesController {

    static final List<String> FIELDS =
            List.of("companyName", "addressLine", "province", "city", "postalCode", "phone", "vatNumber", "taxCode");

    private final CustomerService customers;
    private final RegistrationValidator validator;
    private final LeadService leads;
    private final FeatureFlags flags;

    public BackofficeCustomerChangesController(
            CustomerService customers, RegistrationValidator validator, LeadService leads, FeatureFlags flags) {
        this.customers = customers;
        this.validator = validator;
        this.leads = leads;
        this.flags = flags;
    }

    @GetMapping(BackofficeRoutes.CUSTOMER_NEW)
    public String newCustomer(@RequestParam(name = "richiesta", required = false) String request, Model model) {
        Map<String, String> values = blank();
        values.put("email", "");
        values.put("benvenuto", "on");
        Optional<LeadView> lead = lead(request);
        if (lead.isPresent()) {
            values.putAll(LeadDrafts.customer(lead.get()));
            values.put("email", ItalianFormats.normaliseEmail(lead.get().email()));
            values.put("richiesta", Long.toString(lead.get().id()));
        }
        return formPage(model, null, values, Map.of(), null);
    }

    @PostMapping(BackofficeRoutes.CUSTOMER_NEW)
    public String create(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        Map<String, String> values = typed(form);
        values.put("email", ItalianFormats.normaliseEmail(form.get("email")));
        values.put("benvenuto", form.containsKey("benvenuto") ? "on" : "");
        Optional<LeadView> lead = lead(form.get("richiesta"));
        lead.ifPresent(l -> values.put("richiesta", Long.toString(l.id())));
        RegistrationValidator.Result result = validator.validate(form);
        Map<String, String> errors = new LinkedHashMap<>(result.errors());
        if (values.get("email").isEmpty()) {
            errors.put("email", "customers.error.required");
        } else if (!ItalianFormats.isEmail(values.get("email"))) {
            errors.put("email", "customers.error.email");
        }
        if (!errors.isEmpty()) {
            return formPage(model, null, values, errors, null);
        }
        CustomerView created;
        try {
            created = customers.createByStaff(
                    StaffSession.of(authentication).requireId(),
                    values.get("email"),
                    result.data(),
                    !values.get("benvenuto").isEmpty());
        } catch (RuleViolationException e) {
            return formPage(model, null, values, Map.of(), e.detail());
        }
        String done = "Cliente «" + created.companyName() + "» creato e attivo"
                + (values.get("benvenuto").isEmpty() ? "." : ": gli abbiamo inviato l'e-mail di benvenuto.");
        if (lead.isPresent()) {
            // ENH-06: back to the request, where «Crea ordine» is now offered
            leads.linkCustomer(lead.get().id(), StaffSession.of(authentication).requireId(), created.id());
            redirect.addFlashAttribute("flash", done + " La richiesta è collegata al nuovo cliente.");
            return "redirect:" + BackofficeRoutes.lead(lead.get().id());
        }
        redirect.addFlashAttribute("flash", done);
        return "redirect:" + BackofficeRoutes.customer(created.id());
    }

    @GetMapping(BackofficeRoutes.CUSTOMER_EDIT)
    public String edit(@PathVariable("id") long id, Model model, RedirectAttributes redirect) {
        CustomerView customer = customer(id);
        if (customer.anonymised()) {
            return anonymised(customer, redirect);
        }
        return formPage(model, customer, values(customer), Map.of(), null);
    }

    @PostMapping(BackofficeRoutes.CUSTOMER_EDIT)
    public String update(
            @PathVariable("id") long id,
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerView customer = customer(id);
        if (customer.anonymised()) {
            return anonymised(customer, redirect);
        }
        int version = BackofficeOrderChangesController.version(form.get("versione"));
        Map<String, String> values = typed(form);
        RegistrationValidator.Result result = validator.validate(form);
        if (!result.valid()) {
            model.addAttribute("version", version);
            return formPage(model, customer, values, result.errors(), null);
        }
        try {
            customers.update(customer.id(), StaffSession.of(authentication).requireId(), version, result.data());
        } catch (RuleViolationException e) {
            CustomerView current = customer(id);
            return formPage(model, current, values(current), Map.of(), e.detail());
        }
        redirect.addFlashAttribute(
                "flash", "Dati del cliente aggiornati. Gli ordini già inviati mantengono i dati di allora.");
        return "redirect:" + BackofficeRoutes.customer(customer.id());
    }

    private String formPage(
            Model model, CustomerView customer, Map<String, String> values, Map<String, String> errors, String error) {
        model.addAttribute("customer", customer);
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        model.addAttribute("error", error);
        if (!model.containsAttribute("version")) {
            model.addAttribute("version", customer == null ? null : customer.version());
        }
        return "backoffice/customer-form";
    }

    /** ENH-06: the website request named by {@code ?richiesta=}, while {@code leads.inbox} is on. */
    private Optional<LeadView> lead(String id) {
        String raw = ItalianFormats.trim(id);
        if (!flags.isEnabled(FlagKey.LEADS_INBOX) || !raw.matches("^[0-9]{1,18}$")) {
            return Optional.empty();
        }
        return leads.find(Long.parseLong(raw));
    }

    private static String anonymised(CustomerView customer, RedirectAttributes redirect) {
        redirect.addFlashAttribute("flashError", "I dati di un cliente anonimizzato non si modificano.");
        return "redirect:" + BackofficeRoutes.customer(customer.id());
    }

    private CustomerView customer(long id) {
        return customers.find(id).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    private static Map<String, String> blank() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : FIELDS) {
            values.put(field, "");
        }
        return values;
    }

    private static Map<String, String> typed(Map<String, String> form) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : FIELDS) {
            values.put(field, ItalianFormats.trim(form.get(field)));
        }
        return values;
    }

    private static Map<String, String> values(CustomerView c) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("companyName", c.companyName());
        values.put("addressLine", c.addressLine());
        values.put("province", c.province());
        values.put("city", c.city());
        values.put("postalCode", c.postalCode());
        values.put("phone", c.phone());
        values.put("vatNumber", c.vatNumber() == null ? "" : c.vatNumber());
        values.put("taxCode", c.taxCode() == null ? "" : c.taxCode());
        return values;
    }
}
