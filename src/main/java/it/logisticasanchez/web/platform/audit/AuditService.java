package it.logisticasanchez.web.platform.audit;

import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.security.IpHasher;
import it.logisticasanchez.web.platform.web.RequestId;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
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

    public AuditService(
            AuditLogRepository logs,
            AuditLogDetailRepository details,
            ObjectMapper json,
            IpHasher ipHasher,
            ClientIpResolver clientIp,
            Clock clock) {
        this.logs = logs;
        this.details = details;
        this.json = json;
        this.ipHasher = ipHasher;
        this.clientIp = clientIp;
        this.clock = clock;
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

    private String toJson(Map<String, Object> values) {
        return values == null ? null : json.writeValueAsString(values);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
