package com.bm4a.platform.modules.records;

import java.util.UUID;

import com.bm4a.platform.modules.access.ModuleGrant;

import tools.jackson.databind.JsonNode;

/**
 * Record writes on behalf of the holder of a {@link ModuleGrant} (issued by {@code ModuleAccess}): exactly the record
 * API's behavior, including the grant's record scope (records outside it are 404), its field policy, validation,
 * history, audit, {@link RecordEvent}s and {@link RecordWriteGuard}s ({@link RecordWrite#trusted()} is
 * {@code false}).
 */
public interface RecordWriter {

    /**
     * JSON Merge Patch of {@code data}.
     *
     * @param expectedVersion optimistic concurrency check; {@code null} skips it
     */
    RecordView patch(ModuleGrant grant, UUID recordId, Long expectedVersion, JsonNode data);
}
