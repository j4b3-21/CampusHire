package com.bm4a.platform.modules.dataexchange.web;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bm4a.platform.modules.dataexchange.internal.DownloadFile;
import com.bm4a.platform.modules.dataexchange.internal.ExportService;
import com.bm4a.platform.modules.dataexchange.internal.ImportJob;
import com.bm4a.platform.modules.dataexchange.internal.ImportMode;
import com.bm4a.platform.modules.dataexchange.internal.ImportService;
import com.bm4a.platform.modules.dataexchange.internal.ImportService.ImportOptions;
import com.bm4a.platform.modules.dataexchange.internal.ImportService.Preview;
import com.bm4a.platform.modules.dataexchange.internal.ImportStatus;
import com.bm4a.platform.modules.dataexchange.internal.SpreadsheetFormat;
import com.bm4a.platform.modules.shared.web.Downloads;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.users.CurrentUser;

import tools.jackson.databind.JsonNode;

/**
 * Spreadsheet import and export for every module (ADR-005). Imports are asynchronous: {@code POST .../imports}
 * returns {@code 202} with the queued job, which clients poll until its status is {@code COMPLETED},
 * {@code FAILED} or {@code CANCELLED}.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/modules/{moduleKey}")
class DataExchangeController {

    private final ImportService imports;
    private final ExportService exports;

    DataExchangeController(ImportService imports, ExportService exports) {
        this.imports = imports;
        this.exports = exports;
    }

    @GetMapping("/import-template")
    ResponseEntity<Resource> template(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestParam(required = false) String format) {
        return download(imports.template(user, orgId, moduleKey, SpreadsheetFormat.parse(format)));
    }

    /** Multipart part {@code file}: an XLSX workbook (first sheet) or UTF-8 CSV file. */
    @PostMapping(value = "/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Preview preview(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestPart("file") MultipartFile file) {
        return imports.preview(user, orgId, moduleKey, file);
    }

    /**
     * Multipart parts {@code file} and, optionally, {@code options} ({@code application/json}:
     * {@code {mode, matchField, mapping, dryRun}}).
     */
    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<JobResponse> create(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "options", required = false) ImportOptions options) {
        ImportJob job = imports.create(user, orgId, moduleKey, file, options);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/orgs/" + orgId + "/modules/" + moduleKey + "/imports/" + job.id()))
                .body(JobResponse.of(job));
    }

    @GetMapping("/imports")
    PageResponse<JobResponse> list(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return imports.list(user, orgId, moduleKey, PageQuery.of(page, size)).map(JobResponse::of);
    }

    @GetMapping("/imports/{jobId}")
    JobResponse get(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID jobId) {
        return JobResponse.of(imports.get(user, orgId, moduleKey, jobId));
    }

    @PostMapping("/imports/{jobId}/cancel")
    JobResponse cancel(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID jobId) {
        return JobResponse.of(imports.cancel(user, orgId, moduleKey, jobId));
    }

    @GetMapping("/imports/{jobId}/errors")
    ResponseEntity<Resource> errors(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID jobId, @RequestParam(required = false) String format) {
        return download(imports.errors(user, orgId, moduleKey, jobId, SpreadsheetFormat.parse(format)));
    }

    /** Accepts the record list's {@code filter[field][op]}, {@code sort} and {@code q} parameters. */
    @GetMapping("/export")
    ResponseEntity<Resource> export(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestParam(required = false) String format, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q, @RequestParam MultiValueMap<String, String> params) {
        return download(exports.export(user, orgId, moduleKey, SpreadsheetFormat.parse(format), params, sort, q));
    }

    private static ResponseEntity<Resource> download(DownloadFile file) {
        return ResponseEntity.ok()
                .headers(Downloads.attachment(file.fileName(), file.mediaType(), file.sizeBytes()))
                .body(new InputStreamResource(file.content()));
    }

    /**
     * @param errors the first failed rows: {@code [{row, column, field, code, message}]}
     * @param errorReportAvailable whether {@code GET .../errors} returns a report
     */
    record JobResponse(UUID id, String module, String fileName, UUID fileId, SpreadsheetFormat format,
            ImportMode mode, String matchField, Map<String, String> mapping, boolean dryRun, ImportStatus status,
            Integer totalRows, int processedRows, int createdRows, int updatedRows, int failedRows,
            boolean errorReportAvailable, JsonNode errors, String errorMessage, UUID createdBy, Instant createdAt,
            Instant startedAt, Instant finishedAt) {

        static JobResponse of(ImportJob job) {
            return new JobResponse(job.id(), job.moduleKey(), job.fileName(), job.fileId(), job.format(), job.mode(),
                    job.matchField(), job.mapping(), job.dryRun(), job.status(), job.totalRows(), job.processedRows(),
                    job.createdRows(), job.updatedRows(), job.failedRows(), job.errorFileId() != null,
                    job.errorSample(), job.errorMessage(), job.createdBy(), job.createdAt(), job.startedAt(),
                    job.finishedAt());
        }
    }
}
