package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.security.Authorities;
import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The customer sign-in pages (CUS-01, chapter 10 §2, ADR 0004, ADR 0032). {@code GET
 * /area-clienti/} offers e-mail and password ({@code POST /area-clienti/password}) and, for the
 * first access or a forgotten password, the e-mail form of the link (legacy copy adapted), whose
 * {@code POST} always answers the same page and status. {@code GET /area-clienti/accesso?t=…} only renders the «Accedi» button and never
 * consumes the token (a mail scanner or a link preview cannot burn it); its CSRF-protected
 * {@code POST} consumes it, opens the session, remembers the device for 60 days unless the
 * customer unticks «Resta connesso» (ADR 0031) and redirects without the token in the URL.
 * {@code /area-clienti/cambio-email?t=…} is the same two-step page for a {@code CHANGE_EMAIL}
 * link (CUS-06). The registration page belongs to the {@code customers} module and the home
 * ({@code /area-clienti/ordini/}) to the {@code orders} module.
 */
@Controller
public class CustomerSignInController {

    static final String NO_REFERRER = "no-referrer";

    private final CustomerSignInService signIn;
    private final CustomerSessionService sessions;
    private final CustomerAccountService accounts;
    private final RememberedSignInService remembered;
    private final CustomerPasswordService passwords;
    private final ClientIpResolver clientIp;

    public CustomerSignInController(
            CustomerSignInService signIn,
            CustomerSessionService sessions,
            CustomerAccountService accounts,
            RememberedSignInService remembered,
            CustomerPasswordService passwords,
            ClientIpResolver clientIp) {
        this.signIn = signIn;
        this.sessions = sessions;
        this.accounts = accounts;
        this.remembered = remembered;
        this.passwords = passwords;
        this.clientIp = clientIp;
    }

    @GetMapping(AuthRoutes.CUSTOMER_AREA)
    public String requestForm(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication, Model model) {
        if (isCustomer(authentication)) {
            return "redirect:" + AuthRoutes.CUSTOMER_HOME;
        }
        return signInPage(model, false, false, null);
    }

    @PostMapping(AuthRoutes.CUSTOMER_AREA)
    public String requestLink(
            @RequestParam(name = "email", required = false) String email, HttpServletRequest request, Model model) {
        signIn.requestLink(email, clientIp.resolve(request));
        return signInPage(model, true, false, null);
    }

    /**
     * The e-mail-and-password sign-in (ADR 0032): one answer for every refusal, the e-mail kept in
     * the form, the password never; «Resta connesso» as for the link.
     */
    @PostMapping(AuthRoutes.CUSTOMER_PASSWORD)
    public String usePassword(
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "password", required = false) String password,
            @RequestParam(name = "ricordami", required = false) String rememberDevice,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        return passwords
                .authenticate(email, password, clientIp.resolve(request))
                .map(principal -> {
                    sessions.signIn(
                            principal,
                            CustomerSessionService.SignInMethod.PASSWORD,
                            rememberDevice != null,
                            request,
                            response);
                    return "redirect:" + AuthRoutes.CUSTOMER_HOME;
                })
                .orElseGet(() -> signInPage(model, false, true, CustomerSignInService.normalise(email)));
    }

    @GetMapping(AuthRoutes.CUSTOMER_LINK)
    public String linkPage(
            @RequestParam(name = "t", required = false) String token, Model model, HttpServletResponse response) {
        response.setHeader("Referrer-Policy", NO_REFERRER);
        if (!signIn.isUsableSignInToken(token)) {
            return linkInvalid(model);
        }
        model.addAttribute("token", token);
        model.addAttribute("rememberDays", remembered.offeredDays());
        return "auth/customer-link";
    }

    @PostMapping(AuthRoutes.CUSTOMER_LINK)
    public String useLink(
            @RequestParam(name = "t", required = false) String token,
            @RequestParam(name = "ricordami", required = false) String rememberDevice,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        response.setHeader("Referrer-Policy", NO_REFERRER);
        CustomerPrincipal principal;
        try {
            principal = signIn.signIn(token);
        } catch (LoginTokenService.InvalidTokenException e) {
            return linkInvalid(model);
        }
        sessions.signIn(principal, CustomerSessionService.SignInMethod.LINK, rememberDevice != null, request, response);
        return "redirect:" + AuthRoutes.CUSTOMER_HOME;
    }

    @GetMapping(AuthRoutes.CUSTOMER_CHANGE_EMAIL)
    public String changeEmailPage(
            @RequestParam(name = "t", required = false) String token, Model model, HttpServletResponse response) {
        response.setHeader("Referrer-Policy", NO_REFERRER);
        return accounts.peekEmailChange(token)
                .map(change -> {
                    model.addAttribute("token", token);
                    model.addAttribute("newEmail", change.newEmail());
                    return "auth/customer-change-email";
                })
                .orElseGet(() -> linkInvalid(model));
    }

    @PostMapping(AuthRoutes.CUSTOMER_CHANGE_EMAIL)
    public String changeEmail(
            @RequestParam(name = "t", required = false) String token, Model model, HttpServletResponse response) {
        response.setHeader("Referrer-Policy", NO_REFERRER);
        try {
            CustomerAccountService.EmailChange change = accounts.confirmEmailChange(token);
            model.addAttribute("newEmail", change.newEmail());
            return "auth/customer-change-email-done";
        } catch (LoginTokenService.InvalidTokenException e) {
            return linkInvalid(model);
        }
    }

    private String signInPage(Model model, boolean sent, boolean passwordRefused, String email) {
        model.addAttribute("sent", sent);
        model.addAttribute("passwordRefused", passwordRefused);
        model.addAttribute("loginEmail", email == null ? "" : email);
        model.addAttribute("rememberDays", remembered.offeredDays());
        return "auth/customer-request";
    }

    private static String linkInvalid(Model model) {
        model.addAttribute("sent", false);
        return "auth/customer-link-invalid";
    }

    private static boolean isCustomer(Authentication authentication) {
        if (!SessionLimitsFilter.isAuthenticated(authentication)) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> Authorities.ROLE_CUSTOMER.equals(authority.getAuthority()));
    }
}
