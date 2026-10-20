package com.bm4a.platform.modules.access.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bm4a.platform.modules.access.RecordScope;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Evaluation model of a stored (already validated, canonical) permission document. Parsing is defensive: anything
 * unexpected - for example a document written by a newer version - degrades to "no access" rather than more access.
 */
record RolePermissions(Map<String, ModulePolicy> modules) {

    static final RolePermissions EMPTY = new RolePermissions(Map.of());

    /** @param create creating records is a yes/no decision (ADR-004) */
    record ModulePolicy(Rule read, boolean create, Rule update, Rule delete, Set<String> hiddenFields,
            Set<String> readOnlyFields) {

        /** Whether any write action is granted (OpenFGA {@code writer}). */
        boolean writes() {
            return create || update.grants() || delete.grants();
        }
    }

    /**
     * @param field record field (or {@code $id}) of an {@code ATTRIBUTE} rule
     * @param attribute membership attribute of an {@code ATTRIBUTE} rule
     * @param where canonical constant filters
     */
    record Rule(RecordScope.Kind kind, String field, String attribute, Map<String, List<JsonNode>> where) {

        static final Rule NONE = new Rule(RecordScope.Kind.NONE, null, null, Map.of());

        boolean grants() {
            return kind != RecordScope.Kind.NONE;
        }
    }

    static RolePermissions parse(JsonNode document) {
        if (document == null || !(document.get("modules") instanceof ObjectNode modules)) {
            return EMPTY;
        }
        Map<String, ModulePolicy> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : modules.properties()) {
            if (entry.getValue() instanceof ObjectNode policy) {
                result.put(entry.getKey(), new ModulePolicy(rule(policy.get("read")),
                        policy.path("create").isBoolean() && policy.path("create").booleanValue(),
                        rule(policy.get("update")), rule(policy.get("delete")), names(policy.get("hiddenFields")),
                        names(policy.get("readOnlyFields"))));
            }
        }
        return new RolePermissions(Map.copyOf(result));
    }

    private static Rule rule(JsonNode node) {
        if (node == null) {
            return Rule.NONE;
        }
        if (node.isString()) {
            RecordScope.Kind kind = kind(node.stringValue());
            return kind == null || kind == RecordScope.Kind.ATTRIBUTE ? Rule.NONE : new Rule(kind, null, null, Map.of());
        }
        if (!(node instanceof ObjectNode object)) {
            return Rule.NONE;
        }
        RecordScope.Kind kind = kind(object.path("scope").asString(""));
        if (kind == null || kind == RecordScope.Kind.NONE) {
            return Rule.NONE;
        }
        String field = object.path("field").isString() ? object.get("field").stringValue() : null;
        String attribute = object.path("attribute").isString() ? object.get("attribute").stringValue() : null;
        if (kind == RecordScope.Kind.ATTRIBUTE && (field == null || attribute == null)) {
            return Rule.NONE;
        }
        Map<String, List<JsonNode>> where = new LinkedHashMap<>();
        if (object.get("where") instanceof ObjectNode filters) {
            for (Map.Entry<String, JsonNode> filter : filters.properties()) {
                List<JsonNode> values = new ArrayList<>();
                if (filter.getValue().isArray()) {
                    filter.getValue().forEach(values::add);
                }
                if (values.isEmpty()) {
                    return Rule.NONE;
                }
                where.put(filter.getKey(), List.copyOf(values));
            }
        }
        return new Rule(kind, field, attribute, Map.copyOf(where));
    }

    private static RecordScope.Kind kind(String value) {
        for (RecordScope.Kind kind : RecordScope.Kind.values()) {
            if (kind.name().equals(value)) {
                return kind;
            }
        }
        return null;
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> {
                if (element.isString()) {
                    names.add(element.stringValue());
                }
            });
        }
        return Set.copyOf(names);
    }
}
