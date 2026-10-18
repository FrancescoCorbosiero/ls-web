package it.logisticasanchez.web.platform.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * The one JSON error body of the app (ADR 0002), for API, compatibility and HTMX requests:
 * {@code {status, code, reason, detail?, requestId, timestamp}}. {@code reason} is the Italian
 * sentence shown to people; {@code detail} is optional, never an exception message or a stack
 * trace.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String reason, String detail, String requestId, Instant timestamp) {

    public static ApiError of(ErrorCode code, String detail, String requestId, Instant now) {
        return new ApiError(code.status().value(), code.code(), code.reason(), detail, requestId, now);
    }
}
