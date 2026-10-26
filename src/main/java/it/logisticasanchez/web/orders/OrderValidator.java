package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.platform.config.OrdersProperties;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Server-side rules of the order wizard (CUS-04; chapter 03 §6 corrected by §10 OP-04, OP-07,
 * OP-09, OP-10, OP-12, OP-14; Q-24, Q-35): a line needs a type and integers ≥ 1 for the
 * dimensions, the unit weight and the quantity ({@code ls.orders.lines.integers-only}); the
 * transport document code is required ({@code ls.orders.transport-document.required}); the
 * package count is optional digits; the accessory service must be a seeded one (item 0 = none);
 * the recipient needs name, address, province, city and a five-digit CAP, phone and e-mail
 * optional. Errors are field → message key.
 */
@Component
public class OrderValidator {

    static final int DDT_MAX = 100;
    static final int NOTES_MAX = 2000;
    static final int NAME_MAX = 200;
    static final int ADDRESS_MAX = 200;
    static final int CITY_MAX = 100;
    static final int PROVINCE_MAX = 60;

    private final OrdersProperties rules;
    private final TrackingCatalog catalog;

    public OrderValidator(OrdersProperties rules, TrackingCatalog catalog) {
        this.rules = rules;
        this.catalog = catalog;
    }

    /** The «Aggiungi Pallet/Colli» dialog. */
    public LineResult line(Map<String, String> input) {
        Map<String, String> errors = new LinkedHashMap<>();
        LineKind kind = null;
        String rawKind = ItalianFormats.trim(input.get("kind"));
        if (rawKind.isEmpty()) {
            errors.put("kind", "orders.error.kindRequired");
        } else {
            try {
                kind = LineKind.valueOf(rawKind);
            } catch (IllegalArgumentException e) {
                errors.put("kind", "orders.error.kindRequired");
            }
        }
        Integer height = positiveInteger(input, "heightCm", errors);
        Integer length = positiveInteger(input, "lengthCm", errors);
        Integer width = positiveInteger(input, "widthCm", errors);
        Integer quantity = positiveInteger(input, "quantity", errors);
        BigDecimal weight = null;
        String rawWeight = ItalianFormats.trim(input.get("weightKg"));
        if (rawWeight.isEmpty()) {
            errors.put("weightKg", "orders.error.required");
        } else if (rules.lines().integersOnly()) { // Q-35
            Optional<Integer> parsed = ItalianFormats.parsePositiveInteger(rawWeight);
            if (parsed.isEmpty()) {
                errors.put("weightKg", "orders.error.integer");
            } else {
                weight = BigDecimal.valueOf(parsed.get()).setScale(2);
            }
        } else {
            Optional<BigDecimal> parsed = ItalianFormats.parseDecimal(rawWeight);
            if (parsed.isEmpty() || parsed.get().signum() <= 0 || parsed.get().scale() > 2) {
                errors.put("weightKg", "orders.error.weight");
            } else {
                weight = parsed.get().setScale(2);
            }
        }
        if (height != null
                && rules.lines().maxHeightCm() != null
                && height > rules.lines().maxHeightCm()) {
            errors.put("heightCm", "orders.error.tooLarge");
        }
        if (weight != null
                && rules.lines().maxWeightKg() != null
                && weight.compareTo(BigDecimal.valueOf(rules.lines().maxWeightKg())) > 0) {
            errors.put("weightKg", "orders.error.tooLarge");
        }
        if (!errors.isEmpty()) {
            return new LineResult(null, errors);
        }
        return new LineResult(new OrderDraft.Line(0, kind, height, length, width, weight, quantity), errors);
    }

