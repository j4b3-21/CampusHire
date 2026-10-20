package com.bm4a.platform.modules.records;

import java.util.List;
import java.util.UUID;

import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.tenancy.TenantContext;

import tools.jackson.databind.node.ObjectNode;

/**
 * A validated record change about to be stored, as seen by {@link RecordWriteGuard}s.
 *
 * @param before the current record, {@code null} on create
 * @param after the complete canonical payload that will be stored
 * @param changedFields field keys whose value differs between {@code before} and {@code after}
 * @param actorRoleKey the acting member's org role, {@code null} when none or for trusted operations
 * @param admin whether the actor is an organization owner or admin
 * @param trusted whether the change comes from {@link RecordOperations} rather than the record API
 */
public record RecordWrite(TenantContext tenant, ModuleSchema module, UUID recordId, RecordView before,
        ObjectNode after, List<String> changedFields, String actorRoleKey, boolean admin, boolean trusted) {

    public RecordWrite {
        changedFields = List.copyOf(changedFields);
    }

    public boolean creating() {
        return before == null;
    }
}
