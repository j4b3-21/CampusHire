package com.bm4a.platform.modules.records;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.FieldValues;
import com.bm4a.platform.modules.metadata.ModuleSchema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies a {@link RecordScope} to records of one module, either as an SQL predicate over the {@code record} table or
 * in memory. Both forms use the same canonical containment semantics. The SQL is built from constant fragments; the
 * owner, IDs and values are always bound parameters. Unknown fields and values that cannot be canonicalized match
 * nothing (fail closed).
 */
public final class RecordScopes {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private RecordScopes() {
    }

    /**
     * A boolean SQL expression and its bound parameters.
     *
     * @param sql references the {@code record} table through the alias given to {@link #predicate}
     */
    public record Predicate(String sql, Map<String, Object> params) {

        public Predicate {
            params = Map.copyOf(params);
        }
    }

    /**
     * @param alias SQL alias of the {@code record} table, for example {@code r}
     * @param paramPrefix prefix for the generated parameter names; must not clash with the caller's parameters
     */
    public static Predicate predicate(RecordScope scope, ModuleSchema module, String alias, String paramPrefix) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        int[] counter = { 0 };
        Function<Object, String> bind = value -> {
            String name = paramPrefix + counter[0]++;
            params.put(name, value);
            return ":" + name;
        };
        switch (scope.kind()) {
            case NONE -> conditions.add("FALSE");
            case OWN -> conditions.add(alias + ".owner_user_id = " + bind.apply(scope.ownerUserId()));
            case ATTRIBUTE -> {
                if (RecordScope.ID_FIELD.equals(scope.field())) {
                    List<UUID> ids = scope.values().stream().map(RecordScopes::uuid).filter(Objects::nonNull).toList();
                    conditions.add(ids.isEmpty() ? "FALSE" : alias + ".id IN (" + bind.apply(ids) + ")");
                } else {
                    conditions.add(anyContained(alias, bind, attributeDocuments(module, scope)));
                }
            }
            case ALL -> {
            }
        }
        scope.where().forEach((key, values) -> conditions.add(anyContained(alias, bind,
                whereDocuments(module, key, values))));
        return new Predicate(conditions.isEmpty() ? "TRUE" : "(" + String.join(" AND ", conditions) + ")", params);
    }

    /** Whether a record (its ID, owner and canonical data) lies within the scope. */
    public static boolean matches(RecordScope scope, ModuleSchema module, UUID recordId, UUID ownerUserId,
            ObjectNode data) {
        boolean base = switch (scope.kind()) {
            case NONE -> false;
            case ALL -> true;
            case OWN -> scope.ownerUserId().equals(ownerUserId);
            case ATTRIBUTE -> RecordScope.ID_FIELD.equals(scope.field())
                    ? scope.values().stream().map(RecordScopes::uuid).anyMatch(recordId::equals)
                    : attributeDocuments(module, scope).stream().anyMatch(document -> contains(data, document));
        };
        if (!base) {
            return false;
        }
        for (Map.Entry<String, List<JsonNode>> where : scope.where().entrySet()) {
            if (whereDocuments(module, where.getKey(), where.getValue()).stream()
                    .noneMatch(document -> contains(data, document))) {
                return false;
            }
        }
        return true;
    }

    private static String anyContained(String alias, Function<Object, String> bind,
            List<ObjectNode> documents) {
        if (documents.isEmpty()) {
            return "FALSE";
        }
        List<String> alternatives = new ArrayList<>();
        for (ObjectNode document : documents) {
            alternatives.add(alias + ".data @> CAST(" + bind.apply(document.toString()) + " AS jsonb)");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /** Containment documents {@code {field: canonical attribute value}}, one per value. */
    private static List<ObjectNode> attributeDocuments(ModuleSchema module, RecordScope scope) {
        FieldSchema field = module.field(scope.field()).orElse(null);
        List<ObjectNode> documents = new ArrayList<>();
        if (field == null) {
            return documents;
        }
        for (String value : scope.values()) {
            JsonNode input = field.type() == FieldType.MULTI_SELECT ? NODES.arrayNode().add(value)
                    : NODES.stringNode(value);
            FieldValues.Outcome outcome = FieldValues.canonicalize(field, input, input);
            if (outcome.hasValue()) {
                documents.add(document(field.apiKey(), outcome.value()));
            }
        }
        return documents;
    }

    private static List<ObjectNode> whereDocuments(ModuleSchema module, String key, List<JsonNode> values) {
        if (module.field(key).isEmpty()) {
            return List.of();
        }
        return values.stream().map(value -> document(key, value)).toList();
    }

    private static ObjectNode document(String key, JsonNode value) {
        ObjectNode document = NODES.objectNode();
        document.set(key, value);
        return document;
    }

    /** JSONB {@code @>} for one top-level key: equal values, or for arrays every element contained. */
    private static boolean contains(ObjectNode data, ObjectNode document) {
        for (Map.Entry<String, JsonNode> entry : document.properties()) {
            JsonNode actual = data.get(entry.getKey());
            JsonNode expected = entry.getValue();
            if (actual == null) {
                return false;
            }
            if (expected.isArray() && actual.isArray()) {
                for (JsonNode element : expected) {
                    boolean found = false;
                    for (JsonNode candidate : actual) {
                        found |= candidate.equals(element);
                    }
                    if (!found) {
                        return false;
                    }
                }
            } else if (!actual.equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