    /** The order fields below the lines; {@code lines} is the «at least one line» check. */
    public Map<String, String> details(Map<String, String> input, List<OrderDraft.Line> lines) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (lines.isEmpty()) {
            errors.put("lines", "orders.error.noLines");
        }
        String ddt = ItalianFormats.trim(input.get("transportDocumentCode"));
        if (ddt.isEmpty()) {
            if (rules.transportDocument().required()) { // Q-24
                errors.put("transportDocumentCode", "orders.error.required");
            }
        } else if (ddt.length() > DDT_MAX) {
            errors.put("transportDocumentCode", "orders.error.tooLong");
        }
        String packages = ItalianFormats.trim(input.get("packageCount"));
        if (!packages.isEmpty() && !packages.matches("^[0-9]{1,6}$")) {
            errors.put("packageCount", "orders.error.integer");
        }
        if (!isService(input.get("accessoryServiceId"))) {
            errors.put("accessoryServiceId", "orders.error.service");
        }
        if (ItalianFormats.trim(input.get("notes")).length() > NOTES_MAX) {
            errors.put("notes", "orders.error.tooLong");
        }
        return errors;
    }

    /** The chosen accessory service as stored (after {@link #isService}): null for «Nessun servizio accessorio» (V005). */
    public Integer storedServiceId(String raw) {
        String value = ItalianFormats.trim(raw);
        if (value.isEmpty()) {
            return null;
        }
        return catalog.orderService(Integer.parseInt(value))
                .filter(s -> !s.none())
                .map(TrackingCatalog.ServiceView::id)
                .orElse(null);
    }

    /**
     * True when the raw value is empty (none) or names a service of the order (item 0 included);
     * never one shown only on the public pages (ENH-14).
     */
    public boolean isService(String raw) {
        String value = ItalianFormats.trim(raw);
        if (value.isEmpty()) {
            return true;
        }
        return value.matches("^[0-9]{1,6}$")
                && catalog.orderService(Integer.parseInt(value)).isPresent();
    }

    public RecipientResult recipient(Map<String, String> input) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = required(input, "name", NAME_MAX, errors);
        String addressLine = required(input, "addressLine", ADDRESS_MAX, errors);
        String province = required(input, "province", PROVINCE_MAX, errors);
        String city = required(input, "city", CITY_MAX, errors);
        String postalCode = ItalianFormats.trim(input.get("postalCode"));
        if (postalCode.isEmpty()) {
            errors.put("postalCode", "orders.error.required");
        } else if (!ItalianFormats.isCap(postalCode)) {
            errors.put("postalCode", "orders.error.cap");
        }
        String phone = null;
        String rawPhone = ItalianFormats.trim(input.get("phone"));
        if (!rawPhone.isEmpty()) {
            Optional<String> normalised = ItalianFormats.normalisePhone(rawPhone);
            if (normalised.isEmpty()) {
                errors.put("phone", "orders.error.phone");
            } else {
                phone = normalised.get();
            }
        }
        String email = null;
        String rawEmail = ItalianFormats.normaliseEmail(input.get("email"));
        if (!rawEmail.isEmpty()) {
            if (!ItalianFormats.isEmail(rawEmail)) {
                errors.put("email", "orders.error.email");
            } else {
                email = rawEmail;
            }
        }
        if (!errors.isEmpty()) {
            return new RecipientResult(null, errors);
        }
        return new RecipientResult(
                new OrderDraft.Recipient(name, addressLine, province, city, postalCode, phone, email), errors);
    }

    /** Non-blocking Q-35 warnings for the summary page: tall or heavy units. */
    public List<String> warnings(List<OrderDraft.Line> lines) {
        boolean tall = lines.stream().anyMatch(l -> l.heightCm() > rules.lines().warnHeightCm());
        boolean heavy = lines.stream()
                .anyMatch(l ->
                        l.weightKg().compareTo(BigDecimal.valueOf(rules.lines().warnWeightKg())) > 0);
        List<String> warnings = new java.util.ArrayList<>();
        if (tall) {
            warnings.add("orders.warning.height");
        }
        if (heavy) {
            warnings.add("orders.warning.weight");
        }
        return warnings;
    }

    private static Integer positiveInteger(Map<String, String> input, String field, Map<String, String> errors) {
        String raw = ItalianFormats.trim(input.get(field));
        if (raw.isEmpty()) {
            errors.put(field, "orders.error.required");
            return null;
        }
        Optional<Integer> parsed = ItalianFormats.parsePositiveInteger(raw);
        if (parsed.isEmpty()) {
            errors.put(field, "orders.error.integer");
            return null;
        }
        return parsed.get();
    }

    private static String required(Map<String, String> input, String field, int max, Map<String, String> errors) {
        String value = ItalianFormats.trim(input.get(field));
        if (value.isEmpty()) {
            errors.put(field, "orders.error.required");
        } else if (value.length() > max) {
            errors.put(field, "orders.error.tooLong");
        }
        return value;
    }

    public record LineResult(OrderDraft.Line line, Map<String, String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public record RecipientResult(OrderDraft.Recipient recipient, Map<String, String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }
}
