package it.logisticasanchez.web.platform.web.error;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.web.RequestId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes an error answer in the shape the caller understands (ADR 0002): the JSON schema for
 * API ({@code /api/**}, {@code /mono/**}, {@code /actuator/**}), HTMX ({@code HX-Request}),
 * JSON-sending and JSON-accepting requests; an HTML page from {@code templates/error/} for
 * browsers. Used by the
 * exception handler, the error controller, the security entry point and the access-denied
 * handler, so every error of the app looks the same and no framework page ever shows.
 */
@Component
public class ErrorResponder {

    private static final Set<Integer> PAGES = Set.of(401, 403, 404, 429);
    private static final List<String> JSON_PATH_PREFIXES = List.of("/api/", "/mono/", "/actuator/");

    private final ObjectMapper json;
    private final SpringTemplateEngine templates;
    private final LocaleResolver localeResolver;
    private final BusinessProperties business;
    private final Clock clock;

    public ErrorResponder(
            ObjectMapper json,
            SpringTemplateEngine templates,
            LocaleResolver localeResolver,
            BusinessProperties business,
            Clock clock) {
        this.json = json;
        this.templates = templates;
        this.localeResolver = localeResolver;
        this.business = business;
        this.clock = clock;
    }

    /** True when the answer must be the JSON schema rather than an HTML page. */
    public boolean wantsJson(HttpServletRequest request) {
        String path = originalPath(request);
        for (String prefix : JSON_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        if ("true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            return true; // a client that sent JSON reads JSON
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank()) {
            return false;
        }
        String lower = accept.toLowerCase(Locale.ROOT);
        return lower.contains("application/json") && !lower.contains("text/html");
    }

    /** Writes the error, with any extra headers (for example {@code Retry-After}). */
    public void respond(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode code,
            String detail,
            Map<String, String> headers)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        // resetBuffer() keeps the headers the platform filters set (CSP, request id …) and
        // drops any body a handler had started; reset() would drop the headers too.
        response.resetBuffer();
        response.setStatus(code.status().value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.forEach(response::setHeader);
        String requestId = RequestId.of(request);
        if (requestId != null) {
            response.setHeader(RequestId.HEADER, requestId);
        }
        if (wantsJson(request)) {
            // JSON is UTF-8 by definition (RFC 8259): no charset parameter on the media type.
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), ApiError.of(code, detail, requestId, clock.instant()));
            response.getOutputStream().flush();
        } else {
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Context context = new Context(localeResolver.resolveLocale(request));
            context.setVariable("status", code.status().value());
            context.setVariable("code", code.code());
            context.setVariable("reason", code.reason());
            context.setVariable("detail", detail);
            context.setVariable("requestId", requestId);
            context.setVariable("business", business);
            String template = PAGES.contains(code.status().value())
                    ? "error/" + code.status().value()
                    : "error/error";
            templates.process(template, context, response.getWriter());
        }
        response.flushBuffer();
    }

    public void respond(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        respond(request, response, code, detail, Map.of());
    }

    /** The path the client asked for, also on an error dispatch to {@code /error}. */
    static String originalPath(HttpServletRequest request) {
        Object uri = request.getAttribute("jakarta.servlet.error.request_uri");
        return uri instanceof String s ? s : request.getRequestURI();
    }
}
