package it.logisticasanchez.web.support;

import it.logisticasanchez.web.platform.security.Authorities;
import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import java.time.Instant;
import java.util.List;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** Builds HTTP sessions that carry an authenticated principal, as the session repository stores them. */
public final class Sessions {

    private Sessions() {}

    public static MockHttpSession customer(Instant authenticatedAt) {
        return session(Authorities.ROLE_CUSTOMER, authenticatedAt);
    }

    /** A customer session with a real principal (the customer-area controllers read it). */
    public static MockHttpSession customer(Object principal, Instant authenticatedAt) {
        return session(principal, Authorities.ROLE_CUSTOMER, authenticatedAt);
    }

    public static MockHttpSession staff(String role, Instant authenticatedAt) {
        return session("ROLE_" + role, authenticatedAt);
    }

    private static MockHttpSession session(String authority, Instant authenticatedAt) {
        return session("user-1", authority, authenticatedAt);
    }

    private static MockHttpSession session(Object principal, String authority, Instant authenticatedAt) {
        MockHttpSession session = new MockHttpSession();
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(authority)));
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        if (authenticatedAt != null) {
            session.setAttribute(SessionLimitsFilter.AUTHENTICATED_AT, authenticatedAt);
        }
        return session;
    }
}
