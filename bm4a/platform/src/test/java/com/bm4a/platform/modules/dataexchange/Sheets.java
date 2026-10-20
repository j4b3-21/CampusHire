package com.bm4a.platform.modules.dataexchange;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Builds spreadsheets for import tests and reads exported ones. */
final class Sheets {

    private Sheets() {
    }

    /** An XLSX file: strings, numbers, booleans and dates become native cells; {@code null} leaves a gap. */
    static byte[] xlsx(List<String> headers, List<List<Object>> rows) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Data");
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> values = rows.get(r);
                for (int i = 0; i < values.size(); i++) {
                    Object value = values.get(i);
                    if (value == null) {
                        continue;
                    }
                    Cell cell = row.createCell(i);
                    switch (value) {
                        case Number number -> cell.setCellValue(number.doubleValue());
                        case Boolean bool -> cell.setCellValue(bool);
                        case LocalDate day -> {
                            cell.setCellValue(day);
                            cell.setCellStyle(date);
                        }
                        default -> cell.setCellValue(value.toString());
                    }
                }
            }
            workbook.write(out);
            workbook.dispose();
            return out.toByteArray();
        }
    }

    static byte[] csv(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /** Rows of the first sheet as text (formatted like Excel shows them). */
    static List<List<String>> readXlsx(byte[] content) throws IOException {
        return readXlsx(content, 0);
    }

    static List<List<String>> readXlsx(byte[] content, int sheetIndex) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            List<List<String>> rows = new ArrayList<>();
            for (Row row : workbook.getSheetAt(sheetIndex)) {
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < row.getLastCellNum(); i++) {
                    Cell cell = row.getCell(i);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(cells);
            }
            return rows;
        }
    }

    static String sheetName(byte[] content, int index) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            return workbook.getSheetName(index);
        }
    }

    static Cell cell(byte[] content, int rowIndex, int column) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            return workbook.getSheetAt(0).getRow(rowIndex).getCell(column);
        }
    }

    /** Records of a CSV file that must start with a UTF-8 byte order mark. */
    static List<List<String>> readCsv(byte[] content) throws IOException {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.startsWith("\uFEFF")) {
            throw new AssertionError("CSV does not start with a byte order mark");
        }
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(text.substring(1)))) {
            return parser.stream().map(CSVRecord::toList).toList();
        }
    }
}
