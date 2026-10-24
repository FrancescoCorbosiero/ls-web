package it.logisticasanchez.web.platform.security;

import it.logisticasanchez.web.platform.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Idle and absolute session timeouts per session class (chapter 10 §2; {@code
 * ls.security.sessions.*}): the idle limit is the container's max-inactive interval, set per
 * session from the principal's authorities; the absolute limit counts from the first
 * authenticated request of the session (the session is new at login: fixation protection) and
 * invalidates the session when exceeded, so the request continues as anonymous and the
 * authorization layer sends the user to the sign-in page.
 */
public class SessionLimitsFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_AT = SessionLimitsFilter.class.getName() + ".authenticatedAt";

    private final SecurityProperties.Sessions sessions;
    private final Clock clock;

    public SessionLimitsFilter(SecurityProperties security, Clock clock) {
        this.sessions = security.sessions();
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (isAuthenticated(authentication) && session != null) {
            SecurityProperties.Window window = isStaff(authentication) ? sessions.staff() : sessions.customer();
            session.setMaxInactiveInterval((int) window.idle().toSeconds());
            Instant now = clock.instant();
            Object since = session.getAttribute(AUTHENTICATED_AT);
            if (since instanceof Instant authenticatedAt) {
                if (now.isAfter(authenticatedAt.plus(window.absolute()))) {
                    session.invalidate();
                    SecurityContextHolder.clearContext();
                }
            } else {
                session.setAttribute(AUTHENTICATED_AT, now);
            }
        }
        chain.doFilter(request, response);
    }

    static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    static boolean isStaff(Authentication authentication) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (Authorities.ROLE_ADMIN.equals(name) || Authorities.ROLE_OPERATOR.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
