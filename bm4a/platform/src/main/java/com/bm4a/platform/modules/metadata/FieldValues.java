package com.bm4a.platform.modules.metadata;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.bm4a.platform.modules.metadata.FieldConfig.SelectOption;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates one field value and converts it to its canonical JSON encoding (ADR-001). Pure and side-effect free: no
 * database access (lookup target existence is checked by the record engine) and no evaluation of tenant-supplied
 * expressions or patterns.
 */
public final class FieldValues {

    public static final int DEFAULT_TEXT_MAX = 255;
    public static final int DEFAULT_LONG_TEXT_MAX = 10_000;
    public static final int DEFAULT_DECIMAL_SCALE = 6;
    public static final int DEFAULT_JSON_MAX_BYTES = 16 * 1024;
    public static final int MAX_DECIMAL_PRECISION = 38;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ().-]{4,32}$");
    private static final Pattern FILE_ID = Pattern.compile("^[A-Za-z0-9._:/-]{1,200}$");
    private static final Pattern CONTENT_TYPE = Pattern.compile("^[a-z0-9.+-]+/[a-z0-9.+-]+$");
    private static final Set<String> FILE_PROPERTIES = Set.of("fileId", "name", "contentType");
    private static final Set<String> CURRENCY_PROPERTIES = Set.of("amount", "currency");

    private FieldValues() {
    }

    /**
     * Outcome of validating a value: either a canonical value (possibly {@link #empty()} meaning "no value"), or a
     * violation code and message.
     */
    public record Outcome(JsonNode value, String code, String message) {

        public boolean valid() {
            return code == null;
        }

        public boolean hasValue() {
            return valid() && value != null;
        }

        static Outcome ok(JsonNode value) {
            return new Outcome(value, null, null);
        }

        static Outcome empty() {
            return new Outcome(null, null, null);
        }

        static Outcome invalid(String code, String message) {
            return new Outcome(null, code, message);
        }
    }

    /**
     * @param value the submitted value (never Java {@code null}; JSON null means "no value")
     * @param previous the currently stored canonical value, or {@code null}; deprecated select options remain valid if
     *            unchanged
     */
    public static Outcome canonicalize(FieldSchema field, JsonNode value, JsonNode previous) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return Outcome.empty();
        }
        FieldConfig config = field.config() == null ? FieldConfig.empty() : field.config();
        return switch (field.type()) {
            case TEXT -> text(value, config, DEFAULT_TEXT_MAX);
            case LONG_TEXT -> text(value, config, DEFAULT_LONG_TEXT_MAX);
            case EMAIL -> email(value);
            case PHONE -> phone(value);
            case URL -> url(value);
            case INTEGER -> integer(value, config);
            case DECIMAL -> decimal(value, config);
            case CURRENCY -> currency(value, config);
            case BOOLEAN -> value.isBoolean() ? Outcome.ok(value) : Outcome.invalid("type", "must be true or false");
            case DATE -> date(value);
            case DATETIME -> dateTime(value);
            case SINGLE_SELECT -> singleSelect(value, config, previous);
            case MULTI_SELECT -> multiSelect(value, config, previous);
            case JSON -> json(value, config);
            case FILE_REFERENCE -> file(value, false);
            case IMAGE_REFERENCE -> file(value, true);
            case LOOKUP -> lookup(value);
        };
    }

    private static Outcome text(JsonNode value, FieldConfig config, int defaultMax) {
        if (!value.isString()) {
            return Outcome.invalid("type", "must be a string");
        }
        String text = value.stringValue().strip();
        if (text.isEmpty()) {
            return Outcome.empty();
        }
        int max = config.maxLength() == null ? defaultMax : config.maxLength();
        int min = config.minLength() == null ? 0 : config.minLength();
        int length = text.codePointCount(0, text.length());
        if (length > max) {
            return Outcome.invalid("max_length", "must be at most " + max + " characters");
        }
        if (length < min) {
            return Outcome.invalid("min_length", "must be at least " + min + " characters");
        }
        return Outcome.ok(NODES.stringNode(text));
    }

    private static Outcome email(JsonNode value) {
        if (!value.isString()) {
            return Outcome.invalid("type", "must be a string");
        }
        String email = value.stringValue().strip();
        if (email.isEmpty()) {
            return Outcome.empty();
        }
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            return Outcome.invalid("format", "must be a valid e-mail address");
        }
        return Outcome.ok(NODES.stringNode(email));
    }

    private static Outcome phone(JsonNode value) {
        if (!value.isString()) {
            return Outcome.invalid("type", "must be a string");
        }
        String phone = value.stringValue().strip();
        if (phone.isEmpty()) {
            return Outcome.empty();
        }
        long digits = phone.chars().filter(Character::isDigit).count();
        if (!PHONE.matcher(phone).matches() || digits < 4) {
            return Outcome.invalid("format", "must be a phone number (digits, spaces, + - ( ) .)");
        }
        return Outcome.ok(NODES.stringNode(phone));
    }

    private static Outcome url(JsonNode value) {
        if (!value.isString()) {
            return Outcome.invalid("type", "must be a string");
        }
        String url = value.stringValue().strip();
        if (url.isEmpty()) {
            return Outcome.empty();
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (url.length() > 2048 || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                return Outcome.invalid("format", "must be an absolute http(s) URL");
            }
            return Outcome.ok(NODES.stringNode(url));
        } catch (URISyntaxException ex) {
            return Outcome.invalid("format", "must be an absolute http(s) URL");
        }
    }

    private static Outcome integer(JsonNode value, FieldConfig config) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            return Outcome.invalid("type", "must be a 64-bit integer");
        }
        long number = value.longValue();
        Outcome bounds = bounds(BigDecimal.valueOf(number), config);
        if (!bounds.valid()) {
            return bounds;
        }
        // Same node type a JSON parser yields for this number, so canonical values compare equal to stored ones.
        return Outcome.ok(number == (int) number ? NODES.numberNode((int) number) : NODES.numberNode(number));
    }

    private static Outcome decimal(JsonNode value, FieldConfig config) {
        BigDecimal number = parseDecimal(value);
        if (number == null) {
            return Outcome.invalid("type", "must be a decimal number or numeric string");
        }
        int scale = config.scale() == null ? DEFAULT_DECIMAL_SCALE : config.scale();
        number = stripTrailingZeros(number);
        if (number.scale() > scale) {
            return Outcome.invalid("scale", "must have at most " + scale + " fraction digits");
        }
        if (number.precision() > MAX_DECIMAL_PRECISION) {
            return Outcome.invalid("precision", "must have at most " + MAX_DECIMAL_PRECISION + " digits");
        }
        Outcome bounds = bounds(number, config);
        return bounds.valid() ? Outcome.ok(NODES.stringNode(number.toPlainString())) : bounds;
    }

    private static Outcome currency(JsonNode value, FieldConfig config) {
        if (!(value instanceof ObjectNode object)) {
            return Outcome.invalid("type", "must be an object {\"amount\": \"12.50\", \"currency\": \"INR\"}");
        }
        for (String name : object.propertyNames()) {
            if (!CURRENCY_PROPERTIES.contains(name)) {
                return Outcome.invalid("unknown_property", "unknown property '" + name + "'");
            }
        }
        JsonNode code = object.get("currency");
        if (code == null || !code.isString()) {
            return Outcome.invalid("currency", "currency must be an ISO 4217 code");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(code.stringValue().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Outcome.invalid("currency", "currency must be an ISO 4217 code");
        }
        if (config.currencies() != null && !config.currencies().isEmpty()
                && !config.currencies().contains(currency.getCurrencyCode())) {
            return Outcome.invalid("currency", "currency must be one of " + config.currencies());
        }
        BigDecimal amount = parseDecimal(object.get("amount"));
        if (amount == null) {
            return Outcome.invalid("amount", "amount must be a decimal number or numeric string");
        }
        int digits = Math.max(currency.getDefaultFractionDigits(), 0);
        amount = stripTrailingZeros(amount);
        if (amount.scale() > digits) {
            return Outcome.invalid("scale", "amount must have at most " + digits + " fraction digits for "
                    + currency.getCurrencyCode());
        }
        if (amount.precision() > MAX_DECIMAL_PRECISION) {
            return Outcome.invalid("precision", "amount has too many digits");
        }
        Outcome bounds = bounds(amount, config);
        if (!bounds.valid()) {
            return bounds;
        }
        ObjectNode canonical = NODES.objectNode();
        canonical.put("amount", amount.setScale(digits).toPlainString());
        canonical.put("currency", currency.getCurrencyCode());
        return Outcome.ok(canonical);
    }

    private static Outcome date(JsonNode value) {
        if (!value.isString()) {
            return Outcome.invalid("type", "must be an ISO-8601 date string (YYYY-MM-DD)");
        }
        try {
            return Outcome.ok(NODES.stringNode(LocalDate.parse(value.stringValue().strip()).toString()));
        } catch (DateTimeException ex) {
            return Outcome.invalid("format", "must be an ISO-8601 date (YYYY-MM-DD)");
        }
    }

    private static Outcome dateTime(JsonNode value) {
        if (!value.isString()) {
            return Outcome.invalid("type", "must be an ISO-8601 date-time string with offset");
        }
        try {
            Instant instant = OffsetDateTime.parse(value.stringValue().strip()).toInstant();
            return Outcome.ok(NODES.stringNode(instant.toString()));
        } catch (DateTimeException ex) {
            return Outcome.invalid("format", "must be an ISO-8601 date-time with offset, e.g. 2026-10-20T10:15:00Z");
        }
    }

    private static Outcome singleSelect(JsonNode value, FieldConfig config, JsonNode previous) {
        if (!value.isString()) {
            return Outcome.invalid("type", "must be a string option value");
        }
        String selected = value.stringValue();
        String previousValue = previous != null && previous.isString() ? previous.stringValue() : null;
        String problem = optionProblem(config, selected, selected.equals(previousValue));
        return problem == null ? Outcome.ok(NODES.stringNode(selected)) : Outcome.invalid("option", problem);
    }

    private static Outcome multiSelect(JsonNode value, FieldConfig config, JsonNode previous) {
        if (!value.isArray()) {
            return Outcome.invalid("type", "must be an array of option values");
        }
        Set<String> previousValues = new LinkedHashSet<>();
        if (previous != null && previous.isArray()) {
            previous.values().forEach(node -> previousValues.add(node.asString()));
        }
        Set<String> selected = new LinkedHashSet<>();
        for (JsonNode item : value.values()) {
            if (!item.isString()) {
                return Outcome.invalid("type", "must be an array of option values");
            }
            if (!selected.add(item.stringValue())) {
                return Outcome.invalid("duplicate", "must not contain duplicates");
            }
            String problem = optionProblem(config, item.stringValue(), previousValues.contains(item.stringValue()));
            if (problem != null) {
                return Outcome.invalid("option", problem);
            }
        }
        if (selected.isEmpty()) {
            return Outcome.empty();
        }
        if (config.maxSelections() != null && selected.size() > config.maxSelections()) {
            return Outcome.invalid("max_selections", "must have at most " + config.maxSelections() + " values");
        }
        ArrayNode canonical = NODES.arrayNode();
        for (SelectOption option : config.optionsOrEmpty()) {
            if (selected.contains(option.value())) {
                canonical.add(option.value());
            }
        }
        return Outcome.ok(canonical);
    }

    private static String optionProblem(FieldConfig config, String value, boolean unchanged) {
        for (SelectOption option : config.optionsOrEmpty()) {
            if (option.value().equals(value)) {
                return option.isDeprecated() && !unchanged ? "option '" + value + "' is deprecated" : null;
            }
        }
        return "'" + value + "' is not an allowed option";
    }

    private static Outcome json(JsonNode value, FieldConfig config) {
        int max = config.maxBytes() == null ? DEFAULT_JSON_MAX_BYTES : config.maxBytes();
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > max) {
            return Outcome.invalid("max_bytes", "must not exceed " + max + " bytes");
        }
        return Outcome.ok(value.deepCopy());
    }

    private static Outcome file(JsonNode value, boolean image) {
        if (!(value instanceof ObjectNode object)) {
            return Outcome.invalid("type", "must be an object {\"fileId\": \"...\"}");
        }
        for (String name : object.propertyNames()) {
            if (!FILE_PROPERTIES.contains(name)) {
                return Outcome.invalid("unknown_property", "unknown property '" + name + "'");
            }
        }
        JsonNode fileId = object.get("fileId");
        if (fileId == null || !fileId.isString() || !FILE_ID.matcher(fileId.stringValue()).matches()) {
            return Outcome.invalid("file_id", "fileId must be 1-200 characters of [A-Za-z0-9._:/-]");
        }
        ObjectNode canonical = NODES.objectNode();
        canonical.put("fileId", fileId.stringValue());
        JsonNode name = object.get("name");
        if (name != null && !name.isNull()) {
            if (!name.isString() || name.stringValue().length() > 255) {
                return Outcome.invalid("name", "name must be a string of at most 255 characters");
            }
            canonical.put("name", name.stringValue());
        }
        JsonNode contentType = object.get("contentType");
        if (contentType != null && !contentType.isNull()) {
            String type = contentType.isString() ? contentType.stringValue().toLowerCase(Locale.ROOT) : "";
            if (type.length() > 100 || !CONTENT_TYPE.matcher(type).matches()) {
                return Outcome.invalid("content_type", "contentType must be a media type such as image/png");
            }
            if (image && !type.startsWith("image/")) {
                return Outcome.invalid("content_type", "contentType must be an image/* media type");
            }
            canonical.put("contentType", type);
        }
        return Outcome.ok(canonical);
    }

    private static Outcome lookup(JsonNode value) {
        if (!value.isString()) {
            return Outcome.invalid("type", "must be a record ID (UUID string)");
        }
        try {
            return Outcome.ok(NODES.stringNode(UUID.fromString(value.stringValue().strip()).toString()));
        } catch (IllegalArgumentException ex) {
            return Outcome.invalid("format", "must be a record ID (UUID string)");
        }
    }

    private static BigDecimal parseDecimal(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isString()) {
            String text = value.stringValue().strip();
            if (text.length() > 64) {
                return null;
            }
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal stripTrailingZeros(BigDecimal number) {
        BigDecimal stripped = number.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static Outcome bounds(BigDecimal number, FieldConfig config) {
        if (config.min() != null && number.compareTo(config.min()) < 0) {
            return Outcome.invalid("min", "must be >= " + config.min().toPlainString());
        }
        if (config.max() != null && number.compareTo(config.max()) > 0) {
            return Outcome.invalid("max", "must be <= " + config.max().toPlainString());
        }
        return Outcome.ok(null);
    }

    /** Lower-cased/trimmed form used for uniqueness comparisons. */
    public static String normalizedForUniqueness(FieldType type, JsonNode canonical) {
        return switch (type) {
            case EMAIL, URL, TEXT, PHONE -> canonical.asString().toLowerCase(Locale.ROOT);
            default -> canonical.asString();
        };
    }

    /** Field keys sorted by position then key; useful for deterministic error ordering. */
    public static List<FieldSchema> ordered(List<FieldSchema> fields) {
        List<FieldSchema> copy = new ArrayList<>(fields);
        copy.sort((a, b) -> a.position() != b.position() ? Integer.compare(a.position(), b.position())
                : a.apiKey().compareTo(b.apiKey()));
        return copy;
    }
}
