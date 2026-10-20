package com.bm4a.platform.modules.dataexchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.poi.ss.usermodel.DateUtil;
import org.junit.jupiter.api.Test;

import com.bm4a.platform.modules.access.FieldPolicy;
import com.bm4a.platform.modules.dataexchange.internal.CellConverter.ConversionException;
import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldConfig.SelectOption;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.ModuleAccessMode;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.shared.error.ApiException;

import tools.jackson.databind.JsonNode;

class CellConverterTest {

    static final String DEPARTMENT_ID = UUID.randomUUID().toString();
    final CellConverter converter = new CellConverter(ZoneId.of("Asia/Kolkata"), (field, value) -> {
        if (value.equalsIgnoreCase("CSE")) {
            return DEPARTMENT_ID;
        }
        throw new ConversionException("invalid_reference", "no Department matches '" + value + "'");
    });

    @Test
    void numbersDropGroupingSeparators() throws Exception {
        assertThat(convert(FieldType.INTEGER, "1,23,456")).hasToString("123456");
        assertThat(convert(FieldType.INTEGER, " 12.0 ")).hasToString("12");
        assertThat(convert(FieldType.INTEGER, "9876543210").asLong()).isEqualTo(9_876_543_210L);
        assertThat(code(FieldType.INTEGER, "12.5")).isEqualTo("type");
        assertThat(code(FieldType.INTEGER, "twelve")).isEqualTo("type");
        assertThat(convert(FieldType.DECIMAL, "8.50").asString()).isEqualTo("8.50");
        assertThat(convert(FieldType.DECIMAL, "1 234.5").asString()).isEqualTo("1234.5");
    }

    @Test
    void currencyAmountsTakeACodeOrSymbolOrTheDefault() throws Exception {
        assertThat(convert(FieldType.CURRENCY, "1,200.50")).hasToString("{\"amount\":\"1200.50\",\"currency\":\"INR\"}");
        assertThat(convert(FieldType.CURRENCY, "USD 12")).hasToString("{\"amount\":\"12\",\"currency\":\"USD\"}");
        assertThat(convert(FieldType.CURRENCY, "12 eur")).hasToString("{\"amount\":\"12\",\"currency\":\"EUR\"}");
        assertThat(convert(FieldType.CURRENCY, "₹500")).hasToString("{\"amount\":\"500\",\"currency\":\"INR\"}");
        FieldConfig usd = new FieldConfig(null, null, null, null, null, null, null, List.of("USD"), null, null, null);
        assertThat(converter.convert(field(FieldType.CURRENCY, usd), "7").get("currency").asString()).isEqualTo("USD");
        assertThat(code(FieldType.CURRENCY, "USD 12 INR")).isEqualTo("type");
    }

    @Test
    void booleans() throws Exception {
        for (String yes : List.of("yes", "Y", "TRUE", "1")) {
            assertThat(convert(FieldType.BOOLEAN, yes).asBoolean()).isTrue();
        }
        for (String no : List.of("No", "n", "false", "0")) {
            assertThat(convert(FieldType.BOOLEAN, no).asBoolean()).isFalse();
        }
        assertThat(code(FieldType.BOOLEAN, "maybe")).isEqualTo("type");
    }

    @Test
    void datesInCommonNotationsAndExcelSerials() throws Exception {
        double serial = DateUtil.getExcelDate(LocalDate.of(2026, 10, 20));
        for (String date : List.of("2026-10-20", "20/10/2026", "20-10-2026", "20.10.2026", "20-Oct-2026",
                "20 OCT 2026", "20261020", Long.toString((long) serial), "2026-10-20T00:00")) {
            assertThat(convert(FieldType.DATE, date).asString()).as(date).isEqualTo("2026-10-20");
        }
        assertThat(code(FieldType.DATE, "31/02/2026")).isEqualTo("format");
        assertThat(code(FieldType.DATE, "10/20/2026")).as("day first").isEqualTo("format");
    }

