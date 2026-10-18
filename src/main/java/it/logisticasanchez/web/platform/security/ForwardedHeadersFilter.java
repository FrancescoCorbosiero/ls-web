package it.logisticasanchez.web.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forwarded-header trust of exactly one proxy hop (chapter 10 §4, PD-13). When
 * {@code ls.security.proxy.trust-forwarded-headers} is true: the client IP is the <em>last</em>
 * value of {@code X-Forwarded-For} (the one the proxy appended; anything to its left came from
 * the client and is ignored), and scheme, host and port come from the {@code X-Forwarded-*}
 * headers through Spring's {@link ForwardedHeaderFilter}, which also hides those headers from
 * the application. When false, the headers are ignored entirely and the socket address is the
 * client. The resolved IP replaces {@code getRemoteAddr()} for everything downstream.
 */
public class ForwardedHeadersFilter extends OncePerRequestFilter {

    private final boolean trustForwardedHeaders;
    private final ForwardedHeaderFilter delegate = new ForwardedHeaderFilter();

    public ForwardedHeadersFilter(boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!trustForwardedHeaders) {
            request.setAttribute(ClientIpResolver.ATTRIBUTE, request.getRemoteAddr());
            chain.doFilter(request, response);
            return;
        }
        String clientIp = lastForwardedFor(request.getHeader("X-Forwarded-For"), request.getRemoteAddr());
        request.setAttribute(ClientIpResolver.ATTRIBUTE, clientIp);
        delegate.doFilter(request, response, (req, res) -> chain.doFilter(
                new RemoteAddressRequest((HttpServletRequest) req, clientIp), res));
    }

    /** The last comma-separated value, trimmed; the socket address when the header is absent. */
    static String lastForwardedFor(String header, String fallback) {
        if (header == null || header.isBlank()) {
            return fallback;
        }
        String[] hops = header.split(",");
        String last = hops[hops.length - 1].trim();
        return last.isEmpty() ? fallback : last;
    }

    private static final class RemoteAddressRequest extends HttpServletRequestWrapper {
        private final String remoteAddr;

        RemoteAddressRequest(HttpServletRequest request, String remoteAddr) {
            super(request);
            this.remoteAddr = remoteAddr;
        }

        @Override
        public String getRemoteAddr() {
            return remoteAddr;
        }

        @Override
        public String getRemoteHost() {
            return remoteAddr;
        }
    }
}
