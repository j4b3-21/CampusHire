package com.bm4a.platform.modules.metadata.internal;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.node.ObjectNode;

public record ModuleRow(UUID id, UUID organizationId, String apiKey, String labelSingular, String labelPlural,
        String description, String status, ObjectNode settings, ObjectNode layout, long version, Instant createdAt,
        UUID createdBy, Instant updatedAt, UUID updatedBy) {

    public static final String ACTIVE = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";

    public boolean active() {
        return ACTIVE.equals(status);
    }

    public boolean global() {
        return organizationId == null;
    }
}
