package com.bm4a.platform.modules.authorization;

/**
 * Module-level permissions. Each maps to a computed relation on the OpenFGA {@code module} type (see
 * {@code openfga/model.fga}); organization admins hold both through {@code admin from organization}.
 */
public enum ModulePermission {

    READ("can_read"),
    WRITE("can_write");

    private final String relation;

    ModulePermission(String relation) {
        this.relation = relation;
    }

    public String relation() {
        return relation;
    }
}
