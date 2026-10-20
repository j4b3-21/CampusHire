package com.bm4a.platform.modules.tenancy.web;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.tenancy.internal.InvitationService;
import com.bm4a.platform.modules.tenancy.internal.InvitationService.CreatedInvitation;
import com.bm4a.platform.modules.tenancy.internal.InvitationService.InvitationPreview;
import com.bm4a.platform.modules.tenancy.internal.InvitationService.InvitationView;
import com.bm4a.platform.modules.tenancy.internal.MembershipService.Member;
import com.bm4a.platform.modules.users.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Invitation management ({@code MEMBERS_MANAGE}) and the token endpoints used by invitees. Token endpoints require an
 * authenticated user but no membership; an unknown token is a 404.
 */
@RestController
class InvitationController {

    private static final String BASE = "/api/v1/orgs/{orgId}/invitations";
    private static final String TOKEN = "/api/v1/invitations/{token}";

    private final InvitationService invitations;
    private final TenantAccess access;

    InvitationController(InvitationService invitations, TenantAccess access) {
        this.invitations = invitations;
        this.access = access;
    }

    /** The response is the only place the accept link (and thus the token) is ever returned. */
    @PostMapping(BASE)
    ResponseEntity<CreatedResponse> create(CurrentUser user, @PathVariable UUID orgId,
            @Valid @RequestBody CreateRequest request) {
        TenantContext tenant = access.require(user, orgId, Permission.MEMBERS_MANAGE);
        CreatedInvitation created = invitations.create(tenant, request.email(), request.membershipRole(),
                request.roleKey(), request.attributes());
        return ResponseEntity.created(URI.create("/api/v1/orgs/" + orgId + "/invitations/" + created.invitation().id()))
                .body(CreatedResponse.of(created));
    }

    /** {@code status}: PENDING, EXPIRED, ACCEPTED or REVOKED (optional). */
    @GetMapping(BASE)
    PageResponse<InvitationView> list(CurrentUser user, @PathVariable UUID orgId,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        TenantContext tenant = access.require(user, orgId, Permission.MEMBERS_MANAGE);
        return invitations.list(tenant, status, PageQuery.of(page, size));
    }

    @DeleteMapping(BASE + "/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID id) {
        TenantContext tenant = access.require(user, orgId, Permission.MEMBERS_MANAGE);
        invitations.revoke(tenant, id);
    }

    @GetMapping(TOKEN)
    InvitationPreview preview(CurrentUser user, @PathVariable String token) {
        return invitations.preview(user, token);
    }

    @PostMapping(TOKEN + "/accept")
    Member accept(CurrentUser user, @PathVariable String token) {
        return invitations.accept(user, token);
    }

    record CreateRequest(@NotBlank @Size(max = 254) String email, @NotNull MembershipRole membershipRole,
            @Size(max = 63) String roleKey, JsonNode attributes) {
    }

    record CreatedResponse(UUID id, String email, MembershipRole membershipRole, String roleKey,
            Map<String, Object> attributes, String status, Instant expiresAt, Instant createdAt, UUID createdBy,
            String acceptUrl) {

        static CreatedResponse of(CreatedInvitation created) {
            InvitationView view = created.invitation();
            return new CreatedResponse(view.id(), view.email(), view.membershipRole(), view.roleKey(),
                    view.attributes(), view.status(), view.expiresAt(), view.createdAt(), view.createdBy(),
                    created.acceptUrl());
        }
    }
}
