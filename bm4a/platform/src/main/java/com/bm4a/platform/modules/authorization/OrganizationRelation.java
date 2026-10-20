package com.bm4a.platform.modules.authorization;

/**
 * Directly assignable relations between a user and an organization. Higher relations imply lower ones in the model
 * ({@code owner -> admin -> member}), so a user only ever holds one of them.
 */
public enum OrganizationRelation {

    OWNER("owner"),
    ADMIN("admin"),
    MEMBER("member");

    private final String relation;

    OrganizationRelation(String relation) {
        this.relation = relation;
    }

    public String relation() {
        return relation;
    }
}
