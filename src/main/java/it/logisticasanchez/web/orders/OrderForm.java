package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The order form shared by the customer area and the back office (CUS-04, CUS-07, ADM-11; ADR
 * 0029): one page with the lines as editable rows ({@code line.<n>.<field>}), the shipment data
 * and the recipient. It works without JavaScript — «Aggiungi riga» and «Elimina» are submit
 * buttons ({@code action=addLine}, {@code action=removeLine.<n>}) that round-trip everything typed
 * — and {@code /js/app.js} only adds rows in place and keeps the totals live. Rows left entirely
 * empty are ignored; the rules are {@link OrderValidator}'s (OP-R4…R8, Q-24, Q-35), the totals
 * {@link OrderTotals}' (OP-R5). Errors are keyed by input name ({@code line.2.heightCm},
 * {@code transportDocumentCode}, {@code lines} for the list as a whole) with message keys as values.
 * With {@code customers.addressBook} on (ENH-07, ADR 0033) the recipient section also carries the
 * address-book choice ({@code addressId}, «Usa questo destinatario» = {@code action=useAddress})
 * and «Salva il destinatario nella rubrica» ({@code saveRecipient=1}); they round-trip like every
 * other field and mean nothing while the flag is off.
 */
@Component
public class OrderForm {

    /** More rows than any real shipment request needs; a guard against abuse, not a business rule. */
    public static final int MAX_LINES = 50;

    static final List<String> LINE_FIELDS = List.of("kind", "heightCm", "lengthCm", "widthCm", "weightKg", "quantity");
    static final List<String> DETAIL_FIELDS =
            List.of("transportDocumentCode", "packageCount", "accessoryServiceId", "notes");
    static final List<String> RECIPIENT_FIELDS =
            List.of("name", "addressLine", "province", "city", "postalCode", "phone", "email");
    /** ENH-07: the address-book choice and «Salva il destinatario nella rubrica». */
    static final List<String> BOOK_FIELDS = List.of("addressId", "saveRecipient");

    private static final Pattern LINE_KEY = Pattern.compile("^line\\.([0-9]{1,3})\\.([A-Za-z]+)$");
    private static final Pattern REMOVE_ACTION = Pattern.compile("^removeLine\\.([0-9]{1,3})$");
    private static final String DASH = "–";
    private static final int TEMPLATE_INDEX = 999;

    private final OrderValidator validator;

    public OrderForm(OrderValidator validator) {
        this.validator = validator;
    }

    /** What the button pressed asks for. */
    public enum Action {
        SAVE,
        ADD_LINE,
        REMOVE_LINE,
        /** ENH-07 «Usa questo destinatario»: read only while {@code customers.addressBook} is on. */
        USE_ADDRESS,
        /** ENH-07 «Mostra la rubrica» of the staff form, after choosing the customer: the page again, as typed. */
        SHOW_ADDRESS_BOOK
    }

    /** One row as typed, with its totals when the row is valid («–» otherwise). */
    public record Row(int index, Map<String, String> values, String weightTotal, String volumeTotal) {

        public String get(String field) {
            return values.getOrDefault(field, "");
        }

        public String name(String field) {
            return "line." + index + "." + field;
        }

        boolean isBlank() {
            return LINE_FIELDS.stream().allMatch(f -> get(f).isEmpty());
        }
    }

