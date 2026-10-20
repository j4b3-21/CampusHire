package com.bm4a.platform.modules.records.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bm4a.platform.modules.access.FieldPolicy;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.FieldValues;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.records.RecordScopes;
import com.bm4a.platform.modules.shared.error.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the WHERE and ORDER BY clauses of a record listing from request parameters.
 * <p>
 * Safety model: only fields flagged {@code filterable}/{@code sortable} (and not archived) are accepted, only operators
 * valid for the field type, and the SQL is assembled exclusively from constant fragments. Field keys and values are
 * always bound parameters ({@code r.data -> :k0}, {@code CAST(:v0 AS jsonb)}); nothing from the request is
 * concatenated into SQL.
 * <p>
 * Syntax: {@code filter[field]=value} (equality) or {@code filter[field][op]=value} with op in
 * {@code eq, ne, gt, gte, lt, lte, contains, in, exists}; {@code sort=-price,name} (max 3 keys; {@code created_at} and
 * {@code updated_at} are always sortable); {@code q=text} searches {@code searchable} fields case-insensitively.
 */
final class RecordQuery {

    static final Pattern FILTER_PARAM = Pattern.compile("^filter\\[([a-z][a-z0-9_]{0,62})\\](?:\\[([a-z]+)\\])?$");
    static final int MAX_FILTERS = 20;
    static final int MAX_IN_VALUES = 50;
    static final int MAX_SORT_KEYS = 3;

    enum Op {
        EQ, NE, GT, GTE, LT, LTE, CONTAINS, IN, EXISTS
    }

    private static final Set<Op> EQUALITY = EnumSet.of(Op.EQ, Op.NE, Op.EXISTS);
    private static final Set<Op> RANGE = EnumSet.of(Op.EQ, Op.NE, Op.GT, Op.GTE, Op.LT, Op.LTE, Op.EXISTS);
    private static final Set<Op> TEXT = EnumSet.of(Op.EQ, Op.NE, Op.CONTAINS, Op.IN, Op.EXISTS);
    private static final Set<Op> SELECT = EnumSet.of(Op.EQ, Op.NE, Op.IN, Op.EXISTS);

    private final List<String> conditions = new ArrayList<>();
    private final Map<String, Object> params = new HashMap<>();
    private final List<String> orderBy = new ArrayList<>();
    private FieldPolicy policy = FieldPolicy.unrestricted();
    private int counter;

    private RecordQuery() {
    }

