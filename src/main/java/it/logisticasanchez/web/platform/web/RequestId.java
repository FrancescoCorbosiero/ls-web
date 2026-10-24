package it.logisticasanchez.web.platform.web;

import jakarta.servlet.http.HttpServletRequest;

/** Request-id conventions: the MDC key, the response header and the request attribute. */
public final class RequestId {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String ATTRIBUTE = RequestId.class.getName();

    private RequestId() {}

    /** The id of this request, or null outside the request-id filter. */
    public static String of(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String s ? s : null;
    }
}
