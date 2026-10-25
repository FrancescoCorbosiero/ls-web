package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The remembered device in the chain (ADR 0031), right after the session limits and before the
 * CSRF check: a customer-area request without a signed-in user but with a valid remember cookie
 * gets a new customer session, exactly as the sign-in link opens one; the customer logout
 * forgets the device first, so «Esci» works even from a page whose session had already ended.
 * Only paths under {@code /area-clienti/} are looked at (the cookie is limited to them anyway).
 */
public class RememberedSignInFilter extends OncePerRequestFilter {

    private final RememberedSignInService remembered;
    private final CustomerSessionService sessions;

    public RememberedSignInFilter(RememberedSignInService remembered, CustomerSessionService sessions) {
        this.remembered = remembered;
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(AuthRoutes.CUSTOMER_AREA);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> token = remembered.cookieToken(request);
        if (token.isPresent()) {
            if ("POST".equals(request.getMethod()) && AuthRoutes.CUSTOMER_LOGOUT.equals(request.getRequestURI())) {
                remembered.forget(token.get());
                remembered.clearCookie(response);
            } else if (!SessionLimitsFilter.isAuthenticated(
                    SecurityContextHolder.getContext().getAuthentication())) {
                Optional<CustomerPrincipal> principal = remembered.restore(token.get());
                if (principal.isPresent()) {
                    sessions.signIn(principal.get(), request, response);
                } else {
                    remembered.clearCookie(response);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
