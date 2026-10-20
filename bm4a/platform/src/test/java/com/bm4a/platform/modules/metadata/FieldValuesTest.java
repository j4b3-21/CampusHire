package com.bm4a.platform.modules.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bm4a.platform.modules.metadata.FieldConfig.SelectOption;
import com.bm4a.platform.modules.metadata.FieldValues.Outcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FieldValuesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void textIsTrimmedAndLengthChecked() {
        FieldSchema field = field(FieldType.TEXT, config(2, 5));
        assertThat(ok(field, "\"  abc  \"")).isEqualTo(json("\"abc\""));
        assertThat(code(field, "\"abcdef\"")).isEqualTo("max_length");
        assertThat(code(field, "\"a\"")).isEqualTo("min_length");
        assertThat(code(field, "12")).isEqualTo("type");
        assertThat(FieldValues.canonicalize(field, json("\"   \""), null).hasValue()).isFalse();
    }

    @Test
    void numbersKeepPrecision() {
        FieldSchema integer = field(FieldType.INTEGER, bounds("0", "100"));
        assertThat(ok(integer, "42")).isEqualTo(json("42"));
        assertThat(code(integer, "4.5")).isEqualTo("type");
        assertThat(code(integer, "\"42\"")).isEqualTo("type");
        assertThat(code(integer, "101")).isEqualTo("max");

        FieldSchema decimal = field(FieldType.DECIMAL, FieldConfig.empty());
        assertThat(ok(decimal, "\"12.500\"")).isEqualTo(json("\"12.5\""));
        assertThat(ok(decimal, "0.1")).isEqualTo(json("\"0.1\""));
        assertThat(ok(decimal, "1E+3")).isEqualTo(json("\"1000\""));
        assertThat(code(decimal, "\"1.1234567\"")).isEqualTo("scale");
        assertThat(code(decimal, "\"abc\"")).isEqualTo("type");
    }

    @Test
    void currencyIsNormalizedToCurrencyDigits() {
        FieldSchema field = field(FieldType.CURRENCY, FieldConfig.empty());
        assertThat(ok(field, "{\"amount\":\"12.5\",\"currency\":\"inr\"}"))
                .isEqualTo(json("{\"amount\":\"12.50\",\"currency\":\"INR\"}"));
        assertThat(ok(field, "{\"amount\":100,\"currency\":\"JPY\"}"))
                .isEqualTo(json("{\"amount\":\"100\",\"currency\":\"JPY\"}"));
        assertThat(code(field, "{\"amount\":\"1.234\",\"currency\":\"USD\"}")).isEqualTo("scale");
        assertThat(code(field, "{\"amount\":\"1\",\"currency\":\"XYZ1\"}")).isEqualTo("currency");
        assertThat(code(field, "{\"amount\":\"1\",\"currency\":\"USD\",\"extra\":1}")).isEqualTo("unknown_property");
    }

    @Test
    void datesAndDateTimesAreCanonical() {
        assertThat(ok(field(FieldType.DATE, FieldConfig.empty()), "\"2026-10-20\"")).isEqualTo(json("\"2026-10-20\""));
        assertThat(code(field(FieldType.DATE, FieldConfig.empty()), "\"2026-02-30\"")).isEqualTo("format");
        assertThat(ok(field(FieldType.DATETIME, FieldConfig.empty()), "\"2026-10-20T11:45:00+05:30\""))
                .isEqualTo(json("\"2026-10-20T06:15:00Z\""));
        assertThat(code(field(FieldType.DATETIME, FieldConfig.empty()), "\"2026-10-20T11:45:00\"")).isEqualTo("format");
    }

    @Test
    void formatsAreChecked() {
        assertThat(code(field(FieldType.EMAIL, FieldConfig.empty()), "\"not-an-email\"")).isEqualTo("format");
        assertThat(ok(field(FieldType.EMAIL, FieldConfig.empty()), "\"a@b.io\"")).isEqualTo(json("\"a@b.io\""));
        assertThat(code(field(FieldType.PHONE, FieldConfig.empty()), "\"call me\"")).isEqualTo("format");
        assertThat(ok(field(FieldType.PHONE, FieldConfig.empty()), "\"+91 80 1234 5678\"")).isNotNull();
        assertThat(code(field(FieldType.URL, FieldConfig.empty()), "\"javascript:alert(1)\"")).isEqualTo("format");
        assertThat(code(field(FieldType.URL, FieldConfig.empty()), "\"/relative\"")).isEqualTo("format");
        assertThat(ok(field(FieldType.URL, FieldConfig.empty()), "\"https://example.com/x\"")).isNotNull();
        assertThat(code(field(FieldType.BOOLEAN, FieldConfig.empty()), "\"true\"")).isEqualTo("type");
        String id = UUID.randomUUID().toString();
        assertThat(ok(field(FieldType.LOOKUP, FieldConfig.empty()), "\"" + id.toUpperCase() + "\""))
                .isEqualTo(json("\"" + id + "\""));
        assertThat(code(field(FieldType.LOOKUP, FieldConfig.empty()), "\"nope\"")).isEqualTo("format");
    }

    @Test
    void selectsUseOptionsAndKeepDeprecatedValuesOnlyWhenUnchanged() {
        FieldConfig config = new FieldConfig(null, null, null, null, null,
                List.of(new SelectOption("new", "New", null), new SelectOption("used", "Used", null),
                        new SelectOption("legacy", "Legacy", true)),
                2, null, null, null, null);
        FieldSchema single = field(FieldType.SINGLE_SELECT, config);
        assertThat(ok(single, "\"used\"")).isEqualTo(json("\"used\""));
        assertThat(code(single, "\"broken\"")).isEqualTo("option");
        assertThat(code(single, "\"legacy\"")).isEqualTo("option");
        assertThat(FieldValues.canonicalize(single, json("\"legacy\""), json("\"legacy\"")).valid()).isTrue();

        FieldSchema multi = field(FieldType.MULTI_SELECT, config);
        assertThat(ok(multi, "[\"used\",\"new\"]")).isEqualTo(json("[\"new\",\"used\"]"));
        assertThat(code(multi, "[\"new\",\"new\"]")).isEqualTo("duplicate");
        assertThat(code(multi, "[\"new\",\"used\",\"legacy\"]")).isEqualTo("option");
    }

    @Test
    void jsonAndFileReferences() {
        FieldConfig small = new FieldConfig(null, null, null, null, null, null, null, null, 10, null, null);
        assertThat(code(field(FieldType.JSON, small), "{\"a\":\"0123456789\"}")).isEqualTo("max_bytes");
        assertThat(ok(field(FieldType.JSON, FieldConfig.empty()), "[1,{\"a\":true}]")).isEqualTo(json("[1,{\"a\":true}]"));

        FieldSchema image = field(FieldType.IMAGE_REFERENCE, FieldConfig.empty());
        assertThat(ok(image, "{\"fileId\":\"files/1.png\",\"contentType\":\"IMAGE/PNG\"}"))
                .isEqualTo(json("{\"fileId\":\"files/1.png\",\"contentType\":\"image/png\"}"));
        assertThat(code(image, "{\"fileId\":\"f\",\"contentType\":\"application/pdf\"}")).isEqualTo("content_type");
        assertThat(code(image, "{\"fileId\":\"../../etc passwd\"}")).isEqualTo("file_id");
        assertThat(code(field(FieldType.FILE_REFERENCE, FieldConfig.empty()), "{\"fileId\":\"f\",\"url\":\"x\"}"))
                .isEqualTo("unknown_property");
    }

    @Test
    void typeCompatibilityMatrix() {
        assertThat(FieldType.TEXT.canChangeTo(FieldType.LONG_TEXT)).isTrue();
        assertThat(FieldType.INTEGER.canChangeTo(FieldType.DECIMAL)).isTrue();
        assertThat(FieldType.DECIMAL.canChangeTo(FieldType.INTEGER)).isFalse();
        assertThat(FieldType.LONG_TEXT.canChangeTo(FieldType.TEXT)).isFalse();
        assertThat(FieldType.SINGLE_SELECT.canChangeTo(FieldType.MULTI_SELECT)).isFalse();
    }

    private static FieldSchema field(FieldType type, FieldConfig config) {
        return new FieldSchema(UUID.randomUUID(), "f", "F", type, FieldStatus.ACTIVE, false, false, false, false,
                false, false, false, null, config, 0);
    }

    private static FieldConfig config(int min, int max) {
        return new FieldConfig(min, max, null, null, null, null, null, null, null, null, null);
    }

    private static FieldConfig bounds(String min, String max) {
        return new FieldConfig(null, null, new BigDecimal(min), new BigDecimal(max), null, null, null, null, null, null,
                null);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static JsonNode ok(FieldSchema field, String value) {
        Outcome outcome = FieldValues.canonicalize(field, json(value), null);
        assertThat(outcome.valid()).as(outcome.code() + ": " + outcome.message()).isTrue();
        return outcome.value();
    }

    private static String code(FieldSchema field, String value) {
        return FieldValues.canonicalize(field, json(value), null).code();
    }
}
