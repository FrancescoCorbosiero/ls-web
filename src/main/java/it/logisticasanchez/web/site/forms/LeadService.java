package it.logisticasanchez.web.site.forms;

import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.config.RetentionProperties;
import it.logisticasanchez.web.platform.events.DomainEvent;
import it.logisticasanchez.web.platform.events.DomainEvents;
import it.logisticasanchez.web.platform.security.IpHasher;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores a valid submission as a {@code lead} (status {@code NEW}) and queues the office e-mail
 * (EML-08) in the same transaction (PUB-11): either both rows exist or neither. Honeypot
 * submissions never reach this service. Retention per Q-18 ({@code ls.retention.leads-months});
 * the IP is stored only as an HMAC and the user agent truncated. ENH-06 (ADR 0036): the
 * requests of the back-office inbox, their state machine (chapter 08 §2.4) and their link to the
 * customer and the order made from them, every change audited; the flag {@code leads.inbox} is
 * checked by the screens that call these methods.
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
    private final AuditService audit;
    private final Clock clock;

    public LeadService(
            LeadRepository leads,
            FormMail mail,
            NotificationService notifications,
            IpHasher ipHasher,
            DomainEvents events,
            ObjectMapper json,
            RetentionProperties retention,
            AuditService audit,
            Clock clock) {
        this.leads = leads;
        this.mail = mail;
        this.notifications = notifications;
        this.ipHasher = ipHasher;
        this.events = events;
        this.json = json;
        this.retention = retention;
        this.audit = audit;
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

    /** Erasure on request (NFR-03, ADR 0036): anonymises the leads linked to the customer; returns how many. */
    @Transactional
    public int anonymiseByCustomer(long customerId) {
        return leads.anonymiseByCustomer(customerId);
    }

    /** Export on request (NFR-03): the leads of the address and those linked to the customer (ADR 0036). */
    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> exportFor(String email, Long customerId) {
        return leads.exportFor(email.trim().toLowerCase(java.util.Locale.ROOT), customerId);
    }

    /** Export on request (NFR-03): the leads of the address, as rows of plain values. */
    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> exportByEmail(String email) {
        return leads.exportByEmail(email.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * ENH-06: a page of the requests in these states, newest first, whose name, company, e-mail,
     * phone, cities or subject contain {@code text} (ignoring case); anonymised ones never appear.
     */
    @Transactional(readOnly = true)
    public Page<LeadView> inbox(Collection<LeadStatus> statuses, String text, int page, int size) {
        String q = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        String like = q.isEmpty()
                ? ""
                : "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        PageRequest request = PageRequest.of(Math.max(0, page), size);
        return new PageImpl<>(
                leads.page(statuses, like, (int) request.getOffset(), size), request, leads.count(statuses, like));
    }

    /** ENH-06: the new requests, for the count in the navigation. */
    @Transactional(readOnly = true)
    public long countNew() {
        return leads.count(List.of(LeadStatus.NEW), "");
    }

    @Transactional(readOnly = true)
    public Optional<LeadView> find(long id) {
        return leads.find(id);
    }

    /** ENH-06: one move of the state machine (chapter 08 §2.4), audited; a move it does not allow is refused. */
    @Transactional
    public LeadView changeStatus(long id, long staffUserId, LeadStatus target) {
        LeadView lead = leads.find(id).orElseThrow(() -> new RuleViolationException("Richiesta non trovata."));
        if (!lead.status().canMoveTo(target)) {
            throw new RuleViolationException("Questo passaggio di stato non è previsto per la richiesta.");
        }
        if (!leads.moveStatus(id, lead.status(), target)) {
            throw new RuleViolationException("La richiesta è appena cambiata: controlla il suo stato e riprova.");
        }
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "lead.status")
                .entity("lead", id, null)
                .change(Map.of("status", lead.status().name()), Map.of("status", target.name())));
        return leads.find(id).orElseThrow();
    }

    /** ENH-06: the request belongs to this customer (created from it, or found by its e-mail), audited. */
    @Transactional
    public void linkCustomer(long id, long staffUserId, long customerId) {
        LeadView lead = leads.find(id).orElseThrow(() -> new RuleViolationException("Richiesta non trovata."));
        if (Long.valueOf(customerId).equals(lead.customerId()) || !leads.linkCustomer(id, customerId)) {
            return;
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("customerId", lead.customerId());
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "lead.customer")
                .entity("lead", id, null)
                .change(before, Map.of("customerId", customerId)));
    }

    /**
     * ENH-06: the order made from an open request (NEW or CONTACTED): the request becomes WON and
     * points to the order and its customer, audited. False when the request was no longer open —
     * the order stays, the request does not change.
     */
    @Transactional
    public boolean converted(long id, long staffUserId, long customerId, long orderId) {
        Optional<LeadView> lead = leads.find(id);
        if (lead.isEmpty() || !leads.markConverted(id, customerId, orderId)) {
            return false;
        }
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "lead.convert")
                .entity("lead", id, null)
                .change(
                        Map.of("status", lead.get().status().name()),
                        Map.of("status", LeadStatus.WON.name(), "orderId", orderId, "customerId", customerId)));
        return true;
    }

    /** Retention (Q-18): anonymises every lead past its {@code retain_until}; returns how many. */
    @Transactional
    public int anonymiseExpired(Instant now) {
        return leads.anonymiseExpired(now.atZone(ZoneOffset.UTC).toLocalDate());
    }
}
