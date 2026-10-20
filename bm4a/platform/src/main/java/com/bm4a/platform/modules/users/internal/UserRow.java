package com.bm4a.platform.modules.users.internal;

import java.time.Instant;
import java.util.UUID;

record UserRow(UUID id, String issuer, String subject, String email, String displayName, String status,
        Instant createdAt, Instant updatedAt) {

    boolean active() {
        return "ACTIVE".equals(status);
    }
}
