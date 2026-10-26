package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.util.LinkedHashMap;
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
 * The address book in the profile (ENH-07, flag {@code customers.addressBook}; ADR 0033): the
 * profile page lists the saved recipients ({@link ProfileController}); here the customer adds
 * one, changes one and deletes one. Every route answers 404 while the flag is off, as if it did
 * not exist (ADR 0025); another customer's entry is a 404 too.
 */
@Controller
public class AddressBookController {

    private final AddressBookService book;
    private final CustomerService customers;
    private final FeatureFlags flags;

    public AddressBookController(AddressBookService book, CustomerService customers, FeatureFlags flags) {
        this.book = book;
        this.customers = customers;
        this.flags = flags;
    }

    @GetMapping(CustomerRoutes.ADDRESS_NEW)
    public String newAddress(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication, Model model) {
        signedIn(authentication, model);
        return formPage(model, null, empty(), Map.of());
    }

    @PostMapping(CustomerRoutes.ADDRESS_NEW)
    public String create(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = signedIn(authentication, model);
        Map<String, String> values = typed(form);
        AddressBookService.Result result;
        try {
            result = book.create(
                    principal.customerId(), AddressBookService.Actor.customer(principal.customerUserId()), values);
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.detail());
            return formPage(model, null, values, Map.of());
        }
        if (!result.valid()) {
            return formPage(model, null, values, result.errors());
        }
        redirect.addFlashAttribute("flash", "Destinatario «" + result.address().name() + "» salvato nella rubrica.");
        return "redirect:" + CustomerRoutes.PROFILE + "#rubrica";
    }

    @GetMapping(CustomerRoutes.ADDRESS_EDIT)
    public String edit(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model) {
        CustomerPrincipal principal = signedIn(authentication, model);
        AddressView address =
                book.find(principal.customerId(), id).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
        return formPage(model, address, values(address), Map.of());
    }

    @PostMapping(CustomerRoutes.ADDRESS_EDIT)
    public String update(
            @PathVariable("id") long id,
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = signedIn(authentication, model);
        AddressView address =
                book.find(principal.customerId(), id).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
        Map<String, String> values = typed(form);
        AddressBookService.Result result = book.update(
                principal.customerId(), id, AddressBookService.Actor.customer(principal.customerUserId()), values);
        if (!result.valid()) {
            return formPage(model, address, values, result.errors());
        }
        redirect.addFlashAttribute("flash", "Destinatario «" + result.address().name() + "» aggiornato.");
        return "redirect:" + CustomerRoutes.PROFILE + "#rubrica";
    }

    @PostMapping(CustomerRoutes.ADDRESS_DELETE)
    public String delete(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        CustomerPrincipal principal = requireFlag(authentication);
        AddressView address =
                book.find(principal.customerId(), id).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
        book.delete(principal.customerId(), id, AddressBookService.Actor.customer(principal.customerUserId()));
        redirect.addFlashAttribute("flash", "Destinatario «" + address.name() + "» eliminato dalla rubrica.");
        return "redirect:" + CustomerRoutes.PROFILE + "#rubrica";
    }

    private String formPage(Model model, AddressView address, Map<String, String> values, Map<String, String> errors) {
        model.addAttribute("address", address);
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        if (!model.containsAttribute("error")) {
            model.addAttribute("error", null);
        }
        return "customers/address-form";
    }

    /** The signed-in customer, after the flag check (404 while off). */
    private CustomerPrincipal requireFlag(Authentication authentication) {
        if (!flags.isEnabled(FlagKey.CUSTOMERS_ADDRESS_BOOK)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        return CustomerSessionService.require(authentication);
    }

    private CustomerPrincipal signedIn(Authentication authentication, Model model) {
        CustomerPrincipal principal = requireFlag(authentication);
        model.addAttribute("email", principal.email());
        model.addAttribute("customer", customers.find(principal.customerId()).orElseThrow());
        return principal;
    }

    private static Map<String, String> typed(Map<String, String> form) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : AddressBookService.FIELDS) {
            values.put(field, ItalianFormats.trim(form.get(field)));
        }
        return values;
    }

    private static Map<String, String> empty() {
        return typed(Map.of());
    }

    private static Map<String, String> values(AddressView a) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", a.name());
        values.put("addressLine", a.addressLine());
        values.put("province", a.province());
        values.put("city", a.city());
        values.put("postalCode", a.postalCode());
        values.put("phone", a.phone() == null ? "" : a.phone());
        values.put("email", a.email() == null ? "" : a.email());
        return values;
    }
}
