package com.bm4a.platform.modules.tenancy.internal;

import java.time.Instant;
import java.util.UUID;

import com.bm4a.platform.modules.tenancy.MembershipRole;

public record MembershipRow(UUID organizationId, UUID userId, MembershipRole role, String status, Instant createdAt,
        Instant updatedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    boolean active() {
        return ACTIVE.equals(status);
    }
}
