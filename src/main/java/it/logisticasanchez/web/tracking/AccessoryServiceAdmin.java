package it.logisticasanchez.web.tracking;

import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ENH-14 (flag {@code admin.enumerations}, ADR 0043): the accessory services as the back office
 * manages them — one list in one order, each row saying where it shows: on the order (EP-20
 * included), in «Servizi accessori» of {@code /servizi/}, among the extras of the quote wizard.
 * The legacy rows keep their ids, codes and place on the order (API-02): they are renamed, moved
 * and switched off, never deleted; item 0, «Nessun servizio accessorio», is not touched at all. A
 * new row takes the next id and a code made from its first name, which never changes afterwards.
 * A row an order or a saved quote uses cannot be deleted (the foreign keys say so): it is switched
 * off instead. Errors are keyed by input name.
 */
@Service
public class AccessoryServiceAdmin {

    /** Rows 0–5 are the legacy enumeration (V009): always on the order, never deleted. */
    static final int LAST_LEGACY_ID = 5;

    static final int LABEL_MAX = 120;
    static final int DESCRIPTION_MAX = 300;
    static final int HINT_MAX = 120;

    /** The symbols of {@code /img/icons.svg} the office can give a service of {@code /servizi/}. */
    public static final Map<String, String> ICONS = icons();

