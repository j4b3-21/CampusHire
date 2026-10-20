package com.bm4a.platform.modules.tenancy;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Organization settings for trusted application code (for example app pack installers). <strong>No permission checks
 * are performed</strong>; changes are audited ({@code organization.updated}) with {@code actorUserId} as actor and
 * increment the organization version.
 */
public interface OrganizationSettings {

    /** The current settings; 404 when the organization does not exist. */
    ObjectNode get(UUID organizationId);

    /**
     * Adds every key of {@code defaults} that the settings do not have yet, recursing into objects present on both
     * sides; existing values are never overwritten. The organization row is locked for the merge. Returns the dotted
     * paths of the added keys (empty: nothing changed). 400 {@code validation_failed} when the result would exceed
     * the settings size limit.
     */
    List<String> mergeDefaults(UUID organizationId, UUID actorUserId, JsonNode defaults);
}