    @Test
    void dateTimesWithoutOffsetUseTheConfiguredZone() throws Exception {
        double serial = DateUtil.getExcelDate(LocalDate.of(2026, 10, 20)) + 0.5;
        assertThat(convert(FieldType.DATETIME, "2026-10-20 10:15").asString()).isEqualTo("2026-10-20T04:45:00Z");
        assertThat(convert(FieldType.DATETIME, "20/10/2026 10:15:30").asString()).isEqualTo("2026-10-20T04:45:30Z");
        assertThat(convert(FieldType.DATETIME, "2026-10-20T10:15").asString()).isEqualTo("2026-10-20T04:45:00Z");
        assertThat(convert(FieldType.DATETIME, "2026-10-20T10:15:00Z").asString()).isEqualTo("2026-10-20T10:15:00Z");
        assertThat(convert(FieldType.DATETIME, "2026-10-20T10:15:00+05:30").asString())
                .isEqualTo("2026-10-20T04:45:00Z");
        assertThat(convert(FieldType.DATETIME, Double.toString(serial)).asString()).isEqualTo("2026-10-20T06:30:00Z");
        assertThat(convert(FieldType.DATETIME, "2026-10-20").asString()).isEqualTo("2026-10-19T18:30:00Z");
        assertThat(code(FieldType.DATETIME, "tomorrow")).isEqualTo("format");
    }

