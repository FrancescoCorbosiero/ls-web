package it.logisticasanchez.web.platform.audit;

import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.security.IpHasher;
import it.logisticasanchez.web.platform.web.RequestId;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * Append-only writes to {@code audit_log} and {@code audit_log_detail} (chapter 08 §2.3, NFR-02,
 * ADR 0020): actor type and id, action, entity, ids and non-personal values in the log row;
 * personal values only in the detail row. Written in the caller's transaction, so an action
 * that rolls back leaves no entry. The request context (IP hash, user agent, request id) is
 * taken from the current request when there is one.
 */
@Service
public class AuditService {

    private final AuditLogRepository logs;
    private final AuditLogDetailRepository details;
    private final ObjectMapper json;
    private final IpHasher ipHasher;
    private final ClientIpResolver clientIp;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public AuditService(
            AuditLogRepository logs,
            AuditLogDetailRepository details,
            ObjectMapper json,
            IpHasher ipHasher,
            ClientIpResolver clientIp,
            Clock clock,
            JdbcTemplate jdbc) {
        this.logs = logs;
        this.details = details;
        this.json = json;
        this.ipHasher = ipHasher;
        this.clientIp = clientIp;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    /** Writes the entry and returns the id of the {@code audit_log} row. */
    @Transactional
    public long record(AuditEntry entry) {
        AuditLog.Builder builder = AuditLog.of(entry.actorType(), entry.action())
                .occurredAt(clock.instant())
                .actor(entry.actorId())
                .entity(entry.entityType(), entry.entityId(), entry.entityRef())
                .change(toJson(entry.before()), toJson(entry.after()));
        HttpServletRequest request = currentRequest();
        if (request != null) {
            String userAgent = request.getHeader("User-Agent");
            builder.request(
                    ipHasher.hash(clientIp.resolve(request)),
                    userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 255)),
                    RequestId.of(request));
        }
        AuditLog saved = logs.save(builder.build());
        if (entry.hasPersonalValues()) {
            details.save(
                    new AuditLogDetail(saved.getId(), toJson(entry.personalBefore()), toJson(entry.personalAfter())));
        }
        return saved.getId();
    }

    /**
     * Erasure (NFR-03, ADR 0020): removes the personal values ({@code audit_log_detail}) of every
     * entry about the entity or made by the actor; the {@code audit_log} rows (ids only) stay.
     * Runs as a maintenance transaction ({@code SET LOCAL ls.audit_maintenance = 'on'}), the only
     * way the immutability trigger lets a DELETE through.
     */
    @Transactional
    public int deletePersonalDetails(String entityType, long entityId, AuditActorType actorType, Long actorId) {
        jdbc.execute("SET LOCAL ls.audit_maintenance = 'on'");
        int deleted = jdbc.update(
                "delete from audit_log_detail d using audit_log l where d.audit_log_id = l.id"
                        + " and l.entity_type = ? and l.entity_id = ?",
                entityType,
                entityId);
        if (actorId != null) {
            deleted += jdbc.update(
                    "delete from audit_log_detail d using audit_log l where d.audit_log_id = l.id"
                            + " and l.actor_type = ? and l.actor_id = ?",
                    actorType.name(),
                    actorId);
        }
        return deleted;
    }

    /**
     * Erasure (NFR-03): removes every {@code audit_log_detail} row whose personal values mention
     * the text (an e-mail address), whatever entity the entry is about — report recipients,
     * allow-list changes, notes. Maintenance transaction as above.
     */
    @Transactional
    public int deletePersonalDetailsMentioning(String text) {
        jdbc.execute("SET LOCAL ls.audit_maintenance = 'on'");
        String like = "%" + text.toLowerCase(java.util.Locale.ROOT) + "%";
        return jdbc.update(
                "delete from audit_log_detail d where lower(d.before::text) like ? or lower(d.after::text) like ?",
                like,
                like);
    }

    private String toJson(Map<String, Object> values) {
        return values == null ? null : json.writeValueAsString(values);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
