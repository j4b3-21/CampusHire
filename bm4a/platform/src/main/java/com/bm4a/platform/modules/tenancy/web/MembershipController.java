package com.bm4a.platform.modules.tenancy.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.tenancy.internal.MembershipService;
import com.bm4a.platform.modules.tenancy.internal.MembershipService.Member;
import com.bm4a.platform.modules.tenancy.internal.MembershipService.MembershipUpdate;
import com.bm4a.platform.modules.users.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/members")
class MembershipController {

    private final MembershipService memberships;
    private final TenantAccess access;

    MembershipController(MembershipService memberships, TenantAccess access) {
        this.memberships = memberships;
        this.access = access;
    }

    @GetMapping
    PageResponse<Member> list(CurrentUser user, @PathVariable UUID orgId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        return memberships.list(tenant, PageQuery.of(page, size));
    }

    /** {@code roleKey} (org role) and {@code attributes} (strings or string arrays) are optional. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Member add(CurrentUser user, @PathVariable UUID orgId, @Valid @RequestBody AddRequest request) {
        TenantContext tenant = access.require(user, orgId, Permission.MEMBERS_MANAGE);
        return memberships.add(tenant, request.userId(), request.role(), request.roleKey(), request.attributes());
    }

    /**
     * Partial update of {@code role}, {@code roleKey} and {@code attributes}: omitted properties are unchanged,
     * {@code "roleKey": null} removes the org role, {@code attributes} replaces all attributes.
     */
    @PatchMapping("/{userId}")
    Member update(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID userId,
            @RequestBody UpdateRequest request) {
        TenantContext tenant = access.require(user, orgId, Permission.MEMBERS_MANAGE);
        boolean roleKeySet = request.roleKey() != null;
        if (roleKeySet && !request.roleKey().isNull() && !request.roleKey().isString()) {
            throw ApiException.validation("roleKey", "type", "roleKey must be a string or null");
        }
        String roleKey = roleKeySet && request.roleKey().isString() ? request.roleKey().stringValue() : null;
        return memberships.update(tenant, userId,
                new MembershipUpdate(request.role(), roleKeySet, roleKey, request.attributes()));
    }

    /** Revokes a membership. Members may always revoke their own membership (leave the organization). */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID userId) {
        Permission required = userId.equals(user.id()) ? Permission.ORGANIZATION_VIEW : Permission.MEMBERS_MANAGE;
        TenantContext tenant = access.require(user, orgId, required);
        memberships.revoke(tenant, userId);
    }

    record AddRequest(@NotNull UUID userId, @NotNull MembershipRole role, @Size(max = 63) String roleKey,
            JsonNode attributes) {
    }

    /** {@code roleKey} is a JSON node so that an explicit {@code null} (remove) differs from absence (keep). */
    record UpdateRequest(MembershipRole role, JsonNode roleKey, JsonNode attributes) {
    }
}
