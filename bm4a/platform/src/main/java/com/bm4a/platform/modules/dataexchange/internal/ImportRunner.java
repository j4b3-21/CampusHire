package com.bm4a.platform.modules.dataexchange.internal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.access.FieldPolicy;
import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.dataexchange.internal.CellConverter.ConversionException;
import com.bm4a.platform.modules.dataexchange.internal.ColumnMapping.Column;
import com.bm4a.platform.modules.files.StoredFileView;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.FieldValues;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.records.RecordScopes;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes one claimed import job.
 * <ol>
 * <li>Re-authorizes the requesting user (CREATE, and UPDATE for {@code UPSERT}) and re-validates the mapping against
 * the current field definitions and field policies.</li>
 * <li>Counts the data rows (limit {@code maxRows}), then streams the file again, converting each row with
 * {@link CellConverter}. Rows that fail conversion, repeat a unique value of an earlier row, or have no values are
 * reported without reaching the database.</li>
 * <li>Writes rows in batches of {@code batchSize}, each batch in one transaction through {@link RecordOperations},
 * which applies the record API's validation, uniqueness, lookups, history and audit. When any row of a batch fails,
 * the batch is rolled back and its rows are retried one transaction each, so a failing row never takes others with
 * it and nothing half-written survives. Dry runs execute exactly the same writes and roll every transaction back,
 * which validates against the real data (including uniqueness) without a separate validation path.</li>
 * <li>Records progress (and the heartbeat) after every batch and stops when the job was cancelled.</li>
 * <li>Stores the failed rows (original values plus {@code row} and {@code error}) as the error report.</li>
 * </ol>
 * {@code UPSERT} matches the unique {@code matchField} as uniqueness is enforced (case-insensitive for text) and only
 * within the user's update scope; the match field itself is never rewritten.
 */
