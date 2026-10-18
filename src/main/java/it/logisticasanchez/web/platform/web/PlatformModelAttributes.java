package it.logisticasanchez.web.platform.web;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.security.CspNonce;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Model attributes every template may use: {@code business} (ADR 0027), {@code cspNonce} (the
 * private-area nonce, null on public pages) and {@code htmxConfig} (the meta content).
 */
@ControllerAdvice
public class PlatformModelAttributes {

    private final BusinessProperties business;

    public PlatformModelAttributes(BusinessProperties business) {
        this.business = business;
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
}
