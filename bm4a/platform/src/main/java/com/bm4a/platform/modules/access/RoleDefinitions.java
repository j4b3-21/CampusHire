package com.bm4a.platform.modules.access;

import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Org role definitions for trusted application code (for example app pack installers). <strong>No permission checks
 * are performed</strong>; {@code actorUserId} must be an existing user and is audited. The permission document is
 * validated, stored in canonical form and synchronized to OpenFGA exactly like the roles API.
 */
public interface RoleDefinitions {

    Optional<RoleDefinition> find(UUID organizationId, String roleKey);

    /** 409 {@code role_exists} when the key is taken; 400 {@code validation_failed} for invalid documents. */
    RoleDefinition create(UUID organizationId, UUID actorUserId, String apiKey, String label, String description,
            JsonNode permissions);

    /** @param permissions the canonical permission document */
    record RoleDefinition(UUID id, String apiKey, String label, String description, JsonNode permissions,
            long version) {
    }
}
