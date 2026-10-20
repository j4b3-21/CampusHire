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
import java.util.function.Consumer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.access.FieldPolicy;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.FieldValues;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.FileReferenceValidator;
import com.bm4a.platform.modules.records.RecordData;
import com.bm4a.platform.modules.records.RecordDeletionGuard;
import com.bm4a.platform.modules.records.RecordEvent;
import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordScopes;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.records.RecordWrite;
import com.bm4a.platform.modules.records.RecordWriteGuard;
import com.bm4a.platform.modules.records.RecordWriter;
import com.bm4a.platform.modules.records.internal.RecordPayloadValidator.Mode;
import com.bm4a.platform.modules.records.internal.RecordPayloadValidator.Result;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.UserDirectory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The generic record lifecycle. API operations take the {@link ModuleGrant} issued by {@code ModuleAccess}: the grant's
 * record scope is a bound SQL predicate on every lookup (records outside it behave exactly like missing ones, 404),
 * and its field policy filters what is read and written. Trusted operations ({@link RecordOperations}) run with an
 * unrestricted grant. Each change increments the record version, writes a history snapshot and an audit event, and
 * publishes a {@link RecordEvent}, all in one transaction.
 */
@Service
public class RecordService implements RecordReader, RecordOperations, RecordWriter {

    private static final int AUDIT_VALUE_LIMIT = 256;
    private static final String SCOPE_PARAM_PREFIX = "s";

    private final RecordRepository repository;
    private final ModuleSchemas schemas;
    private final RecordPayloadValidator validator;
    private final ObjectProvider<RecordDeletionGuard> deletionGuards;
    private final ObjectProvider<RecordWriteGuard> writeGuards;
    private final ObjectProvider<FileReferenceValidator> fileValidators;
    private final UserDirectory users;
    private final AuditLog audit;
    private final ApplicationEventPublisher events;
    private final JsonColumns json;
    private final Clock clock;

    RecordService(RecordRepository repository, ModuleSchemas schemas, RecordPayloadValidator validator,
            ObjectProvider<RecordDeletionGuard> deletionGuards, ObjectProvider<RecordWriteGuard> writeGuards,
            ObjectProvider<FileReferenceValidator> fileValidators, UserDirectory users, AuditLog audit,
            ApplicationEventPublisher events, JsonColumns json, Clock clock) {
        this.repository = repository;
        this.schemas = schemas;
        this.validator = validator;
        this.deletionGuards = deletionGuards;
        this.writeGuards = writeGuards;
        this.fileValidators = fileValidators;
        this.users = users;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    // ---- record API (authorized by ModuleAccess) -----------------------------------------------------------

    @Transactional
    public RecordView create(ModuleGrant grant, JsonNode data) {
        return create(grant, data, grant.tenant().userId(), false);
    }

    @Transactional(readOnly = true)
    public RecordView get(ModuleGrant grant, UUID id) {
        return findActive(grant, id).orElseThrow(() -> ApiException.notFound("Record"));
    }

    @Transactional(readOnly = true)
    public PageResponse<RecordView> list(ModuleGrant grant, Map<String, List<String>> parameters, String sort,
            String search, boolean deleted, PageQuery page) {
        RecordQuery query = RecordQuery.build(grant.tenant().organizationId(), grant.module(), parameters, sort, search,
                deleted, grant.fields(), scope(grant));
        return PageResponse.of(repository.list(query, page.offset(), page.size()), page, repository.count(query));
    }

    @Transactional
    public RecordView replace(ModuleGrant grant, UUID id, long expectedVersion, JsonNode data) {
        return update(grant, id, expectedVersion, data, Mode.REPLACE, "UPDATED", false);
    }

    @Override
    @Transactional
    public RecordView patch(ModuleGrant grant, UUID id, Long expectedVersion, JsonNode data) {
        return update(grant, id, expectedVersion, data, Mode.MERGE, "UPDATED", false);
    }

    @Transactional
    public RecordView delete(ModuleGrant grant, UUID id, long expectedVersion) {
        return softDelete(grant, id, expectedVersion);
    }

    @Transactional
    public RecordView restore(ModuleGrant grant, UUID id, long expectedVersion) {
        TenantContext tenant = grant.tenant();
        ModuleSchema module = writable(grant);
        RecordView current = repository.find(tenant.organizationId(), module.id(), id, scope(grant), true)
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
                tenant.userId(), null, null, current.ownerUserId());
        repository.update(restored);
        repository.insertVersion(restored, "RESTORED", now, tenant.userId());
        audit.record(entry(tenant, "record.restored", module, restored));
        events.publishEvent(new RecordEvent.Restored(tenant.organizationId(), module.id(), id, restored.version(),
                tenant.userId()));
        return restored;
    }

