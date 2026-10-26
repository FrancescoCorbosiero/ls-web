package it.logisticasanchez.web.site.notices;

import java.time.Instant;

/** A notice of the public site (ENH-17, ADR 0041); {@code startsAt}, {@code endsAt} null when open-ended. */
public record SiteNoticeView(
        long id,
        String message,
        String linkUrl,
        String linkLabel,
        NoticeLevel level,
        Instant startsAt,
        Instant endsAt,
        Instant createdAt) {

    public boolean warning() {
        return level == NoticeLevel.WARNING;
    }

    /** True while the notice is shown: started (or no start) and not ended. */
    public boolean inForce(Instant now) {
        return (startsAt == null || !startsAt.isAfter(now)) && (endsAt == null || endsAt.isAfter(now));
    }

    /** {@code IN_CORSO}, {@code PROGRAMMATO} or {@code CONCLUSO}: the key of its state on the back-office list. */
    public String state(Instant now) {
        if (inForce(now)) {
            return "IN_CORSO";
        }
        return endsAt != null && !endsAt.isAfter(now) ? "CONCLUSO" : "PROGRAMMATO";
    }
}
