package com.bm4a.platform.modules.records;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.node.ObjectNode;

/**
 * A stored record. {@code data} is the complete canonical payload, including values of archived fields; API responses
 * must filter it to visible fields.
 */
public record RecordView(UUID id, UUID organizationId, UUID moduleId, ObjectNode data, long version,
        long definitionVersion, Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy,
        Instant deletedAt, UUID deletedBy) {

    public boolean deleted() {
        return deletedAt != null;
    }
}
