package it.logisticasanchez.web.site.forms;

/** A field failed validation: answered as 422 {@code invalid:<field>} (chapter 06 §5.6). */
public class InvalidFormException extends RuntimeException {

    private final String field;

    public InvalidFormException(String field) {
        super("invalid:" + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
