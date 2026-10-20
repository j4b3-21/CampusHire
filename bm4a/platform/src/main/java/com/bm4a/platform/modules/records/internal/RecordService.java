package com.bm4a.platform.modules.records.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.FieldValues;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordData;
import com.bm4a.platform.modules.records.RecordDeletionGuard;
import com.bm4a.platform.modules.records.RecordEvent;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.records.internal.RecordPayloadValidator.Mode;
import com.bm4a.platform.modules.records.internal.RecordPayloadValidator.Result;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The generic record lifecycle. Every operation is scoped by the verified {@link TenantContext} and the module
 * resolved for that tenant; IDs from other tenants or modules behave exactly like missing IDs (404). Each change
 * increments the record version, writes a history snapshot and an audit event, and publishes a {@link RecordEvent},
 * all in one transaction.
 */
@Service
public class RecordService implements RecordReader {

    private static final int AUDIT_VALUE_LIMIT = 256;

    private final RecordRepository repository;
    private final ModuleSchemas schemas;
    private final RecordPayloadValidator validator;
    private final ObjectProvider<RecordDeletionGuard> deletionGuards;
    private final AuditLog audit;
    private final ApplicationEventPublisher events;
    private final JsonColumns json;
    private final Clock clock;

    RecordService(RecordRepository repository, ModuleSchemas schemas, RecordPayloadValidator validator,
            ObjectProvider<RecordDeletionGuard> deletionGuards, AuditLog audit, ApplicationEventPublisher events,
            JsonColumns json, Clock clock) {
        this.repository = repository;
        this.schemas = schemas;
        this.validator = validator;
        this.deletionGuards = deletionGuards;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ModuleSchema module(TenantContext tenant, String moduleKey) {
        return schemas.find(tenant.organizationId(), moduleKey).orElseThrow(() -> ApiException.notFound("Module"));
    }

    @Transactional
    public RecordView create(TenantContext tenant, String moduleKey, JsonNode data) {
        ModuleSchema module = writableModule(tenant, moduleKey);
        Result result = validator.apply(module, data, null, Mode.CREATE);
        UUID id = UUID.randomUUID();
        Instant now = SqlValues.now(clock);
        RecordView record = new RecordView(id, tenant.organizationId(), module.id(), result.data(), 1,
                module.version(), now, tenant.userId(), now, tenant.userId(), null, null);
        validateLookups(tenant, module, result.data(), result.changedFields());
        repository.insert(record);
        claimUniqueValues(tenant, module, record.id(), json.newObject(), result.data(), result.changedFields());
        repository.insertVersion(record, "CREATED", now, tenant.userId());
        audit.record(entry(tenant, "record.created", module, record).with("fields", result.changedFields()));
        events.publishEvent(new RecordEvent.Created(tenant.organizationId(), module.id(), id, 1, tenant.userId()));
        return record;
    }

    @Transactional(readOnly = true)
    public RecordView get(TenantContext tenant, String moduleKey, UUID id) {
        ModuleSchema module = module(tenant, moduleKey);
        return repository.find(tenant.organizationId(), module.id(), id, false)
                .filter(record -> !record.deleted())
                .orElseThrow(() -> ApiException.notFound("Record"));
    }

    @Transactional(readOnly = true)
    public PageResponse<RecordView> list(TenantContext tenant, String moduleKey, Map<String, List<String>> parameters,
            String sort, String search, boolean deleted, PageQuery page) {
        ModuleSchema module = module(tenant, moduleKey);
        RecordQuery query = RecordQuery.build(tenant.organizationId(), module, parameters, sort, search, deleted);
        return PageResponse.of(repository.list(query, page.offset(), page.size()), page, repository.count(query));
    }

    @Transactional
    public RecordView replace(TenantContext tenant, String moduleKey, UUID id, long expectedVersion, JsonNode data) {
        return update(tenant, moduleKey, id, expectedVersion, data, Mode.REPLACE, "UPDATED");
    }

    @Transactional
    public RecordView patch(TenantContext tenant, String moduleKey, UUID id, long expectedVersion, JsonNode data) {
        return update(tenant, moduleKey, id, expectedVersion, data, Mode.MERGE, "UPDATED");
    }

    @Transactional
    public RecordView delete(TenantContext tenant, String moduleKey, UUID id, long expectedVersion) {
        ModuleSchema module = writableModule(tenant, moduleKey);
        RecordView current = lockActive(tenant, module, id, expectedVersion);
        rejectInboundRestrictingLookups(tenant, module, current);
        deletionGuards.orderedStream().forEach(guard -> guard.beforeDelete(tenant, module, current));
        repository.releaseAllUnique(tenant.organizationId(), id);
        Instant now = SqlValues.now(clock);
        RecordView deleted = new RecordView(id, current.organizationId(), current.moduleId(), current.data(),
                current.version() + 1, current.definitionVersion(), current.createdAt(), current.createdBy(), now,
                tenant.userId(), now, tenant.userId());
        repository.update(deleted);
        repository.insertVersion(deleted, "DELETED", now, tenant.userId());
        audit.record(entry(tenant, "record.deleted", module, deleted));
        events.publishEvent(new RecordEvent.Deleted(tenant.organizationId(), module.id(), id, deleted.version(),
                tenant.userId()));
        return deleted;
    }

    @Transactional
    public RecordView restore(TenantContext tenant, String moduleKey, UUID id, long expectedVersion) {
        ModuleSchema module = writableModule(tenant, moduleKey);
        RecordView current = repository.find(tenant.organizationId(), module.id(), id, true)
                .orElseThrow(() -> ApiException.notFound("Record"));
        if (!current.deleted()) {
            throw ApiException.conflict("not_deleted", "Record is not deleted");
        }
        checkVersion(current, expectedVersion);
        claimUniqueValues(tenant, module, id, json.newObject(), current.data(),
                List.copyOf(current.data().propertyNames()));
        Instant now = SqlValues.now(clock);
        RecordView restored = new RecordView(id, current.organizationId(), current.moduleId(), current.data(),
                current.version() + 1, current.definitionVersion(), current.createdAt(), current.createdBy(), now,
                tenant.userId(), null, null);
        repository.update(restored);
        repository.insertVersion(restored, "RESTORED", now, tenant.userId());
        audit.record(entry(tenant, "record.restored", module, restored));
        events.publishEvent(new RecordEvent.Restored(tenant.organizationId(), module.id(), id, restored.version(),
                tenant.userId()));
        return restored;
    }

    @Transactional(readOnly = true)
    public PageResponse<RecordVersion> versions(TenantContext tenant, String moduleKey, UUID id, PageQuery page) {
        ModuleSchema module = module(tenant, moduleKey);
        repository.find(tenant.organizationId(), module.id(), id, false).orElseThrow(() -> ApiException.notFound("Record"));
        return PageResponse.of(repository.versions(tenant.organizationId(), id, page.offset(), page.size()), page,
                repository.countVersions(tenant.organizationId(), id));
    }

    @Transactional(readOnly = true)
    public RecordVersion version(TenantContext tenant, String moduleKey, UUID id, long version) {
        ModuleSchema module = module(tenant, moduleKey);
        repository.find(tenant.organizationId(), module.id(), id, false).orElseThrow(() -> ApiException.notFound("Record"));
        return repository.version(tenant.organizationId(), id, version)
                .orElseThrow(() -> ApiException.notFound("Record version"));
    }

    /**
     * Restores the field values of an earlier version as a new version. The old values are re-validated against the
     * current definitions; values of fields archived since then are kept as they are now.
     */
    @Transactional
    public RecordView revert(TenantContext tenant, String moduleKey, UUID id, long targetVersion,
            long expectedVersion) {
        ModuleSchema module = module(tenant, moduleKey);
        RecordVersion old = repository.version(tenant.organizationId(), id, targetVersion)
                .orElseThrow(() -> ApiException.notFound("Record version"));
        ObjectNode payload = RecordData.visible(module, old.data());
        return update(tenant, moduleKey, id, expectedVersion, payload, Mode.REPLACE, "REVERTED");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordView> findActive(UUID organizationId, UUID moduleId, UUID recordId) {
        return repository.find(organizationId, moduleId, recordId, false).filter(record -> !record.deleted());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecordView> findActive(UUID organizationId, Collection<UUID> recordIds) {
        return repository.findActive(organizationId, recordIds);
    }

    // ---- internals -----------------------------------------------------------------------------------------

    private RecordView update(TenantContext tenant, String moduleKey, UUID id, long expectedVersion, JsonNode data,
            Mode mode, String changeType) {
        ModuleSchema module = writableModule(tenant, moduleKey);
        RecordView current = lockActive(tenant, module, id, expectedVersion);
        Result result = validator.apply(module, data, current.data(), mode);
        if (result.changedFields().isEmpty()) {
            return current;
        }
        validateLookups(tenant, module, result.data(), result.changedFields());
        claimUniqueValues(tenant, module, id, current.data(), result.data(), result.changedFields());
        Instant now = SqlValues.now(clock);
        RecordView updated = new RecordView(id, current.organizationId(), current.moduleId(), result.data(),
                current.version() + 1, module.version(), current.createdAt(), current.createdBy(), now,
                tenant.userId(), null, null);
        repository.update(updated);
        repository.insertVersion(updated, changeType, now, tenant.userId());
        audit.record(entry(tenant, "REVERTED".equals(changeType) ? "record.reverted" : "record.updated", module, updated)
                .with("changes", auditChanges(module, current.data(), result.data(), result.changedFields())));
        events.publishEvent(new RecordEvent.Updated(tenant.organizationId(), module.id(), id, updated.version(),
                tenant.userId(), result.changedFields()));
        return updated;
    }

    private ModuleSchema writableModule(TenantContext tenant, String moduleKey) {
        ModuleSchema module = module(tenant, moduleKey);
        if (!module.active()) {
            throw ApiException.conflict("module_archived", "Module is archived; its records are read-only");
        }
        return module;
    }

    private RecordView lockActive(TenantContext tenant, ModuleSchema module, UUID id, long expectedVersion) {
        RecordView current = repository.find(tenant.organizationId(), module.id(), id, true)
                .filter(record -> !record.deleted())
                .orElseThrow(() -> ApiException.notFound("Record"));
        checkVersion(current, expectedVersion);
        return current;
    }

    private static void checkVersion(RecordView current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw ApiException.versionConflict(current.version());
        }
    }

    /** Lookup values must reference an active record of the target module in the same tenant. */
    private void validateLookups(TenantContext tenant, ModuleSchema module, ObjectNode data, List<String> changed) {
        List<FieldViolation> violations = new ArrayList<>();
        for (String key : changed) {
            FieldSchema field = module.field(key).orElse(null);
            if (field == null || field.type() != FieldType.LOOKUP || !data.has(key)) {
                continue;
            }
            UUID targetId = UUID.fromString(data.get(key).asString());
            boolean exists = schemas.find(tenant.organizationId(), field.config().targetModuleKey())
                    .flatMap(target -> repository.find(tenant.organizationId(), target.id(), targetId, false))
                    .filter(record -> !record.deleted())
                    .isPresent();
            if (!exists) {
                violations.add(new FieldViolation("data." + key, "invalid_reference",
                        "referenced " + field.config().targetModuleKey() + " record does not exist"));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
    }

    private void claimUniqueValues(TenantContext tenant, ModuleSchema module, UUID recordId, ObjectNode before,
            ObjectNode after, List<String> changed) {
        List<FieldViolation> conflicts = new ArrayList<>();
        List<UUID> release = new ArrayList<>();
        for (String key : changed) {
            FieldSchema field = module.field(key).orElse(null);
            if (field != null && field.unique() && before.has(key)) {
                release.add(field.id());
            }
        }
        repository.releaseUnique(tenant.organizationId(), recordId, release);
        for (String key : changed) {
            FieldSchema field = module.field(key).orElse(null);
            if (field == null || !field.unique() || !after.has(key)) {
                continue;
            }
            String normalized = FieldValues.normalizedForUniqueness(field.type(), after.get(key));
            if (!repository.claimUnique(tenant.organizationId(), field.id(), normalized, recordId)) {
                conflicts.add(new FieldViolation("data." + key, "unique", "value is already used by another record"));
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "unique_violation", "Unique field values already exist",
                    conflicts);
        }
    }

    /** Lookup fields with {@code onTargetDelete = RESTRICT} (the default) block deleting referenced records. */
    private void rejectInboundRestrictingLookups(TenantContext tenant, ModuleSchema module, RecordView record) {
        for (ModuleSchema referencing : schemas.referencing(tenant.organizationId(), module.apiKey())) {
            for (FieldSchema field : referencing.fields()) {
                if (field.type() != FieldType.LOOKUP || field.status() == FieldStatus.ARCHIVED
                        || !module.apiKey().equals(field.config().targetModuleKey())
                        || !field.config().restrictsTargetDelete()) {
                    continue;
                }
                String containment = RecordRepository.containment(field.apiKey(),
                        tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(record.id().toString()), json);
                long count = repository.countActiveContaining(tenant.organizationId(), referencing.id(), containment);
                if (referencing.id().equals(module.id()) && record.data().has(field.apiKey())
                        && record.id().toString().equals(record.data().get(field.apiKey()).asString())) {
                    count--;
                }
                if (count > 0) {
                    throw ApiException.conflict("record_referenced", "Record is referenced by " + count + " "
                            + referencing.labelPlural() + " record(s) through '" + field.apiKey() + "'");
                }
            }
        }
    }

    private AuditEntry entry(TenantContext tenant, String action, ModuleSchema module, RecordView record) {
        return AuditEntry.of(tenant.organizationId(), tenant.userId(), action, "record", record.id())
                .with("module", module.apiKey())
                .with("version", record.version());
    }

    /** {@code {field: {from, to}}}; sensitive fields are redacted, long values truncated. */
    private Map<String, Object> auditChanges(ModuleSchema module, ObjectNode before, ObjectNode after,
            List<String> changed) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : changed) {
            boolean sensitive = module.field(key).map(FieldSchema::sensitive).orElse(false);
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", auditValue(before.get(key), sensitive));
            change.put("to", auditValue(after.get(key), sensitive));
            changes.put(key, change);
        }
        return changes;
    }

    private static Object auditValue(JsonNode value, boolean sensitive) {
        if (value == null) {
            return null;
        }
        if (sensitive) {
            return AuditEntry.REDACTED;
        }
        String text = value.isString() ? value.stringValue() : value.toString();
        return text.length() > AUDIT_VALUE_LIMIT ? "[TRUNCATED]" : Objects.requireNonNullElse(text, "");
    }
}
