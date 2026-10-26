package it.logisticasanchez.web.customers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A data change request as the templates see it (ENH-16, ADR 0038): the values the customer asked
 * for next to the customer's present ones.
 */
public record ChangeRequestView(
        long id,
        long customerId,
        String customerEmail,
        CustomerChangeStatus status,
        CustomerIdentity requested,
        CustomerIdentity current,
        Instant requestedAt,
        Instant closedAt,
        String declineReason) {

    /** The fields the request changes, in the form's order: the input name, the value now and the one asked for («-» when empty). */
    public List<Field> changes() {
        List<Field> fields = new ArrayList<>();
        add(fields, "companyName", current.companyName(), requested.companyName());
        add(fields, "vatNumber", current.vatNumber(), requested.vatNumber());
        add(fields, "taxCode", current.taxCode(), requested.taxCode());
        return fields;
    }

    private static void add(List<Field> fields, String name, String now, String asked) {
        if (!Objects.equals(now, asked)) {
            fields.add(new Field(name, now == null ? "-" : now, asked == null ? "-" : asked));
        }
    }

    public record Field(String name, String current, String requested) {}
}
