package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.CustomerPasswordService;
import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.auth.PasswordPolicy;
import it.logisticasanchez.web.auth.RememberedSignInService;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
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
 * same way whatever the address (an address already in use gets nothing). The password section
 * (ADR 0032) sets or changes the password: the current one is asked unless the session was opened
 * by an e-mailed link (the «password dimenticata» path); a change ends the other sessions and
 * remembered devices, this one goes on. With {@code customers.addressBook} on, the page also lists
 * the saved recipients (ENH-07, ADR 0033; {@link AddressBookController} adds, changes, deletes).
 * With {@code customers.profileEdit} on, an ACTIVE customer gets «Modifica i dati» in place of
 * «Chiedi una modifica» and sees the change waiting for approval (ENH-16, ADR 0038;
 * {@link ProfileEditController}).
 */
@Controller
public class ProfileController {

    /** The error codes of the password form, in the query string, and their messages. */
    static final Map<String, String> PASSWORD_ERRORS = Map.of(
            "attuale", "customers.signinForm.wrongCurrent",
            "diverse", "customers.signinForm.mismatch",
            "corta", "customers.signinForm.tooShort",
            "comune", "customers.signinForm.common");

    private final CustomerService customers;
    private final CustomerPasswordService passwords;
    private final CustomerSessionService sessions;
    private final RememberedSignInService remembered;
    private final ClientIpResolver clientIp;
    private final AddressBookService addressBook;
    private final CustomerChangeService changes;
    private final FeatureFlags flags;

    public ProfileController(
            CustomerService customers,
            CustomerPasswordService passwords,
            CustomerSessionService sessions,
            RememberedSignInService remembered,
            ClientIpResolver clientIp,
            AddressBookService addressBook,
            CustomerChangeService changes,
            FeatureFlags flags) {
        this.customers = customers;
        this.passwords = passwords;
        this.sessions = sessions;
        this.remembered = remembered;
        this.clientIp = clientIp;
        this.addressBook = addressBook;
        this.changes = changes;
        this.flags = flags;
    }

    @GetMapping(CustomerRoutes.PROFILE)
    public String profile(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            @RequestParam(name = "email-inviata", required = false) String emailSent,
            @RequestParam(name = "email-errata", required = false) String emailInvalid,
            @RequestParam(name = "password-salvata", required = false) String passwordSaved,
            @RequestParam(name = "password-errore", required = false) String passwordError,
            HttpServletRequest request,
            Model model) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        CustomerView customer = customers.find(principal.customerId()).orElseThrow();
        boolean hasPassword = passwords.hasPassword(principal.customerUserId());
        model.addAttribute("email", principal.email());
        model.addAttribute("customer", customer);
        model.addAttribute("emailSent", emailSent != null);
        model.addAttribute("emailInvalid", emailInvalid != null);
        model.addAttribute("hasPassword", hasPassword);
        model.addAttribute("needsCurrentPassword", hasPassword && !CustomerSessionService.signedInByLink(request));
        model.addAttribute("passwordMinLength", passwords.minLength());
        model.addAttribute("passwordSaved", passwordSaved != null);
        model.addAttribute("passwordErrorKey", passwordError == null ? null : PASSWORD_ERRORS.get(passwordError));
        // ENH-07: null while the flag is off, so the page is the M1 one
        model.addAttribute(
                "addresses",
                flags.isEnabled(FlagKey.CUSTOMERS_ADDRESS_BOOK) ? addressBook.list(principal.customerId()) : null);
        model.addAttribute("addressBookMax", AddressBookService.MAX_ENTRIES);
        // ENH-16: false and null while the flag is off (or the customer is not ACTIVE): the M1 page
        boolean profileEdit = flags.isEnabled(FlagKey.CUSTOMERS_PROFILE_EDIT) && customer.canOrder();
        model.addAttribute("profileEdit", profileEdit);
        model.addAttribute(
                "pendingChange", profileEdit ? changes.pending(customer.id()).orElse(null) : null);
        return "customers/profile";
    }

    @PostMapping(CustomerRoutes.PROFILE_PASSWORD)
    public String changePassword(
            @RequestParam(name = "currentPassword", required = false) String currentPassword,
            @RequestParam(name = "newPassword", required = false) String newPassword,
            @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        CustomerPrincipal principal = CustomerSessionService.require(authentication);
        long userId = principal.customerUserId();
        boolean byLink = CustomerSessionService.signedInByLink(request);
        if (passwords.hasPassword(userId) && !byLink && !passwords.isCurrentPassword(userId, currentPassword)) {
            return passwordError("attuale");
        }
        Optional<PasswordPolicy.Violation> violation = passwords.violation(newPassword);
        if (violation.isPresent()) {
            return passwordError(violation.get() == PasswordPolicy.Violation.COMMON ? "comune" : "corta");
        }
        if (!newPassword.equals(confirmPassword)) {
            return passwordError("diverse");
        }
        passwords.setPassword(userId, newPassword);
        // the other sessions and remembered devices end; this browser goes on, remembered as it was
        boolean rememberDevice = remembered.cookieToken(request).isPresent();
        sessions.endAllSessions(userId);
        sessions.signIn(
                principal,
                byLink ? CustomerSessionService.SignInMethod.LINK : CustomerSessionService.SignInMethod.PASSWORD,
                rememberDevice,
                request,
                response);
        return "redirect:" + CustomerRoutes.PROFILE + "?password-salvata=1#password";
    }

    private static String passwordError(String code) {
        return "redirect:" + CustomerRoutes.PROFILE + "?password-errore=" + code + "#password";
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
