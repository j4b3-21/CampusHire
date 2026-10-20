package com.bm4a.platform.modules.authorization;

/**
 * Organization-scoped permissions. Each maps to a computed relation on the OpenFGA {@code organization} type (see
 * {@code openfga/model.fga}).
 */
public enum Permission {

    ORGANIZATION_VIEW("can_view"),
    ORGANIZATION_UPDATE("can_update"),
    ORGANIZATION_ARCHIVE("can_archive"),
    MEMBERS_MANAGE("can_manage_members"),
    METADATA_MANAGE("can_manage_metadata"),
    RECORDS_READ("can_read_records"),
    RECORDS_WRITE("can_write_records"),
    AUDIT_VIEW("can_view_audit");

    private final String relation;

    Permission(String relation) {
        this.relation = relation;
    }

    public String relation() {
        return relation;
    }
}
