package com.bm4a.platform.modules.tenancy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Membership management for trusted application code (for example onboarding a student who signed in with their
 * college account). <strong>No permission checks are performed</strong>: callers decide whether the change is
 * allowed. Changes are validated exactly like the members API (role key must exist, attribute values are strings or
 * string arrays), audited with {@code actorUserId} as actor, and synchronized to OpenFGA. {@code actorUserId} must
 * be an existing user.
 */
public interface MembershipAdministration {

    /**
     * Idempotent upsert: creates the membership, reactivates a revoked one, or updates an active one to the given
     * role, org role ({@code roleKey}, {@code null} for none) and attributes ({@code null} for none). Demoting the
     * last owner fails with 409 {@code last_owner}; unknown users with 404; inactive organizations with 403
     * {@code organization_inactive}.
     */
    MembershipView grant(UUID organizationId, UUID userId, MembershipRole role, String roleKey,
            Map<String, Object> attributes, UUID actorUserId);

    /** Replaces the attributes of an active membership (404 when there is none). */
    MembershipView updateAttributes(UUID organizationId, UUID userId, Map<String, Object> attributes,
            UUID actorUserId);

    /** The membership in any status (active or revoked). */
    Optional<MembershipView> find(UUID organizationId, UUID userId);

    /** Active memberships plus pending, unexpired invitations that hold the org role. */
    long countRoleAssignments(UUID organizationId, UUID roleId);

    /**
     * @param roleKey org role key, {@code null} when none is assigned
     * @param attributes string or list-of-string values
     */
    record MembershipView(UUID organizationId, UUID userId, MembershipRole role, String status, String roleKey,
            Map<String, Object> attributes, Instant createdAt, Instant updatedAt) {

        public static final String ACTIVE = "ACTIVE";

        public boolean active() {
            return ACTIVE.equals(status);
        }
    }
}
