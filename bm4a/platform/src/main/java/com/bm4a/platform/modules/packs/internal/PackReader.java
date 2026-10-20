package com.bm4a.platform.modules.packs.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.packs.internal.PackDocument.PackModule;
import com.bm4a.platform.modules.packs.internal.PackDocument.PackRelationship;
import com.bm4a.platform.modules.packs.internal.PackDocument.PackRole;
import com.bm4a.platform.modules.packs.internal.PackDocument.PackWorkflow;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Parses a pack document strictly: unknown properties and wrongly typed values are rejected with their JSON path
 * ({@code modules[3].fields[2].bogus}), then the pack-level rules are checked (keys, version, labels, duplicates).
 * Definitions inside the pack are validated later by the module, role, relationship and workflow services.
 */
@Component
class PackReader {

    static final int MAX_BYTES = 512 * 1024;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final JsonColumns json;

    PackReader(JsonColumns json) {
        this.json = json;
    }

    PackDocument read(JsonNode pack) {
        if (pack == null || !pack.isObject()) {
            throw ApiException.validation("pack", "type", "a pack must be a JSON object");
        }
        if (json.write(pack).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.validation("pack", "size", "a pack must not exceed " + MAX_BYTES / 1024 + " KiB");
        }
        PackDocument document;
        try {
            document = json.mapper().readerFor(PackDocument.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(pack);
        } catch (DatabindException ex) {
            throw ApiException.validation(path(ex), code(ex), message(ex));
        }
        List<FieldViolation> violations = new ArrayList<>();
        validate(document, violations);
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        return document;
    }

    private static void validate(PackDocument pack, List<FieldViolation> violations) {
        key(pack.key(), "key", violations);
        if (pack.version() == null || pack.version() < 1) {
            violations.add(v("version", "range", "version must be a positive integer"));
        }
        if (pack.label() == null || pack.label().isBlank() || pack.label().length() > 200) {
            violations.add(v("label", "size", "label must be 1-200 characters"));
        }
        if (pack.description() != null && pack.description().length() > 2000) {
            violations.add(v("description", "size", "description must be at most 2000 characters"));
        }
        if (pack.settings() != null && !pack.settings().isNull() && !pack.settings().isObject()) {
            violations.add(v("settings", "type", "settings must be a JSON object"));
        }
        unique(pack.modules(), "modules", PackModule::apiKey, "apiKey", violations);
        for (int i = 0; i < pack.modules().size(); i++) {
            PackModule module = pack.modules().get(i);
            if (module != null) {
                unique(module.fields(), "modules[" + i + "].fields", field -> field.apiKey(), "apiKey", violations);
                if (module.layout() != null && !module.layout().isNull() && !module.layout().isObject()) {
                    violations.add(v("modules[" + i + "].layout", "type", "layout must be a JSON object"));
                }
            }
        }
        unique(pack.roles(), "roles", PackRole::apiKey, "apiKey", violations);
        unique(pack.relationships(), "relationships", PackRelationship::apiKey, "apiKey", violations);
        unique(pack.workflows(), "workflows", PackWorkflow::module, "module", violations);
        for (int i = 0; i < pack.workflows().size(); i++) {
            PackWorkflow workflow = pack.workflows().get(i);
            if (workflow != null && (workflow.field() == null || workflow.field().isBlank())) {
                violations.add(v("workflows[" + i + "].field", "required", "field is required"));
            }
        }
    }

    /** Entries must be objects with a present, distinct key property. */
    private static <T> void unique(List<T> entries, String path, Function<T, String> key, String property,
            List<FieldViolation> violations) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            String p = path + "[" + i + "]";
            T entry = entries.get(i);
            if (entry == null) {
                violations.add(v(p, "type", "must be a JSON object"));
                continue;
            }
            String value = key.apply(entry);
            if (value == null || value.isBlank()) {
                violations.add(v(p + "." + property, "required", property + " is required"));
            } else if (!seen.add(value)) {
                violations.add(v(p + "." + property, "duplicate", "duplicate " + property + " '" + value + "'"));
            }
        }
    }

    private static void key(String value, String path, List<FieldViolation> violations) {
        if (value == null || !KEY.matcher(value).matches()) {
            violations.add(v(path, "format", "must match ^[a-z][a-z0-9_]{0,62}$"));
        }
    }

    private static String path(DatabindException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : ex.getPath()) {
            if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            } else if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            }
        }
        return path.isEmpty() ? "pack" : path.toString();
    }

    private static String code(DatabindException ex) {
        return switch (ex) {
            case UnrecognizedPropertyException unknown -> "unknown_property";
            case InvalidFormatException invalid -> "invalid";
            case MismatchedInputException mismatched -> "type";
            default -> "invalid";
        };
    }

    private static String message(DatabindException ex) {
        return switch (ex) {
            case UnrecognizedPropertyException unknown -> "unknown property";
            case InvalidFormatException invalid -> "invalid value " + invalid.getValue();
            case MismatchedInputException mismatched -> "value has the wrong type";
            default -> "invalid value";
        };
    }

    private static FieldViolation v(String field, String code, String message) {
        return new FieldViolation(field, code, message);
    }
}
