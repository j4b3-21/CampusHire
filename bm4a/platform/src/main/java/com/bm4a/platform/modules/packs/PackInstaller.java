package com.bm4a.platform.modules.packs;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Installs declarative application packs into an organization. <strong>No permission checks are performed</strong>
 * (the HTTP endpoint requires {@code METADATA_MANAGE}); {@code actorUserId} must be an existing user and is recorded
 * as author of every definition and in the audit trail.
 * <p>
 * One transaction per install, serialized per organization. Steps: (1) missing modules without their lookup fields,
 * (2) missing fields, lookups included, and missing select options of existing select fields, (3) layouts of modules
 * created by this install or with an empty layout, (4) missing roles, (5) missing relationships, (6) missing
 * workflows, (7) organization setting defaults (deep merge, existing values win), (8) the installed version. Existing
 * definitions are never modified otherwise. The same version again is a no-op ({@code UP_TO_DATE}); a lower version
 * is 409 {@code pack_downgrade}; an invalid document is 400 {@code validation_failed} with JSON paths such as
 * {@code modules[3].fields[2].type}.
 */
public interface PackInstaller {

    PackInstallReport install(UUID organizationId, UUID actorUserId, JsonNode pack);

    /** Installed packs of the organization, by key. */
    List<InstalledPack> installed(UUID organizationId);
}
