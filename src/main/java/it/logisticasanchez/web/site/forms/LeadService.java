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
}