    /** History of a record within the grant's scope, including soft-deleted records. */
    @Transactional(readOnly = true)
    public PageResponse<RecordVersion> versions(ModuleGrant grant, UUID id, PageQuery page) {
        UUID org = grant.tenant().organizationId();
        repository.find(org, grant.module().id(), id, scope(grant), false)
                .orElseThrow(() -> ApiException.notFound("Record"));
        return PageResponse.of(repository.versions(org, id, page.offset(), page.size()), page,
                repository.countVersions(org, id));
    }

    @Transactional(readOnly = true)
    public RecordVersion version(ModuleGrant grant, UUID id, long version) {
        UUID org = grant.tenant().organizationId();
        repository.find(org, grant.module().id(), id, scope(grant), false)
                .orElseThrow(() -> ApiException.notFound("Record"));
        return repository.version(org, id, version).orElseThrow(() -> ApiException.notFound("Record version"));
    }

    /**
     * Restores the field values of an earlier version as a new version. The old values are re-validated against the
     * current definitions; values of fields archived since then, or hidden from the caller, are kept as they are now.
     */
    @Transactional
    public RecordView revert(ModuleGrant grant, UUID id, long targetVersion, long expectedVersion) {
        UUID org = grant.tenant().organizationId();
        repository.find(org, grant.module().id(), id, scope(grant), false)
                .filter(record -> !record.deleted())
                .orElseThrow(() -> ApiException.notFound("Record"));
        RecordVersion old = repository.version(org, id, targetVersion)
                .orElseThrow(() -> ApiException.notFound("Record version"));
        ObjectNode payload = RecordData.visible(grant.module(), old.data(), grant.fields());
        return update(grant, id, expectedVersion, payload, Mode.REPLACE, "REVERTED", false);
    }

