package com.bm4a.platform.modules.relationships.internal;

import java.time.Instant;
import java.util.UUID;

public record RelationshipRow(UUID id, UUID organizationId, String apiKey, String label, String inverseLabel,
        UUID sourceModuleId, UUID targetModuleId, Cardinality cardinality, OnDelete onDelete, String status,
        long version, Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {

    public enum Cardinality {
        ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY
    }

    /** What happens to links when a linked record is soft-deleted. */
    public enum OnDelete {
        /** Deleting a linked record is rejected (409) until its links are removed. */
        RESTRICT,
        /** Links are removed together with the deletion. */
        DETACH
    }

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
