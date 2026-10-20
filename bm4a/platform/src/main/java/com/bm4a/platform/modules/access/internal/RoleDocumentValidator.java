package com.bm4a.platform.modules.access.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.FieldValues;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates a role permission document against the tenant's metadata and returns its canonical form, in which every
 * module entry lists all keys ({@code read}, {@code create}, {@code update}, {@code delete}, {@code readOnlyFields},
 * {@code hiddenFields}), rules are {@code "NONE" | "ALL" | "OWN"} or objects, and {@code where} values are arrays of
 * canonical field values.
 * <ul>
 * <li>modules must be visible to the tenant; listed fields must exist and not be archived</li>
 * <li>{@code ATTRIBUTE} rules need {@code field} ({@code $id} or a filterable text-like, select or lookup field) and
 * {@code attribute} (a membership attribute name)</li>
 * <li>{@code where} fields must be filterable and support equality; values are validated like record values</li>
 * </ul>
 */
@Component
class RoleDocumentValidator {

    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_WHERE_VALUES = 50;
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final Set<String> MODULE_KEYS = Set.of("read", "create", "update", "delete", "readOnlyFields",
            "hiddenFields");
    private static final Set<String> RULE_KEYS = Set.of("scope", "field", "attribute", "where");
    private static final Set<FieldType> ATTRIBUTE_TYPES = EnumSet.of(FieldType.TEXT, FieldType.EMAIL, FieldType.PHONE,
            FieldType.URL, FieldType.SINGLE_SELECT, FieldType.MULTI_SELECT, FieldType.LOOKUP);
    private static final Set<FieldType> WHERE_TYPES = EnumSet.of(FieldType.TEXT, FieldType.LONG_TEXT, FieldType.EMAIL,
            FieldType.PHONE, FieldType.URL, FieldType.SINGLE_SELECT, FieldType.MULTI_SELECT, FieldType.LOOKUP,
            FieldType.INTEGER, FieldType.BOOLEAN, FieldType.DATE);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ModuleSchemas schemas;

    RoleDocumentValidator(ModuleSchemas schemas) {
        this.schemas = schemas;
    }

