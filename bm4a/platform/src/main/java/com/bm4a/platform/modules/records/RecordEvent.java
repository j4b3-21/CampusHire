package com.bm4a.platform.modules.records;

import java.util.List;
import java.util.UUID;

/**
 * Published inside the transaction of every record change. The extension point for the future automation and
 * workflow engines (which must listen transactionally, after commit, and carry the organization ID explicitly).
 */
public sealed interface RecordEvent {

    UUID organizationId();

    UUID moduleId();

    UUID recordId();

    long version();

    UUID actorUserId();

    record Created(UUID organizationId, UUID moduleId, UUID recordId, long version, UUID actorUserId)
            implements RecordEvent {
    }

    record Updated(UUID organizationId, UUID moduleId, UUID recordId, long version, UUID actorUserId,
            List<String> changedFields) implements RecordEvent {
    }

    record Deleted(UUID organizationId, UUID moduleId, UUID recordId, long version, UUID actorUserId)
            implements RecordEvent {
    }

    record Restored(UUID organizationId, UUID moduleId, UUID recordId, long version, UUID actorUserId)
            implements RecordEvent {
    }
}
