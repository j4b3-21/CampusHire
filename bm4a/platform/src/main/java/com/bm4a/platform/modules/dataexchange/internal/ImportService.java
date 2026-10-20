package com.bm4a.platform.modules.dataexchange.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.bm4a.platform.modules.access.FieldPolicy;
import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.dataexchange.internal.ColumnMapping.Column;
import com.bm4a.platform.modules.files.ContentSniffer;
import com.bm4a.platform.modules.files.StoredFileView;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.users.CurrentUser;

import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The request side of imports: templates, previews, job creation and job administration. Templates, previews and
 * imports need the module's CREATE permission ({@code UPSERT} also UPDATE); only importable fields (not hidden or
 * read-only for the caller) can be mapped. Organization admins see every job of a module, other members their own.
 */
@Service
public class ImportService {

    static final int PREVIEW_ROWS = 20;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .withZone(ZoneOffset.UTC);

    private final ModuleAccess access;
    private final com.bm4a.platform.modules.files.Files files;
    private final ImportJobRepository jobs;
    private final ImportTemplates templates;
    private final AuditLog audit;
    private final DataExchangeProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ImportService(ModuleAccess access, com.bm4a.platform.modules.files.Files files, ImportJobRepository jobs,
            ImportTemplates templates, AuditLog audit, DataExchangeProperties properties,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.access = access;
        this.files = files;
        this.jobs = jobs;
        this.templates = templates;
        this.audit = audit;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param mode default {@code CREATE}
     * @param matchField unique field identifying existing records ({@code UPSERT} only)
     * @param mapping {@code column header -> field key}; {@code null} uses the suggested mapping, a {@code null}
     *            field key skips the column
     * @param dryRun validate every row and write nothing
     */
    public record ImportOptions(ImportMode mode, String matchField, Map<String, String> mapping, Boolean dryRun) {
    }

    /** A field that can be imported, with its template column header. */
    public record ImportField(String key, String label, String header, FieldType type, boolean required,
            boolean unique) {
    }

    /**
     * @param rows the first {@value #PREVIEW_ROWS} data rows, each aligned with {@code headers}
     * @param totalRows number of data rows (non-blank rows after the header)
     * @param suggestedMapping {@code header -> field key} for the headers that match a field
     */
    public record Preview(String fileName, SpreadsheetFormat format, List<String> headers, List<List<String>> rows,
            int totalRows, int maxRows, Map<String, String> suggestedMapping, List<ImportField> fields) {
    }

    public DownloadFile template(CurrentUser user, UUID organizationId, String moduleKey, SpreadsheetFormat format) {
        ModuleGrant grant = access.require(user, organizationId, moduleKey, RecordAction.CREATE);
        ModuleSchema module = grant.module();
        Path target = temporaryFile();
        try {
            templates.write(organizationId, module, ColumnMapping.importable(module, List.of(grant.fields())), format,
                    target);
        } catch (RuntimeException ex) {
            deleteQuietly(target);
            throw ex;
        }
        return DownloadFile.temporary(target, module.apiKey() + "-import-template." + format.extension(),
                format.mediaType());
    }

    public Preview preview(CurrentUser user, UUID organizationId, String moduleKey, MultipartFile upload) {
        ModuleGrant grant = access.require(user, organizationId, moduleKey, RecordAction.CREATE);
        List<FieldSchema> fields = ColumnMapping.importable(grant.module(), List.of(grant.fields()));
        try (Upload file = spool(upload)) {
            List<List<String>> rows = new ArrayList<>();
            List<String> headers = new ArrayList<>();
            int[] total = { 0 };
            SheetReader.read(file.path(), file.format(), (number, cells) -> {
                if (headers.isEmpty()) {
                    headers.addAll(ColumnMapping.headerRow(cells));
                    return true;
                }
                total[0]++;
                if (rows.size() < PREVIEW_ROWS) {
                    rows.add(aligned(cells, headers.size()));
                }
                return true;
            });
            if (headers.isEmpty()) {
                throw ApiException.badRequest("empty_file", "The file has no header row");
            }
            Map<FieldSchema, String> fieldHeaders = ColumnMapping.headers(fields);
            List<ImportField> importFields = fields.stream().map(field -> new ImportField(field.apiKey(),
                    field.label(), fieldHeaders.get(field), field.type(),
                    field.required() && field.defaultValue() == null, field.unique())).toList();
            return new Preview(file.name(), file.format(), List.copyOf(headers), rows, total[0],
                    properties.maxRows(), ColumnMapping.suggest(headers, fields), importFields);
        }
    }

    public ImportJob create(CurrentUser user, UUID organizationId, String moduleKey, MultipartFile upload,
            ImportOptions options) {
        ImportOptions request = options == null ? new ImportOptions(null, null, null, null) : options;
        ImportMode mode = request.mode() == null ? ImportMode.CREATE : request.mode();
        ModuleGrant create = access.require(user, organizationId, moduleKey, RecordAction.CREATE);
        List<FieldPolicy> policies = new ArrayList<>(List.of(create.fields()));
        if (mode == ImportMode.UPSERT) {
            policies.add(access.require(user, organizationId, moduleKey, RecordAction.UPDATE).fields());
        }
        ModuleSchema module = create.module();
        if (!module.active()) {
            throw ApiException.conflict("module_archived", "Module is archived; its records are read-only");
        }
        if (jobs.hasActive(organizationId, module.id())) {
            throw importInProgress(module);
        }
        try (Upload file = spool(upload)) {
            List<String> headers = header(file);
            Map<String, String> mapping = request.mapping() != null ? request.mapping()
                    : ColumnMapping.suggest(headers, ColumnMapping.importable(module, policies));
            List<Column> columns = ColumnMapping.resolve(module, headers, mapping, mode, request.matchField(),
                    policies);
            StoredFileView stored;
            try (InputStream content = Files.newInputStream(file.path())) {
                stored = files.store(create.tenant(), file.name(), file.declaredType(), content);
            }
            Map<String, String> resolved = new LinkedHashMap<>();
            columns.forEach(column -> resolved.put(column.header(), column.field().apiKey()));
            boolean dryRun = Boolean.TRUE.equals(request.dryRun());
            ImportJob job = new ImportJob(UUID.randomUUID(), organizationId, module.id(), module.apiKey(),
                    stored.id(), stored.name(), file.format(), mode,
                    mode == ImportMode.UPSERT ? request.matchField() : null, resolved, dryRun, ImportStatus.QUEUED,
                    null, 0, 0, 0, 0, null, JsonNodeFactory.instance.arrayNode(), null, user.id(),
                    SqlValues.now(clock), null, null, null);
            transactions.executeWithoutResult(status -> {
                if (!jobs.insert(job)) {
                    throw importInProgress(module);
                }
                audit.record(AuditEntry.of(organizationId, user.id(), "import.queued", "import_job", job.id())
                        .with("module", module.apiKey())
                        .with("mode", mode.name())
                        .with("dryRun", dryRun)
                        .with("fileId", stored.id().toString()));
            });
            return job;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public ImportJob get(CurrentUser user, UUID organizationId, String moduleKey, UUID jobId) {
        return visible(user, organizationId, moduleKey, jobId);
    }

    public PageResponse<ImportJob> list(CurrentUser user, UUID organizationId, String moduleKey, PageQuery page) {
        ModuleGrant grant = access.require(user, organizationId, moduleKey, RecordAction.CREATE);
        UUID createdBy = grant.admin() ? null : user.id();
        UUID moduleId = grant.module().id();
        return PageResponse.of(jobs.list(organizationId, moduleId, createdBy, page.offset(), page.size()), page,
                jobs.count(organizationId, moduleId, createdBy));
    }

    /** Queued jobs stop immediately, running jobs after the current batch; completed batches stay written. */
    public ImportJob cancel(CurrentUser user, UUID organizationId, String moduleKey, UUID jobId) {
        ImportJob job = visible(user, organizationId, moduleKey, jobId);
        if (!jobs.cancel(organizationId, job.id(), SqlValues.now(clock))) {
            throw ApiException.conflict("import_finished", "The import has already finished");
        }
        audit.record(AuditEntry.of(organizationId, user.id(), "import.cancelled", "import_job", job.id())
                .with("module", job.moduleKey()));
        return jobs.find(organizationId, job.id()).orElseThrow(() -> ApiException.notFound("Import job"));
    }

    /** The failed rows with their original values plus {@code row} and {@code error} columns. */
    public DownloadFile errors(CurrentUser user, UUID organizationId, String moduleKey, UUID jobId,
            SpreadsheetFormat format) {
        ImportJob job = visible(user, organizationId, moduleKey, jobId);
        if (job.errorFileId() == null) {
            if (job.status().active()) {
                throw ApiException.conflict("import_not_finished", "The error report is available when the import ends");
            }
            throw ApiException.notFound("Error report");
        }
        StoredFileView report = files.find(organizationId, job.errorFileId())
                .orElseThrow(() -> ApiException.notFound("Error report"));
        String name = job.moduleKey() + "-import-errors-" + FILE_STAMP.format(job.createdAt()) + "."
                + format.extension();
        if (format == SpreadsheetFormat.CSV) {
            return new DownloadFile(name, format.mediaType(), report.sizeBytes(),
                    files.open(organizationId, report.id()));
        }
        Path csv = temporaryFile();
        Path xlsx = temporaryFile();
        boolean served = false;
        try {
            try (InputStream content = files.open(organizationId, report.id())) {
                Files.copy(content, csv, StandardCopyOption.REPLACE_EXISTING);
            }
            try (TableWriter writer = TableWriter.create(SpreadsheetFormat.XLSX, xlsx, "Errors")) {
                boolean[] first = { true };
                SheetReader.read(csv, SpreadsheetFormat.CSV, (number, cells) -> {
                    try {
                        if (first[0]) {
                            writer.header(cells);
                            first[0] = false;
                        } else {
                            writer.row(cells.stream().map(TableValue::text).toList());
                        }
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                    return true;
                });
                writer.finish();
            }
            DownloadFile download = DownloadFile.temporary(xlsx, name, format.mediaType());
            served = true;
            return download;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            deleteQuietly(csv);
            if (!served) {
                deleteQuietly(xlsx);
            }
        }
    }

    private ImportJob visible(CurrentUser user, UUID organizationId, String moduleKey, UUID jobId) {
        ModuleGrant grant = access.require(user, organizationId, moduleKey, RecordAction.CREATE);
        return jobs.find(organizationId, jobId)
                .filter(job -> job.moduleId().equals(grant.module().id()))
                .filter(job -> grant.admin() || job.createdBy().equals(user.id()))
                .orElseThrow(() -> ApiException.notFound("Import job"));
    }

    private static ApiException importInProgress(ModuleSchema module) {
        return ApiException.conflict("import_in_progress",
                "An import into " + module.labelPlural() + " is already queued or running");
    }

    private static List<String> header(Upload file) {
        List<String> headers = new ArrayList<>();
        SheetReader.read(file.path(), file.format(), (number, cells) -> {
            headers.addAll(ColumnMapping.headerRow(cells));
            return false;
        });
        if (headers.isEmpty()) {
            throw ApiException.badRequest("empty_file", "The file has no header row");
        }
        return headers;
    }

    private static List<String> aligned(List<String> cells, int size) {
        List<String> row = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            row.add(i < cells.size() ? cells.get(i) : "");
        }
        return row;
    }

    /** An uploaded spreadsheet on local disk, deleted on close. */
    record Upload(Path path, String name, String declaredType, SpreadsheetFormat format) implements AutoCloseable {

        @Override
        public void close() {
            deleteQuietly(path);
        }
    }

    private Upload spool(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw ApiException.badRequest("empty_file", "The file is empty");
        }
        if (upload.getSize() > properties.maxFileSize().toBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                    "Files must not exceed " + properties.maxFileSize().toMegabytes() + " MB");
        }
        Path path = temporaryFile();
        try {
            try (InputStream content = upload.getInputStream()) {
                Files.copy(content, path, StandardCopyOption.REPLACE_EXISTING);
            }
            String name = upload.getOriginalFilename() == null ? "import" : upload.getOriginalFilename();
            SpreadsheetFormat format = ContentSniffer.detect(path, name, upload.getContentType())
                    .flatMap(SpreadsheetFormat::ofContentType)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_file_type",
                            "Upload an XLSX workbook or a UTF-8 CSV file"));
            return new Upload(path, name, upload.getContentType(), format);
        } catch (IOException ex) {
            deleteQuietly(path);
            throw new UncheckedIOException(ex);
        } catch (RuntimeException ex) {
            deleteQuietly(path);
            throw ex;
        }
    }

    static Path temporaryFile() {
        try {
            return Files.createTempFile("bm4a-dataexchange-", ".tmp");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // temporary directory cleanup removes it eventually
        }
    }
}
