package it.logisticasanchez.web.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * {@code ETag} with {@code 304 Not Modified} on {@code If-None-Match} for the public HTML, CSS
 * and JS (PD-02: {@code no-cache} + ETag, since the static file names carry no hash). Private
 * areas, the API and the long-cached fonts and images are left alone.
 */
public class PublicEtagFilter extends ShallowEtagHeaderFilter {

    private final RouteRegistry routes;

    public PublicEtagFilter(RouteRegistry routes) {
        this.routes = routes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !routes.isPublicCacheable(path)
                || path.startsWith("/api/")
                || path.startsWith("/mono/")
                || path.startsWith("/actuator/")
                || path.startsWith("/fonts/")
                || path.startsWith("/img/");
    }
}
