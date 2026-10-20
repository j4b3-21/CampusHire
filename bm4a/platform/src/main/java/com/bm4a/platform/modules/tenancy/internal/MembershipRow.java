package com.bm4a.platform.modules.tenancy.internal;

import java.time.Instant;
import java.util.UUID;

import com.bm4a.platform.modules.tenancy.MembershipRole;

import tools.jackson.databind.node.ObjectNode;

/**
 * @param roleId org role (ADR-004), {@code null} when none is assigned
 * @param attributes canonical membership attributes (see {@link MembershipAttributes})
 */
public record MembershipRow(UUID organizationId, UUID userId, MembershipRole role, String status, UUID roleId,
        ObjectNode attributes, Instant createdAt, Instant updatedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    boolean active() {
        return ACTIVE.equals(status);
    }
}
