package com.bm4a.platform.modules.relationships;

import java.util.Optional;
import java.util.UUID;

/**
 * Relationship definitions for trusted application code (for example app pack installers). <strong>No permission
 * checks are performed</strong>; {@code actorUserId} must be an existing user and is audited. Validation is identical
 * to the relationships API.
 */
public interface RelationshipDefinitions {

    Optional<RelationshipDefinition> find(UUID organizationId, String apiKey);

    /** 409 {@code relationship_exists} when the key is taken. */
    RelationshipDefinition create(UUID organizationId, UUID actorUserId, RelationshipDefinition definition);

    /**
     * Same shape as the relationships API's create request.
     *
     * @param cardinality {@code ONE_TO_ONE}, {@code ONE_TO_MANY} or {@code MANY_TO_MANY}
     * @param onDelete {@code RESTRICT} (default) or {@code DETACH}
     */
    record RelationshipDefinition(String apiKey, String label, String inverseLabel, String sourceModule,
            String targetModule, String cardinality, String onDelete) {
    }
}
