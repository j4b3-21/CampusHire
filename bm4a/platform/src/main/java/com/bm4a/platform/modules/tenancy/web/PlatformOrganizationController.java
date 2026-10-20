package com.bm4a.platform.modules.tenancy.web;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.tenancy.internal.OrganizationRow;
import com.bm4a.platform.modules.tenancy.internal.OrganizationService;
import com.bm4a.platform.modules.users.CurrentUser;

/**
 * Platform administration (realm role {@code bm4a-platform-admin}). Platform admins get no implicit access to tenant
 * data; they can only change an organization's lifecycle status.
 */
@RestController
@RequestMapping("/api/v1/platform/orgs/{orgId}")
class PlatformOrganizationController {

    private final OrganizationService organizations;

    PlatformOrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @PostMapping("/suspend")
    StatusResponse suspend(CurrentUser user, @PathVariable UUID orgId) {
        return StatusResponse.of(organizations.setStatusAsPlatformAdmin(user, orgId, OrganizationRow.SUSPENDED));
    }

    @PostMapping("/reactivate")
    StatusResponse reactivate(CurrentUser user, @PathVariable UUID orgId) {
        return StatusResponse.of(organizations.setStatusAsPlatformAdmin(user, orgId, OrganizationRow.ACTIVE));
    }

    record StatusResponse(UUID id, String status, long version, Instant updatedAt) {

        static StatusResponse of(OrganizationRow org) {
            return new StatusResponse(org.id(), org.status(), org.version(), org.updatedAt());
        }
    }
}
