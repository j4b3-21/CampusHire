package com.bm4a.platform.modules.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An audit event to record. {@code details} must not contain secrets; values under sensitive-looking keys are
 * redacted anyway by {@link Redaction}. Use {@link #REDACTED} for values the caller knows to be sensitive.
 *
 * @param organizationId tenant scope, {@code null} for platform-level events
 * @param actorUserId internal user ID, {@code null} for system actions
 * @param action dotted verb such as {@code record.updated}
 */
public record AuditEntry(UUID organizationId, UUID actorUserId, String action, String resourceType,
        String resourceId, Map<String, Object> details) {

    public static final String REDACTED = "[REDACTED]";

    public AuditEntry {
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static AuditEntry of(UUID organizationId, UUID actorUserId, String action, String resourceType,
            Object resourceId) {
        return new AuditEntry(organizationId, actorUserId, action, resourceType,
                resourceId == null ? null : resourceId.toString(), Map.of());
    }

    public AuditEntry with(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(details);
        copy.put(key, value);
        return new AuditEntry(organizationId, actorUserId, action, resourceType, resourceId, copy);
    }
}
