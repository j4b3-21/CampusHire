package com.bm4a.platform.modules.dataexchange.internal;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.DateUtil;

import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldConfig.SelectOption;
import com.bm4a.platform.modules.metadata.FieldSchema;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Translates spreadsheet cell text into the JSON input of a field. The result is record input, not yet canonical:
 * the record engine still validates and canonicalizes it, so this class only removes spreadsheet-specific notation.
 * <ul>
 * <li>numbers: grouping separators ({@code , _ '} and spaces) are removed</li>
 * <li>currency: an amount with an optional ISO code or symbol ({@code INR 1,200}, {@code 1200 USD}, {@code ₹500});
 * without one the field's first allowed currency, else INR</li>
 * <li>booleans: yes/no, true/false, y/n, 1/0</li>
 * <li>dates: ISO {@code 2026-10-20}, day-first {@code 20/10/2026} ({@code -} and {@code .} too), {@code 20-Oct-2026},
 * {@code 20261020} and Excel serial numbers; date-times additionally with a time, an offset, or in
 * {@code zone}</li>
 * <li>select options by value or label, case-insensitively; multi-select values separated by {@code ,} or
 * {@code ;}</li>
 * <li>lookups through {@link LookupResolver}; file references by file ID</li>
 * </ul>
 */
final class CellConverter {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern GROUPING = Pattern.compile("[,_'\\s\\u00A0\\u202F]");
    private static final Pattern SERIAL = Pattern.compile("^\\d{1,7}(\\.\\d+)?$");
    private static final Pattern CURRENCY = Pattern.compile(
            "^(?<pre>[A-Za-z]{3}|[₹$€£])?\\s*(?<amount>[-+]?[0-9][0-9,_'\\s\\u00A0\\u202F]*(\\.[0-9]+)?)\\s*(?<post>[A-Za-z]{3})?$");
    private static final Map<String, String> CURRENCY_SYMBOLS = Map.of("₹", "INR", "$", "USD", "€", "EUR", "£", "GBP");
    private static final String DEFAULT_CURRENCY = "INR";
    private static final double MAX_EXCEL_SERIAL = 2_958_465;

    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            strict("d/M/uuuu"), strict("d-M-uuuu"), strict("d.M.uuuu"),
            strict("d-MMM-uuuu"), strict("d MMM uuuu"), strict("uuuuMMdd"));
    private static final List<DateTimeFormatter> DATE_TIMES = List.of(
            strict("uuuu-MM-dd'T'H:mm[:ss][.SSS]"), strict("uuuu-MM-dd H:mm[:ss]"),
            strict("d/M/uuuu H:mm[:ss]"), strict("d-M-uuuu H:mm[:ss]"), strict("d.M.uuuu H:mm[:ss]"));

    /** Resolves a lookup cell (a record ID or a value identifying the target record) to a record ID. */
    @FunctionalInterface
    interface LookupResolver {

        String resolve(FieldSchema field, String value) throws ConversionException;
    }

    /** A cell that cannot be converted; {@code code} follows the record API's violation codes. */
    static final class ConversionException extends Exception {

        private final String code;

        ConversionException(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private final ZoneId zone;
    private final LookupResolver lookups;

    CellConverter(ZoneId zone, LookupResolver lookups) {
        this.zone = zone;
        this.lookups = lookups;
    }

    /** @param raw non-blank cell text */
    JsonNode convert(FieldSchema field, String raw) throws ConversionException {
        String value = raw.strip();
        FieldConfig config = field.config() == null ? FieldConfig.empty() : field.config();
        return switch (field.type()) {
            case TEXT, LONG_TEXT, EMAIL, PHONE, URL -> NODES.stringNode(value);
            case INTEGER -> integer(value);
            case DECIMAL -> NODES.stringNode(number(value).toPlainString());
            case CURRENCY -> currency(value, config);
            case BOOLEAN -> bool(value);
            case DATE -> NODES.stringNode(date(value).toString());
            case DATETIME -> NODES.stringNode(dateTime(value));
            case SINGLE_SELECT -> NODES.stringNode(option(config, value));
            case MULTI_SELECT -> options(config, value);
            case LOOKUP -> NODES.stringNode(lookups.resolve(field, value));
            case FILE_REFERENCE, IMAGE_REFERENCE -> {
                ObjectNode reference = NODES.objectNode();
                reference.put("fileId", value);
                yield reference;
            }
            case JSON -> json(value);
        };
    }

    private static JsonNode integer(String value) throws ConversionException {
        BigDecimal number = number(value).stripTrailingZeros();
        if (number.scale() > 0) {
            throw new ConversionException("type", "must be a whole number");
        }
        try {
            long result = number.longValueExact();
            return result == (int) result ? NODES.numberNode((int) result) : NODES.numberNode(result);
        } catch (ArithmeticException ex) {
            throw new ConversionException("type", "must be a 64-bit integer");
        }
    }

    private static BigDecimal number(String value) throws ConversionException {
        String digits = GROUPING.matcher(value).replaceAll("");
        if (digits.length() > 64) {
            throw new ConversionException("type", "must be a number");
        }
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException ex) {
            throw new ConversionException("type", "must be a number");
        }
    }

    private static JsonNode currency(String value, FieldConfig config) throws ConversionException {
        Matcher matcher = CURRENCY.matcher(value);
        if (!matcher.matches() || (matcher.group("pre") != null && matcher.group("post") != null)) {
            throw new ConversionException("type", "must be an amount such as 1200.50, INR 1200 or 1200 USD");
        }
        String code = matcher.group("pre") != null ? matcher.group("pre") : matcher.group("post");
        if (code != null) {
            code = CURRENCY_SYMBOLS.getOrDefault(code, code.toUpperCase(Locale.ROOT));
        } else {
            code = config.currencies() != null && !config.currencies().isEmpty() ? config.currencies().getFirst()
                    : DEFAULT_CURRENCY;
        }
        ObjectNode amount = NODES.objectNode();
        amount.put("amount", number(matcher.group("amount")).toPlainString());
        amount.put("currency", code);
        return amount;
    }

    private static JsonNode bool(String value) throws ConversionException {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1" -> NODES.booleanNode(true);
            case "no", "n", "false", "0" -> NODES.booleanNode(false);
            default -> throw new ConversionException("type", "must be yes/no, true/false or 1/0");
        };
    }

    private static LocalDate date(String value) throws ConversionException {
        Optional<LocalDateTime> serial = serial(value);
        if (serial.isPresent()) {
            return serial.get().toLocalDate();
        }
        for (DateTimeFormatter format : DATES) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeException ignored) {
                // next pattern
            }
        }
        for (DateTimeFormatter format : DATE_TIMES) {
            try {
                return LocalDateTime.parse(value, format).toLocalDate();
            } catch (DateTimeException ignored) {
                // next pattern
            }
        }
        throw new ConversionException("format", "must be a date such as 2026-10-20 or 20/10/2026");
    }

    /** ISO-8601 instant; local values are interpreted in {@link #zone}. */
    private String dateTime(String value) throws ConversionException {
        try {
            return OffsetDateTime.parse(value).toInstant().toString();
        } catch (DateTimeException ignored) {
            // local forms below
        }
        try {
            return ZonedDateTime.parse(value).toInstant().toString();
        } catch (DateTimeException ignored) {
            // local forms below
        }
        LocalDateTime local = serial(value).orElse(null);
        for (int i = 0; local == null && i < DATE_TIMES.size(); i++) {
            try {
                local = LocalDateTime.parse(value, DATE_TIMES.get(i));
            } catch (DateTimeException ignored) {
                // next pattern
            }
        }
        if (local == null) {
            try {
                local = date(value).atTime(LocalTime.MIDNIGHT);
            } catch (ConversionException ex) {
                throw new ConversionException("format",
                        "must be a date-time such as 2026-10-20 10:15, 20/10/2026 10:15 or 2026-10-20T10:15:00+05:30");
            }
        }
        return local.atZone(zone).toInstant().toString();
    }

    private static Optional<LocalDateTime> serial(String value) {
        if (!SERIAL.matcher(value).matches()) {
            return Optional.empty();
        }
        double serial = Double.parseDouble(value);
        if (serial < 1 || serial > MAX_EXCEL_SERIAL) {
            return Optional.empty();
        }
        return Optional.of(DateUtil.getLocalDateTime(serial, false, true));
    }

    /** An option value by exact value, else by value or label ignoring case; active options win over deprecated. */
    static String option(FieldConfig config, String value) throws ConversionException {
        List<SelectOption> options = config.optionsOrEmpty();
        for (SelectOption option : options) {
            if (option.value().equals(value)) {
                return option.value();
            }
        }
        SelectOption deprecatedMatch = null;
        for (SelectOption option : options) {
            boolean matches = option.value().equalsIgnoreCase(value)
                    || (option.label() != null && option.label().strip().equalsIgnoreCase(value));
            if (matches && !option.isDeprecated()) {
                return option.value();
            }
            if (matches && deprecatedMatch == null) {
                deprecatedMatch = option;
            }
        }
        if (deprecatedMatch != null) {
            return deprecatedMatch.value();
        }
        List<String> allowed = options.stream().filter(option -> !option.isDeprecated())
                .map(option -> option.label() == null ? option.value() : option.label()).limit(10).toList();
        throw new ConversionException("option", "'" + value + "' is not an allowed option"
                + (allowed.isEmpty() ? "" : " (allowed: " + String.join(", ", allowed) + ")"));
    }

    private static JsonNode options(FieldConfig config, String value) throws ConversionException {
        ArrayNode values = NODES.arrayNode();
        List<String> seen = new ArrayList<>();
        for (String part : value.split("[,;]")) {
            String item = part.strip();
            if (!item.isEmpty()) {
                String option = option(config, item);
                if (!seen.contains(option)) {
                    seen.add(option);
                    values.add(option);
                }
            }
        }
        return values;
    }

    private static JsonNode json(String value) throws ConversionException {
        try {
            return JsonMapper.shared().readTree(value);
        } catch (JacksonException ex) {
            throw new ConversionException("type", "must be valid JSON");
        }
    }

    private static DateTimeFormatter strict(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT);
    }
}
