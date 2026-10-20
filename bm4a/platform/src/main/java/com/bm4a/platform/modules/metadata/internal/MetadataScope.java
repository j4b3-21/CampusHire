package com.bm4a.platform.modules.metadata.internal;

import java.util.UUID;

/**
 * Where a definition lives: a tenant ({@code organizationId} set) or the platform-global scope ({@code null}).
 * Tenant scopes also <em>see</em> global modules, but can only modify their own.
 */
public record MetadataScope(UUID organizationId) {

    public static MetadataScope tenant(UUID organizationId) {
        return new MetadataScope(java.util.Objects.requireNonNull(organizationId));
    }

    public static MetadataScope global() {
        return new MetadataScope(null);
    }

    public boolean isGlobal() {
        return organizationId == null;
    }
}
