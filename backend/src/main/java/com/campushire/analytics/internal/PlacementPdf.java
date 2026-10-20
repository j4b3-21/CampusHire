package com.campushire.analytics.internal;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import com.campushire.analytics.internal.AnalyticsResponses.CompanyStats;
import com.campushire.analytics.internal.AnalyticsResponses.CtcBucket;
import com.campushire.analytics.internal.AnalyticsResponses.DepartmentStats;
import com.campushire.analytics.internal.AnalyticsResponses.Overview;

/**
 * Renders the placement report as an A4 PDF: a one-page summary (key figures and CTC distribution), then the
 * department table and the top companies.
 */
@Component
class PlacementPdf {

    static final int TOP_COMPANIES = 10;

    private static final Color INK = new Color(0x1F, 0x29, 0x37);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color ACCENT = new Color(0x1E, 0x40, 0xAF);
    private static final Color RULE = new Color(0xE5, 0xE7, 0xEB);
    private static final Color TILE = new Color(0xF8, 0xFA, 0xFC);
    private static final Color HEADER = new Color(0xEE, 0xF2, 0xF7);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Font.NORMAL, INK);
    private static final Font SUBTITLE = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, Font.NORMAL, MUTED);
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Font.NORMAL, ACCENT);
    private static final Font TILE_LABEL = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, Font.NORMAL, MUTED);
    private static final Font TILE_VALUE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 17, Font.NORMAL, INK);
    private static final Font TILE_NOTE = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, Font.NORMAL, MUTED);
    private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Font.NORMAL, INK);
    private static final Font TD = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, Font.NORMAL, INK);
    private static final Font FOOTER = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, Font.NORMAL, MUTED);

    private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z",
            Locale.ENGLISH);
    private static final String NONE = "\u2013";

    byte[] write(PlacementReport report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 48, 48, 52, 56);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        writer.setPageEvent(new Footer());
        document.addTitle("Placement report");
        document.addCreator("CampusHire");
        document.open();

        document.add(new Paragraph("Placement report", TITLE));
        Paragraph subtitle = new Paragraph("Generated " + GENERATED.format(report.generatedAt()) + "  \u00b7  "
                + String.join("  \u00b7  ", report.filters()), SUBTITLE);
        subtitle.setSpacingBefore(4);
        subtitle.setSpacingAfter(14);
        document.add(subtitle);
        document.add(rule());

        document.add(section("Key figures"));
        document.add(tiles(report.overview()));
        document.add(section("CTC distribution"));
        document.add(distribution(report.distribution().buckets(), report.distribution().offers()));

        document.newPage();
        document.add(section("Departments"));
        Paragraph unit = new Paragraph("Rate = placed / participating students. CTC in lakh per annum.", TILE_NOTE);
        unit.setSpacingAfter(6);
        document.add(unit);
        document.add(departments(report.departments()));
        document.add(section("Top companies"));
        document.add(companies(report.companies().stream().limit(TOP_COMPANIES).toList()));

        document.close();
        return out.toByteArray();
    }

    private static PdfPTable tiles(Overview o) {
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.setSpacingAfter(6);
        tile(table, "PLACEMENT RATE", percent(o.placementRate()),
                count(o.placedStudents()) + " of " + count(o.participatingStudents()) + " participating students");
        tile(table, "PLACED STUDENTS", count(o.placedStudents()), count(o.totalStudents()) + " students in scope");
        tile(table, "OFFERS", count(o.offers()), count(o.acceptedOffers()) + " accepted");
        tile(table, "HIGHEST CTC", lpa(o.highestCtc()), "lakh per annum");
        tile(table, "MEDIAN CTC", lpa(o.medianCtc()), "lakh per annum");
        tile(table, "AVERAGE CTC", lpa(o.averageCtc()), "lakh per annum");
        tile(table, "APPLICATIONS", count(o.applications()), "by students in scope");
        tile(table, "ACTIVE DRIVES", count(o.activeDrives()), "published or in progress");
        tile(table, "COMPANIES VISITED", count(o.companiesVisited()), "drives in progress or completed");
        return table;
    }

    private static void tile(PdfPTable table, String label, String value, String note) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(Color.WHITE);
        cell.setBorderWidth(4);
        cell.setBackgroundColor(TILE);
        cell.setPadding(10);
        cell.setPaddingBottom(12);
        cell.addElement(new Paragraph(label, TILE_LABEL));
        Paragraph figure = new Paragraph(value, TILE_VALUE);
        figure.setSpacingBefore(2);
        cell.addElement(figure);
        cell.addElement(new Paragraph(note, TILE_NOTE));
        table.addCell(cell);
    }

    private static PdfPTable distribution(List<CtcBucket> buckets, long total) {
        PdfPTable table = table(new float[] { 3, 2, 2 }, "CTC range (LPA)", "Offers", "Share");
        table.setWidthPercentage(60);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        for (CtcBucket bucket : buckets) {
            cell(table, bucket.label(), false);
            cell(table, count(bucket.count()), true);
            cell(table, total == 0 ? NONE : percent((double) bucket.count() / total), true);
        }
        return table;
    }

    private static PdfPTable departments(List<DepartmentStats> departments) {
        PdfPTable table = table(new float[] { 4, 1.6f, 1.6f, 1.4f, 1.6f, 1.3f, 1.8f, 1.8f, 1.8f }, "Department",
                "Students", "Particip.", "Placed", "Rate", "Offers", "Highest", "Median", "Average");
        for (DepartmentStats d : departments) {
            cell(table, d.departmentName() == null ? "Unassigned" : d.departmentName(), false);
            cell(table, count(d.totalStudents()), true);
            cell(table, count(d.participatingStudents()), true);
            cell(table, count(d.placedStudents()), true);
            cell(table, percent(d.placementRate()), true);
            cell(table, count(d.offers()), true);
            cell(table, lpa(d.highestCtc()), true);
            cell(table, lpa(d.medianCtc()), true);
            cell(table, lpa(d.averageCtc()), true);
        }
        if (departments.isEmpty()) {
            empty(table, 9);
        }
        return table;
    }

    private static PdfPTable companies(List<CompanyStats> companies) {
        PdfPTable table = table(new float[] { 5, 1.5f, 1.5f, 2, 2 }, "Company", "Offers", "Drives",
                "Highest (LPA)", "Average (LPA)");
        for (CompanyStats c : companies) {
            cell(table, c.companyName() == null ? "Unknown company" : c.companyName(), false);
            cell(table, count(c.offers()), true);
            cell(table, count(c.drives()), true);
            cell(table, lpa(c.highestCtc()), true);
            cell(table, lpa(c.averageCtc()), true);
        }
        if (companies.isEmpty()) {
            empty(table, 5);
        }
        return table;
    }

    private static PdfPTable table(float[] widths, String... headers) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setSpacingAfter(10);
        table.setHeaderRows(1);
        for (int i = 0; i < headers.length; i++) {
            PdfPCell cell = new PdfPCell(new Phrase(headers[i], TH));
            cell.setBackgroundColor(HEADER);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(5);
            cell.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            table.addCell(cell);
        }
        return table;
    }

    private static void cell(PdfPTable table, String text, boolean numeric) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TD));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setPadding(5);
        cell.setHorizontalAlignment(numeric ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
        table.addCell(cell);
    }

    private static void empty(PdfPTable table, int columns) {
        PdfPCell cell = new PdfPCell(new Phrase("No data for the selected filters", TILE_NOTE));
        cell.setColspan(columns);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(6);
        table.addCell(cell);
    }

    private static Paragraph section(String title) {
        Paragraph paragraph = new Paragraph(title, SECTION);
        paragraph.setSpacingBefore(14);
        paragraph.setSpacingAfter(8);
        return paragraph;
    }

    private static PdfPTable rule() {
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(ACCENT);
        cell.setBorderWidth(1.5f);
        cell.setFixedHeight(2);
        rule.addCell(cell);
        return rule;
    }

    private static String count(long value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }

    private static String percent(double rate) {
        return String.format(Locale.ENGLISH, "%.1f%%", rate * 100);
    }

    private static String lpa(BigDecimal inr) {
        BigDecimal lpa = PlacementReportService.lpa(inr);
        return lpa == null ? NONE : lpa.toPlainString();
    }

    private static final class Footer extends PdfPageEventHelper {

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            float y = document.bottom() - 24;
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase("CampusHire \u00b7 Placement report", FOOTER), document.left(), y, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), FOOTER), document.right(), y, 0);
        }
    }
}
