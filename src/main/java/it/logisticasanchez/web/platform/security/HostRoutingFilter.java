package it.logisticasanchez.web.platform.security;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.config.SiteProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Host routing (plan §4.3, PUB-04, Q-30, Q-47): the proxy forwards every host and the app
 * decides. {@code www} → 301 to the canonical origin keeping path and query (the browser keeps
 * the fragment, so {@code www…/#tracking} still works); the old order-portal host → 301
 * {@code /area-clienti/}; the old CMS host → 301 {@code /gestione/}; the canonical host and
 * {@code app.} serve the same app; any other host (localhost, staging) is served as is.
 */
public class HostRoutingFilter extends OncePerRequestFilter {

    private final SiteProperties.Hosts hosts;
    private final String siteUrl;

    public HostRoutingFilter(SiteProperties site, BusinessProperties business) {
        this.hosts = site.hosts();
        this.siteUrl = business.siteUrl();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = hostOf(request);
        String target = null;
        if (host.equals(hosts.www().toLowerCase(Locale.ROOT))) {
            String query = request.getQueryString();
            target = siteUrl + request.getRequestURI() + (query == null ? "" : "?" + query);
        } else if (host.equals(hosts.orders().toLowerCase(Locale.ROOT))) {
            target = siteUrl + SecurityRoutes.CUSTOMER_AREA;
        } else if (host.equals(hosts.dashboard().toLowerCase(Locale.ROOT))) {
            target = siteUrl + SecurityRoutes.BACKOFFICE;
        }
        if (target == null) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());
        response.setHeader(HttpHeaders.LOCATION, target);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "max-age=3600");
        response.flushBuffer();
    }

    /** The request host, lower-case, without the port. */
    static String hostOf(HttpServletRequest request) {
        String host = request.getServerName();
        if (host == null) {
            return "";
        }
        return host.toLowerCase(Locale.ROOT);
    }
}
