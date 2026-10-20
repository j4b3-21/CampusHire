package com.bm4a.platform.modules.workflow.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.metadata.FieldConfig.SelectOption;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.workflow.WorkflowDefinition.Category;
import com.bm4a.platform.modules.workflow.WorkflowDefinition.State;
import com.bm4a.platform.modules.workflow.WorkflowDefinition.Transition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates a workflow definition against the module's metadata and returns its canonical form.
 * <ul>
 * <li>the field exists, is an ACTIVE, writable {@code SINGLE_SELECT} field, and its default value is the initial
 * state (new records get the default, and write guards cannot change payloads)</li>
 * <li>every state is an active (not deprecated) option of the field; state and transition keys are unique</li>
 * <li>transitions leave from and lead to known states (never to one of their own {@code from} states); roles are org
 * role keys or {@code "*"} alone</li>
 * </ul>
 * Unknown properties are rejected at every level.
 */
@Component
class WorkflowDefinitionValidator {

    static final int MAX_STATES = 50;
    static final int MAX_TRANSITIONS = 200;
    static final int MAX_ROLES = 50;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final Pattern COLOR = Pattern.compile("^[A-Za-z0-9#-]{1,32}$");
    private static final Set<String> ROOT_PROPERTIES = Set.of("initial", "states", "transitions");
    private static final Set<String> STATE_PROPERTIES = Set.of("key", "label", "category", "color");
    private static final Set<String> TRANSITION_PROPERTIES = Set.of("key", "label", "from", "to", "roles",
            "requireComment");
    private static final String ROOT = "definition";

    /** The canonical document as stored in {@code workflow_definition.definition}. */
    record Document(String initial, List<State> states, List<Transition> transitions) {
    }

    Document validate(ModuleSchema module, String fieldKey, JsonNode definition) {
        List<FieldViolation> violations = new ArrayList<>();
        FieldSchema field = field(module, fieldKey, violations);
        if (!(definition instanceof ObjectNode root)) {
            violations.add(v(ROOT, "type", "definition must be a JSON object"));
            throw ApiException.validation(violations);
        }
        unknownProperties(root, ROOT_PROPERTIES, ROOT, violations);
        Set<String> options = field == null ? null
                : field.config().optionsOrEmpty().stream().filter(option -> !option.isDeprecated())
                        .map(SelectOption::value).collect(Collectors.toSet());
        List<State> states = states(root.get("states"), options, violations);
        Set<String> stateKeys = states.stream().map(State::key).collect(Collectors.toSet());
        String initial = text(root.get("initial"));
        if (initial == null) {
            violations.add(v(ROOT + ".initial", "required", "initial is required"));
        } else if (!stateKeys.contains(initial)) {
            violations.add(v(ROOT + ".initial", "unknown_state", "initial must be one of the states"));
        } else if (field != null && !Objects.equals(text(field.defaultValue()), initial)) {
            violations.add(v("field", "default_mismatch", "the default value of field '" + field.apiKey()
                    + "' must be the initial state '" + initial + "' so that new records start there"));
        }
        List<Transition> transitions = transitions(root.get("transitions"), stateKeys, violations);
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        return new Document(initial, states, transitions);
    }

    private static FieldSchema field(ModuleSchema module, String fieldKey, List<FieldViolation> violations) {
        if (fieldKey == null || fieldKey.isBlank()) {
            violations.add(v("field", "required", "field is required"));
            return null;
        }
        FieldSchema field = module.field(fieldKey).filter(FieldSchema::visible).orElse(null);
        if (field == null) {
            violations.add(v("field", "unknown_field", "module '" + module.apiKey() + "' has no field '" + fieldKey
                    + "'"));
            return null;
        }
        if (field.type() != FieldType.SINGLE_SELECT) {
            violations.add(v("field", "unsupported_type", "workflow fields must be SINGLE_SELECT fields"));
            return null;
        }
        if (field.status() != FieldStatus.ACTIVE || field.readOnly()) {
            violations.add(v("field", "not_writable", "workflow fields must be active and not read-only"));
            return null;
        }
        return field;
    }

