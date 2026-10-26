package it.logisticasanchez.web.site.notices;

import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notice banner of the public site (ENH-17, flag {@code site.noticeBanner}; ADR 0041): an
 * ADMIN writes a notice — up to 300 characters, an optional link to a page of the site or an
 * https address, INFO or WARNING, an optional period (Europe/Rome) — and every public page shows
 * the one in force in its header, rendered by the server. {@link #current} is what the pages
 * read: null while the flag is off or nothing is in force, kept {@value #CACHE_SECONDS} seconds
 * and forgotten at every change made here. Changes are audited.
 */
@Service
public class SiteNoticeService {

    static final long CACHE_SECONDS = 30;
    static final int MESSAGE_MAX = 300;
    static final int LINK_MAX = 300;
    static final int LABEL_MAX = 60;
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private final SiteNoticeRepository notices;
    private final AuditService audit;
    private final FeatureFlags flags;
    private final Clock clock;
    private volatile Cached cached;

    public SiteNoticeService(SiteNoticeRepository notices, AuditService audit, FeatureFlags flags, Clock clock) {
        this.notices = notices;
        this.audit = audit;
        this.flags = flags;
        this.clock = clock;
    }

    /** A validated notice to save; {@code startsAt}, {@code endsAt} null when open-ended. */
    record Draft(
            String message, String linkUrl, String linkLabel, NoticeLevel level, Instant startsAt, Instant endsAt) {}

    /** A save: the notice's id, or the refused fields (input name → message) with nothing stored. */
    public record Result(Long id, Map<String, String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private record Cached(SiteNoticeView notice, Instant until) {}

    /** The notice the public pages show now; null while the flag is off or nothing is in force. */
    public SiteNoticeView current() {
        if (!flags.isEnabled(FlagKey.SITE_NOTICE_BANNER)) {
            return null;
        }
        Instant now = clock.instant();
        Cached snapshot = cached;
        if (snapshot == null || !now.isBefore(snapshot.until())) {
            snapshot = new Cached(notices.inForce(now).orElse(null), now.plus(Duration.ofSeconds(CACHE_SECONDS)));
            cached = snapshot;
        }
        SiteNoticeView notice = snapshot.notice();
        return notice != null && notice.inForce(now) ? notice : null;
    }

    @Transactional(readOnly = true)
    public List<SiteNoticeView> all() {
        return notices.all(clock.instant());
    }

    @Transactional(readOnly = true)
    public Optional<SiteNoticeView> find(long id) {
        return notices.find(id);
    }

    /** Validates the form (the names of the back-office inputs) and saves the notice. */
    @Transactional
    public Result create(long staffUserId, Map<String, String> form) {
        Map<String, String> errors = new LinkedHashMap<>();
        String message = ItalianFormats.trim(form.get("testo")).replaceAll("\\s+", " ");
        if (message.isEmpty()) {
            errors.put("testo", "Scrivi il testo dell'avviso.");
        } else if (message.length() > MESSAGE_MAX) {
            errors.put("testo", "Il testo può avere al massimo " + MESSAGE_MAX + " caratteri.");
        }
        String linkUrl = ItalianFormats.trim(form.get("link"));
        String linkLabel = ItalianFormats.trim(form.get("etichetta"));
        if (linkUrl.isEmpty() && !linkLabel.isEmpty()) {
            errors.put("link", "Inserisci l'indirizzo del link, oppure togli il testo del link.");
        } else if (!linkUrl.isEmpty()) {
            if (linkUrl.length() > LINK_MAX || !isAllowedLink(linkUrl)) {
                errors.put("link", "Il link è una pagina del sito (per esempio /contatti/) o un indirizzo https://.");
            }
            if (linkLabel.isEmpty()) {
                errors.put("etichetta", "Scrivi il testo del link, per esempio «Scopri di più».");
            } else if (linkLabel.length() > LABEL_MAX) {
                errors.put("etichetta", "Il testo del link può avere al massimo " + LABEL_MAX + " caratteri.");
            }
        }
        NoticeLevel level = "WARNING".equals(form.get("livello")) ? NoticeLevel.WARNING : NoticeLevel.INFO;
        Instant startsAt = instant(form.get("dal"), "dal", errors);
        Instant endsAt = instant(form.get("al"), "al", errors);
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            errors.put("al", "La fine deve venire dopo l'inizio.");
        } else if (endsAt != null && !endsAt.isAfter(clock.instant())) {
            errors.put("al", "La fine è già passata: scegli una data futura.");
        }
        if (!errors.isEmpty()) {
            return new Result(null, errors);
        }
        Draft draft = new Draft(
                message,
                linkUrl.isEmpty() ? null : linkUrl,
                linkUrl.isEmpty() ? null : linkLabel,
                level,
                startsAt,
                endsAt);
        long id = notices.insert(draft, staffUserId);
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "site.notice.create")
                .entity("site_notice", id, null)
                .change(
                        null,
                        Map.of(
                                "level", level.name(),
                                "startsAt", startsAt == null ? "-" : startsAt.toString(),
                                "endsAt", endsAt == null ? "-" : endsAt.toString())));
        cached = null;
        return new Result(id, Map.of());
    }

    /** Ends the notice now; false when it had already ended. */
    @Transactional
    public boolean end(long staffUserId, long id) {
        boolean ended = notices.end(id, clock.instant()) > 0;
        if (ended) {
            audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "site.notice.end")
                    .entity("site_notice", id, null));
            cached = null;
        }
        return ended;
    }

    @Transactional
    public boolean delete(long staffUserId, long id) {
        boolean deleted = notices.delete(id) > 0;
        if (deleted) {
            audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "site.notice.delete")
                    .entity("site_notice", id, null));
            cached = null;
        }
        return deleted;
    }

    /** A path of the site ({@code /…}, not {@code //…}) or an {@code https://} address, without spaces or quotes. */
    static boolean isAllowedLink(String url) {
        if (url.chars().anyMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'' || c == '<' || c == '>')) {
            return false;
        }
        return (url.startsWith("/") && !url.startsWith("//")) || url.matches("^https://[A-Za-z0-9.-]+(/.*)?$");
    }

    /** A {@code datetime-local} value read in Europe/Rome; null when empty; an error when not a date. */
    private static Instant instant(String value, String field, Map<String, String> errors) {
        String text = ItalianFormats.trim(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text).atZone(ROME).toInstant();
        } catch (DateTimeParseException e) {
            errors.put(field, "Inserisci data e ora (giorno, mese, anno, ore e minuti).");
            return null;
        }
    }
}
