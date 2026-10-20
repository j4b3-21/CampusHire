package com.bm4a.platform.modules.tenancy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An active membership of an active organization, as verified by {@link TenantAccess#requireMembership}. Carries
 * what record scopes need: the org role ({@code null} when none is assigned) and the membership attributes, whose
 * values are strings or lists of strings.
 */
public record ActiveMembership(UUID organizationId, UUID userId, MembershipRole role, UUID orgRoleId,
        Map<String, Object> attributes) {

    public ActiveMembership {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Organization owners and admins: full access to every module. */
    public boolean admin() {
        return role != MembershipRole.MEMBER;
    }

    /** Values of an attribute (a single string is a one-element list; absent is empty). */
    public List<String> attributeValues(String key) {
        return switch (attributes.get(key)) {
            case String value -> List.of(value);
            case List<?> values -> values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
            case null, default -> List.of();
        };
    }

    /**
     * A tenant context for this membership. Callers create it only after they authorized the operation at hand
     * (see {@link TenantContext}).
     */
    public TenantContext context() {
        return new TenantContext(organizationId, userId, role);
    }
}
