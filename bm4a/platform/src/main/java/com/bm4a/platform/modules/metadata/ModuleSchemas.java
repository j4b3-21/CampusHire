package com.bm4a.platform.modules.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Module lookups for other modules. Every lookup is tenant-scoped: a tenant sees its own modules plus
 * platform-global ones, never another tenant's.
 */
public interface ModuleSchemas {

    Optional<ModuleSchema> find(UUID organizationId, String moduleKey);

    Optional<ModuleSchema> findById(UUID organizationId, UUID moduleId);

    /** Active modules visible to the tenant (its own and platform-global ones), ordered by API key. */
    List<ModuleSchema> listActive(UUID organizationId);

    /** Modules visible to the tenant that have a lookup field targeting {@code moduleKey}. */
    List<ModuleSchema> referencing(UUID organizationId, String moduleKey);
}
