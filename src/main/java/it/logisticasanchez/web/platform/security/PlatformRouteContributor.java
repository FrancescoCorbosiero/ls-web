package it.logisticasanchez.web.platform.security;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * The URL-map baseline of chapter 10 §2 declared by {@code platform}: static resources, the
 * health endpoint, the error page, the sign-in entries of both areas (the pages themselves come
 * with the {@code auth} stage), {@code POST /api/form} and the compatibility paths (handlers in
 * Phase 2). Public pages are declared by the {@code site} module.
 */
@Component
public class PlatformRouteContributor implements SecurityRouteContributor {

    static final String[] STATIC_RESOURCES = {
        "/css/**",
        "/js/**",
        "/img/**",
        "/fonts/**",
        "/*.png",
        "/favicon.ico",
        "/favicon.svg",
        "/site.webmanifest",
        "/robots.txt",
        "/sitemap.xml",
        "/security.txt",
        "/.well-known/**"
    };

    @Override
    public void contribute(SecurityRoutes routes) {
        routes.permitGet(STATIC_RESOURCES)
                .permitGet("/actuator/health")
                .permit(null, "/error")
                // Customer area: sign-in request page and link confirmation (CUS-01; auth stage).
                .permitGet(SecurityRoutes.CUSTOMER_AREA, SecurityRoutes.CUSTOMER_AREA + "accesso")
                .permit(HttpMethod.POST, SecurityRoutes.CUSTOMER_AREA, SecurityRoutes.CUSTOMER_AREA + "accesso")
                .noStore(SecurityRoutes.CUSTOMER_AREA + "accesso")
                // Back office: staff sign-in (ADM-01; auth stage).
                .permitGet(SecurityRoutes.BACKOFFICE)
                .permit(HttpMethod.POST, SecurityRoutes.BACKOFFICE + "login")
                // Public JSON (site module) and the compatibility API (tracking module), session-free.
                .permit(HttpMethod.POST, "/api/form")
                .permit(null, "/mono/**")
                .csrfExempt(HttpMethod.POST, "/api/form")
                .csrfExempt(null, "/mono/**");
    }
}
