package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.orders.OrderSearch;
import it.logisticasanchez.web.orders.OrderStatus;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The filters of «Ordini» (ADM-05) as typed, the search they make and the message of a refused
 * one; shared by the list and its CSV export (ENH-08). The text field {@code codice} searches the
 * codes (M1) or, with the office tools on ({@code admin.dashboard}, ADR 0035), also the recipient,
 * the recipient's city, the transport document and the customer.
 */
record OrderFilters(Map<String, String> values, OrderSearch search, String error) {

    /** The «tracking» value for the shipments not yet delivered (the home's «Spedizioni in corso»). */
    static final String UNDELIVERED = "in-corso";

    static OrderFilters of(Map<String, String> params, boolean freeText) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : new String[] {"stato", "tracking", "cliente", "da", "a", "codice"}) {
            values.put(name, ItalianFormats.trim(params.get(name)));
        }
        if (!params.containsKey("stato")) {
            values.put("stato", OrderStatus.APPROVED.name()); // the legacy «registered» list
        }
        try {
            boolean undelivered = UNDELIVERED.equals(values.get("tracking"));
            String text = values.get("codice");
            OrderSearch search = new OrderSearch(
                    status(values.get("stato")),
                    undelivered ? null : integer(values.get("tracking")).orElse(null),
                    integer(values.get("cliente")).map(Integer::longValue).orElse(null),
                    date(values.get("da")),
                    date(values.get("a")),
                    freeText ? null : text,
                    undelivered,
                    freeText ? text : null);
            if (search.from() != null && search.to() != null && search.from().isAfter(search.to())) {
                throw new RuleViolationException("La data di inizio deve precedere quella di fine.");
            }
            return new OrderFilters(values, search, null);
        } catch (RuleViolationException e) {
            return new OrderFilters(values, OrderSearch.none(), e.detail());
        }
    }

    private static OrderStatus status(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new RuleViolationException("Stato sconosciuto.");
        }
    }

    static Optional<Integer> integer(String value) {
        String raw = ItalianFormats.trim(value);
        return raw.matches("^[0-9]{1,9}$") ? Optional.of(Integer.parseInt(raw)) : Optional.empty();
    }

    private static LocalDate date(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new RuleViolationException("Inserisci una data valida (anno-mese-giorno).");
        }
    }
}