    private static final String COLUMNS = "id, name, label, is_none, active, sort_order, in_orders, on_site,"
            + " in_quote, description, icon, quote_hint";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public AccessoryServiceAdmin(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Every row, in the order the three places show them. */
    public List<Row> all() {
        return jdbc.sql("select " + COLUMNS + " from accessory_service order by sort_order")
                .query(AccessoryServiceAdmin::row)
                .list();
    }

    public Optional<Row> find(int id) {
        return jdbc.sql("select " + COLUMNS + " from accessory_service where id = :id")
                .param("id", id)
                .query(AccessoryServiceAdmin::row)
                .optional();
    }

    /** Adds a service at the end of the list, active. */
    @Transactional
    public Map<String, String> create(long staffUserId, Map<String, String> form) {
        Fields fields = Fields.of(form, false);
        Map<String, String> errors = validate(fields, null);
        if (!errors.isEmpty()) {
            return errors;
        }
        String name = uniqueName(code(fields.label()));
        int sortOrder = jdbc.sql("select coalesce(max(sort_order), 0) + 1 from accessory_service")
                .query(Integer.class)
                .single();
        int id = jdbc.sql("insert into accessory_service (name, label, sort_order, in_orders, on_site, in_quote,"
                        + " description, icon, quote_hint) values (:name, :label, :sort, :inOrders, :onSite, :inQuote,"
                        + " :description, :icon, :hint) returning id")
                .param("name", name)
                .param("label", fields.label())
                .param("sort", sortOrder)
                .param("inOrders", fields.inOrders())
                .param("onSite", fields.onSite())
                .param("inQuote", fields.inQuote())
                .param("description", blankToNull(fields.description()))
                .param("icon", blankToNull(fields.icon()))
                .param("hint", blankToNull(fields.hint()))
                .query(Integer.class)
                .single();
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "accessory.service.create")
                .entity("accessory_service", (long) id, name)
                .change(null, fields.audited()));
        return Map.of();
    }

    /** Changes a service other than item 0; a legacy row stays on the order. */
    @Transactional
    public Map<String, String> update(long staffUserId, Row before, Map<String, String> form) {
        if (before.locked()) {
            throw new IllegalArgumentException("item 0 is not editable");
        }
        Fields fields = Fields.of(form, before.legacy());
        Map<String, String> errors = validate(fields, before.id());
        if (!errors.isEmpty()) {
            return errors;
        }
        jdbc.sql("update accessory_service set label = :label, in_orders = :inOrders, on_site = :onSite,"
                        + " in_quote = :inQuote, description = :description, icon = :icon, quote_hint = :hint"
                        + " where id = :id")
                .param("label", fields.label())
                .param("inOrders", fields.inOrders())
                .param("onSite", fields.onSite())
                .param("inQuote", fields.inQuote())
                .param("description", blankToNull(fields.description()))
                .param("icon", blankToNull(fields.icon()))
                .param("hint", blankToNull(fields.hint()))
                .param("id", before.id())
                .update();
        Map<String, Object> was = Fields.of(before).audited();
        Map<String, Object> is = fields.audited();
        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();
        for (String key : is.keySet()) {
            if (!Objects.equals(was.get(key), is.get(key))) {
                changedBefore.put(key, was.get(key));
                changedAfter.put(key, is.get(key));
            }
        }
        if (!changedAfter.isEmpty()) {
            audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "accessory.service.update")
                    .entity("accessory_service", (long) before.id(), before.name())
                    .change(changedBefore, changedAfter));
        }
        return Map.of();
    }

    /**
     * Swaps a service with the one before ({@code up}) or after it; item 0 stays first. False when
     * there is nothing to swap with.
     */
    @Transactional
    public boolean move(long staffUserId, Row row, boolean up) {
        if (row.locked()) {
            return false;
        }
        Optional<Row> other = jdbc.sql("select " + COLUMNS + " from accessory_service where not is_none and sort_order "
                        + (up ? "< :sort order by sort_order desc" : "> :sort order by sort_order") + " limit 1")
                .param("sort", row.sortOrder())
                .query(AccessoryServiceAdmin::row)
                .optional();
        if (other.isEmpty()) {
            return false;
        }
        // sort_order is unique and checked row by row: park one row before swapping.
        setSortOrder(row.id(), -1);
        setSortOrder(other.get().id(), row.sortOrder());
        setSortOrder(row.id(), other.get().sortOrder());
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "accessory.service.move")
                .entity("accessory_service", (long) row.id(), row.name())
                .change(
                        Map.of("sortOrder", row.sortOrder()),
                        Map.of("sortOrder", other.get().sortOrder())));
        return true;
    }

    /** Switches a service off (hidden everywhere; still served by EP-20 when on the order) or back on. */
    @Transactional
    public void setActive(long staffUserId, Row row, boolean active) {
        if (row.locked()) {
            throw new IllegalArgumentException("item 0 is always active");
        }
        if (row.active() == active) {
            return;
        }
        jdbc.sql("update accessory_service set active = :active where id = :id")
                .param("active", active)
                .param("id", row.id())
                .update();
        audit.record(AuditEntry.of(
                        AuditActorType.STAFF, staffUserId, "accessory.service." + (active ? "activate" : "deactivate"))
                .entity("accessory_service", (long) row.id(), row.name()));
    }

    /**
     * Deletes a service added after the legacy ones. Throws the foreign-key violation when an
     * order or a saved quote uses it: the transaction is rolled back and the caller says so.
     */
    @Transactional
    public void delete(long staffUserId, Row row) {
        if (!row.deletable()) {
            throw new IllegalArgumentException("legacy rows are never deleted");
        }
        jdbc.sql("delete from accessory_service where id = :id")
                .param("id", row.id())
                .update();
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "accessory.service.delete")
                .entity("accessory_service", (long) row.id(), row.name())
                .change(Fields.of(row).audited(), null));
    }

    /** The code of a new service: its name in capitals without accents, words joined by «_». */
    static String code(String label) {
        String ascii = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String code =
                ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (code.length() > 50) {
            code = code.substring(0, 50).replaceAll("_+$", "");
        }
        return code.isEmpty() ? "SERVIZIO" : code;
    }

    private String uniqueName(String base) {
        String name = base;
        for (int n = 2; taken("name", name, null); n++) {
            name = base + "_" + n;
        }
        return name;
    }

    private Map<String, String> validate(Fields fields, Integer self) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (fields.label().isEmpty()) {
            errors.put("nome", "Scrivi il nome del servizio.");
        } else if (fields.label().length() > LABEL_MAX) {
            errors.put("nome", "Il nome può avere al massimo " + LABEL_MAX + " caratteri.");
        } else if (taken("label", fields.label(), self)) {
            errors.put("nome", "C'è già un servizio con questo nome.");
        }
        if (!fields.inOrders() && !fields.onSite() && !fields.inQuote()) {
            errors.put("posti", "Scegli almeno un posto in cui compare.");
        }
        if (fields.description().length() > DESCRIPTION_MAX) {
            errors.put("descrizione", "La descrizione può avere al massimo " + DESCRIPTION_MAX + " caratteri.");
        } else if (fields.onSite() && fields.description().isEmpty()) {
            errors.put("descrizione", "Scrivi la descrizione che compare sul sito.");
        }
        if (!fields.icon().isEmpty() && !ICONS.containsKey(fields.icon())) {
            errors.put("icona", "Scegli un'icona dell'elenco.");
        } else if (fields.onSite() && fields.icon().isEmpty()) {
            errors.put("icona", "Scegli l'icona che compare sul sito.");
        }
        if (fields.hint().length() > HINT_MAX) {
            errors.put("suggerimento", "Il suggerimento può avere al massimo " + HINT_MAX + " caratteri.");
        }
        return errors;
    }

    /** True when another row has this value, case aside ({@code column} is name or label). */
    private boolean taken(String column, String value, Integer self) {
        return jdbc.sql("select count(*) from accessory_service where lower(" + column + ") = lower(:value)"
                                + " and id <> :self")
                        .param("value", value)
                        .param("self", self == null ? -1 : self)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void setSortOrder(int id, int sortOrder) {
        jdbc.sql("update accessory_service set sort_order = :sort where id = :id")
                .param("sort", sortOrder)
                .param("id", id)
                .update();
    }

    private static String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("label"),
                rs.getBoolean("is_none"),
                rs.getBoolean("active"),
                rs.getInt("sort_order"),
                rs.getBoolean("in_orders"),
                rs.getBoolean("on_site"),
                rs.getBoolean("in_quote"),
                rs.getString("description"),
                rs.getString("icon"),
                rs.getString("quote_hint"));
    }

    private static Map<String, String> icons() {
        Map<String, String> icons = new LinkedHashMap<>();
        icons.put("i-arrow-down-to-line", "Freccia verso terra");
        icons.put("i-calendar-clock", "Calendario con orologio");
        icons.put("i-calendar-check", "Calendario con spunta");
        icons.put("i-phone-call", "Telefono");
        icons.put("i-repeat", "Frecce in cerchio");
        icons.put("i-file-check", "Documento con spunta");
        icons.put("i-shield-check", "Scudo");
        icons.put("i-truck", "Camion");
        icons.put("i-pallet", "Pallet");
        icons.put("i-boxes", "Scatole");
        icons.put("i-package-check", "Pacco con spunta");
        icons.put("i-warehouse", "Magazzino");
        icons.put("i-clock", "Orologio");
        icons.put("i-timer", "Cronometro");
        icons.put("i-ruler", "Righello");
        icons.put("i-route", "Percorso");
        icons.put("i-map-pin", "Segnaposto");
        icons.put("i-hard-hat", "Casco da cantiere");
        icons.put("i-triangle-alert", "Attenzione");
        icons.put("i-headset", "Cuffie");
        return Collections.unmodifiableMap(icons);
    }

    /** An accessory service with everything the back office shows and edits. */
    public record Row(
            int id,
            String name,
            String label,
            boolean none,
            boolean active,
            int sortOrder,
            boolean inOrders,
            boolean onSite,
            boolean inQuote,
            String description,
            String icon,
            String quoteHint) {

        /** Item 0: always the first choice of the order, never changed. */
        public boolean locked() {
            return none;
        }

        /** One of the legacy rows 0–5 (API-02): always on the order, never deleted. */
        public boolean legacy() {
            return id <= LAST_LEGACY_ID;
        }

        public boolean deletable() {
            return !legacy();
        }

        public String iconName() {
            return icon == null ? null : ICONS.get(icon);
        }
    }

    /** The trimmed form, by input name; a legacy row stays on the order whatever the form says. */
    private record Fields(
            String label,
            boolean inOrders,
            boolean onSite,
            boolean inQuote,
            String description,
            String icon,
            String hint) {

        static Fields of(Map<String, String> form, boolean legacy) {
            return new Fields(
                    ItalianFormats.trim(form.get("nome")),
                    legacy || form.containsKey("ordini"),
                    form.containsKey("sito"),
                    form.containsKey("preventivo"),
                    ItalianFormats.trim(form.get("descrizione")),
                    ItalianFormats.trim(form.get("icona")),
                    ItalianFormats.trim(form.get("suggerimento")));
        }

        static Fields of(Row row) {
            return new Fields(
                    row.label(),
                    row.inOrders(),
                    row.onSite(),
                    row.inQuote(),
                    Objects.requireNonNullElse(row.description(), ""),
                    Objects.requireNonNullElse(row.icon(), ""),
                    Objects.requireNonNullElse(row.quoteHint(), ""));
        }

        Map<String, Object> audited() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("label", label);
            values.put("inOrders", inOrders);
            values.put("onSite", onSite);
            values.put("inQuote", inQuote);
            values.put("description", description.isEmpty() ? "-" : description);
            values.put("icon", icon.isEmpty() ? "-" : icon);
            values.put("quoteHint", hint.isEmpty() ? "-" : hint);
            return values;
        }
    }
}
