package com.bm4a.platform.modules.audit;

import java.time.Instant;
import java.util.UUID;

import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;

import tools.jackson.databind.JsonNode;

/**
 * Reads audit events of one organization, newest first. Callers must have authorized the organization scope.
 */
public interface AuditQuery {

    PageResponse<AuditEventView> find(UUID organizationId, Filter filter, PageQuery page);

    record Filter(String resourceType, String resourceId, String action, UUID actorUserId) {
    }

    record AuditEventView(UUID id, Instant occurredAt, UUID actorUserId, String action, String resourceType,
            String resourceId, String correlationId, JsonNode details) {
    }
}