    private static List<State> states(JsonNode input, Set<String> options, List<FieldViolation> violations) {
        String path = ROOT + ".states";
        if (!(input instanceof ArrayNode array) || array.isEmpty() || array.size() > MAX_STATES) {
            violations.add(v(path, "size", "states must be an array of 1-" + MAX_STATES + " states"));
            return List.of();
        }
        List<State> states = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String p = path + "[" + i + "]";
            if (!(array.get(i) instanceof ObjectNode state)) {
                violations.add(v(p, "type", "state must be a JSON object"));
                continue;
            }
            unknownProperties(state, STATE_PROPERTIES, p, violations);
            String key = text(state.get("key"));
            if (key == null) {
                violations.add(v(p + ".key", "required", "key is required"));
            } else if (options != null && !options.contains(key)) {
                violations.add(v(p + ".key", "unknown_option", "'" + key
                        + "' is not an active option of the workflow field"));
            } else if (!keys.add(key)) {
                violations.add(v(p + ".key", "duplicate", "duplicate state key"));
            }
            String label = label(state.get("label"), p + ".label", violations);
            Category category = category(state.get("category"), p + ".category", violations);
            JsonNode colorNode = state.get("color");
            String color = text(colorNode);
            if (colorNode != null && !colorNode.isNull() && (color == null || !COLOR.matcher(color).matches())) {
                violations.add(v(p + ".color", "format", "color must match ^[A-Za-z0-9#-]{1,32}$"));
            }
            states.add(new State(key, label, category, color));
        }
        return states;
    }

    private static List<Transition> transitions(JsonNode input, Set<String> states, List<FieldViolation> violations) {
        String path = ROOT + ".transitions";
        if (input == null || input.isNull()) {
            return List.of();
        }
        if (!(input instanceof ArrayNode array) || array.size() > MAX_TRANSITIONS) {
            violations.add(v(path, "size", "transitions must be an array of at most " + MAX_TRANSITIONS
                    + " transitions"));
            return List.of();
        }
        List<Transition> transitions = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String p = path + "[" + i + "]";
            if (!(array.get(i) instanceof ObjectNode transition)) {
                violations.add(v(p, "type", "transition must be a JSON object"));
                continue;
            }
            unknownProperties(transition, TRANSITION_PROPERTIES, p, violations);
            String key = text(transition.get("key"));
            if (key == null || !KEY.matcher(key).matches()) {
                violations.add(v(p + ".key", "format", "key must match ^[a-z][a-z0-9_]{0,62}$"));
            } else if (!keys.add(key)) {
                violations.add(v(p + ".key", "duplicate", "duplicate transition key"));
            }
            String label = label(transition.get("label"), p + ".label", violations);
            String to = text(transition.get("to"));
            if (to == null || !states.contains(to)) {
                violations.add(v(p + ".to", "unknown_state", "to must be one of the states"));
            }
            List<String> from = strings(transition.get("from"), p + ".from", MAX_STATES, violations);
            for (String state : from) {
                if (!states.contains(state)) {
                    violations.add(v(p + ".from", "unknown_state", "'" + state + "' is not one of the states"));
                } else if (state.equals(to)) {
                    violations.add(v(p + ".from", "self_transition", "a transition cannot lead to its own from state"));
                }
            }
            List<String> roles = strings(transition.get("roles"), p + ".roles", MAX_ROLES, violations);
            for (String role : roles) {
                if (!Transition.ANY_ROLE.equals(role) && !KEY.matcher(role).matches()) {
                    violations.add(v(p + ".roles", "format", "roles must be org role keys or \"*\""));
                }
            }
            if (roles.contains(Transition.ANY_ROLE) && roles.size() > 1) {
                violations.add(v(p + ".roles", "format", "\"*\" must be the only entry"));
            }
            JsonNode requireComment = transition.get("requireComment");
            if (requireComment != null && !requireComment.isNull() && !requireComment.isBoolean()) {
                violations.add(v(p + ".requireComment", "type", "requireComment must be true or false"));
            }
            transitions.add(new Transition(key, label, from, to, roles,
                    requireComment != null && requireComment.isBoolean() && requireComment.booleanValue()));
        }
        return transitions;
    }

    /** A non-empty array of distinct strings. */
    private static List<String> strings(JsonNode input, String path, int max, List<FieldViolation> violations) {
        if (!(input instanceof ArrayNode array) || array.isEmpty() || array.size() > max) {
            violations.add(v(path, "size", "must be an array of 1-" + max + " strings"));
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode element : array) {
            if (!element.isString()) {
                violations.add(v(path, "type", "must contain strings only"));
            } else if (!values.add(element.stringValue())) {
                violations.add(v(path, "duplicate", "duplicate entry '" + element.stringValue() + "'"));
            }
        }
        return List.copyOf(values);
    }

    private static String label(JsonNode input, String path, List<FieldViolation> violations) {
        String label = text(input);
        if (label == null || label.isBlank() || label.length() > 200) {
            violations.add(v(path, "size", "label must be 1-200 characters"));
            return label;
        }
        return label.strip();
    }

    private static Category category(JsonNode input, String path, List<FieldViolation> violations) {
        String value = text(input);
        for (Category category : Category.values()) {
            if (category.name().equals(value)) {
                return category;
            }
        }
        violations.add(v(path, "invalid", "category must be OPEN, IN_PROGRESS, WON or LOST"));
        return null;
    }

    private static void unknownProperties(ObjectNode node, Set<String> allowed, String path,
            List<FieldViolation> violations) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) {
                violations.add(v(path + "." + entry.getKey(), "unknown_property", "unknown property"));
            }
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private static FieldViolation v(String field, String code, String message) {
        return new FieldViolation(field, code, message);
    }
}
