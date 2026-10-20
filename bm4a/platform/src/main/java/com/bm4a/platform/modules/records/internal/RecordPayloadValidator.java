package com.bm4a.platform.modules.records.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldValues;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a client payload into the next canonical {@code data} document of a record.
 * <ul>
 * <li>{@link Mode#CREATE} - payload is the full record; defaults apply to absent fields</li>
 * <li>{@link Mode#REPLACE} (PUT) - payload is the full set of visible fields; omitted fields are cleared; values of
 * archived (hidden) fields are preserved</li>
 * <li>{@link Mode#MERGE} (PATCH) - JSON Merge Patch (RFC 7386) on the visible fields: present keys are set,
 * {@code null} clears, absent keys are unchanged</li>
 * </ul>
 * Unknown and archived keys are rejected, read-only and deprecated fields cannot be changed, and every ACTIVE required
 * field must have a value afterwards.
 */
@Component
class RecordPayloadValidator {

    /** Upper bound for a record's complete canonical {@code data} document. */
    static final int MAX_DATA_BYTES = 256 * 1024;

    enum Mode {
        CREATE, REPLACE, MERGE
    }

    record Result(ObjectNode data, List<String> changedFields) {
    }

    Result apply(ModuleSchema module, JsonNode payload, ObjectNode current, Mode mode) {
        if (!(payload instanceof ObjectNode input)) {
            throw ApiException.validation("data", "type", "data must be a JSON object");
        }
        ObjectNode existing = current == null ? JsonNodeFactory.instance.objectNode() : current;
        List<FieldViolation> violations = new ArrayList<>();
        ObjectNode next = switch (mode) {
            case CREATE -> JsonNodeFactory.instance.objectNode();
            case MERGE -> existing.deepCopy();
            case REPLACE -> hiddenValues(module, existing);
        };

        for (String key : input.propertyNames()) {
            FieldSchema field = module.field(key).filter(FieldSchema::visible).orElse(null);
            if (field == null) {
                violations.add(new FieldViolation("data." + key, "unknown_field", "unknown field"));
                continue;
            }
            JsonNode previous = existing.get(key);
            FieldValues.Outcome outcome = FieldValues.canonicalize(field, input.get(key), previous);
            if (!outcome.valid()) {
                violations.add(new FieldViolation("data." + key, outcome.code(), outcome.message()));
                continue;
            }
            boolean changes = mode == Mode.CREATE ? outcome.hasValue() : !Objects.equals(outcome.value(), previous);
            if (changes) {
                if (field.readOnly()) {
                    violations.add(new FieldViolation("data." + key, "read_only", "field is read-only"));
                    continue;
                }
                if (field.status() == FieldStatus.DEPRECATED) {
                    violations.add(new FieldViolation("data." + key, "field_deprecated",
                            "field is deprecated; its value can no longer be changed"));
                    continue;
                }
            }
            if (outcome.hasValue()) {
                next.set(key, outcome.value());
            } else {
                next.remove(key);
            }
        }

        if (mode == Mode.REPLACE) {
            for (FieldSchema field : module.fields()) {
                if (field.visible() && !input.has(field.apiKey()) && existing.has(field.apiKey())) {
                    if (field.readOnly() || field.status() == FieldStatus.DEPRECATED) {
                        next.set(field.apiKey(), existing.get(field.apiKey()));
                    }
                }
            }
        }
        if (mode == Mode.CREATE) {
            for (FieldSchema field : module.fields()) {
                if (field.writable() && field.defaultValue() != null && !next.has(field.apiKey())) {
                    next.set(field.apiKey(), field.defaultValue().deepCopy());
                }
            }
        }
        for (FieldSchema field : FieldValues.ordered(module.fields())) {
            if (field.status() == FieldStatus.ACTIVE && field.required() && !next.has(field.apiKey())) {
                violations.add(new FieldViolation("data." + field.apiKey(), "required", "field is required"));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        if (next.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DATA_BYTES) {
            throw ApiException.validation("data", "payload_too_large",
                    "record data must not exceed " + MAX_DATA_BYTES / 1024 + " KiB");
        }
        return new Result(next, changedFields(existing, next));
    }

    private static ObjectNode hiddenValues(ModuleSchema module, ObjectNode existing) {
        ObjectNode hidden = existing.deepCopy();
        for (FieldSchema field : module.fields()) {
            if (field.visible()) {
                hidden.remove(field.apiKey());
            }
        }
        return hidden;
    }

    static List<String> changedFields(ObjectNode before, ObjectNode after) {
        Set<String> keys = new LinkedHashSet<>(before.propertyNames());
        keys.addAll(after.propertyNames());
        return keys.stream().filter(key -> !Objects.equals(before.get(key), after.get(key))).toList();
    }
}
