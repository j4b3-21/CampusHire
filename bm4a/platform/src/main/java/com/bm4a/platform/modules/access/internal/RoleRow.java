package com.bm4a.platform.modules.access.internal;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.node.ObjectNode;

/** An organization role with its canonical permission document. */
public record RoleRow(UUID id, UUID organizationId, String apiKey, String label, String description,
        ObjectNode permissions, long version, Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {
}
