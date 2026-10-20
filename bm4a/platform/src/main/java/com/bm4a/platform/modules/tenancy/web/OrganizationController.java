package com.bm4a.platform.modules.tenancy.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.shared.web.ETags;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.tenancy.internal.OrganizationRow;
import com.bm4a.platform.modules.tenancy.internal.OrganizationService;
import com.bm4a.platform.modules.tenancy.internal.OrganizationWithRole;
import com.bm4a.platform.modules.users.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/orgs")
class OrganizationController {

    private final OrganizationService organizations;
    private final TenantAccess access;

    OrganizationController(OrganizationService organizations, TenantAccess access) {
        this.organizations = organizations;
        this.access = access;
    }

    @PostMapping
    ResponseEntity<OrganizationResponse> create(CurrentUser user, @Valid @RequestBody CreateRequest request) {
        OrganizationWithRole created = organizations.create(user, request.name(), request.settings());
        return ResponseEntity.created(URI.create("/api/v1/orgs/" + created.organization().id()))
                .eTag(ETags.of(created.organization().version()))
                .body(OrganizationResponse.of(created));
    }

    /** Organizations the caller is an active member of. */
    @GetMapping
    List<OrganizationResponse> mine(CurrentUser user) {
        return organizations.listForUser(user).stream().map(OrganizationResponse::of).toList();
    }

    @GetMapping("/{orgId}")
    ResponseEntity<OrganizationResponse> get(CurrentUser user, @PathVariable UUID orgId) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        return withETag(organizations.get(tenant));
    }

    @PatchMapping("/{orgId}")
    ResponseEntity<OrganizationResponse> update(CurrentUser user, @PathVariable UUID orgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateRequest request) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_UPDATE);
        return withETag(organizations.update(tenant, ETags.requireVersion(ifMatch), request.name(), request.settings()));
    }

    /** Archives the organization (owners only). Archived organizations reject all tenant-scoped requests. */
    @DeleteMapping("/{orgId}")
    ResponseEntity<OrganizationResponse> archive(CurrentUser user, @PathVariable UUID orgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_ARCHIVE);
        return withETag(organizations.archive(tenant, ETags.requireVersion(ifMatch)));
    }

    private static ResponseEntity<OrganizationResponse> withETag(OrganizationWithRole org) {
        return ResponseEntity.ok().eTag(ETags.of(org.organization().version())).body(OrganizationResponse.of(org));
    }

    record CreateRequest(@NotBlank @Size(max = 200) String name, JsonNode settings) {
    }

    record UpdateRequest(@Size(min = 1, max = 200) String name, JsonNode settings) {
    }

    record OrganizationResponse(UUID id, String name, String status, JsonNode settings, MembershipRole role,
            long version, Instant createdAt, Instant updatedAt) {

        static OrganizationResponse of(OrganizationWithRole view) {
            OrganizationRow org = view.organization();
            return new OrganizationResponse(org.id(), org.name(), org.status(), org.settings(), view.role(),
                    org.version(), org.createdAt(), org.updatedAt());
        }
    }
}
