package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.ratelimit.RateLimitExceededException;
import it.logisticasanchez.web.platform.ratelimit.RateLimitKeys;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.ErrorResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The per-IP limit on staff login attempts ({@code staff.login}, ADM-01, chapter 10 §4), taken
 * before the credentials are even read: past the limit the answer is the 429 of the error model
 * with {@code Retry-After}. The per-account lock-out (PD-10) is in {@link StaffAccountService}.
 */
class StaffLoginRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIp;
    private final ErrorResponder responder;

    StaffLoginRateLimitFilter(RateLimiter rateLimiter, ClientIpResolver clientIp, ErrorResponder responder) {
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
        this.responder = responder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !AuthRoutes.STAFF_LOGIN.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            rateLimiter.require(RateLimitKeys.STAFF_LOGIN, clientIp.resolve(request));
        } catch (RateLimitExceededException e) {
            responder.respond(
                    request,
                    response,
                    ErrorCode.TOO_MANY_REQUESTS,
                    null,
                    Map.of("Retry-After", Long.toString(e.retryAfterSeconds())));
            return;
        }
        chain.doFilter(request, response);
    }
}
