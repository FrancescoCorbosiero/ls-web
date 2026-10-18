package it.logisticasanchez.web.platform.security;

import it.logisticasanchez.web.platform.config.SecurityProperties;
import it.logisticasanchez.web.platform.config.SiteProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The security headers, CSP and caching rules set by the app, not the proxy (chapter 10 §4,
 * PUB-05, PD-02, ADR 0018). Public routes get exactly the header set of
 * {@code legacy/ls-home/public/_headers} (with {@code connect-src 'self'} since everything is
 * same-origin); the customer area and back office get a CSP without {@code unsafe-*} with a
 * per-request nonce, {@code frame-ancestors 'none'} and {@code Cache-Control: no-store}. HSTS is
 * sent only where TLS terminates for real ({@code ls.security.hsts.enabled}, production).
 * Headers are set before the handler runs, so a handler may still override Cache-Control
 * (for example {@code no-referrer} on the link page).
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /** {@code _headers} values, verbatim except {@code connect-src}. */
    public static final String PUBLIC_CSP = "default-src 'self'; script-src 'self' 'inline-speculation-rules';"
            + " style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self';"
            + " frame-src https://www.google.com https://maps.google.com; form-action 'self';"
            + " base-uri 'self'; frame-ancestors 'self'; object-src 'none'; upgrade-insecure-requests";

    public static final String X_CONTENT_TYPE_OPTIONS = "nosniff";
    public static final String REFERRER_POLICY = "strict-origin-when-cross-origin";
    public static final String X_FRAME_OPTIONS = "SAMEORIGIN";
    public static final String HSTS = "max-age=31536000";
    public static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), browsing-topics=()";

    public static final String NO_STORE = "no-store";
    public static final String NO_CACHE = "no-cache";

    private final RouteRegistry routes;
    private final SiteProperties site;
    private final boolean hsts;

    public SecurityHeadersFilter(RouteRegistry routes, SiteProperties site, SecurityProperties security) {
        this.routes = routes;
        this.site = site;
        this.hsts = security.hsts().enabled();
    }

    /** The private-area CSP for a given nonce (ADR 0018). */
    public static String privateCsp(String nonce) {
        return "default-src 'self'; script-src 'self' 'nonce-" + nonce + "'; style-src 'self';"
                + " img-src 'self' data:; font-src 'self'; connect-src 'self'; form-action 'self';"
                + " frame-ancestors 'none'; base-uri 'none'; object-src 'none'";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", X_CONTENT_TYPE_OPTIONS);
        response.setHeader("Referrer-Policy", REFERRER_POLICY);
        response.setHeader("X-Frame-Options", X_FRAME_OPTIONS);
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        if (hsts) {
            response.setHeader("Strict-Transport-Security", HSTS);
        }
        String path = request.getRequestURI();
        if (RouteRegistry.isPrivateArea(path)) {
            String nonce = CspNonce.generate();
            request.setAttribute(CspNonce.ATTRIBUTE, nonce);
            response.setHeader("Content-Security-Policy", privateCsp(nonce));
            response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        } else {
            response.setHeader("Content-Security-Policy", PUBLIC_CSP);
            response.setHeader(HttpHeaders.CACHE_CONTROL, cacheControlFor(path));
        }
        chain.doFilter(request, response);
    }

    /** PD-02: fonts immutable one year, images one week, everything else no-cache + ETag. */
    String cacheControlFor(String path) {
        if (!routes.isPublicCacheable(path)) {
            return NO_STORE;
        }
        if (path.startsWith("/fonts/")) {
            return "public, max-age=" + site.cache().fontsMaxAge().toSeconds() + ", immutable";
        }
        if (path.startsWith("/img/") || (path.endsWith(".png") && path.indexOf('/', 1) < 0)) {
            return "public, max-age=" + site.cache().imagesMaxAge().toSeconds();
        }
        return NO_CACHE;
    }
}
