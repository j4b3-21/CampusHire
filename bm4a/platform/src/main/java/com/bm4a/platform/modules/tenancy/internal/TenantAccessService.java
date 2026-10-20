package com.bm4a.platform.modules.tenancy.internal;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.authorization.AuthorizationService;
import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.tenancy.ActiveMembership;
import com.bm4a.platform.modules.tenancy.TenancyEvent;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

@Service
class TenantAccessService implements TenantAccess {

    private final TenancyRepository repository;
    private final AuthorizationService authorization;
    private final ApplicationEventPublisher events;

    TenantAccessService(TenancyRepository repository, AuthorizationService authorization,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.authorization = authorization;
        this.events = events;
    }

    @Override
    public TenantContext require(CurrentUser user, UUID organizationId, Permission permission) {
        MembershipRow membership = activeMembership(user.id(), organizationId, permission.name());
        if (!authorization.isAllowed(user.id(), permission, organizationId)) {
            events.publishEvent(new TenancyEvent.TenantAccessDenied(organizationId, user.id(), permission.name(),
                    "permission_denied"));
            throw ApiException.forbidden("access_denied", "Missing permission " + permission.name());
        }
        return new TenantContext(organizationId, user.id(), membership.role());
    }

    @Override
    public ActiveMembership requireMembership(UUID userId, UUID organizationId) {
        MembershipRow membership = activeMembership(userId, organizationId, "MEMBERSHIP");
        return new ActiveMembership(organizationId, userId, membership.role(), membership.roleId(),
                MembershipAttributes.asMap(membership.attributes()));
    }

    private MembershipRow activeMembership(UUID userId, UUID organizationId, String purpose) {
        MembershipRow membership = repository.findMembership(organizationId, userId)
                .filter(MembershipRow::active)
                .orElseThrow(() -> ApiException.notFound("Organization"));
        OrganizationRow organization = repository.findOrganization(organizationId)
                .orElseThrow(() -> ApiException.notFound("Organization"));
        if (!organization.active()) {
            events.publishEvent(new TenancyEvent.TenantAccessDenied(organizationId, userId, purpose,
                    "organization_" + organization.status().toLowerCase()));
            throw ApiException.forbidden("organization_inactive", "Organization is " + organization.status().toLowerCase());
        }
        return membership;
    }
}
