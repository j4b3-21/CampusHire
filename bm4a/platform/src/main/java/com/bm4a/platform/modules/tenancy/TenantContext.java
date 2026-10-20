package com.bm4a.platform.modules.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * A verified tenant scope: the user is an active member of an active organization and held the requested permission
 * when the context was created. Only {@link TenantAccess} creates instances from request data; services receive it as
 * an explicit parameter and must scope every query by {@link #organizationId()}.
 */
public record TenantContext(UUID organizationId, UUID userId, MembershipRole role) {

    public TenantContext {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
    }
}
