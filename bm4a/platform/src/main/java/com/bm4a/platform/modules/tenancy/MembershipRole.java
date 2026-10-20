package com.bm4a.platform.modules.tenancy;

import com.bm4a.platform.modules.authorization.OrganizationRelation;

public enum MembershipRole {

    OWNER(OrganizationRelation.OWNER),
    ADMIN(OrganizationRelation.ADMIN),
    MEMBER(OrganizationRelation.MEMBER);

    private final OrganizationRelation relation;

    MembershipRole(OrganizationRelation relation) {
        this.relation = relation;
    }

    public OrganizationRelation relation() {
        return relation;
    }
}
