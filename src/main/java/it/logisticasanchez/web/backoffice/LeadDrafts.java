package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.orders.LineKind;
import it.logisticasanchez.web.orders.OrderDraft;
import it.logisticasanchez.web.site.forms.LeadView;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ENH-06 (ADR 0036): a website request as the start of a customer or an order typed by staff.
 * Only what the public form asked is carried over; the forms validate as when typed.
 *
 * <ul>
 *   <li>«Crea cliente»: company (else the name), e-mail, phone, and the pick-up city and CAP as
 *       the customer's (the request leaves address and province to type).
 *   <li>«Crea ordine»: one line from the pallet format — EPAL 120×80, industrial 120×100, half
 *       80×60, quarter 60×40, «altro» with no size —, the height, the quantity when it is a whole
 *       number and the weight of one unit (the total divided by the quantity, rounded to the kg);
 *       «Sponda idraulica» as the accessory service when it is offered; the goods as notes; the
 *       delivery city and CAP as the recipient's.
 * </ul>
 */
final class LeadDrafts {

    private static final Map<String, int[]> PALLET_SIZES = Map.of(
            "eur", new int[] {120, 80},
            "industriale", new int[] {120, 100},
            "mezzo", new int[] {80, 60},
            "quarto", new int[] {60, 40});

    private LeadDrafts() {}

    /** The fields of «Nuovo cliente» (ADM-12) the request knows. */
    static Map<String, String> customer(LeadView lead) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("companyName", text(lead.company() != null ? lead.company() : lead.name()));
        values.put("addressLine", "");
        values.put("province", "");
        values.put("city", text(lead.pickupCity()));
        values.put("postalCode", text(lead.pickupPostalCode()));
        values.put("phone", text(lead.phone()));
        values.put("vatNumber", "");
        values.put("taxCode", "");
        return values;
    }

    /** The order line the request describes (empty values for what it does not say). */
    static Map<String, String> line(LeadView lead) {
        Map<String, String> line = new LinkedHashMap<>();
        if (!lead.quote()) {
            return line;
        }
        String format = lead.palletFormat() == null ? "" : lead.palletFormat();
        int[] size = PALLET_SIZES.get(format);
        line.put("kind", "eur".equals(format) ? LineKind.EPAL.name() : LineKind.GENERIC.name());
        if (size != null) {
            line.put("lengthCm", Integer.toString(size[0]));
            line.put("widthCm", Integer.toString(size[1]));
        }
        if (lead.maxHeightCm() != null) {
            line.put(
                    "heightCm",
                    lead.maxHeightCm().setScale(0, RoundingMode.HALF_UP).toPlainString());
        }
        Integer quantity = wholeNumber(lead.quantity());
        if (quantity != null && quantity > 0) {
            line.put("quantity", quantity.toString());
            if (lead.totalWeightKg() != null) {
                BigDecimal each = lead.totalWeightKg().divide(BigDecimal.valueOf(quantity), 0, RoundingMode.HALF_UP);
                line.put("weightKg", each.max(BigDecimal.ONE).toPlainString());
            }
        }
        return line;
    }

    /** «Sponda idraulica», the one extra of the public form with an accessory service of the same name. */
    static Integer accessoryService(LeadView lead, List<TrackingCatalog.ServiceView> services) {
        for (String extra : lead.extras()) {
            for (TrackingCatalog.ServiceView service : services) {
                if (!service.none()
                        && service.label().toLowerCase(Locale.ROOT).equals(extra.toLowerCase(Locale.ROOT))) {
                    return service.id();
                }
            }
        }
        return null;
    }

    static String notes(LeadView lead) {
        return lead.goodsDescription() == null ? "" : lead.goodsDescription();
    }

    /** The recipient's city and CAP: name, address and province are typed by staff. */
    static OrderDraft.Recipient recipient(LeadView lead) {
        return new OrderDraft.Recipient(null, null, null, lead.deliveryCity(), lead.deliveryPostalCode(), null, null);
    }

    /** The quantity when it is a whole number (the form accepts «2,5»: then staff decide the lines). */
    static Integer wholeNumber(BigDecimal quantity) {
        if (quantity == null || quantity.stripTrailingZeros().scale() > 0) {
            return null;
        }
        return quantity.intValueExact();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
