package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.security.Authorities;
import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
 * session with a rotated id and {@code ROLE_CUSTOMER}, and may remember the device for 60 days
 * (ADR 0031); {@link #endAllSessions} expires every session of a customer user in the {@link
 * SessionRegistry} and forgets its remembered devices — a decline (ADM-02), an e-mail change
 * (CUS-06) and the anonymisation (NFR-03) call it; the expired sessions are invalidated at their
 * next request and sent to the sign-in page.
 */
@Service
public class CustomerSessionService {

    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final SessionRegistry registry;
    private final RememberedSignInService remembered;
    private final Clock clock;

    public CustomerSessionService(
            SessionAuthenticationStrategy sessionStrategy,
            SecurityContextRepository contextRepository,
            SessionRegistry registry,
            RememberedSignInService remembered,
            Clock clock) {
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
        this.registry = registry;
        this.remembered = remembered;
        this.clock = clock;
    }

    /** The session attribute naming how the session was opened ({@link SignInMethod}). */
    public static final String SIGN_IN_METHOD = CustomerSessionService.class.getName() + ".method";

    /**
     * Opens the session after a sign-in link, the registration or the password and remembers the
     * device when asked (ADR 0031); otherwise the device forgets any earlier remembered sign-in.
     * The method is kept in the session: a session opened by a link proved the mailbox, so the
     * profile lets it set a new password without the current one (ADR 0032).
     */
    public void signIn(
            CustomerPrincipal principal,
            SignInMethod method,
            boolean rememberDevice,
            HttpServletRequest request,
            HttpServletResponse response) {
        signIn(principal, request, response);
        request.getSession().setAttribute(SIGN_IN_METHOD, method);
        if (rememberDevice) {
            remembered.remember(principal.customerUserId(), request, response);
        } else {
            remembered.forgetDevice(request, response);
        }
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

    /** Expires every live session of the customer user and forgets its devices; returns how many sessions were found. */
    public int endAllSessions(long customerUserId) {
        remembered.forgetAll(customerUserId);
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

    /** True when the current session was opened by an e-mailed link (or the registration that followed one). */
    public static boolean signedInByLink(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(SIGN_IN_METHOD) == SignInMethod.LINK;
    }

    /** How a customer session was opened. */
    public enum SignInMethod {
        /** The e-mailed link (or the registration behind a link): the mailbox is proven. */
        LINK,
        /** E-mail and password (ADR 0032). */
        PASSWORD
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
