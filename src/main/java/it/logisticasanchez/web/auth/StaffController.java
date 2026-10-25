package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.security.Authorities;
import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The back-office entry (ADM-01, ADR 0003): {@code /gestione/} is the login page for an
 * anonymous visitor (copy of the legacy CMS: «Login», «Username o password errate.») and the
 * back-office home for a staff session, rendered by the back-office module through
 * {@link StaffHomePage} (ADR 0029); {@code /gestione/imposta-password?t=…} renders the
 * set-password form on {@code GET} and consumes the link on {@code POST}. The login itself is
 * Spring Security's form login on {@code POST /gestione/login} (see {@link AuthChainContributor}).
 * Minimal templates, restyled with the layout in stage 5.
 */
@Controller
public class StaffController {

    static final String NO_REFERRER = "no-referrer";

    private final StaffAccountService accounts;
    private final ObjectProvider<StaffHomePage> homePage;

    public StaffController(StaffAccountService accounts, ObjectProvider<StaffHomePage> homePage) {
        this.accounts = accounts;
        this.homePage = homePage;
    }

    @GetMapping(AuthRoutes.BACKOFFICE)
    public String home(
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            @RequestParam(name = "errore", required = false) String error,
            @RequestParam(name = "impostata", required = false) String passwordSet,
            @RequestParam(name = "uscito", required = false) String loggedOut,
            Model model) {
        if (SessionLimitsFilter.isAuthenticated(authentication) && SessionLimitsFilter.isStaff(authentication)) {
            model.addAttribute("displayName", displayName(authentication));
            model.addAttribute("isAdmin", isAdmin(authentication));
            StaffHomePage page = homePage.getIfAvailable();
            return page == null ? "auth/staff-home" : page.render(authentication, model);
        }
        model.addAttribute("loginError", error != null);
        model.addAttribute("passwordSet", passwordSet != null);
        model.addAttribute("loggedOut", loggedOut != null);
        return "auth/staff-login";
    }

    @GetMapping(AuthRoutes.STAFF_SET_PASSWORD)
    public String setPasswordPage(
            @RequestParam(name = "t", required = false) String token, Model model, HttpServletResponse response) {
        response.setHeader("Referrer-Policy", NO_REFERRER);
        return accounts.peekSetupLink(token)
                .map(user -> {
                    model.addAttribute("username", user.getUsername());
                    model.addAttribute("token", token);
                    return "auth/staff-set-password";
                })
                .orElse("auth/staff-link-invalid");
    }

    @PostMapping(AuthRoutes.STAFF_SET_PASSWORD)
    public String setPassword(
            @RequestParam(name = "t", required = false) String token,
            @RequestParam(name = "password", required = false) String password,
            @RequestParam(name = "passwordConfirm", required = false) String passwordConfirm,
            Model model,
            HttpServletResponse response) {
        response.setHeader("Referrer-Policy", NO_REFERRER);
        if (!Objects.equals(password, passwordConfirm)) {
            return accounts.peekSetupLink(token)
                    .map(user -> form(model, user, token, "auth.staff.setPassword.mismatch"))
                    .orElse("auth/staff-link-invalid");
        }
        try {
            accounts.setPassword(token, password == null ? "" : password);
            return "redirect:" + AuthRoutes.BACKOFFICE + "?impostata=1";
        } catch (StaffAccountService.PasswordRefusedException e) {
            return accounts.peekSetupLink(token)
                    .map(user -> form(model, user, token, e.violation().messageKey()))
                    .orElse("auth/staff-link-invalid");
        } catch (StaffAccountService.InvalidLinkException e) {
            return "auth/staff-link-invalid";
        }
    }

    private static String form(Model model, StaffUser user, String token, String errorKey) {
        model.addAttribute("username", user.getUsername());
        model.addAttribute("token", token);
        model.addAttribute("errorKey", errorKey);
        return "auth/staff-set-password";
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> Authorities.ROLE_ADMIN.equals(a.getAuthority()));
    }

    private static String displayName(Authentication authentication) {
        return authentication.getPrincipal() instanceof StaffPrincipal staff
                ? staff.displayName()
                : authentication.getName();
    }
}
