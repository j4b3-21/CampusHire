package com.bm4a.platform.modules.dataexchange.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldConfig.SelectOption;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;

/**
 * Import templates: one column per importable field, headed by its label. The XLSX template adds drop-downs for
 * single-select fields and a second sheet documenting every column (field key, label, type, required, unique,
 * allowed values, example).
 */
@Component
class ImportTemplates {

    private static final int MAX_LIST_CONSTRAINT = 255;
    private static final int FIELDS_SHEET_COLUMNS = 7;

    private final ModuleSchemas schemas;

    ImportTemplates(ModuleSchemas schemas) {
        this.schemas = schemas;
    }

    void write(UUID organizationId, ModuleSchema module, List<FieldSchema> fields, SpreadsheetFormat format,
            Path target) {
        Map<FieldSchema, String> headers = ColumnMapping.headers(fields);
        try {
            if (format == SpreadsheetFormat.CSV) {
                try (TableWriter writer = TableWriter.create(format, target, module.labelPlural())) {
                    writer.header(List.copyOf(headers.values()));
                    writer.finish();
                }
                return;
            }
            try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(target)) {
                CellStyle header = TableWriter.headerStyle(workbook);
                Sheet data = workbook.createSheet(sheetName(module.labelPlural()));
                Row row = data.createRow(0);
                int column = 0;
                for (Map.Entry<FieldSchema, String> entry : headers.entrySet()) {
                    Cell cell = row.createCell(column);
                    cell.setCellValue(entry.getValue());
                    cell.setCellStyle(header);
                    data.setColumnWidth(column, Math.clamp(entry.getValue().length() + 4, 12, 50) * 256);
                    dropDown(data, column, entry.getKey());
                    column++;
                }
                data.createFreezePane(0, 1);

                Sheet documentation = workbook.createSheet("Fields");
                Row titles = documentation.createRow(0);
                List<String> names = List.of("Column", "Field key", "Type", "Required", "Unique", "Allowed values",
                        "Example");
                for (int i = 0; i < names.size(); i++) {
                    Cell cell = titles.createCell(i);
                    cell.setCellValue(names.get(i));
                    cell.setCellStyle(header);
                }
                int rowIndex = 1;
                for (Map.Entry<FieldSchema, String> entry : headers.entrySet()) {
                    FieldSchema field = entry.getKey();
                    Row line = documentation.createRow(rowIndex++);
                    List<String> values = List.of(entry.getValue(), field.apiKey(), field.type().name(),
                            requiredText(field), field.unique() ? "yes" : "no", allowedValues(field),
                            example(organizationId, field));
                    for (int i = 0; i < values.size(); i++) {
                        line.createCell(i).setCellValue(values.get(i));
                    }
                }
                for (int i = 0; i < FIELDS_SHEET_COLUMNS; i++) {
                    documentation.setColumnWidth(i, (i == 5 ? 50 : 22) * 256);
                }
                documentation.createFreezePane(0, 1);
                workbook.write(out);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A list constraint offering the option labels, when they fit Excel's 255-character limit. */
    private static void dropDown(Sheet sheet, int column, FieldSchema field) {
        if (field.type() != FieldType.SINGLE_SELECT && field.type() != FieldType.BOOLEAN) {
            return;
        }
        List<String> choices = field.type() == FieldType.BOOLEAN ? List.of("yes", "no")
                : options(field).stream().map(ImportTemplates::label).toList();
        String joined = String.join(",", choices);
        if (choices.isEmpty() || joined.length() > MAX_LIST_CONSTRAINT
                || choices.stream().anyMatch(choice -> choice.contains(",") || choice.contains("\""))) {
            return;
        }
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidation validation = helper.createValidation(helper.createExplicitListConstraint(
                choices.toArray(String[]::new)), new CellRangeAddressList(1,
                SpreadsheetVersion.EXCEL2007.getLastRowIndex(), column, column));
        validation.setShowErrorBox(false);
        sheet.addValidationData(validation);
    }

    private static String requiredText(FieldSchema field) {
        if (!field.required()) {
            return "no";
        }
        return field.defaultValue() == null ? "yes" : "no (default " + field.defaultValue().asString() + ")";
    }

    private static String allowedValues(FieldSchema field) {
        return switch (field.type()) {
            case SINGLE_SELECT, MULTI_SELECT -> options(field).stream()
                    .map(option -> label(option) + (option.label() == null ? "" : " [" + option.value() + "]"))
                    .collect(Collectors.joining(", "))
                    + (field.type() == FieldType.MULTI_SELECT ? " (separate several with , or ;)" : "");
            case BOOLEAN -> "yes / no";
            case CURRENCY -> {
                FieldConfig config = config(field);
                yield config.currencies() == null || config.currencies().isEmpty() ? "any ISO 4217 code (default INR)"
                        : String.join(", ", config.currencies()) + " (default " + config.currencies().getFirst() + ")";
            }
            default -> "";
        };
    }

    private String example(UUID organizationId, FieldSchema field) {
        FieldConfig config = config(field);
        return switch (field.type()) {
            case TEXT, LONG_TEXT -> "Text";
            case INTEGER -> "42";
            case DECIMAL -> "8.75";
            case BOOLEAN -> "yes";
            case DATE -> "2026-10-20 or 20/10/2026";
            case DATETIME -> "2026-10-20 10:15";
            case EMAIL -> "name@example.com";
            case PHONE -> "+91 98765 43210";
            case URL -> "https://example.com";
            case CURRENCY -> "1200.50";
            case SINGLE_SELECT -> options(field).isEmpty() ? "" : label(options(field).getFirst());
            case MULTI_SELECT -> options(field).stream().limit(2).map(ImportTemplates::label)
                    .collect(Collectors.joining(", "));
            case JSON -> "{\"key\": \"value\"}";
            case FILE_REFERENCE, IMAGE_REFERENCE -> "file ID returned by the upload API";
            case LOOKUP -> lookupExample(organizationId, config.targetModuleKey());
        };
    }

    private String lookupExample(UUID organizationId, String targetKey) {
        return schemas.find(organizationId, targetKey).map(target -> {
            List<String> keys = new ArrayList<>();
            target.fields().stream().filter(field -> field.visible() && field.unique()).limit(2)
                    .forEach(field -> keys.add(field.label()));
            return (keys.isEmpty() ? "" : String.join(" or ", keys) + " of a " + target.labelSingular() + ", or ")
                    + "record ID";
        }).orElse("record ID");
    }

    private static List<SelectOption> options(FieldSchema field) {
        return config(field).optionsOrEmpty().stream().filter(option -> !option.isDeprecated()).toList();
    }

    private static String label(SelectOption option) {
        return option.label() == null ? option.value() : option.label();
    }

    private static FieldConfig config(FieldSchema field) {
        return field.config() == null ? FieldConfig.empty() : field.config();
    }

    private static String sheetName(String name) {
        return org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(name);
    }
}
