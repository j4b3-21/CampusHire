package com.bm4a.platform.modules.authorization;

import java.util.UUID;

/**
 * A relationship tuple {@code object#relation@user} in OpenFGA notation.
 */
public record RelationshipTuple(String user, String relation, String object) {

    public static RelationshipTuple organization(UUID userId, OrganizationRelation relation, UUID organizationId) {
        return new RelationshipTuple(userRef(userId), relation.relation(), organizationRef(organizationId));
    }

    public static String userRef(UUID userId) {
        return "user:" + userId;
    }

    public static String organizationRef(UUID organizationId) {
        return "organization:" + organizationId;
    }
}
