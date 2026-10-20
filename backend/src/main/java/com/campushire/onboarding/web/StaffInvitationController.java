package com.campushire.onboarding.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.tenancy.InvitationAdministration.IssuedInvitation;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.onboarding.internal.StaffInvitationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/campushire")
class StaffInvitationController {

    private final StaffInvitationService invitations;

    StaffInvitationController(StaffInvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/companies/{companyId}/recruiters/invite")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedInvitation inviteRecruiter(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID companyId,
            @Valid @RequestBody InviteRequest request) {
        return invitations.inviteRecruiter(user, orgId, companyId, request.email());
    }

    @PostMapping("/departments/{departmentId}/coordinators/invite")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedInvitation inviteCoordinator(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID departmentId,
            @Valid @RequestBody InviteRequest request) {
        return invitations.inviteCoordinator(user, orgId, departmentId, request.email());
    }

    record InviteRequest(@NotBlank String email) {
    }
}
