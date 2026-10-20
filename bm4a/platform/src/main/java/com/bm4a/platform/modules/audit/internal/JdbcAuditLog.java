package com.bm4a.platform.modules.audit.internal;

import static com.bm4a.platform.modules.shared.persistence.SqlValues.instant;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.ts;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.uuid;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.audit.AuditQuery;
import com.bm4a.platform.modules.audit.Redaction;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.CorrelationId;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;

@Service
class JdbcAuditLog implements AuditLog, AuditQuery {

    static final int MAX_DETAILS_BYTES = 32 * 1024;

    private final JdbcClient jdbc;
    private final JsonColumns json;
    private final Clock clock;

    JdbcAuditLog(JdbcClient jdbc, JsonColumns json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(AuditEntry entry) {
        String details = json.writeValue(Redaction.redact(entry.details()));
        if (details.length() > MAX_DETAILS_BYTES) {
            details = json.writeValue(Map.of("truncated", true, "originalBytes", details.length()));
        }
        jdbc.sql("""
                INSERT INTO audit_event (id, occurred_at, organization_id, actor_user_id, action, resource_type,
                                         resource_id, correlation_id, details)
                VALUES (:id, :occurredAt, :org, :actor, :action, :resourceType, :resourceId, :correlationId,
                        CAST(:details AS jsonb))
                """)
                .param("id", UUID.randomUUID())
                .param("occurredAt", ts(SqlValues.now(clock)))
                .param("org", entry.organizationId())
                .param("actor", entry.actorUserId())
                .param("action", entry.action())
                .param("resourceType", entry.resourceType())
                .param("resourceId", entry.resourceId())
                .param("correlationId", CorrelationId.current())
                .param("details", details)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditEventView> find(UUID organizationId, Filter filter, PageQuery page) {
        String where = """
                 WHERE organization_id = :org
                   AND (CAST(:resourceType AS varchar) IS NULL OR resource_type = :resourceType)
                   AND (CAST(:resourceId AS varchar) IS NULL OR resource_id = :resourceId)
                   AND (CAST(:action AS varchar) IS NULL OR action = :action)
                   AND (CAST(:actor AS uuid) IS NULL OR actor_user_id = :actor)
                """;
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("org", organizationId);
        params.put("resourceType", filter.resourceType());
        params.put("resourceId", filter.resourceId());
        params.put("action", filter.action());
        params.put("actor", filter.actorUserId());
        List<AuditEventView> items = jdbc.sql("SELECT * FROM audit_event" + where
                        + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query((rs, n) -> new AuditEventView(uuid(rs, "id"), instant(rs, "occurred_at"),
                        uuid(rs, "actor_user_id"), rs.getString("action"), rs.getString("resource_type"),
                        rs.getString("resource_id"), rs.getString("correlation_id"),
                        json.read(rs.getString("details"))))
                .list();
        long total = jdbc.sql("SELECT count(*) FROM audit_event" + where).params(params).query(Long.class).single();
        return PageResponse.of(items, page, total);
    }
}