    // ---- RecordReader --------------------------------------------------------------------------------------

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

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordView> findActive(ModuleGrant grant, UUID recordId) {
        return repository.find(grant.tenant().organizationId(), grant.module().id(), recordId, scope(grant), false)
                .filter(record -> !record.deleted());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean anyActiveContaining(ModuleGrant grant, Collection<ObjectNode> documents) {
        if (documents.isEmpty()) {
            return false;
        }
        List<String> containments = documents.stream().map(json::write).toList();
        return repository.existsActiveContaining(grant.tenant().organizationId(), grant.module().id(), scope(grant),
                containments);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void forEachActive(ModuleGrant grant, Map<String, List<String>> parameters, String sort, String q,
            int pageSize, Consumer<List<RecordView>> pages) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        RecordQuery query = RecordQuery.build(grant.tenant().organizationId(), grant.module(),
                parameters == null ? Map.of() : parameters, sort, q, false, grant.fields(), scope(grant));
        for (long offset = 0;; offset += pageSize) {
            List<RecordView> page = repository.list(query, offset, pageSize);
            if (!page.isEmpty()) {
                pages.accept(page);
            }
            if (page.size() < pageSize) {
                return;
            }
        }
    }

    // ---- RecordOperations (trusted, no permission checks) --------------------------------------------------

    @Override
    @Transactional
    public RecordView create(TenantContext tenant, String moduleKey, JsonNode data, UUID ownerUserId) {
        UUID owner = ownerUserId == null ? tenant.userId() : activeUser(ownerUserId);
        return create(trusted(tenant, moduleKey, RecordAction.CREATE), data, owner, true);
    }

    @Override
    @Transactional
    public RecordView patch(TenantContext tenant, String moduleKey, UUID id, Long expectedVersion, JsonNode data) {
        return update(trusted(tenant, moduleKey, RecordAction.UPDATE), id, expectedVersion, data, Mode.MERGE,
                "UPDATED", true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordView> get(TenantContext tenant, String moduleKey, UUID id) {
        return findActive(trusted(tenant, moduleKey, RecordAction.READ), id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordView> findActiveByField(UUID organizationId, String moduleKey, String fieldKey,
            JsonNode value) {
        ModuleSchema module = schemas.find(organizationId, moduleKey).orElseThrow(() -> ApiException.notFound("Module"));
        FieldSchema field = module.field(fieldKey).filter(FieldSchema::visible)
                .orElseThrow(() -> ApiException.validation("fieldKey", "unknown_field", "unknown field " + fieldKey));
        FieldValues.Outcome outcome = FieldValues.canonicalize(field, value, value);
        if (!outcome.valid()) {
            throw ApiException.validation("value", outcome.code(), outcome.message());
        }
        if (!outcome.hasValue()) {
            return Optional.empty();
        }
        return repository.findFirstActiveContaining(organizationId, module.id(),
                RecordRepository.containment(field.apiKey(), outcome.value(), json));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordView> findActiveByUniqueValue(UUID organizationId, String moduleKey, String fieldKey,
            JsonNode value) {
        ModuleSchema module = schemas.find(organizationId, moduleKey).orElseThrow(() -> ApiException.notFound("Module"));
        FieldSchema field = module.field(fieldKey).filter(FieldSchema::visible)
                .orElseThrow(() -> ApiException.validation("fieldKey", "unknown_field", "unknown field " + fieldKey));
        if (!field.unique()) {
            throw ApiException.badRequest("field_not_unique", "Field '" + fieldKey + "' is not unique");
        }
        FieldValues.Outcome outcome = FieldValues.canonicalize(field, value, value);
        if (!outcome.valid()) {
            throw ApiException.validation("value", outcome.code(), outcome.message());
        }
        if (!outcome.hasValue()) {
            return Optional.empty();
        }
        return repository.findActiveByUniqueValue(organizationId, module.id(), field.id(),
                FieldValues.normalizedForUniqueness(field.type(), outcome.value()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RecordView> list(TenantContext tenant, String moduleKey, Map<String, List<String>> filters,
            String sort, String q, RecordScope scope, PageQuery page) {
        ModuleSchema module = module(tenant, moduleKey);
        ModuleGrant grant = new ModuleGrant(tenant, module, RecordAction.READ, scope == null ? RecordScope.all() : scope,
                FieldPolicy.unrestricted(), null);
        return list(grant, filters == null ? Map.of() : filters, sort, q, false, page);
    }

    @Override
    @Transactional
    public RecordView transferOwner(TenantContext tenant, String moduleKey, UUID id, UUID newOwnerUserId) {
        ModuleGrant grant = trusted(tenant, moduleKey, RecordAction.UPDATE);
        ModuleSchema module = writable(grant);
        UUID owner = activeUser(Objects.requireNonNull(newOwnerUserId, "newOwnerUserId"));
        RecordView current = lockActive(grant, id, null);
        if (owner.equals(current.ownerUserId())) {
            return current;
        }
        Instant now = SqlValues.now(clock);
        RecordView updated = new RecordView(id, current.organizationId(), current.moduleId(), current.data(),
                current.version() + 1, current.definitionVersion(), current.createdAt(), current.createdBy(), now,
                tenant.userId(), null, null, owner);
        repository.update(updated);
        repository.insertVersion(updated, "UPDATED", now, tenant.userId());
        audit.record(entry(tenant, "record.owner_transferred", module, updated)
                .with("from", Objects.toString(current.ownerUserId(), null)).with("to", owner.toString()));
        events.publishEvent(new RecordEvent.Updated(tenant.organizationId(), module.id(), id, updated.version(),
                tenant.userId(), List.of()));
        return updated;
    }

    @Override
    @Transactional
    public RecordView delete(TenantContext tenant, String moduleKey, UUID id, Long expectedVersion) {
        return softDelete(trusted(tenant, moduleKey, RecordAction.DELETE), id, expectedVersion);
    }

    // ---- internals -----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ModuleSchema module(TenantContext tenant, String moduleKey) {
        return schemas.find(tenant.organizationId(), moduleKey).orElseThrow(() -> ApiException.notFound("Module"));
    }

    private ModuleGrant trusted(TenantContext tenant, String moduleKey, RecordAction action) {
        return ModuleGrant.trusted(tenant, module(tenant, moduleKey), action);
    }

    private RecordView create(ModuleGrant grant, JsonNode data, UUID owner, boolean trusted) {
        TenantContext tenant = grant.tenant();
        ModuleSchema module = writable(grant);
        Result result = validator.apply(module, data, null, Mode.CREATE, grant.fields());
        UUID id = UUID.randomUUID();
        guard(grant, id, null, result, trusted);
        Instant now = SqlValues.now(clock);
        RecordView record = new RecordView(id, tenant.organizationId(), module.id(), result.data(), 1,
                module.version(), now, tenant.userId(), now, tenant.userId(), null, null, owner);
        validateLookups(tenant, module, result.data(), result.changedFields());
        validateFileReferences(tenant, module, result.data(), result.changedFields());
        repository.insert(record);
        claimUniqueValues(tenant, module, record.id(), json.newObject(), result.data(), result.changedFields());
        repository.insertVersion(record, "CREATED", now, tenant.userId());
        audit.record(entry(tenant, "record.created", module, record).with("fields", result.changedFields()));
        events.publishEvent(new RecordEvent.Created(tenant.organizationId(), module.id(), id, 1, tenant.userId()));
        return record;
    }

    /** {@code expectedVersion} {@code null} skips the concurrency check (trusted operations only). */
    private RecordView update(ModuleGrant grant, UUID id, Long expectedVersion, JsonNode data, Mode mode,
            String changeType, boolean trusted) {
        TenantContext tenant = grant.tenant();
        ModuleSchema module = writable(grant);
        RecordView current = lockActive(grant, id, expectedVersion);
        Result result = validator.apply(module, data, current.data(), mode, grant.fields());
        if (result.changedFields().isEmpty()) {
            return current;
        }
        if (!RecordScopes.matches(grant.scope(), module, id, current.ownerUserId(), result.data())) {
            throw ApiException.forbidden("scope_violation",
                    "The change would move the record outside the records you may update");
        }
        guard(grant, id, current, result, trusted);
        validateLookups(tenant, module, result.data(), result.changedFields());
        validateFileReferences(tenant, module, result.data(), result.changedFields());
        claimUniqueValues(tenant, module, id, current.data(), result.data(), result.changedFields());
        Instant now = SqlValues.now(clock);
        RecordView updated = new RecordView(id, current.organizationId(), current.moduleId(), result.data(),
                current.version() + 1, module.version(), current.createdAt(), current.createdBy(), now,
                tenant.userId(), null, null, current.ownerUserId());
        repository.update(updated);
        repository.insertVersion(updated, changeType, now, tenant.userId());
        audit.record(entry(tenant, "REVERTED".equals(changeType) ? "record.reverted" : "record.updated", module, updated)
                .with("changes", auditChanges(module, current.data(), result.data(), result.changedFields())));
        events.publishEvent(new RecordEvent.Updated(tenant.organizationId(), module.id(), id, updated.version(),
                tenant.userId(), result.changedFields()));
        return updated;
    }

    private RecordView softDelete(ModuleGrant grant, UUID id, Long expectedVersion) {
        TenantContext tenant = grant.tenant();
        ModuleSchema module = writable(grant);
        RecordView current = lockActive(grant, id, expectedVersion);
        rejectInboundRestrictingLookups(tenant, module, current);
        deletionGuards.orderedStream().forEach(guard -> guard.beforeDelete(tenant, module, current));
        repository.releaseAllUnique(tenant.organizationId(), id);
        Instant now = SqlValues.now(clock);
        RecordView deleted = new RecordView(id, current.organizationId(), current.moduleId(), current.data(),
                current.version() + 1, current.definitionVersion(), current.createdAt(), current.createdBy(), now,
                tenant.userId(), now, tenant.userId(), current.ownerUserId());
        repository.update(deleted);
        repository.insertVersion(deleted, "DELETED", now, tenant.userId());
        audit.record(entry(tenant, "record.deleted", module, deleted));
        events.publishEvent(new RecordEvent.Deleted(tenant.organizationId(), module.id(), id, deleted.version(),
                tenant.userId()));
        return deleted;
    }

    private void guard(ModuleGrant grant, UUID id, RecordView before, Result result, boolean trusted) {
        RecordWrite write = new RecordWrite(grant.tenant(), grant.module(), id, before, result.data(),
                result.changedFields(), grant.roleKey(), grant.admin(), trusted);
        writeGuards.orderedStream().forEach(guard -> guard.beforeWrite(write));
    }

    private RecordScopes.Predicate scope(ModuleGrant grant) {
        return RecordScopes.predicate(grant.scope(), grant.module(), "r", SCOPE_PARAM_PREFIX);
    }

    private static ModuleSchema writable(ModuleGrant grant) {
        if (!grant.module().active()) {
            throw ApiException.conflict("module_archived", "Module is archived; its records are read-only");
        }
        return grant.module();
    }

    private RecordView lockActive(ModuleGrant grant, UUID id, Long expectedVersion) {
        RecordView current = repository.find(grant.tenant().organizationId(), grant.module().id(), id, scope(grant),
                true)
                .filter(record -> !record.deleted())
                .orElseThrow(() -> ApiException.notFound("Record"));
        if (expectedVersion != null) {
            checkVersion(current, expectedVersion);
        }
        return current;
    }

    private static void checkVersion(RecordView current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw ApiException.versionConflict(current.version());
        }
    }

    private UUID activeUser(UUID userId) {
        return users.findActive(userId).map(user -> user.id())
                .orElseThrow(() -> ApiException.validation("ownerUserId", "unknown_user", "no active user with this ID"));
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

    private void validateFileReferences(TenantContext tenant, ModuleSchema module, ObjectNode data,
            List<String> changed) {
        FileReferenceValidator files = fileValidators.getIfAvailable();
        if (files == null) {
            return;
        }
        List<FieldViolation> violations = new ArrayList<>();
        for (String key : changed) {
            FieldSchema field = module.field(key).orElse(null);
            if (field == null || !data.has(key)
                    || (field.type() != FieldType.FILE_REFERENCE && field.type() != FieldType.IMAGE_REFERENCE)) {
                continue;
            }
            files.validate(tenant.organizationId(), data.get(key).get("fileId").asString(),
                    field.type() == FieldType.IMAGE_REFERENCE)
                    .ifPresent(rejection -> violations.add(
                            new FieldViolation("data." + key, rejection.code(), rejection.message())));
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
                        JsonNodeFactory.instance.stringNode(record.id().toString()), json);
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
