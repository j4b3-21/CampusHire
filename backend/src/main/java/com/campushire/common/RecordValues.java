package com.campushire.common;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed reads of canonical record values (ADR-001): DECIMAL as numeric strings, CURRENCY as
 * {@code {"amount": "1200000.00", "currency": "INR"}}, LOOKUP as UUID strings, DATE as {@code YYYY-MM-DD}, DATETIME
 * as ISO-8601 instants, FILE_REFERENCE as {@code {"fileId": ..., "name": ...}}. Missing or malformed values are
 * {@code null}.
 */
public final class RecordValues {

    private RecordValues() {
    }

    public static String text(ObjectNode data, String key) {
        JsonNode value = data.get(key);
        return value != null && value.isString() && !value.stringValue().isBlank() ? value.stringValue() : null;
    }

    public static UUID uuid(ObjectNode data, String key) {
        String text = text(data, key);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text.strip());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static BigDecimal decimal(ObjectNode data, String key) {
        return decimal(data.get(key));
    }

    public static Integer integer(ObjectNode data, String key) {
        JsonNode value = data.get(key);
        return value != null && value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : null;
    }

    public static boolean bool(ObjectNode data, String key) {
        JsonNode value = data.get(key);
        return value != null && value.isBoolean() && value.booleanValue();
    }

    /** The amount of a CURRENCY value. */
    public static BigDecimal amount(ObjectNode data, String key) {
        JsonNode value = data.get(key);
        return value != null && value.isObject() ? decimal(value.get("amount")) : null;
    }

    public static Instant instant(ObjectNode data, String key) {
        String text = text(data, key);
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeException ex) {
            return null;
        }
    }

    public static LocalDate date(ObjectNode data, String key) {
        String text = text(data, key);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    /** The {@code fileId} of a FILE_REFERENCE or IMAGE_REFERENCE value. */
    public static String fileId(ObjectNode data, String key) {
        JsonNode value = data.get(key);
        JsonNode id = value != null && value.isObject() ? value.get("fileId") : null;
        return id != null && id.isString() ? id.stringValue() : null;
    }

    /** The optional {@code name} of a FILE_REFERENCE or IMAGE_REFERENCE value. */
    public static String fileName(ObjectNode data, String key) {
        JsonNode value = data.get(key);
        JsonNode name = value != null && value.isObject() ? value.get("name") : null;
        return name != null && name.isString() ? name.stringValue() : null;
    }

    /** Whether the record holds a non-empty value for {@code key}. */
    public static boolean present(ObjectNode data, String key) {
        JsonNode value = data.get(key);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isString()) {
            return !value.stringValue().isBlank();
        }
        return !(value.isContainer() && value.isEmpty());
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isString()) {
            try {
                return new BigDecimal(value.stringValue().strip());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
