package com.bm4a.platform.modules.tenancy.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Membership attributes (ADR-004): the values record scopes compare against, for example
 * {@code {"department": "<uuid>", "company": ["<uuid>", "<uuid>"]}}. Values are trimmed strings or arrays of distinct
 * strings; an empty array or {@code null} removes the attribute.
 */
final class MembershipAttributes {

    static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    static final int MAX_KEYS = 20;
    static final int MAX_VALUES = 100;
    static final int MAX_VALUE_LENGTH = 200;
    static final int MAX_BYTES = 8 * 1024;

    private MembershipAttributes() {
    }

    /** Validates and canonicalizes; {@code null} or JSON null means "no attributes". */
    static ObjectNode canonical(JsonNode input, String path) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (input == null || input.isNull()) {
            return result;
        }
        if (!(input instanceof ObjectNode object)) {
            throw ApiException.validation(path, "type", path + " must be a JSON object");
        }
        List<FieldViolation> violations = new ArrayList<>();
        if (object.size() > MAX_KEYS) {
            violations.add(new FieldViolation(path, "size", "at most " + MAX_KEYS + " attributes"));
        }
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            String key = entry.getKey();
            String field = path + "." + key;
            if (!KEY.matcher(key).matches()) {
                violations.add(new FieldViolation(field, "format", "attribute names must match ^[a-z][a-z0-9_]{0,62}$"));
                continue;
            }
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                continue;
            }
            if (value.isString()) {
                String text = value.stringValue().strip();
                if (text.isEmpty() || text.length() > MAX_VALUE_LENGTH) {
                    violations.add(new FieldViolation(field, "size", "values must be 1-" + MAX_VALUE_LENGTH + " characters"));
                } else {
                    result.put(key, text);
                }
            } else if (value.isArray()) {
                Set<String> values = new LinkedHashSet<>();
                boolean valid = value.size() <= MAX_VALUES;
                for (JsonNode element : value) {
                    String text = element.isString() ? element.stringValue().strip() : "";
                    valid &= !text.isEmpty() && text.length() <= MAX_VALUE_LENGTH;
                    values.add(text);
                }
                if (!valid) {
                    violations.add(new FieldViolation(field, "type", "arrays must hold 1-" + MAX_VALUES
                            + " strings of 1-" + MAX_VALUE_LENGTH + " characters"));
                } else if (!values.isEmpty()) {
                    ArrayNode array = result.putArray(key);
                    values.forEach(array::add);
                }
            } else {
                violations.add(new FieldViolation(field, "type", "values must be strings or arrays of strings"));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.validation(path, "size", path + " must not exceed " + MAX_BYTES / 1024 + " KiB");
        }
        return result;
    }

    /** Read model: string values stay strings, arrays become unmodifiable lists of strings. */
    static Map<String, Object> asMap(ObjectNode attributes) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (attributes == null) {
            return result;
        }
        for (Map.Entry<String, JsonNode> entry : attributes.properties()) {
            JsonNode value = entry.getValue();
            if (value.isString()) {
                result.put(entry.getKey(), value.stringValue());
            } else if (value.isArray()) {
                List<String> values = new ArrayList<>();
                value.forEach(element -> values.add(element.asString()));
                result.put(entry.getKey(), List.copyOf(values));
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
