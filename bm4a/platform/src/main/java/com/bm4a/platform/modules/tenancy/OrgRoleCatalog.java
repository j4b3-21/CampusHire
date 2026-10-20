package com.bm4a.platform.modules.tenancy;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lookup of organization roles for membership and invitation management. Roles and their permission documents are
 * owned by the access module, which implements this interface; tenancy only stores the role ID of a membership.
 * (The interface lives here so that tenancy does not depend on access, which itself builds on tenancy.)
 */
public interface OrgRoleCatalog {

    /**
     * The role with {@code roleKey} in the organization, share-locked until the end of the current transaction so
     * that it cannot be deleted while it is being assigned.
     */
    Optional<OrgRoleRef> findForAssignment(UUID organizationId, String roleKey);

    /** Roles among {@code roleIds} that belong to the organization. */
    Map<UUID, OrgRoleRef> findAll(UUID organizationId, Collection<UUID> roleIds);

    record OrgRoleRef(UUID id, String key, String label) {
    }
}
