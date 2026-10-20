package com.bm4a.platform.modules.tenancy;

import java.util.Map;
import java.util.UUID;

/**
 * Domain events published by the tenancy module inside the transaction that caused them. Listeners that must be
 * atomic with the change (audit) handle them synchronously.
 */
public sealed interface TenancyEvent {

    UUID organizationId();

    UUID actorUserId();

    record OrganizationCreated(UUID organizationId, UUID actorUserId, String name) implements TenancyEvent {
    }

    record OrganizationUpdated(UUID organizationId, UUID actorUserId, Map<String, Object> changes)
            implements TenancyEvent {
    }

    record OrganizationStatusChanged(UUID organizationId, UUID actorUserId, String from, String to)
            implements TenancyEvent {
    }

    /** @param roleKey org role, {@code null} for none */
    record MembershipGranted(UUID organizationId, UUID actorUserId, UUID userId, MembershipRole role, String roleKey,
            Map<String, Object> attributes) implements TenancyEvent {
    }

    record MembershipRoleChanged(UUID organizationId, UUID actorUserId, UUID userId, MembershipRole from,
            MembershipRole to) implements TenancyEvent {
    }

    /** The org role and/or the attributes of an active membership changed. */
    record MembershipAccessChanged(UUID organizationId, UUID actorUserId, UUID userId, String fromRoleKey,
            String toRoleKey, Map<String, Object> fromAttributes, Map<String, Object> toAttributes)
            implements TenancyEvent {
    }

    record MembershipRevoked(UUID organizationId, UUID actorUserId, UUID userId, MembershipRole role)
            implements TenancyEvent {
    }

    record InvitationCreated(UUID organizationId, UUID actorUserId, UUID invitationId, String email,
            MembershipRole role, String roleKey) implements TenancyEvent {
    }

    record InvitationRevoked(UUID organizationId, UUID actorUserId, UUID invitationId, String email)
            implements TenancyEvent {
    }

    record InvitationAccepted(UUID organizationId, UUID actorUserId, UUID invitationId, String email)
            implements TenancyEvent {
    }

    /** A member was refused an operation (inactive organization or missing permission). */
    record TenantAccessDenied(UUID organizationId, UUID actorUserId, String permission, String reason)
            implements TenancyEvent {
    }
}
