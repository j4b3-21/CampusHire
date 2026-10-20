package com.bm4a.platform.modules.records;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.bm4a.platform.modules.access.ModuleGrant;

import tools.jackson.databind.node.ObjectNode;

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

    /**
     * Whether an active record of the grant's module within its record scope contains any of {@code documents}
     * (JSONB {@code @>}, served by the containment index). No documents means no match.
     */
    boolean anyActiveContaining(ModuleGrant grant, Collection<ObjectNode> documents);

    /**
     * Passes every active record matching the record API query language ({@code filter[field][op]} entries of
     * {@code parameters}, {@code sort}, {@code q}) within the grant's scope and field policy to {@code pages}, in pages
     * of at most {@code pageSize}, read from one consistent snapshot. Records carry their full data; callers filter
     * hidden fields themselves (for example with {@link RecordData#visible}).
     */
    void forEachActive(ModuleGrant grant, Map<String, List<String>> parameters, String sort, String q, int pageSize,
            Consumer<List<RecordView>> pages);
}
