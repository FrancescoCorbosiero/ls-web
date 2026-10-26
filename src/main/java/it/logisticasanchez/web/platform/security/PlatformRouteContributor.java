package it.logisticasanchez.web.platform.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * The URL-map baseline of chapter 10 §2 declared by {@code platform}: static resources, the
 * health endpoint, the error page, {@code POST /api/form} and the compatibility paths (handlers
 * in Phase 2). The sign-in entries of both areas are declared by the {@code auth} module; public
 * pages by the {@code site} module.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
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
        "/area-clienti.webmanifest",
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
                // The sign-in entries of both areas are declared by the auth module (AuthRouteContributor).
                // Public JSON (site module) and the compatibility API (tracking module), session-free.
                .permit(HttpMethod.POST, "/api/form")
                .permit(null, "/mono/**")
                .csrfExempt(HttpMethod.POST, "/api/form")
                .csrfExempt(null, "/mono/**");
    }
}
