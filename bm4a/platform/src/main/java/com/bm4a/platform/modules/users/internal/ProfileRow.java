package com.bm4a.platform.modules.users.internal;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.node.ObjectNode;

public record ProfileRow(UUID userId, String locale, String timeZone, ObjectNode preferences, long version,
        Instant updatedAt) {
}
