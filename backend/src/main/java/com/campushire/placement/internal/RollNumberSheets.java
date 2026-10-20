package com.campushire.placement.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.bm4a.platform.modules.shared.error.ApiException;

/**
 * Reads roll numbers from a shortlist spreadsheet (XLSX/XLS, first sheet) or CSV. The roll number column is found by
 * its header ({@code roll}, {@code roll number}, {@code roll_no}, {@code roll no}, ...) within the first rows; values
 * below it are trimmed, blank ones skipped, duplicates removed (case-insensitively, first spelling kept).
 */
final class RollNumberSheets {

    static final int MAX_ROWS = 5_000;
    private static final int HEADER_SEARCH_ROWS = 10;
    private static final Set<String> HEADERS = Set.of("roll", "rollnumber", "rollno", "rollnum", "rollnumbers",
            "registernumber", "regno");

    private RollNumberSheets() {
    }

    static List<String> read(byte[] content) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("file_empty", "The uploaded file is empty");
        }
        List<List<String>> rows = isWorkbook(content) ? workbookRows(content) : csvRows(content);
        int headerRow = -1;
        int column = -1;
        for (int r = 0; r < Math.min(HEADER_SEARCH_ROWS, rows.size()) && column < 0; r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (HEADERS.contains(normalizeHeader(row.get(c)))) {
                    headerRow = r;
                    column = c;
                    break;
                }
            }
        }
        if (column < 0) {
            throw ApiException.badRequest("roll_column_missing",
                    "No roll number column found; name the column 'Roll number'");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> rolls = new ArrayList<>();
        for (int r = headerRow + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String value = column < row.size() ? row.get(column).strip() : "";
            if (!value.isEmpty() && seen.add(value.toLowerCase(Locale.ROOT))) {
                rolls.add(value);
            }
        }
        if (rolls.isEmpty()) {
            throw ApiException.badRequest("no_roll_numbers", "The roll number column is empty");
        }
        return rolls;
    }

    static String normalizeHeader(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean isWorkbook(byte[] content) {
        boolean zip = content.length > 3 && content[0] == 'P' && content[1] == 'K' && content[2] == 3
                && content[3] == 4;
        boolean ole2 = content.length > 7 && (content[0] & 0xFF) == 0xD0 && (content[1] & 0xFF) == 0xCF
                && (content[2] & 0xFF) == 0x11 && (content[3] & 0xFF) == 0xE0;
        return zip || ole2;
    }

    private static List<List<String>> workbookRows(byte[] content) {
        try (InputStream in = new ByteArrayInputStream(content); Workbook workbook = WorkbookFactory.create(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (rows.size() > MAX_ROWS) {
                    throw tooManyRows();
                }
                while (rows.size() < row.getRowNum()) {
                    rows.add(List.of());
                }
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(cells);
            }
            return rows;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ApiException api) {
                throw api;
            }
            throw ApiException.badRequest("unreadable_file", "The spreadsheet could not be read");
        }
    }

    private static List<List<String>> csvRows(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String firstLine = text.lines().findFirst().orElse("");
        char delimiter = delimiter(firstLine);
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)) {
            List<List<String>> rows = new ArrayList<>();
            Iterator<CSVRecord> records = CSVFormat.DEFAULT.builder().setDelimiter(delimiter)
                    .setIgnoreEmptyLines(false).get().parse(reader).iterator();
            while (records.hasNext()) {
                if (rows.size() > MAX_ROWS) {
                    throw tooManyRows();
                }
                rows.add(records.next().toList());
            }
            return rows;
        } catch (IOException | IllegalStateException | java.io.UncheckedIOException ex) {
            throw ApiException.badRequest("unreadable_file", "The CSV file could not be read");
        }
    }

    private static char delimiter(String line) {
        long commas = line.chars().filter(c -> c == ',').count();
        long semicolons = line.chars().filter(c -> c == ';').count();
        long tabs = line.chars().filter(c -> c == '\t').count();
        if (tabs > commas && tabs >= semicolons) {
            return '\t';
        }
        return semicolons > commas ? ';' : ',';
    }

    private static ApiException tooManyRows() {
        return ApiException.badRequest("too_many_rows", "At most " + MAX_ROWS + " rows can be uploaded at once");
    }
}
