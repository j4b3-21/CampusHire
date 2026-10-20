package com.campushire.placement.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Eligibility rules of a drive, stored in the drive record's {@code eligibility} JSON field. Absent rules do not
 * restrict. Empty department, batch or program sets mean "all".
 */
record EligibilityCriteria(BigDecimal minCgpa, Integer maxActiveBacklogs, Set<UUID> departments, Set<UUID> batches,
        Set<String> programs, BigDecimal minTenth, BigDecimal minTwelfth, boolean excludePlaced) {

    static final EligibilityCriteria OPEN = new EligibilityCriteria(null, null, Set.of(), Set.of(), Set.of(), null,
            null, false);

    private static final Set<String> PROPERTIES = Set.of("minCgpa", "maxActiveBacklogs", "departments", "batches",
            "programs", "minTenth", "minTwelfth", "excludePlaced");

    EligibilityCriteria {
        departments = Set.copyOf(departments);
        batches = Set.copyOf(batches);
        programs = Set.copyOf(programs);
    }

    static EligibilityCriteria parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return OPEN;
        }
        List<FieldViolation> violations = new ArrayList<>();
        if (!node.isObject()) {
            throw ApiException.validation("eligibility", "type", "eligibility must be a JSON object");
        }
        for (String name : node.propertyNames()) {
            if (!PROPERTIES.contains(name)) {
                violations.add(new FieldViolation("eligibility." + name, "unknown_property",
                        "unknown eligibility rule '" + name + "'"));
            }
        }
        BigDecimal minCgpa = decimal(node, "minCgpa", BigDecimal.ZERO, BigDecimal.TEN, violations);
        BigDecimal minTenth = decimal(node, "minTenth", BigDecimal.ZERO, BigDecimal.valueOf(100), violations);
        BigDecimal minTwelfth = decimal(node, "minTwelfth", BigDecimal.ZERO, BigDecimal.valueOf(100), violations);
        Integer maxBacklogs = null;
        JsonNode backlogs = node.get("maxActiveBacklogs");
        if (backlogs != null && !backlogs.isNull()) {
            if (!backlogs.isIntegralNumber() || backlogs.intValue() < 0) {
                violations.add(new FieldViolation("eligibility.maxActiveBacklogs", "range",
                        "maxActiveBacklogs must be a non-negative integer"));
            } else {
                maxBacklogs = backlogs.intValue();
            }
        }
        Set<UUID> departments = ids(node, "departments", violations);
        Set<UUID> batches = ids(node, "batches", violations);
        Set<String> programs = strings(node, "programs", violations);
        JsonNode excludePlaced = node.get("excludePlaced");
        if (excludePlaced != null && !excludePlaced.isNull() && !excludePlaced.isBoolean()) {
            violations.add(new FieldViolation("eligibility.excludePlaced", "type", "excludePlaced must be a boolean"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        return new EligibilityCriteria(minCgpa, maxBacklogs, departments, batches, programs, minTenth, minTwelfth,
                excludePlaced != null && excludePlaced.booleanValue());
    }

    ObjectNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (minCgpa != null) {
            node.put("minCgpa", minCgpa);
        }
        if (maxActiveBacklogs != null) {
            node.put("maxActiveBacklogs", maxActiveBacklogs);
        }
        departments.stream().map(UUID::toString).sorted().forEach(node.putArray("departments")::add);
        batches.stream().map(UUID::toString).sorted().forEach(node.putArray("batches")::add);
        programs.stream().sorted().forEach(node.putArray("programs")::add);
        if (minTenth != null) {
            node.put("minTenth", minTenth);
        }
        if (minTwelfth != null) {
            node.put("minTwelfth", minTwelfth);
        }
        node.put("excludePlaced", excludePlaced);
        return node;
    }

    private static BigDecimal decimal(JsonNode node, String name, BigDecimal min, BigDecimal max,
            List<FieldViolation> violations) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        BigDecimal number = null;
        if (value.isNumber()) {
            number = value.decimalValue();
        } else if (value.isString()) {
            try {
                number = new BigDecimal(value.stringValue().trim());
            } catch (NumberFormatException e) {
                number = null;
            }
        }
        if (number == null || number.compareTo(min) < 0 || number.compareTo(max) > 0) {
            violations.add(new FieldViolation("eligibility." + name, "range",
                    name + " must be a number between " + min.toPlainString() + " and " + max.toPlainString()));
            return null;
        }
        return number;
    }

    private static Set<UUID> ids(JsonNode node, String name, List<FieldViolation> violations) {
        Set<UUID> result = new LinkedHashSet<>();
        for (String value : strings(node, name, violations)) {
            try {
                result.add(UUID.fromString(value));
            } catch (IllegalArgumentException e) {
                violations.add(new FieldViolation("eligibility." + name, "format", "'" + value + "' is not a record id"));
            }
        }
        return result;
    }

    private static Set<String> strings(JsonNode node, String name, List<FieldViolation> violations) {
        JsonNode value = node.get(name);
        Set<String> result = new LinkedHashSet<>();
        if (value == null || value.isNull()) {
            return result;
        }
        if (!value.isArray() || value.size() > 200) {
            violations.add(new FieldViolation("eligibility." + name, "type", name + " must be an array (max 200)"));
            return result;
        }
        for (JsonNode item : value.values()) {
            if (!item.isString() || item.stringValue().isBlank()) {
                violations.add(new FieldViolation("eligibility." + name, "type", name + " must contain strings"));
            } else {
                result.add(item.stringValue().trim());
            }
        }
        return result;
    }
}