    /** The page model: rows, the other fields as typed, the errors (input name → message key) and the totals of the valid rows. */
    public record Model(
            List<Row> rows,
            Map<String, String> values,
            Map<String, String> errors,
            String totalWeight,
            String totalVolume,
            int units) {

        public String value(String field) {
            return values.getOrDefault(field, "");
        }

        public String error(String field) {
            return errors.get(field);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean canAddRow() {
            return rows.size() < MAX_LINES;
        }

        public int maxRows() {
            return MAX_LINES;
        }

        /** True when a field of this row was refused. */
        public boolean hasError(Row row) {
            String prefix = "line." + row.index() + ".";
            return errors.keySet().stream().anyMatch(k -> k.startsWith(prefix));
        }

        /** The empty row that {@code /js/app.js} clones for «Aggiungi riga» (renumbered on insertion). */
        public Row template() {
            return new Row(TEMPLATE_INDEX, emptyRowValues(), DASH, DASH);
        }
    }

    /** The outcome of a save: the content when every rule holds, else the model with its errors. */
    public record Submission(OrderContent content, Model model) {
        public boolean valid() {
            return content != null;
        }
    }

    public Action action(Map<String, String> form) {
        String action = ItalianFormats.trim(form.get("action"));
        if ("addLine".equals(action)) {
            return Action.ADD_LINE;
        }
        if ("useAddress".equals(action)) {
            return Action.USE_ADDRESS;
        }
        if ("showAddressBook".equals(action)) {
            return Action.SHOW_ADDRESS_BOOK;
        }
        return REMOVE_ACTION.matcher(action).matches() ? Action.REMOVE_LINE : Action.SAVE;
    }

    /** The row index of {@code action=removeLine.<n>}, or -1. */
    public int removedIndex(Map<String, String> form) {
        Matcher m = REMOVE_ACTION.matcher(ItalianFormats.trim(form.get("action")));
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** A new order: one empty row, «Nessun servizio accessorio». */
    public Model blank() {
        return model(List.of(emptyRowValues()), defaults(), Map.of());
    }

    /** The wizard's draft (CUS-04), back from the summary. */
    public Model of(OrderDraft draft) {
        if (draft.lines().isEmpty() && draft.recipient() == null) {
            return blank();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (OrderDraft.Line line : draft.lines()) {
            rows.add(rowValues(
                    line.kind(), line.heightCm(), line.lengthCm(), line.widthCm(), line.weightKg(), line.quantity()));
        }
        Map<String, String> values = defaults();
        values.put("transportDocumentCode", draft.transportDocumentCode());
        values.put("packageCount", draft.packageCount());
        values.put(
                "accessoryServiceId",
                draft.accessoryServiceId() == null
                        ? ""
                        : draft.accessoryServiceId().toString());
        values.put("notes", draft.notes());
        recipientValues(values, draft.recipient());
        values.put("saveRecipient", draft.saveRecipient() ? "1" : "");
        return model(rows.isEmpty() ? List.of(emptyRowValues()) : rows, values, Map.of());
    }

    /** A stored order to edit (CUS-07, ADM-11). */
    public Model of(OrderView order) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (OrderView.Line line : order.lines()) {
            rows.add(rowValues(
                    line.kind(), line.heightCm(), line.lengthCm(), line.widthCm(), line.weightKg(), line.quantity()));
        }
        Map<String, String> values = defaults();
        values.put("transportDocumentCode", nullToEmpty(order.transportDocumentCode()));
        values.put(
                "packageCount",
                order.packageCount() == null ? "" : order.packageCount().toString());
        values.put(
                "accessoryServiceId",
                order.accessoryServiceId() == null
                        ? ""
                        : order.accessoryServiceId().toString());
        values.put("notes", nullToEmpty(order.customerNotes()));
        ShipmentOrder.Party r = order.recipient();
        recipientValues(
                values,
                new OrderDraft.Recipient(
                        r.name(), r.addressLine(), r.province(), r.city(), r.postalCode(), r.phone(), r.email()));
        return model(rows, values, Map.of());
    }

    /** The form as posted: rows in index order (renumbered from 0), every other field trimmed. */
    public Model read(Map<String, String> form) {
        TreeMap<Integer, Map<String, String>> byIndex = new TreeMap<>();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            Matcher m = LINE_KEY.matcher(entry.getKey());
            if (m.matches() && LINE_FIELDS.contains(m.group(2))) {
                byIndex.computeIfAbsent(Integer.parseInt(m.group(1)), i -> emptyRowValues())
                        .put(m.group(2), ItalianFormats.trim(entry.getValue()));
            }
        }
        List<Map<String, String>> rows = new ArrayList<>(byIndex.values());
        if (rows.size() > MAX_LINES) {
            rows = new ArrayList<>(rows.subList(0, MAX_LINES));
        }
        Map<String, String> values = defaults();
        for (String field : DETAIL_FIELDS) {
            values.put(field, ItalianFormats.trim(form.get(field)));
        }
        for (String field : RECIPIENT_FIELDS) {
            values.put(field, ItalianFormats.trim(form.get(field)));
        }
        for (String field : BOOK_FIELDS) {
            values.put(field, ItalianFormats.trim(form.get(field)));
        }
        return model(rows, values, Map.of());
    }

    /** ENH-07 «Usa questo destinatario»: the form as typed with the recipient fields of the chosen entry. */
    public Model withRecipient(Model form, OrderDraft.Recipient recipient) {
        Map<String, String> values = new LinkedHashMap<>(form.values());
        for (String field : RECIPIENT_FIELDS) {
            values.put(field, "");
        }
        recipientValues(values, recipient);
        return model(rowValues(form), values, Map.of());
    }

    /** The form as typed with one refused field (input name → message key). */
    public Model withError(Model form, String field, String messageKey) {
        return model(rowValues(form), form.values(), Map.of(field, messageKey));
    }

    /**
     * ENH-07 «Ordina di nuovo» (back office): a stored order as a new one — its lines, accessory
     * service, notes and recipient; the transport document and the number of packages left empty,
     * since every shipment has its own (Q-24).
     */
    public Model repeat(OrderView order) {
        Model stored = of(order);
        Map<String, String> values = new LinkedHashMap<>(stored.values());
        values.put("transportDocumentCode", "");
        values.put("packageCount", "");
        return model(rowValues(stored), values, Map.of());
    }

    public Model withRowAdded(Model form) {
        List<Map<String, String>> rows = rowValues(form);
        if (rows.size() < MAX_LINES) {
            rows.add(emptyRowValues());
        }
        return model(rows, form.values(), Map.of());
    }

    public Model withRowRemoved(Model form, int index) {
        List<Map<String, String>> rows = rowValues(form);
        if (index >= 0 && index < rows.size()) {
            rows.remove(index);
        }
        if (rows.isEmpty()) {
            rows.add(emptyRowValues());
        }
        return model(rows, form.values(), Map.of());
    }

    /** Every rule at once: the rows (blank ones ignored, at least one), the shipment data, the recipient. */
    public Submission validate(Model form) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<Map<String, String>> kept = new ArrayList<>();
        for (Row row : form.rows()) {
            if (!row.isBlank()) {
                kept.add(row.values());
            }
        }
        List<OrderDraft.Line> lines = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            OrderValidator.LineResult result = validator.line(kept.get(i));
            if (result.valid()) {
                OrderDraft.Line l = result.line();
                lines.add(new OrderDraft.Line(
                        i + 1L, l.kind(), l.heightCm(), l.lengthCm(), l.widthCm(), l.weightKg(), l.quantity()));
            } else {
                for (Map.Entry<String, String> e : result.errors().entrySet()) {
                    errors.put("line." + i + "." + e.getKey(), e.getValue());
                }
            }
        }
        if (kept.isEmpty()) {
            errors.put("lines", "orders.error.noLines");
        }
        Map<String, String> details = validator.details(form.values(), kept.isEmpty() ? List.of() : lines);
        details.remove("lines"); // already reported, and an invalid row is not a missing one
        errors.putAll(details);
        OrderValidator.RecipientResult recipient = validator.recipient(form.values());
        errors.putAll(recipient.errors());
        if (!errors.isEmpty()) {
            List<Map<String, String>> rows = kept.isEmpty() ? new ArrayList<>(List.of(emptyRowValues())) : kept;
            return new Submission(null, model(rows, form.values(), errors));
        }
        String packages = form.value("packageCount");
        String notes = form.value("notes");
        OrderContent content = new OrderContent(
                lines,
                form.value("transportDocumentCode"),
                packages.isEmpty() ? null : Integer.valueOf(packages),
                validator.storedServiceId(form.value("accessoryServiceId")),
                notes.isEmpty() ? null : notes,
                recipient.recipient());
        return new Submission(content, model(kept, form.values(), Map.of()));
    }

    private Model model(List<Map<String, String>> rowValues, Map<String, String> values, Map<String, String> errors) {
        List<Row> rows = new ArrayList<>();
        BigDecimal weight = BigDecimal.ZERO.setScale(2);
        BigDecimal volume = BigDecimal.ZERO.setScale(2);
        int units = 0;
        for (int i = 0; i < rowValues.size(); i++) {
            Map<String, String> v = rowValues.get(i);
            OrderValidator.LineResult result = isBlank(v) ? null : validator.line(v);
            if (result != null && result.valid()) {
                OrderDraft.Line l = result.line();
                weight = weight.add(l.weightTotal());
                volume = volume.add(l.volumeTotal());
                units += l.quantity();
                rows.add(new Row(
                        i,
                        Collections.unmodifiableMap(new LinkedHashMap<>(v)),
                        ItalianFormats.formatNumber(l.weightTotal(), 2),
                        ItalianFormats.formatNumber(l.volumeTotal(), 2)));
            } else {
                rows.add(new Row(i, Collections.unmodifiableMap(new LinkedHashMap<>(v)), DASH, DASH));
            }
        }
        return new Model(
                List.copyOf(rows),
                Collections.unmodifiableMap(new LinkedHashMap<>(values)),
                Collections.unmodifiableMap(new LinkedHashMap<>(errors)),
                ItalianFormats.formatNumber(weight, 2),
                ItalianFormats.formatNumber(volume, 2),
                units);
    }

    private static boolean isBlank(Map<String, String> row) {
        return LINE_FIELDS.stream().allMatch(f -> row.getOrDefault(f, "").isEmpty());
    }

    private static List<Map<String, String>> rowValues(Model form) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Row row : form.rows()) {
            rows.add(new LinkedHashMap<>(row.values()));
        }
        return rows;
    }

    private static Map<String, String> emptyRowValues() {
        Map<String, String> row = new LinkedHashMap<>();
        for (String field : LINE_FIELDS) {
            row.put(field, "");
        }
        return row;
    }

    private static Map<String, String> rowValues(
            LineKind kind, int heightCm, int lengthCm, int widthCm, BigDecimal weightKg, int quantity) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("kind", kind.name());
        row.put("heightCm", Integer.toString(heightCm));
        row.put("lengthCm", Integer.toString(lengthCm));
        row.put("widthCm", Integer.toString(widthCm));
        row.put("weightKg", weightKg.stripTrailingZeros().toPlainString().replace('.', ','));
        row.put("quantity", Integer.toString(quantity));
        return row;
    }

    private static Map<String, String> defaults() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : DETAIL_FIELDS) {
            values.put(field, "");
        }
        for (String field : RECIPIENT_FIELDS) {
            values.put(field, "");
        }
        for (String field : BOOK_FIELDS) {
            values.put(field, "");
        }
        return values;
    }

    private static void recipientValues(Map<String, String> values, OrderDraft.Recipient r) {
        if (r == null) {
            return;
        }
        values.put("name", nullToEmpty(r.name()));
        values.put("addressLine", nullToEmpty(r.addressLine()));
        values.put("province", nullToEmpty(r.province()));
        values.put("city", nullToEmpty(r.city()));
        values.put("postalCode", nullToEmpty(r.postalCode()));
        values.put("phone", nullToEmpty(r.phone()));
        values.put("email", nullToEmpty(r.email()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
