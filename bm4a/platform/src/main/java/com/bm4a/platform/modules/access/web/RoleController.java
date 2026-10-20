package com.bm4a.platform.modules.access.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.access.internal.RoleRow;
import com.bm4a.platform.modules.access.internal.RoleService;
import com.bm4a.platform.modules.access.internal.RoleService.RoleInput;
import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.shared.web.ETags;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

import tools.jackson.databind.JsonNode;

/** Org role administration (ADR-004). Every endpoint requires {@code METADATA_MANAGE} (organization admins). */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/roles")
class RoleController {

    private final RoleService roles;
    private final TenantAccess access;

    RoleController(RoleService roles, TenantAccess access) {
        this.roles = roles;
        this.access = access;
    }

    @GetMapping
    List<RoleResponse> list(CurrentUser user, @PathVariable UUID orgId) {
        return roles.list(manage(user, orgId)).stream().map(RoleResponse::of).toList();
    }

    @PostMapping
    ResponseEntity<RoleResponse> create(CurrentUser user, @PathVariable UUID orgId, @RequestBody RoleInput request) {
        RoleRow role = roles.create(manage(user, orgId), request);
        return ResponseEntity.created(URI.create("/api/v1/orgs/" + orgId + "/roles/" + role.apiKey()))
                .eTag(ETags.of(role.version()))
                .body(RoleResponse.of(role));
    }

    @GetMapping("/{roleKey}")
    ResponseEntity<RoleResponse> get(CurrentUser user, @PathVariable UUID orgId, @PathVariable String roleKey) {
        return respond(roles.get(manage(user, orgId), roleKey));
    }

    /** Full replacement of {@code label}, {@code description} and {@code permissions}. */
    @PutMapping("/{roleKey}")
    ResponseEntity<RoleResponse> update(CurrentUser user, @PathVariable UUID orgId, @PathVariable String roleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody RoleInput request) {
        TenantContext tenant = manage(user, orgId);
        return respond(roles.update(tenant, roleKey, ETags.requireVersion(ifMatch), request));
    }

    @DeleteMapping("/{roleKey}")
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable UUID orgId, @PathVariable String roleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        TenantContext tenant = manage(user, orgId);
        roles.delete(tenant, roleKey, ETags.requireVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }

    private TenantContext manage(CurrentUser user, UUID orgId) {
        return access.require(user, orgId, Permission.METADATA_MANAGE);
    }

    private static ResponseEntity<RoleResponse> respond(RoleRow role) {
        return ResponseEntity.ok().eTag(ETags.of(role.version())).body(RoleResponse.of(role));
    }

    record RoleResponse(UUID id, String apiKey, String label, String description, JsonNode permissions, long version,
            Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {

        static RoleResponse of(RoleRow role) {
            return new RoleResponse(role.id(), role.apiKey(), role.label(), role.description(), role.permissions(),
                    role.version(), role.createdAt(), role.createdBy(), role.updatedAt(), role.updatedBy());
        }
    }
}
