package it.logisticasanchez.web.platform.web;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.security.CspNonce;
import it.logisticasanchez.web.platform.security.RouteRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Model attributes every template may use: {@code business} (ADR 0027), {@code layout}, {@code cspNonce} (the
 * private-area nonce, null on public pages), {@code htmxConfig} (the meta content) and
 * {@code hxHeaders} (the {@code hx-headers} JSON carrying the CSRF token, for {@code <body>}).
 */
@ControllerAdvice
public class PlatformModelAttributes {

    private final BusinessProperties business;
    private final Layout layout;

    public PlatformModelAttributes(BusinessProperties business, Clock clock) {
        this.business = business;
        this.layout = new Layout(clock);
    }

    @ModelAttribute("layout")
    public Layout layout() {
        return layout;
    }

    @ModelAttribute("business")
    public BusinessProperties business() {
        return business;
    }

    @ModelAttribute("cspNonce")
    public String cspNonce(HttpServletRequest request) {
        return CspNonce.of(request);
    }

    @ModelAttribute("htmxConfig")
    public String htmxConfig() {
        return HtmxConfig.META_CONTENT;
    }

    /**
     * {@code {"X-CSRF-TOKEN": "<token>"}} for {@code <body th:attr="hx-headers=${hxHeaders}">},
     * so every HTMX request of the private areas carries the CSRF token. Computed only inside
     * the private areas: reading the deferred token creates the session that stores it, and
     * public pages must stay cookie-free (chapter 10 §2).
     */
    @ModelAttribute("hxHeaders")
    public String hxHeaders(HttpServletRequest request) {
        if (!RouteRegistry.isPrivateArea(request.getRequestURI())) {
            return null;
        }
        return request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrf
                ? HtmxConfig.hxHeaders(csrf.getHeaderName(), csrf.getToken())
                : null;
    }
}
