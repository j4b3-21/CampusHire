package com.bm4a.platform.modules.dataexchange.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JRuntimeException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.util.RecordFormatException;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.bm4a.platform.modules.shared.error.ApiException;

/**
 * Streams the rows of the first worksheet of an XLSX file (POI's SAX event API, shared strings read-only) or of a
 * UTF-8 CSV file (Commons CSV; optional byte order mark; {@code ,}, {@code ;} or tab detected from the first line).
 * Memory use does not depend on the number of rows. Blank rows are skipped; the first non-blank row is the header.
 * <p>
 * XLSX cell values arrive as text: numbers in their shortest exact form (never locale-formatted), date-formatted
 * numbers as ISO-8601 local dates or date-times, booleans as {@code TRUE}/{@code FALSE}. A leading apostrophe that
 * protects a formula-like value ({@link Cells}) is removed.
 */
final class SheetReader {

    static final int MAX_COLUMNS = 500;

    @FunctionalInterface
    interface RowHandler {

        /**
         * @param rowNumber 1-based row number as a spreadsheet application shows it
         * @return {@code false} to stop reading
         */
        boolean row(int rowNumber, List<String> cells);
    }

    private SheetReader() {
    }

    static void read(Path file, SpreadsheetFormat format, RowHandler handler) {
        switch (format) {
            case XLSX -> readXlsx(file, handler);
            case CSV -> readCsv(file, handler);
        }
    }

    // ---- XLSX ----------------------------------------------------------------------------------------------

    private static void readXlsx(Path file, RowHandler handler) {
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            reader.setUseReadOnlySharedStringsTable(true);
            SharedStrings strings = reader.getSharedStringsTable();
            StylesTable styles = reader.getStylesTable();
            Iterator<InputStream> sheets = reader.getSheetsData();
            if (!sheets.hasNext()) {
                return;
            }
            try (InputStream sheet = sheets.next()) {
                XMLReader parser = XMLHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(styles, strings, new SheetRows(handler),
                        new RawValueFormatter(), false));
                parser.parse(new InputSource(sheet));
            }
        } catch (Stop stop) {
            // the handler has seen enough
        } catch (IOException | OpenXML4JException | SAXException | ParserConfigurationException
                | UnsupportedFileFormatException | OpenXML4JRuntimeException | POIXMLException
                | RecordFormatException ex) {
            throw unreadable("The file is not a readable XLSX workbook");
        }
    }

    /** Collects the cells of a row by column (gaps become empty strings) and hands non-blank rows on. */
    private static final class SheetRows implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final RowHandler handler;
        private final List<String> cells = new ArrayList<>();

        SheetRows(RowHandler handler) {
            this.handler = handler;
        }

        @Override
        public void startRow(int rowNum) {
            cells.clear();
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int column = cellReference == null ? cells.size() : new CellReference(cellReference).getCol();
            if (column >= MAX_COLUMNS) {
                throw tooManyColumns();
            }
            while (cells.size() < column) {
                cells.add("");
            }
            cells.add(formattedValue == null ? "" : Cells.unescape(formattedValue));
        }

        @Override
        public void endRow(int rowNum) {
            if (!blank(cells) && !handler.row(rowNum + 1, List.copyOf(cells))) {
                throw new Stop();
            }
        }
    }

    /**
     * Numbers verbatim (no grouping, no locale), dates as ISO-8601 local values. A number format that names an ISO
     * currency code in quotes (as exports write currency amounts, {@code #,##0.00 "USD"}) keeps the code:
     * {@code 99.5 USD}.
     */
    private static final class RawValueFormatter extends DataFormatter {

        private static final Pattern CURRENCY_CODE = Pattern.compile("\"([A-Z]{3})\"");

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString) {
            return formatRawCellContents(value, formatIndex, formatString, false);
        }

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString, boolean use1904) {
            if (DateUtil.isADateFormat(formatIndex, formatString) && DateUtil.isValidExcelDate(value)) {
                LocalDateTime dateTime = DateUtil.getLocalDateTime(value, use1904, true);
                return value == Math.floor(value) ? dateTime.toLocalDate().toString() : dateTime.toString();
            }
            Matcher currency = formatString == null ? null : CURRENCY_CODE.matcher(formatString);
            return NumberToTextConverter.toText(value)
                    + (currency != null && currency.find() ? " " + currency.group(1) : "");
        }
    }

    // ---- CSV -----------------------------------------------------------------------------------------------

    private static void readCsv(Path file, RowHandler handler) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder),
                64 * 1024)) {
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }
            CSVFormat format = CSVFormat.RFC4180.builder()
                    .setDelimiter(detectDelimiter(reader))
                    .setIgnoreEmptyLines(false)
                    .get();
            try (CSVParser parser = format.parse(reader)) {
                for (CSVRecord record : parser) {
                    if (record.size() > MAX_COLUMNS) {
                        throw tooManyColumns();
                    }
                    List<String> cells = record.stream().map(Cells::unescape).toList();
                    if (!blank(cells) && !handler.row((int) record.getRecordNumber(), cells)) {
                        return;
                    }
                }
            }
        } catch (CharacterCodingException ex) {
            throw unreadable("CSV files must be UTF-8 encoded");
        } catch (UncheckedIOException ex) {
            throw ex.getCause() instanceof CharacterCodingException ? unreadable("CSV files must be UTF-8 encoded")
                    : unreadable("The CSV file is malformed: " + ex.getCause().getMessage());
        } catch (IOException | IllegalStateException ex) {
            throw unreadable("The CSV file is malformed: " + ex.getMessage());
        }
    }

    /** The most frequent of {@code , ; tab} outside quotes in the first line; comma when none occurs. */
    private static char detectDelimiter(BufferedReader reader) throws IOException {
        int limit = 64 * 1024;
        reader.mark(limit + 1);
        int commas = 0;
        int semicolons = 0;
        int tabs = 0;
        boolean quoted = false;
        for (int i = 0, c; i < limit && (c = reader.read()) != -1; i++) {
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted) {
                commas += c == ',' ? 1 : 0;
                semicolons += c == ';' ? 1 : 0;
                tabs += c == '\t' ? 1 : 0;
            }
        }
        reader.reset();
        if (tabs > commas && tabs > semicolons) {
            return '\t';
        }
        return semicolons > commas ? ';' : ',';
    }

    // ---- shared --------------------------------------------------------------------------------------------

    private static boolean blank(List<String> cells) {
        return cells.stream().allMatch(String::isBlank);
    }

    private static ApiException unreadable(String message) {
        return ApiException.badRequest("unreadable_file", message);
    }

    private static ApiException tooManyColumns() {
        return ApiException.badRequest("too_many_columns", "Files may have at most " + MAX_COLUMNS + " columns");
    }

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }
}