    /**
     * @param policy hidden fields can be neither filtered, sorted nor searched (they would leak through results)
     * @param scope record scope predicate over alias {@code r}; its parameter names must not start with {@code p}
     */
    static RecordQuery build(UUID organizationId, ModuleSchema module, Map<String, List<String>> parameters,
            String sort, String search, boolean deleted, FieldPolicy policy, RecordScopes.Predicate scope) {
        RecordQuery query = new RecordQuery();
        query.policy = policy;
        query.conditions.add("r.organization_id = :org");
        query.conditions.add("r.module_id = :module");
        query.conditions.add(deleted ? "r.deleted_at IS NOT NULL" : "r.deleted_at IS NULL");
        query.conditions.add(scope.sql());
        query.params.put("org", organizationId);
        query.params.put("module", module.id());
        query.params.putAll(scope.params());

        int filters = 0;
        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            if (!entry.getKey().startsWith("filter")) {
                continue;
            }
            Matcher matcher = FILTER_PARAM.matcher(entry.getKey());
            if (!matcher.matches()) {
                throw ApiException.badRequest("invalid_filter", "Malformed filter parameter");
            }
            if (++filters > MAX_FILTERS) {
                throw ApiException.badRequest("invalid_filter", "At most " + MAX_FILTERS + " filters");
            }
            FieldSchema field = module.field(matcher.group(1))
                    .filter(f -> f.visible() && f.filterable() && !policy.hidden(f.apiKey()))
                    .orElseThrow(() -> ApiException.badRequest("invalid_filter",
                            "Field '" + matcher.group(1) + "' is not filterable"));
            Op op = parseOp(matcher.group(2));
            for (String value : entry.getValue()) {
                query.addFilter(field, op, value);
            }
        }
        if (search != null && !search.isBlank()) {
            query.addSearch(module, search.strip());
        }
        query.addSort(module, sort);
        return query;
    }

    String where() {
        return String.join(" AND ", conditions);
    }

    String orderBy() {
        return String.join(", ", orderBy);
    }

    Map<String, Object> params() {
        return params;
    }

    private void addFilter(FieldSchema field, Op op, String raw) {
        if (!allowedOps(field.type()).contains(op)) {
            throw ApiException.badRequest("invalid_filter",
                    "Operator " + op.name().toLowerCase() + " is not supported for " + field.type());
        }
        String k = param(field.apiKey());
        switch (op) {
            case EXISTS -> {
                boolean exists = switch (raw) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> throw ApiException.badRequest("invalid_filter", "exists expects true or false");
                };
                conditions.add(exists ? "(r.data -> :" + k + ") IS NOT NULL" : "(r.data -> :" + k + ") IS NULL");
            }
            case EQ, NE -> {
                String v = param(containment(field, canonical(field, raw)));
                String contains = "r.data @> CAST(:" + v + " AS jsonb)";
                conditions.add(op == Op.EQ ? contains : "NOT (" + contains + ")");
            }
            case CONTAINS -> {
                if (field.type() == FieldType.MULTI_SELECT) {
                    JsonNode option = canonical(field, "[\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]");
                    conditions.add("r.data @> CAST(:" + param(containment(field, option)) + " AS jsonb)");
                } else {
                    String v = param("%" + escapeLike(raw) + "%");
                    conditions.add("(r.data ->> :" + k + ") ILIKE :" + v + " ESCAPE '\\'");
                }
            }
            case IN -> {
                List<String> values = List.of(raw.split(",", -1));
                if (values.isEmpty() || values.size() > MAX_IN_VALUES) {
                    throw ApiException.badRequest("invalid_filter", "in expects 1-" + MAX_IN_VALUES + " values");
                }
                List<String> canonical = values.stream().map(value -> canonical(field, quote(value)).asString()).toList();
                conditions.add("(r.data ->> :" + k + ") IN (:" + param(canonical) + ")");
            }
            case GT, GTE, LT, LTE -> {
                String comparator = switch (op) {
                    case GT -> ">";
                    case GTE -> ">=";
                    case LT -> "<";
                    default -> "<=";
                };
                conditions.add(typedExpression(field, k) + " " + comparator + " " + typedValue(field, raw));
            }
        }
    }

    private void addSearch(ModuleSchema module, String search) {
        if (search.length() > 200) {
            throw ApiException.badRequest("invalid_search", "q must be at most 200 characters");
        }
        List<FieldSchema> searchable = module.fields().stream()
                .filter(f -> f.visible() && f.searchable() && !policy.hidden(f.apiKey())).toList();
        if (searchable.isEmpty()) {
            throw ApiException.badRequest("search_not_supported", "This module has no searchable fields");
        }
        String v = param("%" + escapeLike(search) + "%");
        List<String> alternatives = new ArrayList<>();
        for (FieldSchema field : searchable) {
            alternatives.add("(r.data ->> :" + param(field.apiKey()) + ") ILIKE :" + v + " ESCAPE '\\'");
        }
        conditions.add("(" + String.join(" OR ", alternatives) + ")");
    }

    private void addSort(ModuleSchema module, String sort) {
        if (sort != null && !sort.isBlank()) {
            String[] keys = sort.split(",");
            if (keys.length > MAX_SORT_KEYS) {
                throw ApiException.badRequest("invalid_sort", "At most " + MAX_SORT_KEYS + " sort keys");
            }
            for (String key : keys) {
                String name = key.strip();
                boolean descending = name.startsWith("-");
                name = descending ? name.substring(1) : name;
                String direction = descending ? " DESC" : " ASC";
                switch (name) {
                    case "created_at" -> orderBy.add("r.created_at" + direction);
                    case "updated_at" -> orderBy.add("r.updated_at" + direction);
                    default -> {
                        String fieldKey = name;
                        FieldSchema field = module.field(fieldKey)
                                .filter(f -> f.visible() && f.sortable() && !policy.hidden(f.apiKey()))
                                .orElseThrow(() -> ApiException.badRequest("invalid_sort",
                                        "Field '" + fieldKey + "' is not sortable"));
                        orderBy.add(typedExpression(field, param(field.apiKey())) + direction + " NULLS LAST");
                    }
                }
            }
        } else {
            orderBy.add("r.created_at DESC");
        }
        orderBy.add("r.id ASC");
    }

    private static Set<Op> allowedOps(FieldType type) {
        return switch (type) {
            case INTEGER, DECIMAL, CURRENCY, DATE, DATETIME -> RANGE;
            case TEXT, LONG_TEXT, EMAIL, PHONE, URL -> TEXT;
            case SINGLE_SELECT, LOOKUP -> SELECT;
            case MULTI_SELECT -> EnumSet.of(Op.CONTAINS, Op.EXISTS);
            case BOOLEAN -> EQUALITY;
            default -> EnumSet.noneOf(Op.class);
        };
    }

    /** Typed SQL expression for a field; the key is always a bound parameter. */
    private static String typedExpression(FieldSchema field, String keyParam) {
        return switch (field.type()) {
            case INTEGER, DECIMAL -> "CAST(r.data ->> :" + keyParam + " AS numeric)";
            case CURRENCY -> "CAST(r.data -> :" + keyParam + " ->> 'amount' AS numeric)";
            case DATE -> "CAST(r.data ->> :" + keyParam + " AS date)";
            case DATETIME -> "CAST(r.data ->> :" + keyParam + " AS timestamptz)";
            case BOOLEAN -> "CAST(r.data ->> :" + keyParam + " AS boolean)";
            default -> "(r.data ->> :" + keyParam + ")";
        };
    }

    private String typedValue(FieldSchema field, String raw) {
        return switch (field.type()) {
            case INTEGER, DECIMAL, CURRENCY -> {
                try {
                    yield "CAST(:" + param(new BigDecimal(raw.strip()).toPlainString()) + " AS numeric)";
                } catch (NumberFormatException ex) {
                    throw ApiException.badRequest("invalid_filter", "Expected a number for " + field.apiKey());
                }
            }
            case DATE -> "CAST(:" + param(canonical(field, quote(raw)).asString()) + " AS date)";
            case DATETIME -> "CAST(:" + param(canonical(field, quote(raw)).asString()) + " AS timestamptz)";
            default -> throw ApiException.badRequest("invalid_filter", "Range filters are not supported here");
        };
    }

    /** Canonicalizes a filter value with the same rules as stored values, so equality matches exactly. */
    private static JsonNode canonical(FieldSchema field, String raw) {
        JsonNode node;
        switch (field.type()) {
            case INTEGER, BOOLEAN -> node = parseJson(raw);
            case CURRENCY -> throw ApiException.badRequest("invalid_filter",
                    "Use gt/gte/lt/lte on currency amounts");
            case MULTI_SELECT -> node = parseJson(raw);
            default -> node = raw.startsWith("\"") ? parseJson(raw) : JsonNodeFactory.instance.stringNode(raw);
        }
        FieldValues.Outcome outcome = FieldValues.canonicalize(field, node, null);
        if (!outcome.hasValue()) {
            throw ApiException.badRequest("invalid_filter", "Invalid value for " + field.apiKey()
                    + (outcome.message() == null ? "" : ": " + outcome.message()));
        }
        return outcome.value();
    }

    private static String containment(FieldSchema field, JsonNode value) {
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        document.set(field.apiKey(), value);
        return document.toString();
    }

    private static JsonNode parseJson(String raw) {
        try {
            return tools.jackson.databind.json.JsonMapper.shared().readTree(raw);
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("invalid_filter", "Malformed filter value");
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Op parseOp(String op) {
        if (op == null) {
            return Op.EQ;
        }
        try {
            return Op.valueOf(op.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_filter", "Unknown filter operator");
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String param(Object value) {
        String name = "p" + counter++;
        params.put(name, value);
        return name;
    }
}
