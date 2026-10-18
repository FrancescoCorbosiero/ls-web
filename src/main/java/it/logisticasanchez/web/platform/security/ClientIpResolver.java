package it.logisticasanchez.web.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * The client IP for rate limits and IP hashes (chapter 10 §4): behind the proxy it is the last
 * {@code X-Forwarded-For} hop as resolved by {@link ForwardedHeadersFilter} — never the socket
 * address (the proxy) and never a value the client wrote further left in the header; without a
 * proxy (local, test) it is the socket address.
 */
@Component
public class ClientIpResolver {

    static final String ATTRIBUTE = ClientIpResolver.class.getName() + ".clientIp";

    public String resolve(HttpServletRequest request) {
        Object resolved = request.getAttribute(ATTRIBUTE);
        return resolved instanceof String ip ? ip : request.getRemoteAddr();
    }
}