    ObjectNode validate(UUID organizationId, JsonNode document) {
        ObjectNode canonical = NODES.objectNode();
        ObjectNode modulesOut = canonical.putObject("modules");
        if (document == null || document.isNull()) {
            return canonical;
        }
        String root = "permissions";
        if (!(document instanceof ObjectNode object)) {
            throw ApiException.validation(root, "type", "permissions must be a JSON object");
        }
        List<FieldViolation> violations = new ArrayList<>();
        for (String key : object.propertyNames()) {
            if (!"modules".equals(key)) {
                violations.add(new FieldViolation(root + "." + key, "unknown_property", "unknown property"));
            }
        }
        JsonNode modules = object.get("modules");
        if (modules != null && !modules.isNull() && !(modules instanceof ObjectNode)) {
            violations.add(new FieldViolation(root + ".modules", "type", "modules must be a JSON object"));
        } else if (modules instanceof ObjectNode entries) {
            for (Map.Entry<String, JsonNode> entry : entries.properties()) {
                String path = root + ".modules." + entry.getKey();
                ModuleSchema module = schemas.find(organizationId, entry.getKey()).orElse(null);
                if (module == null) {
                    violations.add(new FieldViolation(path, "unknown_module", "no module with this apiKey"));
                    continue;
                }
                ObjectNode policy = modulePolicy(module, entry.getValue(), path, violations);
                if (policy != null) {
                    modulesOut.set(module.apiKey(), policy);
                }
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        if (canonical.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.validation(root, "size", "permissions must not exceed " + MAX_BYTES / 1024 + " KiB");
        }
        return canonical;
    }

    private ObjectNode modulePolicy(ModuleSchema module, JsonNode input, String path, List<FieldViolation> violations) {
        if (!(input instanceof ObjectNode object)) {
            violations.add(new FieldViolation(path, "type", "module permissions must be a JSON object"));
            return null;
        }
        for (String key : object.propertyNames()) {
            if (!MODULE_KEYS.contains(key)) {
                violations.add(new FieldViolation(path + "." + key, "unknown_property", "unknown property"));
            }
        }
        ObjectNode policy = NODES.objectNode();
        policy.set("read", rule(module, object.get("read"), path + ".read", violations));
        JsonNode create = object.get("create");
        if (create != null && !create.isNull() && !create.isBoolean()) {
            violations.add(new FieldViolation(path + ".create", "type", "create must be true or false"));
        }
        policy.put("create", create != null && create.isBoolean() && create.booleanValue());
        policy.set("update", rule(module, object.get("update"), path + ".update", violations));
        policy.set("delete", rule(module, object.get("delete"), path + ".delete", violations));
        policy.set("readOnlyFields", fieldList(module, object.get("readOnlyFields"), path + ".readOnlyFields",
                violations));
        policy.set("hiddenFields", fieldList(module, object.get("hiddenFields"), path + ".hiddenFields", violations));
        return policy;
    }

    private JsonNode rule(ModuleSchema module, JsonNode input, String path, List<FieldViolation> violations) {
        if (input == null || input.isNull()) {
            return NODES.stringNode(RecordScope.Kind.NONE.name());
        }
        if (input.isString()) {
            RecordScope.Kind kind = kind(input.stringValue());
            if (kind == null || kind == RecordScope.Kind.ATTRIBUTE) {
                violations.add(new FieldViolation(path, "invalid_rule",
                        "rule must be NONE, ALL, OWN or an object {scope, field, attribute, where}"));
                return NODES.stringNode(RecordScope.Kind.NONE.name());
            }
            return NODES.stringNode(kind.name());
        }
        if (!(input instanceof ObjectNode object)) {
            violations.add(new FieldViolation(path, "invalid_rule",
                    "rule must be NONE, ALL, OWN or an object {scope, field, attribute, where}"));
            return NODES.stringNode(RecordScope.Kind.NONE.name());
        }
        for (String key : object.propertyNames()) {
            if (!RULE_KEYS.contains(key)) {
                violations.add(new FieldViolation(path + "." + key, "unknown_property", "unknown property"));
            }
        }
        RecordScope.Kind kind = kind(object.path("scope").asString(""));
        if (kind == null) {
            violations.add(new FieldViolation(path + ".scope", "invalid_rule",
                    "scope must be NONE, ALL, OWN or ATTRIBUTE"));
            return NODES.stringNode(RecordScope.Kind.NONE.name());
        }
        ObjectNode rule = NODES.objectNode();
        rule.put("scope", kind.name());
        JsonNode field = object.get("field");
        JsonNode attribute = object.get("attribute");
        if (kind == RecordScope.Kind.ATTRIBUTE) {
            attributeField(module, field, path + ".field", violations);
            if (attribute == null || !attribute.isString() || !NAME.matcher(attribute.stringValue()).matches()) {
                violations.add(new FieldViolation(path + ".attribute", "invalid_rule",
                        "attribute must be a membership attribute name matching ^[a-z][a-z0-9_]{0,62}$"));
            }
            if (field != null && field.isString()) {
                rule.put("field", field.stringValue());
            }
            if (attribute != null && attribute.isString()) {
                rule.put("attribute", attribute.stringValue());
            }
        } else if (field != null || attribute != null) {
            violations.add(new FieldViolation(path, "invalid_rule", "field and attribute require scope ATTRIBUTE"));
        }
        JsonNode where = object.get("where");
        if (where != null && !where.isNull()) {
            if (kind == RecordScope.Kind.NONE) {
                violations.add(new FieldViolation(path + ".where", "invalid_rule", "where is not allowed with NONE"));
            } else {
                ObjectNode filters = where(module, where, path + ".where", violations);
                if (filters != null && !filters.isEmpty()) {
                    rule.set("where", filters);
                }
            }
        }
        return rule.size() == 1 ? NODES.stringNode(kind.name()) : rule;
    }

    private static void attributeField(ModuleSchema module, JsonNode field, String path,
            List<FieldViolation> violations) {
        if (field == null || !field.isString()) {
            violations.add(new FieldViolation(path, "invalid_rule", "field is required for scope ATTRIBUTE"));
            return;
        }
        if (RecordScope.ID_FIELD.equals(field.stringValue())) {
            return;
        }
        FieldSchema schema = module.field(field.stringValue()).filter(FieldSchema::visible).orElse(null);
        if (schema == null) {
            violations.add(new FieldViolation(path, "unknown_field", "no field with this apiKey"));
        } else if (!schema.filterable()) {
            violations.add(new FieldViolation(path, "not_filterable", "ATTRIBUTE fields must be filterable"));
        } else if (!ATTRIBUTE_TYPES.contains(schema.type())) {
            violations.add(new FieldViolation(path, "unsupported_type",
                    "ATTRIBUTE fields must be text, e-mail, phone, URL, select or lookup fields"));
        }
    }

    private static ObjectNode where(ModuleSchema module, JsonNode where, String path, List<FieldViolation> violations) {
        if (!(where instanceof ObjectNode object)) {
            violations.add(new FieldViolation(path, "type", "where must be a JSON object"));
            return null;
        }
        ObjectNode result = NODES.objectNode();
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            String fieldPath = path + "." + entry.getKey();
            FieldSchema field = module.field(entry.getKey()).filter(FieldSchema::visible).orElse(null);
            if (field == null) {
                violations.add(new FieldViolation(fieldPath, "unknown_field", "no field with this apiKey"));
                continue;
            }
            if (!field.filterable() || !WHERE_TYPES.contains(field.type())) {
                violations.add(new FieldViolation(fieldPath, "not_filterable",
                        "where fields must be filterable and support equality"));
                continue;
            }
            List<JsonNode> inputs = new ArrayList<>();
            if (entry.getValue().isArray()) {
                entry.getValue().forEach(inputs::add);
            } else {
                inputs.add(entry.getValue());
            }
            if (inputs.isEmpty() || inputs.size() > MAX_WHERE_VALUES) {
                violations.add(new FieldViolation(fieldPath, "size", "1-" + MAX_WHERE_VALUES + " values"));
                continue;
            }
            Set<JsonNode> canonical = new LinkedHashSet<>();
            for (JsonNode value : inputs) {
                JsonNode candidate = field.type() == FieldType.MULTI_SELECT ? NODES.arrayNode().add(value) : value;
                FieldValues.Outcome outcome = FieldValues.canonicalize(field, candidate, candidate);
                if (!outcome.hasValue()) {
                    violations.add(new FieldViolation(fieldPath, outcome.code() == null ? "invalid" : outcome.code(),
                            outcome.message() == null ? "invalid value" : outcome.message()));
                } else {
                    canonical.add(outcome.value());
                }
            }
            ArrayNode values = result.putArray(entry.getKey());
            canonical.forEach(values::add);
        }
        return result;
    }

    private static ArrayNode fieldList(ModuleSchema module, JsonNode input, String path,
            List<FieldViolation> violations) {
        ArrayNode result = NODES.arrayNode();
        if (input == null || input.isNull()) {
            return result;
        }
        if (!input.isArray()) {
            violations.add(new FieldViolation(path, "type", "must be an array of field apiKeys"));
            return result;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode element : input) {
            if (!element.isString() || module.field(element.stringValue()).filter(FieldSchema::visible).isEmpty()) {
                violations.add(new FieldViolation(path, "unknown_field",
                        "unknown field " + (element.isString() ? element.stringValue() : element.toString())));
            } else {
                keys.add(element.stringValue());
            }
        }
        keys.stream().sorted().forEach(result::add);
        return result;
    }

    private static RecordScope.Kind kind(String value) {
        for (RecordScope.Kind kind : RecordScope.Kind.values()) {
            if (kind.name().equals(value)) {
                return kind;
            }
        }
        return null;
    }
}
