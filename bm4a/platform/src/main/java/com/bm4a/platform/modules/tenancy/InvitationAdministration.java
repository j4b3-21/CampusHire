package com.bm4a.platform.modules.tenancy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Invitations for trusted application code, with the same rules as {@code POST /orgs/{orgId}/invitations}: validated
 * e-mail, org role and attributes, one pending invitation per address, hashed single-use token, mail after commit.
 * Callers authorize the inviter themselves; only owners may invite {@code ADMIN} or {@code OWNER}.
 */
public interface InvitationAdministration {

    /** {@code tenant.userId()} is recorded as the inviter. */
    IssuedInvitation invite(TenantContext tenant, String email, MembershipRole membershipRole, String roleKey,
            JsonNode attributes);

    /** {@code acceptUrl} carries the token and is only available here. */
    record IssuedInvitation(UUID id, String email, MembershipRole membershipRole, String roleKey,
            Map<String, Object> attributes, String status, Instant expiresAt, Instant createdAt, UUID createdBy,
            String acceptUrl) {
    }
}
