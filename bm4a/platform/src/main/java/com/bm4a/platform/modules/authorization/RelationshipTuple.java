package com.bm4a.platform.modules.authorization;

import java.util.UUID;

/**
 * A relationship tuple {@code object#relation@user} in OpenFGA notation. {@code user} may be a userset such as
 * {@code organization:<id>#member}.
 */
public record RelationshipTuple(String user, String relation, String object) {

    public static RelationshipTuple organization(UUID userId, OrganizationRelation relation, UUID organizationId) {
        return new RelationshipTuple(userRef(userId), relation.relation(), organizationRef(organizationId));
    }

    /** {@code role:<roleId>#assignee@user:<userId>} */
    public static RelationshipTuple roleAssignee(UUID userId, UUID roleId) {
        return new RelationshipTuple(userRef(userId), "assignee", roleRef(roleId));
    }

    /** {@code role:<roleId>#organization@organization:<organizationId>} */
    public static RelationshipTuple roleOrganization(UUID roleId, UUID organizationId) {
        return new RelationshipTuple(organizationRef(organizationId), "organization", roleRef(roleId));
    }

    /** {@code module:<moduleId>#organization@organization:<organizationId>} */
    public static RelationshipTuple moduleOrganization(UUID moduleId, UUID organizationId) {
        return new RelationshipTuple(organizationRef(organizationId), "organization", moduleRef(moduleId));
    }

    /** {@code module:<moduleId>#<relation>@organization:<organizationId>#member} (MEMBERS access mode). */
    public static RelationshipTuple moduleMembers(ModuleRelation relation, UUID moduleId, UUID organizationId) {
        return new RelationshipTuple(organizationRef(organizationId) + "#member", relation.relation(),
                moduleRef(moduleId));
    }

    /** {@code module:<moduleId>#<relation>@role:<roleId>#assignee} */
    public static RelationshipTuple moduleRole(ModuleRelation relation, UUID moduleId, UUID roleId) {
        return new RelationshipTuple(roleRef(roleId) + "#assignee", relation.relation(), moduleRef(moduleId));
    }

    public static String userRef(UUID userId) {
        return "user:" + userId;
    }

    public static String organizationRef(UUID organizationId) {
        return "organization:" + organizationId;
    }

    public static String roleRef(UUID roleId) {
        return "role:" + roleId;
    }

    public static String moduleRef(UUID moduleId) {
        return "module:" + moduleId;
    }
}
