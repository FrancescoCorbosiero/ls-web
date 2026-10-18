package it.logisticasanchez.web.platform.web.error;

import java.util.Objects;

/**
 * A client error raised by a service or controller and answered with its {@link ErrorCode}
 * (status 4xx) through the error model, never as 500. {@code detail} is safe to show (no
 * internals, no personal data).
 */
public class ClientErrorException extends RuntimeException {

    private final ErrorCode code;
    private final String detail;

    public ClientErrorException(ErrorCode code) {
        this(code, null);
    }

    public ClientErrorException(ErrorCode code, String detail) {
        super(code.code() + (detail == null ? "" : ": " + detail));
        if (code.status().is5xxServerError()) {
            throw new IllegalArgumentException("ClientErrorException carries a 4xx code, got " + code);
        }
        this.code = Objects.requireNonNull(code, "code");
        this.detail = detail;
    }

    public ErrorCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
