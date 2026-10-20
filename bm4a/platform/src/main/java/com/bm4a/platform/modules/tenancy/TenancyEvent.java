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

    record MembershipGranted(UUID organizationId, UUID actorUserId, UUID userId, MembershipRole role)
            implements TenancyEvent {
    }

    record MembershipRoleChanged(UUID organizationId, UUID actorUserId, UUID userId, MembershipRole from,
            MembershipRole to) implements TenancyEvent {
    }

    record MembershipRevoked(UUID organizationId, UUID actorUserId, UUID userId, MembershipRole role)
            implements TenancyEvent {
    }

    /** A member was refused an operation (inactive organization or missing permission). */
    record TenantAccessDenied(UUID organizationId, UUID actorUserId, String permission, String reason)
            implements TenancyEvent {
    }
}
