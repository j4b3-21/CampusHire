package com.bm4a.platform.modules.authorization;

/**
 * Directly assignable relations on a module. {@code writer} implies {@code can_read} in the model.
 */
public enum ModuleRelation {

    READER("reader"),
    WRITER("writer");

    private final String relation;

    ModuleRelation(String relation) {
        this.relation = relation;
    }

    public String relation() {
        return relation;
    }
}
