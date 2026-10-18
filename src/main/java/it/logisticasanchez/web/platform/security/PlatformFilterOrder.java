package it.logisticasanchez.web.platform.security;

import org.springframework.core.Ordered;

/**
 * Registration order of the platform servlet filters, all ahead of the Spring Security chain
 * (order -100): request id, forwarded headers, host routing, security headers, ETag.
 */
public final class PlatformFilterOrder {

    public static final int REQUEST_ID = Ordered.HIGHEST_PRECEDENCE;
    public static final int FORWARDED_HEADERS = Ordered.HIGHEST_PRECEDENCE + 10;
    public static final int HOST_ROUTING = Ordered.HIGHEST_PRECEDENCE + 20;
    public static final int SECURITY_HEADERS = Ordered.HIGHEST_PRECEDENCE + 30;
    public static final int ETAG = Ordered.HIGHEST_PRECEDENCE + 40;

    private PlatformFilterOrder() {}
}
