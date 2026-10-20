package com.bm4a.platform.modules.access;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Which records of a module an operation may touch (ADR-004). The record engine turns it into bound SQL predicates;
 * records outside the scope behave exactly like missing ones (404).
 * <ul>
 * <li>{@code ALL} - every record</li>
 * <li>{@code OWN} - records whose owner is {@code ownerUserId}</li>
 * <li>{@code ATTRIBUTE} - records whose {@code field} equals any of {@code values}; {@code field = "$id"} compares
 * the record ID. No values means no records.</li>
 * <li>{@code NONE} - no records</li>
 * </ul>
 * {@code where} adds constant filters, ANDed together: the field must equal one of the listed canonical values (for
 * multi-select fields: contain).
 */
public record RecordScope(Kind kind, UUID ownerUserId, String field, List<String> values,
        Map<String, List<JsonNode>> where) {

    /** {@code field} value of an {@code ATTRIBUTE} scope that compares the record ID. */
    public static final String ID_FIELD = "$id";

    public enum Kind {
        ALL, OWN, ATTRIBUTE, NONE
    }

    public RecordScope {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.OWN) {
            Objects.requireNonNull(ownerUserId, "ownerUserId");
        }
        if (kind == Kind.ATTRIBUTE) {
            Objects.requireNonNull(field, "field");
        }
        values = values == null ? List.of() : List.copyOf(values);
        Map<String, List<JsonNode>> copy = new LinkedHashMap<>();
        if (where != null) {
            where.forEach((key, list) -> copy.put(key, List.copyOf(list)));
        }
        where = Collections.unmodifiableMap(copy);
    }

    public static RecordScope all() {
        return new RecordScope(Kind.ALL, null, null, List.of(), Map.of());
    }

    public static RecordScope none() {
        return new RecordScope(Kind.NONE, null, null, List.of(), Map.of());
    }

    public static RecordScope own(UUID userId) {
        return new RecordScope(Kind.OWN, userId, null, List.of(), Map.of());
    }

    public static RecordScope attribute(String field, Collection<String> values) {
        return new RecordScope(Kind.ATTRIBUTE, null, field, List.copyOf(values), Map.of());
    }

    public RecordScope withWhere(Map<String, List<JsonNode>> where) {
        return new RecordScope(kind, ownerUserId, field, values, where);
    }

    /** Every record, no additional filters. */
    public boolean unrestricted() {
        return kind == Kind.ALL && where.isEmpty();
    }
}
