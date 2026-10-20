package com.bm4a.platform.modules.dataexchange.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.access.AccessSummary;
import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldConfig.SelectOption;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.FieldValues;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordScopes;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.users.CurrentUser;

import tools.jackson.databind.JsonNode;

/**
 * Exports the records of a module that the caller can read, with the record list's filters, sort and search, as
 * XLSX or CSV. Columns are the visible fields the caller may see (labels as headers, so an export can be imported
 * again) plus record ID and timestamps. Records are read in pages of {@value #PAGE_SIZE} from one snapshot and
 * written to a temporary file, so memory stays flat and a failure never produces a truncated download.
 * <p>
 * Values are human-readable: select options as labels, lookups as the target record's identifying value (its first
 * unique, else searchable, text field) when the caller can read that record, else its ID; date-times in the
 * configured time zone.
 */
@Service
public class ExportService {

    static final int PAGE_SIZE = 1000;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final Set<FieldType> LABEL_TYPES = EnumSet.of(FieldType.TEXT, FieldType.EMAIL, FieldType.PHONE,
            FieldType.URL, FieldType.INTEGER);

    private final ModuleAccess access;
    private final RecordReader records;
    private final AuditLog audit;
    private final DataExchangeProperties properties;
    private final Clock clock;

    ExportService(ModuleAccess access, RecordReader records, AuditLog audit, DataExchangeProperties properties,
            Clock clock) {
        this.access = access;
        this.records = records;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    public DownloadFile export(CurrentUser user, UUID organizationId, String moduleKey, SpreadsheetFormat format,
            Map<String, List<String>> parameters, String sort, String q) {
        ModuleGrant grant = access.require(user, organizationId, moduleKey, RecordAction.READ);
        ModuleSchema module = grant.module();
        List<FieldSchema> fields = FieldValues.ordered(module.fields()).stream()
                .filter(field -> field.visible() && !grant.fields().hidden(field.apiKey()))
                .toList();
        List<String> names = new ArrayList<>(ColumnMapping.headers(fields).values());
        names.addAll(List.of("Record ID", "Created at", "Updated at"));
        LookupLabels labels = new LookupLabels(user, grant, fields);
        ZoneId zone = properties.timeZone();
        Path target = ImportService.temporaryFile();
        int[] count = { 0 };
        boolean served = false;
        try {
            try (TableWriter writer = TableWriter.create(format, target, module.labelPlural())) {
                writer.header(names);
                records.forEachActive(grant, parameters, sort, q, PAGE_SIZE, page -> {
                    Map<UUID, String> lookupLabels = labels.resolve(page);
                    try {
                        for (RecordView record : page) {
                            writer.row(row(record, fields, lookupLabels, zone));
                            count[0]++;
                        }
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
                writer.finish();
            }
            audit.record(AuditEntry.of(organizationId, user.id(), "records.exported", "module", module.id())
                    .with("module", module.apiKey())
                    .with("format", format.name())
                    .with("rows", count[0]));
            String name = module.apiKey() + "-" + FILE_STAMP.format(Instant.now(clock).atZone(zone)) + "."
                    + format.extension();
            DownloadFile download = DownloadFile.temporary(target, name, format.mediaType());
            served = true;
            return download;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            if (!served) {
                ImportService.deleteQuietly(target);
            }
        }
    }

    private static List<TableValue> row(RecordView record, List<FieldSchema> fields, Map<UUID, String> lookups,
            ZoneId zone) {
        List<TableValue> values = new ArrayList<>(fields.size() + 3);
        for (FieldSchema field : fields) {
            JsonNode value = record.data().get(field.apiKey());
            values.add(value == null || value.isNull() ? TableValue.EMPTY : value(field, value, lookups, zone));
        }
        values.add(TableValue.text(record.id().toString()));
        values.add(new TableValue.DateTime(LocalDateTime.ofInstant(record.createdAt(), zone)));
        values.add(new TableValue.DateTime(LocalDateTime.ofInstant(record.updatedAt(), zone)));
        return values;
    }

    private static TableValue value(FieldSchema field, JsonNode value, Map<UUID, String> lookups, ZoneId zone) {
        return switch (field.type()) {
            case TEXT, LONG_TEXT, EMAIL, PHONE, URL -> TableValue.text(value.asString());
            case INTEGER, DECIMAL -> new TableValue.Number(new BigDecimal(value.asString()));
            case CURRENCY -> new TableValue.Money(new BigDecimal(value.get("amount").asString()),
                    value.get("currency").asString());
            case BOOLEAN -> new TableValue.Bool(value.asBoolean());
            case DATE -> new TableValue.Date(LocalDate.parse(value.asString()));
            case DATETIME -> new TableValue.DateTime(LocalDateTime.ofInstant(Instant.parse(value.asString()), zone));
            case SINGLE_SELECT -> TableValue.text(optionLabel(field, value.asString()));
            case MULTI_SELECT -> {
                List<String> labels = new ArrayList<>();
                for (JsonNode item : value.values()) {
                    labels.add(optionLabel(field, item.asString()));
                }
                yield TableValue.text(String.join(", ", labels));
            }
            case LOOKUP -> TableValue.text(lookups.getOrDefault(uuid(value.asString()), value.asString()));
            case FILE_REFERENCE, IMAGE_REFERENCE -> TableValue.text(value.path("fileId").asString());
            case JSON -> TableValue.text(value.toString());
        };
    }

    private static String optionLabel(FieldSchema field, String value) {
        FieldConfig config = field.config() == null ? FieldConfig.empty() : field.config();
        return config.optionsOrEmpty().stream().filter(option -> option.value().equals(value)).findFirst()
                .map(SelectOption::label).filter(label -> !label.isBlank()).orElse(value);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Identifying values of lookup targets, limited to target modules the caller can read (decided once per export
     * from the role documents, then with the target's grant) and to records within the caller's scope there.
     */
    private final class LookupLabels {

        private record Target(ModuleSchema module, ModuleGrant grant, FieldSchema label) {
        }

        private final UUID organizationId;
        private final Map<String, List<FieldSchema>> fieldsByTarget = new HashMap<>();
        private final Map<String, Target> targets = new HashMap<>();

        LookupLabels(CurrentUser user, ModuleGrant grant, List<FieldSchema> fields) {
            this.organizationId = grant.tenant().organizationId();
            for (FieldSchema field : fields) {
                if (field.type() == FieldType.LOOKUP && field.config() != null
                        && field.config().targetModuleKey() != null) {
                    fieldsByTarget.computeIfAbsent(field.config().targetModuleKey(), key -> new ArrayList<>())
                            .add(field);
                }
            }
            if (fieldsByTarget.isEmpty()) {
                return;
            }
            AccessSummary summary = access.describe(user, organizationId);
            for (String key : fieldsByTarget.keySet()) {
                if (!summary.modules().containsKey(key)) {
                    continue;
                }
                try {
                    ModuleGrant target = key.equals(grant.module().apiKey()) ? grant
                            : access.require(grant.tenant(), key, RecordAction.READ);
                    labelField(target).ifPresent(label -> targets.put(key, new Target(target.module(), target, label)));
                } catch (ApiException ex) {
                    if (ex.status().is5xxServerError()) {
                        throw ex;
                    }
                }
            }
        }

        private static Optional<FieldSchema> labelField(ModuleGrant grant) {
            List<FieldSchema> candidates = FieldValues.ordered(grant.module().fields()).stream()
                    .filter(field -> field.visible() && !grant.fields().hidden(field.apiKey())).toList();
            return candidates.stream().filter(field -> field.unique() && LABEL_TYPES.contains(field.type()))
                    .findFirst()
                    .or(() -> candidates.stream()
                            .filter(field -> field.searchable() && field.type() == FieldType.TEXT).findFirst())
                    .or(() -> candidates.stream().filter(field -> field.type() == FieldType.TEXT).findFirst());
        }

        Map<UUID, String> resolve(List<RecordView> page) {
            if (targets.isEmpty()) {
                return Map.of();
            }
            Set<UUID> ids = new HashSet<>();
            for (Map.Entry<String, List<FieldSchema>> entry : fieldsByTarget.entrySet()) {
                if (!targets.containsKey(entry.getKey())) {
                    continue;
                }
                for (RecordView record : page) {
                    for (FieldSchema field : entry.getValue()) {
                        JsonNode value = record.data().get(field.apiKey());
                        UUID id = value == null ? null : uuid(value.asString());
                        if (id != null) {
                            ids.add(id);
                        }
                    }
                }
            }
            Map<UUID, String> labels = new HashMap<>();
            Map<UUID, Target> byModule = new HashMap<>();
            targets.values().forEach(target -> byModule.put(target.module().id(), target));
            for (RecordView referenced : records.findActive(organizationId, ids)) {
                Target target = byModule.get(referenced.moduleId());
                if (target == null || !RecordScopes.matches(target.grant().scope(), target.module(), referenced.id(),
                        referenced.ownerUserId(), referenced.data())) {
                    continue;
                }
                JsonNode label = referenced.data().get(target.label().apiKey());
                if (label != null && !label.isNull()) {
                    labels.put(referenced.id(), label.asString());
                }
            }
            return labels;
        }
    }
}
