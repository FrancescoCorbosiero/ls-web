package it.logisticasanchez.web.platform.web.error;

import it.logisticasanchez.web.platform.ratelimit.RateLimitExceededException;
import it.logisticasanchez.web.platform.web.RequestId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every exception that reaches Spring MVC to the error model (ADR 0002): explicit 4xx for
 * client errors (the legacy answered 500 to most of them, SEC-09), 500 with the generic reason
 * and no internals for everything else. Each handler writes the response through
 * {@link ErrorResponder}, so JSON and HTML answers share one catalogue.
 */
@ControllerAdvice
public class PlatformExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PlatformExceptionHandler.class);

    private final ErrorResponder responder;

    public PlatformExceptionHandler(ErrorResponder responder) {
        this.responder = responder;
    }

    @ExceptionHandler(ClientErrorException.class)
    public void clientError(ClientErrorException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Map<String, String> headers = e instanceof RateLimitExceededException limited
                ? Map.of("Retry-After", Long.toString(limited.retryAfterSeconds()))
                : Map.of();
        responder.respond(request, response, e.code(), e.detail(), headers);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public void notFound(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        responder.respond(request, response, ErrorCode.NOT_FOUND, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Map<String, String> headers = e.getSupportedMethods() == null
                ? Map.of()
                : Map.of("Allow", String.join(", ", e.getSupportedMethods()));
        responder.respond(request, response, ErrorCode.METHOD_NOT_ALLOWED, null, headers);
    }

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class})
    public void unsupportedMediaType(Exception e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        responder.respond(request, response, ErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        ServletRequestBindingException.class,
        MethodArgumentTypeMismatchException.class,
        MaxUploadSizeExceededException.class
    })
    public void badRequest(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        responder.respond(request, response, ErrorCode.BAD_REQUEST, null);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        BindException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class
    })
    public void validationFailed(Exception e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        responder.respond(request, response, ErrorCode.VALIDATION_FAILED, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public void accessDenied(AccessDeniedException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        responder.respond(request, response, ErrorCode.FORBIDDEN, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public void unauthenticated(AuthenticationException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        responder.respond(request, response, ErrorCode.UNAUTHENTICATED, null);
    }

    @ExceptionHandler(Exception.class)
    public void unexpected(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // The stack trace goes to the log with the request id; the client gets the generic reason.
        log.error(
                "Unhandled exception on {} {} requestId={}",
                request.getMethod(),
                request.getRequestURI(),
                RequestId.of(request),
                e);
        responder.respond(request, response, ErrorCode.INTERNAL_ERROR, null);
    }
}
