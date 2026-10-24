package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** A staff login succeeded: counters cleared, absolute-limit stamp on the (new) session, on to the back office. */
@Component
public class StaffLoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final StaffAccountService accounts;
    private final Clock clock;

    public StaffLoginSuccessHandler(StaffAccountService accounts, Clock clock) {
        super(AuthRoutes.BACKOFFICE);
        setAlwaysUseDefaultTargetUrl(true);
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        if (authentication.getPrincipal() instanceof StaffPrincipal staff) {
            accounts.recordSuccess(staff.id());
        }
        request.getSession().setAttribute(SessionLimitsFilter.AUTHENTICATED_AT, clock.instant());
        response.setHeader("Cache-Control", "no-store");
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
