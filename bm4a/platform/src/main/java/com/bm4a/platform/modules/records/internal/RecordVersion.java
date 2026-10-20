package com.bm4a.platform.modules.records.internal;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.node.ObjectNode;

/** Immutable history entry: the complete record payload as of {@code version}. */
public record RecordVersion(long version, String changeType, ObjectNode data, boolean deleted, Instant changedAt,
        UUID changedBy) {
}
