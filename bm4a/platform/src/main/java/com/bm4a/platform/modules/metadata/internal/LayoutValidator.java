package com.bm4a.platform.modules.metadata.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Layout = ordered sections (optionally grouped into tabs), each listing field API keys:
 *
 * <pre>
 * {"sections": [{"key": "general", "label": "General", "tab": "Details", "columns": 2, "fields": ["name", "serial"]}]}
 * </pre>
 *
 * Every field may appear at most once and must exist and not be archived. Purely presentational: the record API
 * never depends on it.
 */
@Component
class LayoutValidator {

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final Set<String> LAYOUT_PROPERTIES = Set.of("sections");
    private static final Set<String> SECTION_PROPERTIES = Set.of("key", "label", "tab", "columns", "fields");
    private static final int MAX_SECTIONS = 50;

    ObjectNode validate(JsonNode layout, List<FieldRow> fields) {
        List<FieldViolation> violations = new ArrayList<>();
        if (!(layout instanceof ObjectNode root) || !onlyProperties(root, LAYOUT_PROPERTIES)
                || !(root.get("sections") instanceof ArrayNode sections)) {
            throw ApiException.validation("layout", "format", "layout must be {\"sections\": [...]}");
        }
        if (sections.size() > MAX_SECTIONS) {
            throw ApiException.validation("layout.sections", "size", "at most " + MAX_SECTIONS + " sections");
        }
        Map<String, FieldStatus> known = fields.stream().collect(Collectors.toMap(FieldRow::apiKey, FieldRow::status));
        Set<String> sectionKeys = new HashSet<>();
        Set<String> placedFields = new HashSet<>();
        ArrayNode canonicalSections = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < sections.size(); i++) {
            String p = "layout.sections[" + i + "]";
            if (!(sections.get(i) instanceof ObjectNode section) || !onlyProperties(section, SECTION_PROPERTIES)) {
                violations.add(new FieldViolation(p, "format", "section has unknown properties"));
                continue;
            }
            String key = text(section, "key");
            if (key == null || !KEY.matcher(key).matches()) {
                violations.add(new FieldViolation(p + ".key", "format", "section key must match ^[a-z][a-z0-9_]{0,62}$"));
            } else if (!sectionKeys.add(key)) {
                violations.add(new FieldViolation(p + ".key", "duplicate", "duplicate section key"));
            }
            String label = text(section, "label");
            if (label == null || label.isBlank() || label.length() > 200) {
                violations.add(new FieldViolation(p + ".label", "size", "section label must be 1-200 characters"));
            }
            String tab = text(section, "tab");
            if (tab != null && (tab.isBlank() || tab.length() > 100)) {
                violations.add(new FieldViolation(p + ".tab", "size", "tab must be 1-100 characters"));
            }
            JsonNode columns = section.get("columns");
            int columnCount = columns == null || columns.isNull() ? 1 : columns.isInt() ? columns.intValue() : -1;
            if (columnCount < 1 || columnCount > 4) {
                violations.add(new FieldViolation(p + ".columns", "range", "columns must be between 1 and 4"));
            }
            ArrayNode canonicalFields = JsonNodeFactory.instance.arrayNode();
            if (!(section.get("fields") instanceof ArrayNode sectionFields)) {
                violations.add(new FieldViolation(p + ".fields", "format", "fields must be an array of field keys"));
            } else {
                for (JsonNode fieldKey : sectionFields.values()) {
                    String fk = fieldKey.isString() ? fieldKey.stringValue() : null;
                    if (fk == null || !known.containsKey(fk) || known.get(fk) == FieldStatus.ARCHIVED) {
                        violations.add(new FieldViolation(p + ".fields", "unknown_field",
                                "'" + fieldKey.asString() + "' is not an active field of this module"));
                    } else if (!placedFields.add(fk)) {
                        violations.add(new FieldViolation(p + ".fields", "duplicate",
                                "field '" + fk + "' is placed more than once"));
                    } else {
                        canonicalFields.add(fk);
                    }
                }
            }
            ObjectNode canonical = JsonNodeFactory.instance.objectNode();
            canonical.put("key", key);
            canonical.put("label", label);
            if (tab != null) {
                canonical.put("tab", tab);
            }
            canonical.put("columns", columnCount);
            canonical.set("fields", canonicalFields);
            canonicalSections.add(canonical);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("sections", canonicalSections);
        return result;
    }

    private static boolean onlyProperties(ObjectNode node, Set<String> allowed) {
        return allowed.containsAll(node.propertyNames());
    }

    private static String text(ObjectNode node, String property) {
        JsonNode value = node.get(property);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
