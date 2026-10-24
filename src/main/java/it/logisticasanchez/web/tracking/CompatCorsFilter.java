package it.logisticasanchez.web.tracking;

import it.logisticasanchez.web.platform.config.CompatProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CORS for the kept compatibility endpoints (chapter 02 §10.2 rule 5,
 * Q-39, ADR 0010): exact origins from {@code ls.compat.cors.allowed-origins} (the apex by
 * default), methods {@code GET, POST, OPTIONS}, header {@code Content-Type}, no credentials, a
 * one-hour preflight cache. A preflight from an allowed origin is answered here with 204; any
 * other request goes on without CORS headers, so a foreign page gets nothing readable.
 */
public class CompatCorsFilter extends OncePerRequestFilter {

    /** Only the kept endpoints answer CORS; retired paths get no CORS headers (chapter 02 §10.3). */
    static final Set<String> PATHS = Set.of(
            CompatApiController.TRACE_PATH,
            CompatApiController.TRACKING_STATES_PATH,
            CompatApiController.ACCESSORY_SERVICES_PATH);

    private final Set<String> allowedOrigins;

    public CompatCorsFilter(CompatProperties compat) {
        this.allowedOrigins = compat.cors().allowedOrigins().stream()
                .map(o -> o.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        boolean allowed =
                origin != null && allowedOrigins.contains(origin.trim().toLowerCase(Locale.ROOT));
        boolean preflight = "OPTIONS".equals(request.getMethod())
                && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;
        if (allowed) {
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin.trim());
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            if (preflight) {
                response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
                response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
                response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
                response.setStatus(HttpStatus.NO_CONTENT.value());
                response.flushBuffer();
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
