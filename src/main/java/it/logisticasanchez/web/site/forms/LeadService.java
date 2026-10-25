package it.logisticasanchez.web.site.forms;

import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.platform.config.RetentionProperties;
import it.logisticasanchez.web.platform.events.DomainEvent;
import it.logisticasanchez.web.platform.events.DomainEvents;
import it.logisticasanchez.web.platform.security.IpHasher;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores a valid submission as a {@code lead} (status {@code NEW}) and queues the office e-mail
 * (EML-08) in the same transaction (PUB-11): either both rows exist or neither. Honeypot
 * submissions never reach this service. Retention per Q-18 ({@code ls.retention.leads-months});
 * the IP is stored only as an HMAC and the user agent truncated.
 */
@Service
public class LeadService {

    private final LeadRepository leads;
    private final FormMail mail;
    private final NotificationService notifications;
    private final IpHasher ipHasher;
    private final DomainEvents events;
    private final ObjectMapper json;
    private final RetentionProperties retention;
    private final Clock clock;

    public LeadService(
            LeadRepository leads,
            FormMail mail,
            NotificationService notifications,
            IpHasher ipHasher,
            DomainEvents events,
            ObjectMapper json,
            RetentionProperties retention,
            Clock clock) {
        this.leads = leads;
        this.mail = mail;
        this.notifications = notifications;
        this.ipHasher = ipHasher;
        this.events = events;
        this.json = json;
        this.retention = retention;
        this.clock = clock;
    }

    /** Writes the lead and the outbox row; returns the lead id. */
    @Transactional
    public long store(FormSubmission form, String clientIp, String userAgent) {
        Instant now = clock.instant();
        LocalDate retainUntil = now.atZone(ZoneOffset.UTC).toLocalDate().plusMonths(retention.leadsMonths());
        String agent = userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 255));
        long id = leads.insert(
                form, json.writeValueAsString(form.payload()), now, retainUntil, ipHasher.hash(clientIp), agent);
        notifications.enqueue(mail.compose(form));
        events.publish(new DomainEvent.LeadStored(id));
        return id;
    }

    /** Erasure on request (NFR-03): anonymises every lead of the address; returns how many. */
    @Transactional
    public int anonymiseByEmail(String email) {
        return leads.anonymiseByEmail(email.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Export on request (NFR-03): the leads of the address, as rows of plain values. */
    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> exportByEmail(String email) {
        return leads.exportByEmail(email.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Retention (Q-18): anonymises every lead past its {@code retain_until}; returns how many. */
    @Transactional
    public int anonymiseExpired(Instant now) {
        return leads.anonymiseExpired(now.atZone(ZoneOffset.UTC).toLocalDate());
    }
}
