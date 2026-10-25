package it.logisticasanchez.web.platform.web.error;

import org.springframework.http.HttpStatus;

/**
 * The error catalogue of the app (ADR 0002): one HTTP status, one stable machine code and one
 * Italian reason per entry. The reasons of the legacy {@code ErrorEnum} (12a §3.2) are reused
 * where they fit; the texts marked "new" are owner-reviewed at the next gate.
 */
public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "bad_request", "Richiesta non valida. Controlla nuovamente i dati inseriti."),
    VALIDATION_FAILED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "validation_failed",
            "Richiesta non valida. Controlla nuovamente i dati inseriti."),
    INVALID_RECIPIENT(
            HttpStatus.UNPROCESSABLE_CONTENT, "invalid_recipient", "Indirizzo email di destinazione non valido."),
    UNAUTHENTICATED(
            HttpStatus.UNAUTHORIZED, "unauthenticated", "Non disponi delle autorizzazioni necessarie per procedere."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Username o password errate."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden", "Non disponi dei permessi necessari per questo tipo di operazione."),
    /** New text: a CSRF token missing or stale (the session changed or expired). */
    CSRF_REJECTED(HttpStatus.FORBIDDEN, "csrf_rejected", "La sessione non è più valida. Ricarica la pagina e riprova."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not_found", "Impossibile recuperare i dati richiesti."),
    /** New text: a page or resource that existed and was removed (PUB-04 redirects). */
    GONE(HttpStatus.GONE, "gone", "Questa pagina non esiste più."),
    /** New text. */
    METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Operazione non consentita per questa risorsa."),
    CONFLICT(
            HttpStatus.CONFLICT,
            "conflict",
            "E' già stata effettuata una registrazione con questi dati. Si prega di riprovare con dati diversi."),
    CODE_CONFLICT(
            HttpStatus.CONFLICT,
            "code_conflict",
            "E' già stata effettuata una registrazione con questo codice. Si prega di riprovare con un nuovo codice."),
    /** New text. */
    UNSUPPORTED_MEDIA_TYPE(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Formato della richiesta non supportato."),
    /** New text. */
    TOO_MANY_REQUESTS(
            HttpStatus.TOO_MANY_REQUESTS,
            "too_many_requests",
            "Hai fatto troppe richieste in poco tempo. Riprova tra qualche minuto."),
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal_error",
            "Qualcosa è andato storto. Si prega di riprovare oppure contattare il centro logistico."),
    UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "unavailable",
            "Servizio non disponibile. Si prega di riprovare più tardi oppure contattare il centro logistico.");

    private final HttpStatus status;
    private final String code;
    private final String reason;

    ErrorCode(HttpStatus status, String code, String reason) {
        this.status = status;
        this.code = code;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String reason() {
        return reason;
    }

    /** The generic entry for a status produced outside the app's own handlers (container errors). */
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 410 -> GONE;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> VALIDATION_FAILED;
            case 429 -> TOO_MANY_REQUESTS;
            case 503 -> UNAVAILABLE;
            default -> INTERNAL_ERROR;
        };
    }
}
