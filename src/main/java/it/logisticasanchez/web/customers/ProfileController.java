package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The profile page (CUS-06): the registration data read-only with «chiedi una modifica» (an
 * e-mail to the office), and the login e-mail change — the customer types the new address, which
 * gets a {@code CHANGE_EMAIL} link while the current address gets a notice; the page answers the
 * same way whatever the address (an address already in use gets nothing).
 */
@Controller
public class ProfileController {

    private final CustomerService customers;
    private final ClientIpResolver clientIp;

    public ProfileController(CustomerService customers, ClientIpResolver clientIp) {
        this.customers = customers;
        this.clientIp = clientIp;
    }

    @GetMapping(CustomerRoutes.PROFILE)
    public String profile(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            @RequestParam(name = "email-inviata", required = false) String emailSent,
            @RequestParam(name = "email-errata", required = false) String emailInvalid,
            Model model) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        CustomerView customer = customers.find(principal.customerId()).orElseThrow();
        model.addAttribute("email", principal.email());
        model.addAttribute("customer", customer);
        model.addAttribute("emailSent", emailSent != null);
        model.addAttribute("emailInvalid", emailInvalid != null);
        return "customers/profile";
    }

    @PostMapping(CustomerRoutes.PROFILE_EMAIL)
    public String changeEmail(
            @RequestParam(name = "newEmail", required = false) String newEmail,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpServletRequest request) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        String normalised = ItalianFormats.normaliseEmail(newEmail);
        if (!ItalianFormats.isEmail(normalised)) {
            return "redirect:" + CustomerRoutes.PROFILE + "?email-errata=1";
        }
        customers.requestEmailChange(principal.customerUserId(), normalised, clientIp.resolve(request));
        return "redirect:" + CustomerRoutes.PROFILE + "?email-inviata=1";
    }
}
