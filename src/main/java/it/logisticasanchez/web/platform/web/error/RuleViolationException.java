package it.logisticasanchez.web.platform.web.error;

/**
 * A business rule refused the action (an illegal state transition, a duplicate code, an amount
 * that does not parse): answered 422 with an Italian detail the caller may show (ADR 0002).
 */
public class RuleViolationException extends ClientErrorException {

    public RuleViolationException(String detail) {
        super(ErrorCode.VALIDATION_FAILED, detail);
    }
}
