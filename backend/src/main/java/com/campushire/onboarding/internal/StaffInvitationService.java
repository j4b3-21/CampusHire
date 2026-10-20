package com.campushire.onboarding.internal;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.tenancy.InvitationAdministration;
import com.bm4a.platform.modules.tenancy.InvitationAdministration.IssuedInvitation;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.common.CampusMember;
import com.campushire.common.CampusMembers;
import com.campushire.common.CampusModules;
import com.campushire.common.CampusRoles;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Invitations bound to a CampusHire persona: recruiters to one company, coordinators to one department. Only
 * placement officers (owners, admins, {@code tpo}) may invite.
 */
@Service
public class StaffInvitationService {

    private final CampusMembers members;
    private final RecordOperations records;
    private final InvitationAdministration invitations;

    StaffInvitationService(CampusMembers members, RecordOperations records, InvitationAdministration invitations) {
        this.members = members;
        this.records = records;
        this.invitations = invitations;
    }

    @Transactional
    public IssuedInvitation inviteRecruiter(CurrentUser user, UUID organizationId, UUID companyId, String email) {
        return invite(user, organizationId, CampusModules.COMPANY, "Company", companyId, CampusRoles.RECRUITER,
                CampusRoles.ATTR_COMPANY, email);
    }

    @Transactional
    public IssuedInvitation inviteCoordinator(CurrentUser user, UUID organizationId, UUID departmentId, String email) {
        return invite(user, organizationId, CampusModules.DEPARTMENT, "Department", departmentId,
                CampusRoles.COORDINATOR, CampusRoles.ATTR_DEPARTMENT, email);
    }

    private IssuedInvitation invite(CurrentUser user, UUID organizationId, String moduleKey, String label,
            UUID recordId, String roleKey, String attribute, String email) {
        CampusMember inviter = members.require(user, organizationId).requireOfficer();
        if (records.get(inviter.context(), moduleKey, recordId).isEmpty()) {
            throw ApiException.notFound(label);
        }
        ObjectNode attributes = JsonNodeFactory.instance.objectNode().put(attribute, recordId.toString());
        return invitations.invite(inviter.context(), email, MembershipRole.MEMBER, roleKey, attributes);
    }
}
