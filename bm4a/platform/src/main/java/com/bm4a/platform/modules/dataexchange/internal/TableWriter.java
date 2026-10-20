package com.bm4a.platform.modules.dataexchange.internal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFCell;
import org.apache.poi.xssf.streaming.SXSSFRow;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Writes one table (header row plus data rows) to a file. XLSX uses POI's streaming workbook (500 rows in memory,
 * compressed temporary files) with a styled, frozen header row, auto filter, column widths and date/number formats;
 * CSV is RFC 4180 with a UTF-8 byte order mark so that Excel detects the encoding. Text that a spreadsheet would
 * evaluate as a formula is escaped in both ({@link Cells}).
 */
abstract class TableWriter implements AutoCloseable {

    static final DateTimeFormatter CSV_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static TableWriter create(SpreadsheetFormat format, Path target, String sheetName) throws IOException {
        return switch (format) {
            case XLSX -> new Xlsx(target, sheetName);
            case CSV -> new Csv(target);
        };
    }

    abstract void header(List<String> names) throws IOException;

    abstract void row(List<TableValue> values) throws IOException;

    /** Completes the file; nothing may be written afterwards. */
    abstract void finish() throws IOException;

    @Override
    public abstract void close() throws IOException;

    /** Header row style shared with the import template. */
    static CellStyle headerStyle(Workbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private static final class Xlsx extends TableWriter {

        private static final int MIN_WIDTH = 8;
        private static final int MAX_WIDTH = 60;
        private static final int MAX_TEXT = SpreadsheetVersion.EXCEL2007.getMaxTextLength();
        /** Numbers with more significant digits are written as text; Excel stores IEEE doubles. */
        private static final int MAX_EXACT_DIGITS = 15;

        private final Path target;
        private final SXSSFWorkbook workbook = new SXSSFWorkbook(500);
        private final SXSSFSheet sheet;
        private final CellStyle header;
        private final CellStyle date;
        private final CellStyle dateTime;
        private final Map<String, CellStyle> money = new HashMap<>();
        private final List<Integer> widths = new ArrayList<>();
        private int rows;

        Xlsx(Path target, String sheetName) {
            this.target = target;
            workbook.setCompressTempFiles(true);
            sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(sheetName));
            header = headerStyle(workbook);
            date = format("yyyy-mm-dd");
            dateTime = format("yyyy-mm-dd hh:mm:ss");
        }

        @Override
        void header(List<String> names) {
            SXSSFRow row = sheet.createRow(rows++);
            for (int i = 0; i < names.size(); i++) {
                SXSSFCell cell = row.createCell(i);
                cell.setCellValue(names.get(i));
                cell.setCellStyle(header);
                track(i, names.get(i).length() + 2);
            }
            sheet.createFreezePane(0, 1);
        }

        @Override
        void row(List<TableValue> values) {
            SXSSFRow row = sheet.createRow(rows++);
            for (int i = 0; i < values.size(); i++) {
                TableValue value = values.get(i);
                if (value instanceof TableValue.Empty) {
                    continue;
                }
                SXSSFCell cell = row.createCell(i);
                int width = switch (value) {
                    case TableValue.Text text -> {
                        String escaped = Cells.escape(text.value());
                        cell.setCellValue(escaped.length() > MAX_TEXT ? escaped.substring(0, MAX_TEXT) : escaped);
                        yield escaped.length();
                    }
                    case TableValue.Number number -> {
                        if (number.value().precision() > MAX_EXACT_DIGITS) {
                            cell.setCellValue(number.value().toPlainString());
                        } else {
                            cell.setCellValue(number.value().doubleValue());
                        }
                        yield number.value().toPlainString().length();
                    }
                    case TableValue.Bool bool -> {
                        cell.setCellValue(bool.value());
                        yield 5;
                    }
                    case TableValue.Date day -> {
                        cell.setCellValue(day.value());
                        cell.setCellStyle(date);
                        yield 10;
                    }
                    case TableValue.DateTime time -> {
                        cell.setCellValue(time.value());
                        cell.setCellStyle(dateTime);
                        yield 19;
                    }
                    case TableValue.Money amount -> {
                        cell.setCellValue(amount.amount().doubleValue());
                        cell.setCellStyle(money.computeIfAbsent(amount.currency(), this::moneyStyle));
                        yield amount.amount().toPlainString().length() + 6;
                    }
                    case TableValue.Empty empty -> 0;
                };
                track(i, width);
            }
        }

        @Override
        void finish() throws IOException {
            for (int i = 0; i < widths.size(); i++) {
                sheet.setColumnWidth(i, Math.clamp(widths.get(i), MIN_WIDTH, MAX_WIDTH) * 256);
            }
            if (!widths.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rows - 1, 0), 0, widths.size() - 1));
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                workbook.write(out);
            }
        }

        @Override
        public void close() throws IOException {
            workbook.dispose();
            workbook.close();
        }

        private void track(int column, int width) {
            while (widths.size() <= column) {
                widths.add(MIN_WIDTH);
            }
            widths.set(column, Math.max(widths.get(column), width + 1));
        }

        private CellStyle format(String pattern) {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat(pattern));
            return style;
        }

        private CellStyle moneyStyle(String currency) {
            int digits;
            try {
                digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
            } catch (IllegalArgumentException ex) {
                digits = 2;
            }
            String fraction = digits == 0 ? "" : "." + "0".repeat(digits);
            return format("#,##0" + fraction + " \"" + currency.replaceAll("[^A-Z]", "") + "\"");
        }
    }

    private static final class Csv extends TableWriter {

        private final Writer writer;
        private final CSVPrinter printer;

        Csv(Path target) throws IOException {
            writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(target), StandardCharsets.UTF_8));
            writer.write('\uFEFF');
            printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
        }

        @Override
        void header(List<String> names) throws IOException {
            printer.printRecord(names);
        }

        @Override
        void row(List<TableValue> values) throws IOException {
            List<String> texts = new ArrayList<>(values.size());
            for (TableValue value : values) {
                texts.add(switch (value) {
                    case TableValue.Empty empty -> "";
                    case TableValue.Text text -> Cells.escape(text.value());
                    case TableValue.Number number -> number.value().toPlainString();
                    case TableValue.Bool bool -> Boolean.toString(bool.value());
                    case TableValue.Date day -> day.value().toString();
                    case TableValue.DateTime time -> CSV_DATE_TIME.format(time.value());
                    case TableValue.Money amount -> amount.amount().toPlainString() + " " + amount.currency();
                });
            }
            printer.printRecord(texts);
        }

        @Override
        void finish() throws IOException {
            printer.flush();
        }

        @Override
        public void close() throws IOException {
            printer.close();
        }
    }
}
