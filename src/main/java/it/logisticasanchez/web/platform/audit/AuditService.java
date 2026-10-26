package it.logisticasanchez.web.platform.audit;

import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.security.IpHasher;
import it.logisticasanchez.web.platform.web.RequestId;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
 * taken from the current request when there is one. The viewer of ENH-13 reads the log rows
 * through {@link #search}, never the personal details.
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

    /**
     * ENH-13 (ADR 0040): the entries matching the query, newest first, a page at a time; the
     * change of each comes from the log row (field names, non-personal values) — the personal
     * detail is never read, only flagged as present.
     */
    @Transactional(readOnly = true)
    public Page<AuditView> search(AuditQuery query, int page, int size) {
        StringBuilder where = new StringBuilder(" where true");
        List<Object> params = new ArrayList<>();
        if (query.actorType() != null) {
            where.append(" and l.actor_type = ?");
            params.add(query.actorType().name());
        }
        if (query.actorId() != null) {
            where.append(" and l.actor_id = ?");
            params.add(query.actorId());
        }
        if (query.action() != null) {
            where.append(" and l.action = ?");
            params.add(query.action());
        }
        if (query.entityType() != null) {
            where.append(" and l.entity_type = ?");
            params.add(query.entityType());
        }
        if (query.entityId() != null) {
            where.append(" and l.entity_id = ?");
            params.add(query.entityId());
        }
        if (query.from() != null) {
            where.append(" and l.occurred_at >= ?");
            params.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            where.append(" and l.occurred_at < ?");
            params.add(Timestamp.from(query.to()));
        }
        long total = jdbc.queryForObject("select count(*) from audit_log l" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) Math.max(0, page) * size);
        List<AuditView> rows = jdbc.query(
                "select l.id, l.occurred_at, l.actor_type, l.actor_id, l.action, l.entity_type, l.entity_id,"
                        + " l.entity_ref, l.before::text as before_json, l.after::text as after_json,"
                        + " exists (select 1 from audit_log_detail d where d.audit_log_id = l.id) as personal"
                        + " from audit_log l" + where + " order by l.occurred_at desc, l.id desc limit ? offset ?",
                (rs, n) -> view(rs),
                pageParams.toArray());
        return new PageImpl<>(rows, PageRequest.of(Math.max(0, page), size), total);
    }

    /** ENH-13: the actions found in the log, for the viewer's filter. */
    @Transactional(readOnly = true)
    public List<String> actions() {
        return jdbc.queryForList("select distinct action from audit_log order by action", String.class);
    }

    /** ENH-13: the entity types found in the log, for the viewer's filter. */
    @Transactional(readOnly = true)
    public List<String> entityTypes() {
        return jdbc.queryForList(
                "select distinct entity_type from audit_log where entity_type is not null order by entity_type",
                String.class);
    }

    private AuditView view(ResultSet rs) throws SQLException {
        Map<String, Object> before = parse(rs.getString("before_json"));
        Map<String, Object> after = parse(rs.getString("after_json"));
        Set<String> fields = new LinkedHashSet<>(before.keySet());
        fields.addAll(after.keySet());
        List<AuditView.Change> changes = new ArrayList<>();
        for (String field : fields) {
            changes.add(new AuditView.Change(
                    field,
                    before.containsKey(field) ? text(before.get(field)) : "-",
                    after.containsKey(field) ? text(after.get(field)) : "-"));
        }
        return new AuditView(
                rs.getLong("id"),
                rs.getTimestamp("occurred_at").toInstant(),
                AuditActorType.valueOf(rs.getString("actor_type")),
                rs.getObject("actor_id", Long.class),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getObject("entity_id", Long.class),
                rs.getString("entity_ref"),
                changes,
                rs.getBoolean("personal"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        Object value = json.readValue(text, Object.class);
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : new LinkedHashMap<>(Map.of("valore", value));
    }

    private String text(Object value) {
        if (value == null) {
            return "-";
        }
        return value instanceof String string ? string : json.writeValueAsString(value);
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
