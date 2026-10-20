package com.bm4a.platform.modules.tenancy.internal;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.node.ObjectNode;

public record OrganizationRow(UUID id, String name, String status, ObjectNode settings, long version,
        Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String ARCHIVED = "ARCHIVED";

    boolean active() {
        return ACTIVE.equals(status);
    }
}
