package it.logisticasanchez.web.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * A staff login failed, whatever the reason (wrong password, unknown username, locked or
 * disabled account): one failure recorded for the account if it exists (PD-10 lock-out) and
 * back to the login page with the one message «Username o password errate.» — nothing reveals
 * which part was wrong or whether the account is locked.
 */
@Component
public class StaffLoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    static final String ERROR_URL = AuthRoutes.BACKOFFICE + "?errore=1";

    private final StaffAccountService accounts;

    public StaffLoginFailureHandler(StaffAccountService accounts) {
        super(ERROR_URL);
        setAllowSessionCreation(false);
        this.accounts = accounts;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        accounts.recordFailure(request.getParameter("username"));
        response.setHeader("Cache-Control", "no-store");
        super.onAuthenticationFailure(request, response, exception);
    }
}
