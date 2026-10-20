package com.bm4a.platform.modules.metadata.internal;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** An immutable snapshot of a module definition at one definition version. */
public record VersionRow(long version, JsonNode snapshot, Instant createdAt, UUID createdBy) {
}
