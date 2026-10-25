package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.security.Authorities;
import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

/**
 * Customer sessions (chapter 10 §2, ADR 0004): {@link #signIn} turns a consumed token into a
 * session with a rotated id and {@code ROLE_CUSTOMER}; {@link #endAllSessions} expires every
 * session of a customer user in the {@link SessionRegistry} — a decline (ADM-02) and an e-mail
 * change (CUS-06) call it from the Phase 2 {@code customers} module; the expired sessions are
 * invalidated at their next request and sent to the sign-in page.
 */
@Service
public class CustomerSessionService {

    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final SessionRegistry registry;
    private final Clock clock;

    public CustomerSessionService(
            SessionAuthenticationStrategy sessionStrategy,
            SecurityContextRepository contextRepository,
            SessionRegistry registry,
            Clock clock) {
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
        this.registry = registry;
        this.clock = clock;
    }

    /** Opens the customer session: new session id, registry entry, security context saved, absolute-limit stamp. */
    public void signIn(CustomerPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(Authorities.ROLE_CUSTOMER)));
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        request.getSession().setAttribute(SessionLimitsFilter.AUTHENTICATED_AT, clock.instant());
    }

    /** Expires every live session of the customer user; returns how many were found. */
    public int endAllSessions(long customerUserId) {
        int ended = 0;
        for (Object principal : registry.getAllPrincipals()) {
            if (principal instanceof CustomerPrincipal customer && customer.customerUserId() == customerUserId) {
                for (SessionInformation session : registry.getAllSessions(principal, false)) {
                    session.expireNow();
                    ended++;
                }
            }
        }
        return ended;
    }

    /** The principal of the current request, or 401 through the error model when it is not a customer session. */
    public static CustomerPrincipal require(Authentication authentication) {
        CustomerPrincipal principal = current(authentication);
        if (principal == null) {
            throw new ClientErrorException(ErrorCode.UNAUTHENTICATED);
        }
        return principal;
    }

    /** The principal of the current request, if it is a customer session. */
    public static CustomerPrincipal current(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof CustomerPrincipal principal
                ? principal
                : null;
    }
}
