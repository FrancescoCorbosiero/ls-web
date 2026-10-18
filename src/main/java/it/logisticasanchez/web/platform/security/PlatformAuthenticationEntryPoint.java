package it.logisticasanchez.web.platform.security;

import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.ErrorResponder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * What an anonymous request gets when the chain refuses it (ADR 0002): inside a private area a
 * 302 to that area's sign-in page (HTMX and JSON callers get 401 instead, since a redirect
 * would be followed into the sign-in HTML); on a declared route elsewhere 401; on an
 * undeclared path 404 — a route that is not declared does not exist, so nothing is revealed.
 */
public class PlatformAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final RouteRegistry routes;
    private final ErrorResponder responder;

    public PlatformAuthenticationEntryPoint(RouteRegistry routes, ErrorResponder responder) {
        this.routes = routes;
        this.responder = responder;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String path = request.getRequestURI();
        if (RouteRegistry.isPrivateArea(path)) {
            if (responder.wantsJson(request)) {
                responder.respond(request, response, ErrorCode.UNAUTHENTICATED, null);
                return;
            }
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, RouteRegistry.signInPathFor(path));
            response.flushBuffer();
            return;
        }
        ErrorCode code = routes.isDeclared(request) ? ErrorCode.UNAUTHENTICATED : ErrorCode.NOT_FOUND;
        responder.respond(request, response, code, null);
    }
}
