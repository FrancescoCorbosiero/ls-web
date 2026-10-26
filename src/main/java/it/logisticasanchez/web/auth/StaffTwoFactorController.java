package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * «Verifica in due passaggi» (ENH-09, flag {@code security.adminTotp}; ADR 0039):
 * {@code /gestione/verifica} after the right password. A user with a secret types the code of the
 * app; a user without one sets it up first (QR code or key) and confirms it with a code. The
 * right code opens the staff session; a wrong one counts as a failed login (PD-10 lock-out) and,
 * after {@value PendingStaffLogin#MAX_WRONG_CODES}, the login starts again, as it does after
 * {@code PendingStaffLogin.VALIDITY}. 404 while the flag is off.
 */
@Controller
public class StaffTwoFactorController {

    static final String RESTART = AuthRoutes.BACKOFFICE + "?verifica=1";

    private final StaffTwoFactorService twoFactor;
    private final StaffAccountService accounts;
    private final StaffSignIn signIn;
    private final Clock clock;

    public StaffTwoFactorController(
            StaffTwoFactorService twoFactor, StaffAccountService accounts, StaffSignIn signIn, Clock clock) {
        this.twoFactor = twoFactor;
        this.accounts = accounts;
        this.signIn = signIn;
        this.clock = clock;
    }

    @GetMapping(AuthRoutes.STAFF_TWO_FACTOR)
    public String page(HttpServletRequest request, Model model) {
        PendingStaffLogin pending = pending(request);
        if (pending == null) {
            return "redirect:" + AuthRoutes.BACKOFFICE;
        }
        return codePage(model, pending, request, false);
    }

    @PostMapping(AuthRoutes.STAFF_TWO_FACTOR)
    public String verify(
            @RequestParam(name = "codice", required = false) String code,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        PendingStaffLogin pending = pending(request);
        if (pending == null) {
            return "redirect:" + RESTART;
        }
        Optional<StaffPrincipal> principal = twoFactor.principal(pending.staffUserId());
        if (principal.isEmpty()) {
            PendingStaffLogin.clear(request);
            return "redirect:" + RESTART;
        }
        if (twoFactor.check(pending.staffUserId(), pending.newSecret(), code)) {
            signIn.signIn(principal.get(), request, response);
            accounts.recordSuccess(pending.staffUserId());
            return "redirect:" + AuthRoutes.BACKOFFICE;
        }
        accounts.recordFailure(pending.username());
        PendingStaffLogin next = pending.withWrongCode();
        if (next.wrongCodes() >= PendingStaffLogin.MAX_WRONG_CODES) {
            PendingStaffLogin.clear(request);
            return "redirect:" + RESTART;
        }
        next.store(request);
        return codePage(model, next, request, true);
    }

    private String codePage(Model model, PendingStaffLogin pending, HttpServletRequest request, boolean wrongCode) {
        PendingStaffLogin current = pending;
        boolean setUp = !twoFactor.enabled(pending.staffUserId());
        if (setUp) {
            if (current.newSecret() == null) {
                current = current.withNewSecret(Totp.newSecret());
                current.store(request);
            }
            model.addAttribute("setup", twoFactor.setup(current.username(), current.newSecret()));
        }
        model.addAttribute("setUp", setUp);
        model.addAttribute("username", current.username());
        model.addAttribute("wrongCode", wrongCode);
        return "auth/staff-two-factor";
    }

    /** The pending login of this session; 404 while the flag is off, as if the page did not exist. */
    private PendingStaffLogin pending(HttpServletRequest request) {
        if (!twoFactor.required()) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        return PendingStaffLogin.current(request, clock.instant());
    }
}
