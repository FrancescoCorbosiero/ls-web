package it.logisticasanchez.web.platform.security;

import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.ErrorResponder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * What an authenticated request gets when refused (ADR 0002): 403 with the CSRF reason when the
 * token is missing or stale, 403 on a declared route the role may not call, 404 on an
 * undeclared path (the route does not exist). Never 500 (the legacy answered 500 to every
 * authorisation failure, SEC-09).
 */
public class PlatformAccessDeniedHandler implements AccessDeniedHandler {

    private final RouteRegistry routes;
    private final ErrorResponder responder;

    public PlatformAccessDeniedHandler(RouteRegistry routes, ErrorResponder responder) {
        this.routes = routes;
        this.responder = responder;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        ErrorCode code;
        if (exception instanceof CsrfException) {
            code = ErrorCode.CSRF_REJECTED;
        } else {
            code = routes.isDeclared(request) ? ErrorCode.FORBIDDEN : ErrorCode.NOT_FOUND;
        }
        responder.respond(request, response, code, null);
    }
}
