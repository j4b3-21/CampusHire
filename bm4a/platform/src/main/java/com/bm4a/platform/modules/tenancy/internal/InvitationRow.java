package com.bm4a.platform.modules.tenancy.internal;

import java.time.Instant;
import java.util.UUID;

import com.bm4a.platform.modules.tenancy.MembershipRole;

import tools.jackson.databind.node.ObjectNode;

/** An invitation. Only the SHA-256 hash of its token is stored. */
public record InvitationRow(UUID id, UUID organizationId, String email, MembershipRole membershipRole, UUID roleId,
        ObjectNode attributes, String tokenHash, String status, Instant expiresAt, Instant createdAt, UUID createdBy,
        Instant acceptedAt, UUID acceptedBy, Instant revokedAt, UUID revokedBy) {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REVOKED = "REVOKED";
    /** Derived status of a pending invitation past its expiry; never stored. */
    public static final String EXPIRED = "EXPIRED";

    public String effectiveStatus(Instant now) {
        return PENDING.equals(status) && !expiresAt.isAfter(now) ? EXPIRED : status;
    }
}
