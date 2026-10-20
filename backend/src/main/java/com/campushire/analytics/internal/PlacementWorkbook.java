package com.campushire.analytics.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import com.campushire.analytics.internal.AnalyticsResponses.CompanyStats;
import com.campushire.analytics.internal.AnalyticsResponses.CtcBucket;
import com.campushire.analytics.internal.AnalyticsResponses.DepartmentStats;
import com.campushire.analytics.internal.AnalyticsResponses.Overview;
import com.campushire.analytics.internal.PlacementReport.OfferLine;

/**
 * Writes the placement report as a streaming XLSX workbook with the sheets Summary, Departments, Companies and
 * Offers. Text that a spreadsheet could interpret as a formula is prefixed with an apostrophe.
 */
@Component
class PlacementWorkbook {

    static final List<String> SHEETS = List.of("Summary", "Departments", "Companies", "Offers");
    static final List<String> OFFER_HEADERS = List.of("Roll number", "Student", "Department", "Company", "CTC (LPA)",
            "Type", "Status", "Date");
    private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    byte[] write(PlacementReport report) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            try {
                Styles styles = new Styles(workbook);
                summary(workbook.createSheet(SHEETS.get(0)), styles, report);
                departments(workbook.createSheet(SHEETS.get(1)), styles, report.departments());
                companies(workbook.createSheet(SHEETS.get(2)), styles, report.companies());
                offers(workbook.createSheet(SHEETS.get(3)), styles, report.offers());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                workbook.write(out);
                return out.toByteArray();
            } finally {
                workbook.dispose();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void summary(Sheet sheet, Styles styles, PlacementReport report) {
        Overview o = report.overview();
        widths(sheet, 34, 22);
        int r = 0;
        Row title = sheet.createRow(r++);
        text(title.createCell(0), "Placement report", styles.title);
        text(sheet.createRow(r++).createCell(0), "Generated " + GENERATED.format(report.generatedAt()), null);
        for (String filter : report.filters()) {
            text(sheet.createRow(r++).createCell(0), filter, null);
        }
        r++;
        header(sheet.createRow(r++), styles, List.of("Metric", "Value"));
        r = metric(sheet, r, styles, "Total students", o.totalStudents());
        r = metric(sheet, r, styles, "Participating students", o.participatingStudents());
        r = metric(sheet, r, styles, "Placed students", o.placedStudents());
        r = percentMetric(sheet, r, styles, "Placement rate", o.placementRate());
        r = metric(sheet, r, styles, "Offers (extended or accepted)", o.offers());
        r = metric(sheet, r, styles, "Accepted offers", o.acceptedOffers());
        r = lpaMetric(sheet, r, styles, "Highest CTC (LPA)", o.highestCtc());
        r = lpaMetric(sheet, r, styles, "Average CTC (LPA)", o.averageCtc());
        r = lpaMetric(sheet, r, styles, "Median CTC (LPA)", o.medianCtc());
        r = metric(sheet, r, styles, "Applications", o.applications());
        r = metric(sheet, r, styles, "Active drives", o.activeDrives());
        r = metric(sheet, r, styles, "Companies visited", o.companiesVisited());
        r++;
        header(sheet.createRow(r++), styles, List.of("CTC range (LPA)", "Offers"));
        for (CtcBucket bucket : report.distribution().buckets()) {
            r = metric(sheet, r, styles, bucket.label(), bucket.count());
        }
    }

    private static void departments(Sheet sheet, Styles styles, List<DepartmentStats> departments) {
        widths(sheet, 30, 14, 14, 14, 14, 10, 18, 18, 18);
        header(sheet.createRow(0), styles, List.of("Department", "Students", "Participating", "Placed",
                "Placement rate", "Offers", "Highest CTC (LPA)", "Average CTC (LPA)", "Median CTC (LPA)"));
        sheet.createFreezePane(0, 1);
        int r = 1;
        for (DepartmentStats d : departments) {
            Row row = sheet.createRow(r++);
            text(row.createCell(0), d.departmentName() == null ? "Unassigned" : d.departmentName(), null);
            number(row.createCell(1), d.totalStudents(), styles.integer);
            number(row.createCell(2), d.participatingStudents(), styles.integer);
            number(row.createCell(3), d.placedStudents(), styles.integer);
            number(row.createCell(4), d.placementRate(), styles.percent);
            number(row.createCell(5), d.offers(), styles.integer);
            lpa(row.createCell(6), d.highestCtc(), styles);
            lpa(row.createCell(7), d.averageCtc(), styles);
            lpa(row.createCell(8), d.medianCtc(), styles);
        }
    }

    private static void companies(Sheet sheet, Styles styles, List<CompanyStats> companies) {
        widths(sheet, 34, 10, 10, 18, 18);
        header(sheet.createRow(0), styles,
                List.of("Company", "Offers", "Drives", "Highest CTC (LPA)", "Average CTC (LPA)"));
        sheet.createFreezePane(0, 1);
        int r = 1;
        for (CompanyStats c : companies) {
            Row row = sheet.createRow(r++);
            text(row.createCell(0), c.companyName(), null);
            number(row.createCell(1), c.offers(), styles.integer);
            number(row.createCell(2), c.drives(), styles.integer);
            lpa(row.createCell(3), c.highestCtc(), styles);
            lpa(row.createCell(4), c.averageCtc(), styles);
        }
    }

    private static void offers(Sheet sheet, Styles styles, List<OfferLine> offers) {
        widths(sheet, 16, 30, 24, 30, 12, 14, 12, 12);
        header(sheet.createRow(0), styles, OFFER_HEADERS);
        sheet.createFreezePane(0, 1);
        int r = 1;
        for (OfferLine offer : offers) {
            Row row = sheet.createRow(r++);
            text(row.createCell(0), offer.rollNumber(), null);
            text(row.createCell(1), offer.studentName(), null);
            text(row.createCell(2), offer.department(), null);
            text(row.createCell(3), offer.company(), null);
            if (offer.ctcLpa() != null) {
                number(row.createCell(4), offer.ctcLpa().doubleValue(), styles.decimal);
            }
            text(row.createCell(5), offer.offerType(), null);
            text(row.createCell(6), offer.status(), null);
            date(row.createCell(7), offer.date(), styles);
        }
    }

    private static int metric(Sheet sheet, int r, Styles styles, String label, long value) {
        Row row = sheet.createRow(r);
        text(row.createCell(0), label, null);
        number(row.createCell(1), value, styles.integer);
        return r + 1;
    }

    private static int percentMetric(Sheet sheet, int r, Styles styles, String label, double value) {
        Row row = sheet.createRow(r);
        text(row.createCell(0), label, null);
        number(row.createCell(1), value, styles.percent);
        return r + 1;
    }

    private static int lpaMetric(Sheet sheet, int r, Styles styles, String label, BigDecimal inr) {
        Row row = sheet.createRow(r);
        text(row.createCell(0), label, null);
        lpa(row.createCell(1), inr, styles);
        return r + 1;
    }

    private static void header(Row row, Styles styles, List<String> labels) {
        for (int c = 0; c < labels.size(); c++) {
            text(row.createCell(c), labels.get(c), styles.header);
        }
    }

    private static void widths(Sheet sheet, int... characters) {
        for (int c = 0; c < characters.length; c++) {
            sheet.setColumnWidth(c, characters[c] * 256);
        }
    }

    static void text(Cell cell, String value, CellStyle style) {
        if (value != null) {
            cell.setCellValue(neutralize(value));
        }
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    /** Prevents formula injection when the sheet is opened or converted (OWASP CSV injection). */
    static String neutralize(String value) {
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private static void number(Cell cell, double value, CellStyle style) {
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void lpa(Cell cell, BigDecimal inr, Styles styles) {
        BigDecimal lpa = PlacementReportService.lpa(inr);
        if (lpa != null) {
            number(cell, lpa.doubleValue(), styles.decimal);
        }
    }

    private static void date(Cell cell, LocalDate value, Styles styles) {
        if (value != null) {
            cell.setCellValue(value);
            cell.setCellStyle(styles.date);
        }
    }

    private static final class Styles {

        final CellStyle title;
        final CellStyle header;
        final CellStyle integer;
        final CellStyle decimal;
        final CellStyle percent;
        final CellStyle date;

        Styles(SXSSFWorkbook workbook) {
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = workbook.createCellStyle();
            title.setFont(titleFont);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            integer = numeric(workbook, "#,##0");
            decimal = numeric(workbook, "#,##0.00");
            percent = numeric(workbook, "0.0%");
            date = numeric(workbook, "yyyy-mm-dd");
        }

        private static CellStyle numeric(SXSSFWorkbook workbook, String format) {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat(format));
            return style;
        }
    }
}
