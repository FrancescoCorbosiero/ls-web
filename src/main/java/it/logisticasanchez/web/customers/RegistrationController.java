package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.AuthRoutes;
import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.auth.RememberedSignInService;
import it.logisticasanchez.web.platform.ratelimit.RateLimitKeys;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

/**
 * The registration of the customer area (CUS-02; chapter 03 §5.3–§5.5 corrected by §10): the
 * form behind a {@code REGISTER} link («Inserisci i dati dell'azienda», e-mail typed again and
 * checked against the link), a summary page («Controlla i dati inseriti», OP-16) and the
 * confirmation that creates the customer and signs it in («Resta connesso» remembers the device,
 * ADR 0031). The same form, without a token, lets a signed-in DECLINED customer register again
 * (Q-09). A honeypot field answers like a success; registrations are rate-limited per IP; the
 * page always answers the same way whether the e-mail was new or already known (CUS-02).
 */
@Controller
public class RegistrationController {

    static final List<String> FIELDS =
            List.of("companyName", "addressLine", "province", "city", "postalCode", "phone", "vatNumber", "taxCode");
    static final String HONEYPOT = "website";

    private final CustomerService customers;
    private final RegistrationValidator validator;
    private final CustomerSessionService sessions;
    private final RememberedSignInService remembered;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIp;

    public RegistrationController(
            CustomerService customers,
            RegistrationValidator validator,
            CustomerSessionService sessions,
            RememberedSignInService remembered,
            RateLimiter rateLimiter,
            ClientIpResolver clientIp) {
        this.customers = customers;
        this.validator = validator;
        this.sessions = sessions;
        this.remembered = remembered;
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
    }

    @GetMapping(CustomerRoutes.REGISTRATION)
    public String form(
            @RequestParam(name = "t", required = false) String token,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        CustomerPrincipal principal = CustomerSessionService.current(authentication);
        if (principal != null) {
            CustomerView customer = customers.find(principal.customerId()).orElseThrow();
            if (customer.status() != CustomerStatus.DECLINED) {
                return "redirect:" + AuthRoutes.CUSTOMER_HOME;
            }
            prefill(model, customer, principal.email());
            return "customers/registration";
        }
        if (!customers.isUsableRegistrationLink(token)) {
            return "auth/customer-link-invalid";
        }
        model.addAttribute("token", token);
        model.addAttribute("values", Map.of());
        model.addAttribute("errors", Map.of());
        model.addAttribute("reregister", false);
        return "customers/registration";
    }

    @PostMapping(CustomerRoutes.REGISTRATION)
    public String submit(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        CustomerPrincipal principal = CustomerSessionService.current(authentication);
        String token = form.get("t");
        String email = principal != null ? principal.email() : ItalianFormats.normaliseEmail(form.get("email"));
        if (principal == null && !customers.isUsableRegistrationLink(token)) {
            return "auth/customer-link-invalid";
        }
        if (principal != null
                && customers
                                .find(principal.customerId())
                                .map(CustomerView::status)
                                .orElse(null)
                        != CustomerStatus.DECLINED) {
            return "redirect:" + AuthRoutes.CUSTOMER_HOME;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : FIELDS) {
            values.put(field, ItalianFormats.trim(form.get(field)));
        }
        values.put("email", email);
        model.addAttribute("token", token);
        model.addAttribute("values", values);
        model.addAttribute("reregister", principal != null);

        RegistrationValidator.Result result = validator.validate(values);
        Map<String, String> errors = new LinkedHashMap<>(result.errors());
        if (principal == null && !ItalianFormats.isEmail(email)) {
            errors.put("email", "customers.error.email");
        } else if (principal == null && !customers.registrationLinkMatches(token, email)) {
            errors.put("email", "customers.error.emailMismatch"); // the link was sent to another address
        }
        if (!errors.isEmpty() || "edit".equals(form.get("step"))) {
            model.addAttribute("errors", errors);
            return "customers/registration";
        }
        if (!"confirm".equals(form.get("step"))) {
            model.addAttribute("data", result.data());
            model.addAttribute("rememberDays", remembered.offeredDays());
            return "customers/registration-review";
        }
        String ip = clientIp.resolve(request);
        rateLimiter.require(RateLimitKeys.REGISTRATION, ip);
        if (form.get(HONEYPOT) != null && !form.get(HONEYPOT).isBlank()) {
            return sent(model); // a bot filled the hidden field: nothing stored, same answer
        }
        if (principal != null) {
            customers.registerAgain(principal.customerId(), principal.customerUserId(), result.data());
            return "redirect:" + AuthRoutes.CUSTOMER_HOME;
        }
        CustomerService.Registration registration = customers.register(token, email, result.data(), ip);
        return switch (registration.outcome()) {
            case REGISTERED -> {
                sessions.signIn(registration.principal(), form.get("ricordami") != null, request, response);
                yield "redirect:" + AuthRoutes.CUSTOMER_HOME;
            }
            case SIGN_IN_LINK_SENT -> sent(model);
            case LINK_INVALID -> "auth/customer-link-invalid";
        };
    }

    private static String sent(Model model) {
        model.addAttribute("sent", true);
        return "auth/customer-request";
    }

    private static void prefill(Model model, CustomerView customer, String email) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("companyName", customer.companyName());
        values.put("addressLine", customer.addressLine());
        values.put("province", customer.province());
        values.put("city", customer.city());
        values.put("postalCode", customer.postalCode());
        values.put("phone", customer.phone());
        values.put("vatNumber", customer.vatNumber() == null ? "" : customer.vatNumber());
        values.put("taxCode", customer.taxCode() == null ? "" : customer.taxCode());
        values.put("email", email);
        model.addAttribute("values", values);
        model.addAttribute("errors", Map.of());
        model.addAttribute("reregister", true);
    }
}
