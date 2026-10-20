package com.bm4a.platform.modules.relationships.internal;

import java.time.Instant;
import java.util.UUID;

public record LinkRow(UUID id, UUID organizationId, UUID relationshipId, UUID sourceRecordId, UUID targetRecordId,
        Instant createdAt, UUID createdBy) {
}
