package com.campushire.common;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.tenancy.ActiveMembership;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantContext;

/**
 * The caller's CampusHire persona in an organization: an active membership plus its org role. Organization owners and
 * admins act as placement staff with institute-wide reach whatever their org role.
 *
 * @param roleKey org role key, {@code null} when none is assigned
 * @param roleLabel label of the org role, {@code null} when none is assigned
 */
public record CampusMember(ActiveMembership membership, String roleKey, String roleLabel) {

    public CampusMember {
        Objects.requireNonNull(membership, "membership");
    }

    public UUID organizationId() {
        return membership.organizationId();
    }

    public UUID userId() {
        return membership.userId();
    }

    public MembershipRole membershipRole() {
        return membership.role();
    }

    /** Only after the operation at hand has been authorized (see {@link TenantContext}). */
    public TenantContext context() {
        return membership.context();
    }

    public boolean admin() {
        return membership.admin();
    }

    public boolean tpo() {
        return CampusRoles.TPO.equals(roleKey);
    }

    /** A department coordinator restricted to their departments (admins never are). */
    public boolean coordinator() {
        return !admin() && CampusRoles.COORDINATOR.equals(roleKey);
    }

    public boolean student() {
        return !admin() && CampusRoles.STUDENT.equals(roleKey);
    }

    public boolean recruiter() {
        return !admin() && CampusRoles.RECRUITER.equals(roleKey);
    }

    /** Placement staff: owners, admins, placement officers and coordinators. */
    public boolean staff() {
        return admin() || tpo() || coordinator();
    }

    /** Institute-wide staff: owners, admins and placement officers. */
    public boolean officer() {
        return admin() || tpo();
    }

    public Optional<UUID> studentId() {
        return ids(CampusRoles.ATTR_STUDENT).stream().findFirst();
    }

    public List<UUID> departmentIds() {
        return ids(CampusRoles.ATTR_DEPARTMENT);
    }

    public List<UUID> companyIds() {
        return ids(CampusRoles.ATTR_COMPANY);
    }

    /** 403 {@code access_denied} unless the caller is placement staff. */
    public CampusMember requireStaff() {
        if (!staff()) {
            throw ApiException.forbidden("access_denied", "Only placement staff can do this");
        }
        return this;
    }

    /** 403 {@code access_denied} unless the caller is an owner, admin or placement officer. */
    public CampusMember requireOfficer() {
        if (!officer()) {
            throw ApiException.forbidden("access_denied", "Only placement officers can do this");
        }
        return this;
    }

    /**
     * The student record linked to a student member: 403 {@code access_denied} for other personas, 403
     * {@code student_not_linked} when the membership carries no student record.
     */
    public UUID requireStudentId() {
        if (!student()) {
            throw ApiException.forbidden("access_denied", "Only students can do this");
        }
        return studentId().orElseThrow(() -> ApiException.forbidden("student_not_linked",
                "Your account is not linked to a student record; contact the placement cell"));
    }

    private List<UUID> ids(String attribute) {
        return membership.attributeValues(attribute).stream().map(CampusMember::uuid).filter(Objects::nonNull)
                .toList();
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
