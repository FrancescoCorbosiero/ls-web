package it.logisticasanchez.web.site.hours;

import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * «Aperto ora / Chiuso ora» (ENH-18, flag {@code site.openNow}; ADR 0042): the closure days an
 * ADMIN keeps under «Impostazioni» ({@code office_closure}, V022) and the openings of the next
 * {@value #DAYS} days the public pages carry for their small script ({@link #openingsJson}), which
 * compares them with the visitor's clock: a page kept in a cache never shows a stale state. The
 * openings are kept {@value #CACHE_SECONDS} seconds and forgotten at every change made here.
 */
@Service
public class OfficeClosureService {

    /** Days of openings in a page: more than any cache keeps it. */
    static final int DAYS = 14;

    static final long CACHE_SECONDS = 60;
    /** A range typed at once: enough for August, not a mistake of a year. */
    static final int MAX_RANGE_DAYS = 62;

    private final JdbcClient jdbc;
    private final OpeningHours hours;
    private final AuditService audit;
    private final FeatureFlags flags;
    private final Clock clock;
    private volatile Cached cached;

    public OfficeClosureService(
            JdbcClient jdbc, BusinessProperties business, AuditService audit, FeatureFlags flags, Clock clock) {
        this.jdbc = jdbc;
        this.hours = new OpeningHours(business.hours());
        this.audit = audit;
        this.flags = flags;
        this.clock = clock;
    }

    /** A closure day as the back office lists it. */
    public record Closure(LocalDate day, String label) {}

    private record Cached(String json, Instant until) {}

    /**
     * The openings of the next {@value #DAYS} days (from today in Rome) as JSON pairs of epoch
     * milliseconds, {@code [[start,end],…]}; null while the flag is off.
     */
    public String openingsJson() {
        if (!flags.isEnabled(FlagKey.SITE_OPEN_NOW)) {
            return null;
        }
        Instant now = clock.instant();
        Cached snapshot = cached;
        if (snapshot == null || !now.isBefore(snapshot.until())) {
            LocalDate today = now.atZone(OpeningHours.ROME).toLocalDate();
            String json = hours.intervals(today, DAYS, closures(today, today.plusDays(DAYS))).stream()
                    .map(i -> "[" + i.start().toEpochMilli() + "," + i.end().toEpochMilli() + "]")
                    .collect(Collectors.joining(",", "[", "]"));
            snapshot = new Cached(json, now.plus(Duration.ofSeconds(CACHE_SECONDS)));
            cached = snapshot;
        }
        return snapshot.json();
    }

    /** True when the office is open now (the same rule as the script, for the tests and the back office). */
    @Transactional(readOnly = true)
    public boolean isOpenNow() {
        Instant now = clock.instant();
        LocalDate today = now.atZone(OpeningHours.ROME).toLocalDate();
        return hours.isOpen(now, closures(today, today));
    }

    /** The closure days from today on, in order. */
    @Transactional(readOnly = true)
    public List<Closure> upcoming() {
        LocalDate today = clock.instant().atZone(OpeningHours.ROME).toLocalDate();
        return jdbc.sql("select day, label from office_closure where day >= :today order by day")
                .param("today", Date.valueOf(today))
                .query((rs, n) -> new Closure(rs.getDate("day").toLocalDate(), rs.getString("label")))
                .list();
    }

    /**
     * Adds the days from {@code dal} to {@code al} (one day when {@code al} is empty) with an
     * optional reason; a day already closed keeps its reason. Errors by input name.
     */
    @Transactional
    public Map<String, String> add(long staffUserId, Map<String, String> form) {
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDate today = clock.instant().atZone(OpeningHours.ROME).toLocalDate();
        LocalDate from = day(form.get("dal"), "dal", errors);
        LocalDate to = ItalianFormats.trim(form.get("al")).isEmpty() ? from : day(form.get("al"), "al", errors);
        String label = ItalianFormats.trim(form.get("motivo"));
        if (from == null && !errors.containsKey("dal")) {
            errors.put("dal", "Scegli il giorno di chiusura.");
        }
        if (from != null && from.isBefore(today)) {
            errors.put("dal", "Il giorno è già passato.");
        }
        if (from != null && to != null && to.isBefore(from)) {
            errors.put("al", "L'ultimo giorno viene dopo il primo.");
        } else if (from != null && to != null && from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            errors.put("al", "Al massimo " + MAX_RANGE_DAYS + " giorni alla volta.");
        }
        if (label.length() > 100) {
            errors.put("motivo", "Il motivo può avere al massimo 100 caratteri.");
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        int added = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            added += jdbc.sql("insert into office_closure (day, label, created_by) values (:day, :label, :staff)"
                            + " on conflict (day) do nothing")
                    .param("day", Date.valueOf(day))
                    .param("label", label.isEmpty() ? null : label)
                    .param("staff", staffUserId)
                    .update();
        }
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "office.closure.add")
                .change(null, Map.of("from", from.toString(), "to", to.toString(), "days", added)));
        cached = null;
        return Map.of();
    }

    /** Removes a closure day; false when it was not there. */
    @Transactional
    public boolean remove(long staffUserId, LocalDate day) {
        boolean removed = jdbc.sql("delete from office_closure where day = :day")
                        .param("day", Date.valueOf(day))
                        .update()
                > 0;
        if (removed) {
            audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "office.closure.remove")
                    .change(Map.of("day", day.toString()), null));
            cached = null;
        }
        return removed;
    }

    /** Forgets the kept openings (tests that change the closure days behind the service's back). */
    void evictCache() {
        cached = null;
    }

    private Set<LocalDate> closures(LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.sql("select day from office_closure where day between :from and :to")
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query((rs, n) -> rs.getDate("day").toLocalDate())
                .list());
    }

    private static LocalDate day(String value, String field, Map<String, String> errors) {
        String text = ItalianFormats.trim(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            errors.put(field, "Inserisci una data valida.");
            return null;
        }
    }
}
