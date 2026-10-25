package it.logisticasanchez.web.site;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The redirect table of the public site (PUB-04, ADR 0013): the rules of
 * {@code legacy/ls-home/public/_redirects} (except the {@code /mono/*} proxy line, which the app
 * answers itself — API-01…03), {@code /index.html} → {@code /} and, for every page, the URL
 * without its trailing slash and its {@code index.html} form (chapter 06 §6.8). Every rule is a
 * 301 keeping the query string; the browser keeps the fragment, so {@code /index.html#chisiamo}
 * still reaches its section. Host rules ({@code www}, {@code ordini.}, {@code dashboard.}) are
 * the platform's {@code HostRoutingFilter}.
 */
public class SiteRedirectFilter extends OncePerRequestFilter {

    /** The {@code _redirects} rules at ls-home@9b78785, old template URLs first. */
    static final Map<String, String> LEGACY_REDIRECTS = Map.ofEntries(
            Map.entry("/about.html", "/chi-siamo/"),
            Map.entry("/service.html", "/servizi/"),
            Map.entry("/contact.html", "/contatti/"),
            Map.entry("/about", "/chi-siamo/"),
            Map.entry("/service", "/servizi/"),
            Map.entry("/contact", "/contatti/"),
            Map.entry("/servizi.html", "/servizi/"),
            Map.entry("/contatti.html", "/contatti/"),
            Map.entry("/tracking.html", "/tracking/"),
            Map.entry("/preventivo.html", "/preventivo/"));

    private final Map<String, String> table;

    public SiteRedirectFilter() {
        this.table = buildTable();
    }

    /** The complete table, path → target, for the test and the filter. */
    public static Map<String, String> buildTable() {
        Map<String, String> table = new LinkedHashMap<>(LEGACY_REDIRECTS);
        for (SitePages.Page page : SitePages.all()) {
            table.put(page.path() + "index.html", page.path());
            String withoutSlash = page.pathWithoutSlash();
            if (withoutSlash != null) {
                table.put(withoutSlash, page.path());
            }
        }
        return table;
    }

    public Map<String, String> table() {
        return table;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String target = table.get(request.getRequestURI());
        if (target == null
                || !(request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))) {
            chain.doFilter(request, response);
            return;
        }
        String query = request.getQueryString();
        response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());
        response.setHeader(HttpHeaders.LOCATION, target + (query == null ? "" : "?" + query));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "max-age=3600");
        response.flushBuffer();
    }
}
