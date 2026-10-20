package com.campushire.analytics.internal;

import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.users.CurrentUser;

/** Downloadable placement report (PRD T7); never cached. Accepts the same filters as the analytics endpoints. */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/campushire/reports")
class ReportController {

    static final MediaType XLSX = MediaType
            .parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StaffAccess staff;
    private final PlacementReportService reports;
    private final PlacementWorkbook workbook;
    private final PlacementPdf pdf;

    ReportController(StaffAccess staff, PlacementReportService reports, PlacementWorkbook workbook, PlacementPdf pdf) {
        this.staff = staff;
        this.reports = reports;
        this.workbook = workbook;
        this.pdf = pdf;
    }

    @GetMapping("/placement")
    ResponseEntity<byte[]> placement(CurrentUser user, @PathVariable UUID orgId,
            @ModelAttribute AnalyticsQuery query, @RequestParam(defaultValue = "xlsx") String format) {
        boolean excel = switch (format) {
            case "xlsx" -> true;
            case "pdf" -> false;
            default -> throw ApiException.validation("format", "unsupported", "format must be xlsx or pdf");
        };
        PlacementReport report = reports.build(staff.require(user, orgId), query.validated());
        byte[] body = excel ? workbook.write(report) : pdf.write(report);
        String filename = "placement-report-" + FILE_DATE.format(report.generatedAt()) + "." + format;
        return ResponseEntity.ok()
                .contentType(excel ? XLSX : MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