    @Test
    void selectOptionsByValueOrLabelIgnoringCase() throws Exception {
        FieldConfig options = new FieldConfig(null, null, null, null, null, List.of(
                new SelectOption("general", "General", null), new SelectOption("obc", "Other Backward Class", null),
                new SelectOption("old", "General", true)), null, null, null, null, null);
        assertThat(converter.convert(field(FieldType.SINGLE_SELECT, options), "general").asString()).isEqualTo("general");
        assertThat(converter.convert(field(FieldType.SINGLE_SELECT, options), "GENERAL").asString())
                .isEqualTo("general");
        assertThat(converter.convert(field(FieldType.SINGLE_SELECT, options), "other backward class").asString())
                .isEqualTo("obc");
        assertThat(converter.convert(field(FieldType.SINGLE_SELECT, options), "old").asString()).isEqualTo("old");
        assertThatThrownBy(() -> converter.convert(field(FieldType.SINGLE_SELECT, options), "x"))
                .isInstanceOfSatisfying(ConversionException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("option");
                    assertThat(ex.getMessage()).contains("General, Other Backward Class").doesNotContain("old");
                });
        assertThat(converter.convert(field(FieldType.MULTI_SELECT, options), "General; obc, general"))
                .hasToString("[\"general\",\"obc\"]");
    }

    @Test
    void lookupsReferencesTextAndJson() throws Exception {
        assertThat(convert(FieldType.LOOKUP, "cse").asString()).isEqualTo(DEPARTMENT_ID);
        assertThat(code(FieldType.LOOKUP, "XYZ")).isEqualTo("invalid_reference");
        assertThat(convert(FieldType.FILE_REFERENCE, " abc ")).hasToString("{\"fileId\":\"abc\"}");
        assertThat(convert(FieldType.TEXT, "  Asha  ").asString()).isEqualTo("Asha");
        assertThat(convert(FieldType.JSON, "{\"a\": 1}")).hasToString("{\"a\":1}");
        assertThat(code(FieldType.JSON, "{oops")).isEqualTo("type");
    }

    @Test
    void formulaEscapingRoundTrips() {
        assertThat(Cells.escape("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(Cells.escape("+91 98765")).isEqualTo("'+91 98765");
        assertThat(Cells.escape("-5")).isEqualTo("'-5");
        assertThat(Cells.escape("@cmd")).isEqualTo("'@cmd");
        assertThat(Cells.escape("\tx")).isEqualTo("'\tx");
        assertThat(Cells.escape("Asha")).isEqualTo("Asha");
        assertThat(Cells.unescape("'=SUM(A1)")).isEqualTo("=SUM(A1)");
        assertThat(Cells.unescape("'quoted")).isEqualTo("'quoted");
    }

    @Test
    void mappingSuggestionsAndValidation() {
        FieldSchema roll = new FieldSchema(UUID.randomUUID(), "roll_number", "Roll number", FieldType.TEXT,
                FieldStatus.ACTIVE, true, true, false, false, true, true, true, null, FieldConfig.empty(), 1);
        FieldSchema name = new FieldSchema(UUID.randomUUID(), "full_name", "Name", FieldType.TEXT, FieldStatus.ACTIVE,
                true, false, false, false, false, false, false, null, FieldConfig.empty(), 2);
        FieldSchema email = new FieldSchema(UUID.randomUUID(), "email", "E-mail", FieldType.EMAIL, FieldStatus.ACTIVE,
                false, true, false, false, false, false, false, null, FieldConfig.empty(), 3);
        FieldSchema altName = new FieldSchema(UUID.randomUUID(), "nick", "Name", FieldType.TEXT, FieldStatus.ACTIVE,
                false, false, false, false, false, false, false, null, FieldConfig.empty(), 4);
        FieldSchema legacy = new FieldSchema(UUID.randomUUID(), "legacy", "Legacy", FieldType.TEXT,
                FieldStatus.ACTIVE, false, false, true, false, false, false, false, null, FieldConfig.empty(), 5);
        ModuleSchema module = new ModuleSchema(UUID.randomUUID(), UUID.randomUUID(), "student", "Student", "Students",
                true, 1, ModuleAccessMode.ROLES, List.of(roll, name, email, altName, legacy));

        List<FieldSchema> importable = ColumnMapping.importable(module, List.of(FieldPolicy.unrestricted()));
        assertThat(importable).extracting(FieldSchema::apiKey).containsExactly("roll_number", "full_name", "email",
                "nick");
        assertThat(ColumnMapping.importable(module, List.of(new FieldPolicy(Set.of("email"), Set.of("nick")))))
                .extracting(FieldSchema::apiKey).containsExactly("roll_number", "full_name");
        assertThat(ColumnMapping.headers(importable).values()).containsExactly("Roll number", "Name (full_name)",
                "E-mail", "Name (nick)");

        List<String> headers = List.of("ROLL NUMBER", "Name (nick)", "e mail", "full_name", "Other", "");
        assertThat(ColumnMapping.suggest(headers, importable)).containsExactly(Map.entry("ROLL NUMBER", "roll_number"),
                Map.entry("Name (nick)", "nick"), Map.entry("e mail", "email"), Map.entry("full_name", "full_name"));

        assertThatThrownBy(() -> ColumnMapping.resolve(module, headers, Map.of("Other", "legacy", "Missing", "email"),
                ImportMode.UPSERT, "full_name", List.of(FieldPolicy.unrestricted())))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.violations())
                        .extracting(v -> v.field() + "=" + v.code())
                        .containsExactlyInAnyOrder("mapping.Other=field_read_only", "mapping.Missing=unknown_column",
                                "matchField=match_field"));
        assertThatThrownBy(() -> ColumnMapping.resolve(module, headers, Map.of("full_name", "full_name"),
                ImportMode.CREATE, null, List.of(FieldPolicy.unrestricted())))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.violations())
                        .extracting(v -> v.code()).containsExactly("required_field_unmapped"));
        assertThat(ColumnMapping.resolve(module, headers, Map.of("ROLL NUMBER", "roll_number", "full_name",
                "full_name"), ImportMode.UPSERT, "roll_number", List.of(FieldPolicy.unrestricted())))
                .extracting(ColumnMapping.Column::index).containsExactly(0, 3);
        assertThatThrownBy(() -> ColumnMapping.headerRow(List.of("A", " A ")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("duplicate_header"));
    }

    private JsonNode convert(FieldType type, String raw) throws ConversionException {
        return converter.convert(field(type, FieldConfig.empty()), raw);
    }

    private String code(FieldType type, String raw) {
        try {
            converter.convert(field(type, FieldConfig.empty()), raw);
            return null;
        } catch (ConversionException ex) {
            return ex.code();
        }
    }

    private static FieldSchema field(FieldType type, FieldConfig config) {
        return new FieldSchema(UUID.randomUUID(), "f", "F", type, FieldStatus.ACTIVE, false, false, false, false, false,
                false, false, null, config, 1);
    }
}
