package com.bm4a.platform.modules.records;

import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.tenancy.TenantContext;

/**
 * Extension point invoked inside the delete transaction before a record is soft-deleted. Implementations either veto
 * the deletion by throwing an {@code ApiException} (for example 409 when a restricting relationship exists) or apply
 * dependent changes (for example detaching links), which then commit or roll back with the deletion.
 */
public interface RecordDeletionGuard {

    void beforeDelete(TenantContext tenant, ModuleSchema module, RecordView record);
}
