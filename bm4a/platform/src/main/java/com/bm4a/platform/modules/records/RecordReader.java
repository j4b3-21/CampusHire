package com.bm4a.platform.modules.records;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bm4a.platform.modules.access.ModuleGrant;

/**
 * Tenant-scoped record reads for other modules. A record of another tenant is indistinguishable from a missing one.
 */
public interface RecordReader {

    /** An active (not soft-deleted) record of the given module. */
    Optional<RecordView> findActive(UUID organizationId, UUID moduleId, UUID recordId);

    /** Active records among {@code recordIds}, in no particular order. */
    List<RecordView> findActive(UUID organizationId, Collection<UUID> recordIds);

    /** An active record of the grant's module within the grant's record scope. */
    Optional<RecordView> findActive(ModuleGrant grant, UUID recordId);
}
