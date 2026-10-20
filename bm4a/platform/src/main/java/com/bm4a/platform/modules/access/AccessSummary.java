package com.bm4a.platform.modules.access;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bm4a.platform.modules.tenancy.MembershipRole;

/**
 * What a member may do in an organization, for rendering menus and forms ({@code GET /orgs/{orgId}/me/access}).
 * Server-side enforcement does not depend on it.
 *
 * @param role the org role, {@code null} when none is assigned
 * @param modules every active module the caller can read, keyed by module API key
 */
public record AccessSummary(UUID organizationId, MembershipRole membershipRole, boolean admin, RoleSummary role,
        Map<String, Object> attributes, Map<String, ModuleCapabilities> modules) {

    public record RoleSummary(String key, String label) {
    }

    /** @param readScope ALL, OWN or ATTRIBUTE (modules with scope NONE are not listed) */
    public record ModuleCapabilities(boolean read, boolean create, boolean update, boolean delete,
            RecordScope.Kind readScope, List<String> hiddenFields, List<String> readOnlyFields) {
    }
}
