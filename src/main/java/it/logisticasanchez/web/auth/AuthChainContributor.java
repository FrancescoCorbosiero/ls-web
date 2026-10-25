package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.security.RouteRegistry;
import it.logisticasanchez.web.platform.security.SecurityChainContributor;
import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.ErrorResponder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.stereotype.Component;

/**
 * Plugs the auth module into the chain (ADR 0003, ADR 0004): the staff form login on
 * {@code POST /gestione/login} (page {@code /gestione/}, per-IP rate limit before any password
 * check, lock-out bookkeeping on failure), the two logouts ({@code POST} with CSRF; the session
 * is invalidated and the cookie deleted), the session registry with the concurrent-session
 * filter that invalidates a session expired by {@link CustomerSessionService#endAllSessions},
 * the shared context repository and session strategy, and the remembered device that signs a
 * customer back in after the session ended (ADR 0031).
 */
@Component
public class AuthChainContributor implements SecurityChainContributor {

    private final StaffUserDetailsService staffUsers;
    private final PasswordEncoder encoder;
    private final StaffLoginSuccessHandler successHandler;
    private final StaffLoginFailureHandler failureHandler;
    private final SessionRegistry registry;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIp;
    private final ErrorResponder responder;
    private final RememberedSignInService remembered;
    private final CustomerSessionService customerSessions;

    public AuthChainContributor(
            StaffUserDetailsService staffUsers,
            PasswordEncoder encoder,
            StaffLoginSuccessHandler successHandler,
            StaffLoginFailureHandler failureHandler,
            SessionRegistry registry,
            SessionAuthenticationStrategy sessionStrategy,
            SecurityContextRepository contextRepository,
            RateLimiter rateLimiter,
            ClientIpResolver clientIp,
            ErrorResponder responder,
            RememberedSignInService remembered,
            CustomerSessionService customerSessions) {
        this.staffUsers = staffUsers;
        this.encoder = encoder;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
        this.registry = registry;
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
        this.responder = responder;
        this.remembered = remembered;
        this.customerSessions = customerSessions;
    }

    @Override
    public void contribute(HttpSecurity http) throws Exception {
        http.authenticationProvider(staffAuthenticationProvider())
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .sessionManagement(sessions -> sessions.sessionAuthenticationStrategy(sessionStrategy)
                        .maximumSessions(-1)
                        .sessionRegistry(registry)
                        .expiredSessionStrategy(expiredSession()))
                .formLogin(login -> login.loginPage(AuthRoutes.BACKOFFICE)
                        .loginProcessingUrl(AuthRoutes.STAFF_LOGIN)
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                .addFilterBefore(
                        new StaffLoginRateLimitFilter(rateLimiter, clientIp, responder),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new RememberedSignInFilter(remembered, customerSessions), SessionLimitsFilter.class)
                .logout(logout -> logout.logoutRequestMatcher(new OrRequestMatcher(
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.POST, AuthRoutes.STAFF_LOGOUT),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.POST, AuthRoutes.CUSTOMER_LOGOUT)))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .logoutSuccessHandler(logoutSuccess()));
    }

    private AuthenticationProvider staffAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(staffUsers);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    /** After logout: the sign-in page of the area the logout was posted to, never cached. */
    private static LogoutSuccessHandler logoutSuccess() {
        return (request, response, authentication) -> {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, RouteRegistry.signInPathFor(request.getRequestURI()));
        };
    }

    /** A session ended elsewhere (decline, e-mail change) is refused like an anonymous request. */
    private SessionInformationExpiredStrategy expiredSession() {
        return event -> {
            HttpServletRequest request = event.getRequest();
            HttpServletResponse response = event.getResponse();
            if (responder.wantsJson(request)) {
                respondUnauthenticated(request, response);
                return;
            }
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, RouteRegistry.signInPathFor(request.getRequestURI()));
        };
    }

    private void respondUnauthenticated(HttpServletRequest request, HttpServletResponse response) {
        try {
            responder.respond(request, response, ErrorCode.UNAUTHENTICATED, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
