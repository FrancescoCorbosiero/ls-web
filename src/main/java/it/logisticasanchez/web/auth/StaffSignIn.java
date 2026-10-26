package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * The staff session around the second step (ENH-09, ADR 0039): after the password,
 * {@link #holdForCode} empties the security context and keeps only the pending login in the
 * session; after the code, {@link #signIn} opens the staff session as the form login does — new
 * session id, registry entry, fresh CSRF token, context saved, absolute-limit stamp.
 */
@Component
class StaffSignIn {

    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final Clock clock;

    StaffSignIn(
            SessionAuthenticationStrategy sessionStrategy, SecurityContextRepository contextRepository, Clock clock) {
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
        this.clock = clock;
    }

    void holdForCode(StaffPrincipal staff, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext empty = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(empty);
        contextRepository.saveContext(empty, request, response);
        request.getSession().removeAttribute(SessionLimitsFilter.AUTHENTICATED_AT);
        PendingStaffLogin.of(staff, clock.instant()).store(request);
    }

    void signIn(StaffPrincipal staff, HttpServletRequest request, HttpServletResponse response) {
        PendingStaffLogin.clear(request);
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(staff, null, staff.getAuthorities());
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        request.getSession().setAttribute(SessionLimitsFilter.AUTHENTICATED_AT, clock.instant());
    }
}
