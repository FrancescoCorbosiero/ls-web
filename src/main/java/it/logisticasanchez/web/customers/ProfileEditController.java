package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.util.ArrayList;
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
 * «Modifica i tuoi dati» (ENH-16, flag {@code customers.profileEdit}; ADR 0038): an ACTIVE
 * customer changes the registration fields from the profile — the address and the phone at once,
 * the company name, VAT number and tax code with the office's approval
 * ({@link CustomerChangeService}) — and can take a change waiting for approval back. While a
 * change waits, the form shows the values asked for. Every route answers 404 while the flag is
 * off (ADR 0025); the form is a 404 for a customer that is not ACTIVE too, whose profile keeps the
 * M1 «Chiedi una modifica».
 */
@Controller
public class ProfileEditController {

    /** The inputs of the form: the registration fields (CUS-02). */
    static final List<String> FIELDS =
            List.of("companyName", "addressLine", "province", "city", "postalCode", "phone", "vatNumber", "taxCode");

    private final CustomerService customers;
    private final CustomerChangeService changes;
    private final RegistrationValidator validator;
    private final FeatureFlags flags;

    public ProfileEditController(
            CustomerService customers,
            CustomerChangeService changes,
            RegistrationValidator validator,
            FeatureFlags flags) {
        this.customers = customers;
        this.changes = changes;
        this.validator = validator;
        this.flags = flags;
    }

    @GetMapping(CustomerRoutes.PROFILE_DATA)
    public String form(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication, Model model) {
        CustomerView customer = activeCustomer(authentication, model);
        ChangeRequestView pending = changes.pending(customer.id()).orElse(null);
        return formPage(model, customer, values(customer, pending), Map.of(), customer.version(), pending);
    }

    @PostMapping(CustomerRoutes.PROFILE_DATA)
    public String save(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerView customer = activeCustomer(authentication, model);
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        int version = version(form.get("versione"));
        RegistrationValidator.Result result = validator.validate(form);
        if (!result.valid()) {
            return formPage(
                    model,
                    customer,
                    typed(form),
                    result.errors(),
                    version,
                    changes.pending(customer.id()).orElse(null));
        }
        CustomerChangeService.Saved saved;
        try {
            saved = changes.save(customer.id(), principal.customerUserId(), version, result.data());
        } catch (RuleViolationException e) {
            CustomerView current = customers.find(customer.id()).orElseThrow();
            ChangeRequestView pending = changes.pending(current.id()).orElse(null);
            model.addAttribute("error", e.detail());
            return formPage(model, current, values(current, pending), Map.of(), current.version(), pending);
        }
        redirect.addFlashAttribute("flash", message(saved));
        return "redirect:" + CustomerRoutes.PROFILE;
    }

    @PostMapping(CustomerRoutes.PROFILE_DATA_WITHDRAW)
    public String withdraw(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = requireFlag(authentication);
        boolean withdrawn = changes.withdraw(principal.customerId(), principal.customerUserId());
        redirect.addFlashAttribute(
                "flash",
                withdrawn
                        ? "Hai ritirato la richiesta di modifica: i tuoi dati restano quelli di prima."
                        : "Non c'è nessuna richiesta di modifica in attesa.");
        return "redirect:" + CustomerRoutes.PROFILE;
    }

    /** The flash after a save: what changed now, what waits for the office. */
    static String message(CustomerChangeService.Saved saved) {
        if (saved.nothing()) {
            return "Non hai cambiato nessun dato.";
        }
        List<String> parts = new ArrayList<>();
        if (saved.applied()) {
            parts.add("Dati salvati: valgono per i prossimi ordini.");
        }
        if (saved.requested()) {
            parts.add("La modifica di ragione sociale, partita IVA o codice fiscale è stata inviata al nostro ufficio:"
                    + " vale quando la approviamo e ti avvisiamo via e-mail.");
        }
        if (saved.withdrawn()) {
            parts.add("La richiesta di modifica in attesa è stata ritirata.");
        }
        return String.join(" ", parts);
    }

    private String formPage(
            Model model,
            CustomerView customer,
            Map<String, String> values,
            Map<String, String> errors,
            int version,
            ChangeRequestView pending) {
        Map<String, ChangeRequestView.Field> pendingFields = new LinkedHashMap<>();
        if (pending != null) {
            pending.changes().forEach(f -> pendingFields.put(f.name(), f));
        }
        model.addAttribute("customer", customer);
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        model.addAttribute("version", version);
        model.addAttribute("pendingFields", pendingFields);
        if (!model.containsAttribute("error")) {
            model.addAttribute("error", null);
        }
        return "customers/profile-form";
    }

    private CustomerPrincipal requireFlag(Authentication authentication) {
        if (!flags.isEnabled(FlagKey.CUSTOMERS_PROFILE_EDIT)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        return CustomerSessionService.require(authentication);
    }

    /** The signed-in ACTIVE customer, after the flag check (404 while off or for another state). */
    private CustomerView activeCustomer(Authentication authentication, Model model) {
        CustomerPrincipal principal = requireFlag(authentication);
        CustomerView customer = customers.find(principal.customerId()).orElseThrow();
        if (!customer.canOrder()) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        model.addAttribute("email", principal.email());
        return customer;
    }

    private static int version(String raw) {
        String value = ItalianFormats.trim(raw);
        return value.matches("^[0-9]{1,9}$") ? Integer.parseInt(value) : -1;
    }

    private static Map<String, String> typed(Map<String, String> form) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : FIELDS) {
            values.put(field, ItalianFormats.trim(form.get(field)));
        }
        return values;
    }

    /** The customer's data, with the values asked for in place of the present ones while a change waits. */
    private static Map<String, String> values(CustomerView c, ChangeRequestView pending) {
        CustomerIdentity identity = pending == null
                ? new CustomerIdentity(c.companyName(), c.vatNumber(), c.taxCode())
                : pending.requested();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("companyName", identity.companyName());
        values.put("addressLine", c.addressLine());
        values.put("province", c.province());
        values.put("city", c.city());
        values.put("postalCode", c.postalCode());
        values.put("phone", c.phone());
        values.put("vatNumber", identity.vatNumber() == null ? "" : identity.vatNumber());
        values.put("taxCode", identity.taxCode() == null ? "" : identity.taxCode());
        return values;
    }
}
