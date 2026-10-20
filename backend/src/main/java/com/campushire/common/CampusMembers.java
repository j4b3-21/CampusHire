package com.campushire.common;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.tenancy.ActiveMembership;
import com.bm4a.platform.modules.tenancy.OrgRoleCatalog;
import com.bm4a.platform.modules.tenancy.OrgRoleCatalog.OrgRoleRef;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.users.CurrentUser;

/**
 * Resolves the caller's {@link CampusMember}: an active membership of an active organization (404 / 403
 * {@code organization_inactive} exactly like {@code TenantAccess}) and its org role.
 */
@Component
public class CampusMembers {

    private final TenantAccess tenants;
    private final OrgRoleCatalog roles;

    CampusMembers(TenantAccess tenants, OrgRoleCatalog roles) {
        this.tenants = tenants;
        this.roles = roles;
    }

    public CampusMember require(CurrentUser user, UUID organizationId) {
        return require(user.id(), organizationId);
    }

    public CampusMember require(UUID userId, UUID organizationId) {
        ActiveMembership membership = tenants.requireMembership(userId, organizationId);
        if (membership.orgRoleId() == null) {
            return new CampusMember(membership, null, null);
        }
        OrgRoleRef role = roles.findAll(organizationId, List.of(membership.orgRoleId())).get(membership.orgRoleId());
        return new CampusMember(membership, role == null ? null : role.key(), role == null ? null : role.label());
    }
}
