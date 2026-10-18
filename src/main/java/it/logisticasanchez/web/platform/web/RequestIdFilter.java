package it.logisticasanchez.web.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id (the proxy's {@code X-Request-Id} when well-formed, otherwise a
 * UUID), puts it in the MDC and the response, and writes one access-log line per request with
 * method, path (never the query string, which may carry a one-time token), status and
 * duration — never a body, a header value or an address (chapter 10 §5, ADR 0019).
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger access = LoggerFactory.getLogger("it.logisticasanchez.web.access");
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = requestId(request.getHeader(RequestId.HEADER));
        request.setAttribute(RequestId.ATTRIBUTE, id);
        response.setHeader(RequestId.HEADER, id);
        MDC.put(RequestId.MDC_KEY, id);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            access.info(
                    "access method={} path={} status={} ms={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    millis);
            MDC.remove(RequestId.MDC_KEY);
        }
    }

    static String requestId(String incoming) {
        if (incoming != null && WELL_FORMED.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