@Component
class ImportRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);
    private static final int ERROR_SAMPLE_SIZE = 100;
    private static final Set<FieldType> IDENTIFYING_TYPES = EnumSet.of(FieldType.TEXT, FieldType.EMAIL,
            FieldType.PHONE, FieldType.URL, FieldType.INTEGER);
    private static final Set<FieldType> SEARCHABLE_TYPES = EnumSet.of(FieldType.TEXT, FieldType.EMAIL,
            FieldType.PHONE, FieldType.URL);

    private final ImportJobRepository jobs;
    private final ModuleAccess access;
    private final ModuleSchemas schemas;
    private final RecordOperations records;
    private final com.bm4a.platform.modules.files.Files files;
    private final AuditLog audit;
    private final DataExchangeProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ImportRunner(ImportJobRepository jobs, ModuleAccess access, ModuleSchemas schemas, RecordOperations records,
            com.bm4a.platform.modules.files.Files files, AuditLog audit, DataExchangeProperties properties,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.jobs = jobs;
        this.access = access;
        this.schemas = schemas;
        this.records = records;
        this.files = files;
        this.audit = audit;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** A reason to stop the whole job, shown to the user as the job's error message. */
    private static final class JobFailure extends RuntimeException {

        JobFailure(String message) {
            super(message, null, false, false);
        }
    }

    record RowError(String column, String field, String code, String message) {
    }

    void run(ImportJob job) {
        Path file = null;
        Execution execution = null;
        try {
            Grants grants = authorize(job);
            file = ImportService.temporaryFile();
            try (InputStream content = files.open(job.organizationId(), job.fileId())) {
                Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            }
            int total = countRows(file, job.format());
            execution = new Execution(job, grants, total);
            if (jobs.progress(job.id(), total, 0, 0, 0, 0, now())) {
                Execution current = execution;
                SheetReader.read(file, job.format(), current::row);
                current.flush();
            } else {
                execution.cancelled = true;
            }
            execution.finish(ImportStatus.COMPLETED, null);
        } catch (JobFailure failure) {
            finishFailed(job, execution, failure.getMessage());
        } catch (ApiException ex) {
            finishFailed(job, execution, ex.getMessage());
        } catch (IOException | RuntimeException ex) {
            log.error("Import job {} failed", job.id(), ex);
            finishFailed(job, execution, "The import stopped because of an internal error");
        } finally {
            if (execution != null) {
                execution.close();
            }
            if (file != null) {
                ImportService.deleteQuietly(file);
            }
        }
    }

    private void finishFailed(ImportJob job, Execution execution, String message) {
        try {
            if (execution == null) {
                jobs.fail(job.id(), message, now());
                audit.record(AuditEntry.of(job.organizationId(), job.createdBy(), "import.failed", "import_job",
                        job.id()).with("module", job.moduleKey()));
            } else {
                execution.finish(ImportStatus.FAILED, message);
            }
        } catch (RuntimeException ex) {
            log.error("Could not record the failure of import job {}", job.id(), ex);
        }
    }

    private record Grants(ModuleSchema module, TenantContext tenant, ModuleGrant update, List<FieldPolicy> policies) {
    }

    private Grants authorize(ImportJob job) {
        TenantContext requester = new TenantContext(job.organizationId(), job.createdBy(), MembershipRole.MEMBER);
        ModuleGrant create;
        ModuleGrant update = null;
        try {
            create = access.require(requester, job.moduleKey(), RecordAction.CREATE);
            if (job.mode() == ImportMode.UPSERT) {
                update = access.require(requester, job.moduleKey(), RecordAction.UPDATE);
            }
        } catch (ApiException ex) {
            if (ex.status().is5xxServerError()) {
                throw new JobFailure("Permissions could not be verified (" + ex.getMessage() + "); start the import again");
            }
            throw new JobFailure("The importing user may no longer import into this module");
        }
        ModuleSchema module = create.module();
        if (!module.id().equals(job.moduleId())) {
            throw new JobFailure("The module no longer exists");
        }
        if (!module.active()) {
            throw new JobFailure("The module is archived; its records are read-only");
        }
        List<FieldPolicy> policies = update == null ? List.of(create.fields()) : List.of(create.fields(), update.fields());
        return new Grants(module, create.tenant(), update, policies);
    }

    private int countRows(Path file, SpreadsheetFormat format) {
        int limit = properties.maxRows();
        int[] rows = { -1 };
        SheetReader.read(file, format, (number, cells) -> ++rows[0] <= limit);
        if (rows[0] > limit) {
            throw new JobFailure("The file has more than " + limit + " data rows; split it into smaller files");
        }
        return Math.max(rows[0], 0);
    }

    private java.time.Instant now() {
        return SqlValues.now(clock);
    }

    /** State of one run: the current batch, counters, unique values seen, lookup cache and the error report. */
    private final class Execution implements AutoCloseable {

        private final ImportJob job;
        private final ModuleSchema module;
        private final TenantContext tenant;
        private final ModuleGrant update;
        private final List<FieldPolicy> policies;
        private final int total;
        private final CellConverter converter;
        private final Map<String, Integer> uniqueValues = new HashMap<>();
        private final Map<String, Optional<String>> lookups = new HashMap<>();
        private final Map<String, Optional<ModuleSchema>> targets = new HashMap<>();
        private final ArrayNode sample = JsonNodeFactory.instance.arrayNode();
        private final List<Pending> batch = new ArrayList<>();
        private List<String> headers;
        private List<Column> columns;
        private Map<String, String> headerByField;
        private int processed;
        private int created;
        private int updated;
        private int failed;
        private boolean cancelled;
        private Path errorFile;
        private CSVPrinter errorPrinter;

        /** A row of the current batch; {@code errors} is non-empty when it failed before reaching the database. */
        private record Pending(int rowNumber, List<String> cells, ObjectNode data, List<RowError> errors) {
        }

        private record Written(int created, int updated) {
        }

        Execution(ImportJob job, Grants grants, int total) {
            this.job = job;
            this.module = grants.module();
            this.tenant = grants.tenant();
            this.update = grants.update();
            this.policies = grants.policies();
            this.total = total;
            this.converter = new CellConverter(properties.timeZone(), this::resolveLookup);
        }

        boolean row(int rowNumber, List<String> cells) {
            if (columns == null) {
                header(cells);
                return true;
            }
            processed++;
            batch.add(convert(rowNumber, cells));
            if (batch.size() >= properties.batchSize()) {
                flush();
            }
            return !cancelled;
        }

        private void header(List<String> cells) {
            headers = ColumnMapping.headerRow(cells);
            try {
                columns = ColumnMapping.resolve(module, headers, job.mapping(), job.mode(), job.matchField(),
                        policies);
            } catch (ApiException ex) {
                String details = ex.violations().stream().map(v -> v.field() + ": " + v.message())
                        .collect(Collectors.joining("; "));
                throw new JobFailure("The column mapping is no longer valid" + (details.isEmpty() ? "" : ": " + details));
            }
            headerByField = new HashMap<>();
            columns.forEach(column -> headerByField.put(column.field().apiKey(), column.header()));
        }

        private Pending convert(int rowNumber, List<String> cells) {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            List<RowError> errors = new ArrayList<>();
            for (Column column : columns) {
                String raw = column.index() < cells.size() ? cells.get(column.index()) : "";
                if (raw.isBlank()) {
                    continue;
                }
                try {
                    data.set(column.field().apiKey(), converter.convert(column.field(), raw));
                } catch (ConversionException ex) {
                    errors.add(new RowError(column.header(), column.field().apiKey(), ex.code(), ex.getMessage()));
                }
            }
            if (errors.isEmpty() && data.isEmpty()) {
                errors.add(new RowError(null, null, "empty_row", "the row has no values in the imported columns"));
            }
            if (errors.isEmpty() && job.mode() == ImportMode.UPSERT && !data.has(job.matchField())) {
                errors.add(new RowError(headerByField.get(job.matchField()), job.matchField(), "required",
                        "a value is required to match existing records"));
            }
            if (errors.isEmpty()) {
                duplicates(rowNumber, data, errors);
            }
            return new Pending(rowNumber, cells, data, errors);
        }

        /** Unique values repeated within the file are reported on the later row, with the earlier row number. */
        private void duplicates(int rowNumber, ObjectNode data, List<RowError> errors) {
            for (Column column : columns) {
                FieldSchema field = column.field();
                JsonNode value = data.get(field.apiKey());
                if (!field.unique() || value == null) {
                    continue;
                }
                FieldValues.Outcome outcome = FieldValues.canonicalize(field, value, value);
                if (!outcome.hasValue()) {
                    continue;
                }
                String key = field.apiKey() + '\u0000' + FieldValues.normalizedForUniqueness(field.type(),
                        outcome.value());
                Integer first = uniqueValues.putIfAbsent(key, rowNumber);
                if (first != null) {
                    errors.add(new RowError(column.header(), field.apiKey(), "duplicate_in_file",
                            "the same value is used in row " + first));
                }
            }
        }

        /** Writes the valid rows of the batch, then reports its failed rows in row order and records progress. */
        void flush() {
            List<Pending> rows = List.copyOf(batch);
            batch.clear();
            List<Pending> valid = rows.stream().filter(row -> row.errors().isEmpty()).toList();
            Map<Integer, List<RowError>> writeFailures = new HashMap<>();
            if (!valid.isEmpty()) {
                try {
                    Written written = transactions.execute(status -> {
                        int[] counts = new int[2];
                        valid.forEach(row -> write(row, counts));
                        if (job.dryRun()) {
                            status.setRollbackOnly();
                        }
                        return new Written(counts[0], counts[1]);
                    });
                    created += written.created();
                    updated += written.updated();
                } catch (ApiException | DataIntegrityViolationException batchFailure) {
                    valid.forEach(row -> writeAlone(row)
                            .ifPresent(errors -> writeFailures.put(row.rowNumber(), errors)));
                }
            }
            for (Pending row : rows) {
                List<RowError> errors = row.errors().isEmpty() ? writeFailures.get(row.rowNumber()) : row.errors();
                if (errors != null) {
                    fail(row.rowNumber(), row.cells(), errors);
                }
            }
            if (!jobs.progress(job.id(), total, processed, created, updated, failed, now())) {
                cancelled = true;
            }
        }

        /** @return the row's errors when it could not be written */
        private Optional<List<RowError>> writeAlone(Pending row) {
            try {
                Written written = transactions.execute(status -> {
                    int[] counts = new int[2];
                    write(row, counts);
                    if (job.dryRun()) {
                        status.setRollbackOnly();
                    }
                    return new Written(counts[0], counts[1]);
                });
                created += written.created();
                updated += written.updated();
                return Optional.empty();
            } catch (ApiException ex) {
                return Optional.of(errors(ex));
            } catch (DataIntegrityViolationException ex) {
                return Optional.of(
                        List.of(new RowError(null, null, "conflict", "the row conflicts with existing data")));
            }
        }

        /** @param counts {@code [created, updated]} */
        private void write(Pending row, int[] counts) {
            if (job.mode() == ImportMode.CREATE) {
                records.create(tenant, module.apiKey(), row.data(), null);
                counts[0]++;
                return;
            }
            Optional<RecordView> existing = records.findActiveByUniqueValue(tenant.organizationId(), module.apiKey(),
                    job.matchField(), row.data().get(job.matchField()))
                    .filter(record -> RecordScopes.matches(update.scope(), module, record.id(), record.ownerUserId(),
                            record.data()));
            if (existing.isEmpty()) {
                records.create(tenant, module.apiKey(), row.data(), null);
                counts[0]++;
                return;
            }
            ObjectNode changes = row.data().deepCopy();
            changes.remove(job.matchField());
            RecordView result = records.patch(tenant, module.apiKey(), existing.get().id(), null, changes);
            if (!RecordScopes.matches(update.scope(), module, result.id(), result.ownerUserId(), result.data())) {
                throw ApiException.forbidden("scope_violation",
                        "The change would move the record outside the records you may update");
            }
            counts[1]++;
        }

        private List<RowError> errors(ApiException ex) {
            if (ex.violations().isEmpty()) {
                return List.of(new RowError(null, null, ex.code(), ex.getMessage()));
            }
            List<RowError> errors = new ArrayList<>();
            for (FieldViolation violation : ex.violations()) {
                String field = violation.field() != null && violation.field().startsWith("data.")
                        ? violation.field().substring("data.".length()) : null;
                errors.add(new RowError(field == null ? null : headerByField.get(field), field, violation.code(),
                        violation.message()));
            }
            return errors;
        }

        private void fail(int rowNumber, List<String> cells, List<RowError> errors) {
            failed++;
            for (RowError error : errors) {
                if (sample.size() >= ERROR_SAMPLE_SIZE) {
                    break;
                }
                ObjectNode entry = sample.addObject();
                entry.put("row", rowNumber);
                entry.put("column", error.column());
                entry.put("field", error.field());
                entry.put("code", error.code());
                entry.put("message", error.message());
            }
            String message = errors.stream()
                    .map(error -> error.column() == null ? error.message() : error.column() + ": " + error.message())
                    .collect(Collectors.joining("; "));
            try {
                List<String> values = new ArrayList<>(headers.size() + 2);
                for (int i = 0; i < headers.size(); i++) {
                    values.add(Cells.escape(printable(i < cells.size() ? cells.get(i) : "")));
                }
                values.add(Integer.toString(rowNumber));
                values.add(Cells.escape(printable(message)));
                errorPrinter().printRecord(values);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private CSVPrinter errorPrinter() throws IOException {
            if (errorPrinter == null) {
                errorFile = ImportService.temporaryFile();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(errorFile),
                        StandardCharsets.UTF_8));
                writer.write('\uFEFF');
                errorPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT);
                List<String> header = new ArrayList<>(headers.stream().map(this::printable).map(Cells::escape).toList());
                header.add("row");
                header.add("error");
                errorPrinter.printRecord(header);
            }
            return errorPrinter;
        }

        /** Control characters would make the report unreadable as text; line breaks and tabs are kept. */
        private String printable(String value) {
            StringBuilder clean = new StringBuilder(value.length());
            value.codePoints().map(c -> (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F ? ' ' : c)
                    .forEach(clean::appendCodePoint);
            return clean.toString();
        }

        void finish(ImportStatus status, String message) {
            String errorMessage = message;
            UUID report = null;
            if (errorPrinter != null) {
                try {
                    errorPrinter.close();
                    errorPrinter = null;
                    try (InputStream content = Files.newInputStream(errorFile)) {
                        StoredFileView stored = files.store(tenant, job.moduleKey() + "-import-errors.csv", "text/csv",
                                content);
                        report = stored.id();
                    }
                } catch (ApiException ex) {
                    errorMessage = (message == null ? "" : message + "; ") + "the error report could not be saved ("
                            + ex.getMessage() + ")";
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            jobs.finish(job.id(), status, processed, created, updated, failed, report, sample, errorMessage, now());
            if (cancelled) {
                return;
            }
            audit.record(AuditEntry.of(job.organizationId(), job.createdBy(),
                    status == ImportStatus.COMPLETED ? "import.completed" : "import.failed", "import_job", job.id())
                    .with("module", job.moduleKey())
                    .with("dryRun", job.dryRun())
                    .with("processed", processed)
                    .with("created", created)
                    .with("updated", updated)
                    .with("failed", failed));
        }

        @Override
        public void close() {
            if (errorPrinter != null) {
                try {
                    errorPrinter.close();
                } catch (IOException ignored) {
                    // the file is deleted next
                }
            }
            if (errorFile != null) {
                ImportService.deleteQuietly(errorFile);
            }
        }

        // ---- lookups ---------------------------------------------------------------------------------------

        /**
         * A record ID is used as is (the record engine checks it); other values are matched against the target
         * module's unique text, e-mail, phone, URL and integer fields (as uniqueness compares them), then exactly
         * (ignoring case) against its searchable text fields. Results are cached for the job, except for lookups
         * into the imported module itself, whose records change during the run.
         */
        private String resolveLookup(FieldSchema field, String value) throws ConversionException {
            String targetKey = field.config() == null ? null : field.config().targetModuleKey();
            try {
                return UUID.fromString(value).toString();
            } catch (IllegalArgumentException notAnId) {
                // match by value below
            }
            boolean cacheable = !module.apiKey().equals(targetKey);
            String cacheKey = targetKey + '\u0000' + value.toLowerCase(Locale.ROOT);
            Optional<String> id = cacheable ? lookups.get(cacheKey) : null;
            if (id == null) {
                id = findTarget(targetKey, value);
                if (cacheable) {
                    lookups.put(cacheKey, id);
                }
            }
            ModuleSchema target = target(targetKey).orElse(null);
            return id.orElseThrow(() -> new ConversionException("invalid_reference", "no "
                    + (target == null ? "record" : target.labelSingular()) + " matches '" + value + "'"));
        }

        private Optional<String> findTarget(String targetKey, String value) throws ConversionException {
            ModuleSchema target = target(targetKey).orElse(null);
            if (target == null) {
                return Optional.empty();
            }
            for (FieldSchema candidate : FieldValues.ordered(target.fields())) {
                if (!candidate.visible() || !candidate.unique() || !IDENTIFYING_TYPES.contains(candidate.type())) {
                    continue;
                }
                JsonNode node = candidate.type() == FieldType.INTEGER ? integer(value)
                        : JsonNodeFactory.instance.stringNode(value);
                if (node == null) {
                    continue;
                }
                try {
                    Optional<RecordView> match = records.findActiveByUniqueValue(tenant.organizationId(), targetKey,
                            candidate.apiKey(), node);
                    if (match.isPresent()) {
                        return Optional.of(match.get().id().toString());
                    }
                } catch (ApiException invalidForField) {
                    // the value cannot be a value of this field
                }
            }
            List<FieldSchema> searchable = target.fields().stream()
                    .filter(f -> f.visible() && f.searchable() && SEARCHABLE_TYPES.contains(f.type())).toList();
            if (searchable.isEmpty()) {
                return Optional.empty();
            }
            List<RecordView> candidates;
            try {
                candidates = records.list(tenant, targetKey, Map.of(), null, value, RecordScope.all(),
                        PageQuery.of(0, PageQuery.MAX_SIZE)).items();
            } catch (ApiException invalidSearch) {
                return Optional.empty();
            }
            List<String> exact = candidates.stream()
                    .filter(record -> searchable.stream().anyMatch(f -> record.data().has(f.apiKey())
                            && record.data().get(f.apiKey()).asString().strip().equalsIgnoreCase(value)))
                    .map(record -> record.id().toString())
                    .toList();
            if (exact.size() > 1) {
                throw new ConversionException("lookup_ambiguous",
                        "'" + value + "' matches several " + target.labelPlural() + "; use the record ID");
            }
            return exact.stream().findFirst();
        }

        private Optional<ModuleSchema> target(String key) {
            if (key == null) {
                return Optional.empty();
            }
            return targets.computeIfAbsent(key, k -> schemas.find(tenant.organizationId(), k));
        }

        private static JsonNode integer(String value) {
            try {
                return JsonNodeFactory.instance.numberNode(Long.parseLong(value.strip()));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}
