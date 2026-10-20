package com.bm4a.platform.modules.workflow.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.records.RecordWriter;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.workflow.WorkflowDefinition;
import com.bm4a.platform.modules.workflow.WorkflowDefinition.Transition;
import com.bm4a.platform.modules.workflow.WorkflowTransition;
import com.bm4a.platform.modules.workflow.Workflows;
import com.bm4a.platform.modules.workflow.internal.PendingTransitions.Request;
import com.bm4a.platform.modules.workflow.internal.WorkflowDefinitionValidator.Document;
import com.bm4a.platform.modules.workflow.internal.WorkflowRepository.DefinitionRow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Workflow definitions (versioned with If-Match, audited) and explicit transitions. A transition is a JSON Merge
 * Patch of the workflow field through the record engine; the {@link WorkflowGuard} decides whether it is allowed and
 * writes the history entry, with the comment handed over through {@link PendingTransitions}.
 */
@Service
public class WorkflowService implements Workflows {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);
    static final int MAX_BULK = 500;
    static final int MAX_COMMENT = 2000;

    private final WorkflowRepository repository;
    private final WorkflowDefinitionValidator validator;
    private final ModuleSchemas schemas;
    private final ModuleAccess access;
    private final RecordReader reader;
    private final RecordWriter writer;
    private final RecordOperations operations;
    private final AuditLog audit;
    private final JsonColumns json;
    private final TransactionTemplate perRecord;
    private final Clock clock;

    WorkflowService(WorkflowRepository repository, WorkflowDefinitionValidator validator, ModuleSchemas schemas,
            ModuleAccess access, RecordReader reader, RecordWriter writer, RecordOperations operations, AuditLog audit,
            JsonColumns json, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.validator = validator;
        this.schemas = schemas;
        this.access = access;
        this.reader = reader;
        this.writer = writer;
        this.operations = operations;
        this.audit = audit;
        this.json = json;
        this.perRecord = new TransactionTemplate(transactionManager);
        this.perRecord.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Result of {@code PUT .../workflow}. */
    public record Saved(WorkflowDefinition definition, boolean created) {
    }

    /** Transitions the caller may perform on a record right now. */
    public record Available(UUID recordId, long version, String field, String currentState,
            List<Transition> transitions) {
    }

    /** Per-record outcome of a bulk transition; {@code version} on success, {@code error} on failure. */
    public record BulkResult(UUID recordId, boolean ok, Long version, BulkError error) {
    }

    public record BulkError(String code, String message) {
    }

    // ---- definitions ---------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowDefinition> find(UUID organizationId, String moduleKey) {
        return schemas.find(organizationId, moduleKey).flatMap(module -> definition(organizationId, module));
    }

    @Transactional(readOnly = true)
    public WorkflowDefinition get(TenantContext tenant, String moduleKey) {
        return definition(tenant.organizationId(), module(tenant.organizationId(), moduleKey))
                .orElseThrow(() -> ApiException.notFound("Workflow"));
    }

    /**
     * Creates the module's workflow ({@code expectedVersion} must be {@code null}) or replaces it (the current
     * version is required: missing 428, stale 412).
     */
    @Transactional
    public Saved put(TenantContext tenant, String moduleKey, Long expectedVersion, String fieldKey,
            JsonNode definition) {
        ModuleSchema module = module(tenant.organizationId(), moduleKey);
        Optional<DefinitionRow> current = repository.find(tenant.organizationId(), module.id(), true);
        if (current.isEmpty()) {
            if (expectedVersion != null) {
                throw ApiException.versionConflict(0);
            }
            return new Saved(insert(tenant.organizationId(), tenant.userId(), module, fieldKey, definition), true);
        }
        DefinitionRow row = current.get();
        if (expectedVersion == null) {
            throw ApiException.preconditionRequired();
        }
        if (row.version() != expectedVersion) {
            throw ApiException.versionConflict(row.version());
        }
        ObjectNode canonical = canonical(validator.validate(module, fieldKey, definition));
        if (row.fieldKey().equals(fieldKey) && row.definition().equals(canonical)) {
            return new Saved(view(module, row), false);
        }
        DefinitionRow updated = new DefinitionRow(row.id(), row.organizationId(), row.moduleId(), fieldKey, canonical,
                row.version() + 1, row.createdAt(), row.createdBy(), SqlValues.now(clock), tenant.userId());
        repository.update(updated);
        audit.record(AuditEntry.of(tenant.organizationId(), tenant.userId(), "workflow.updated", "workflow", row.id())
                .with("module", module.apiKey()).with("field", fieldKey).with("version", updated.version()));
        return new Saved(view(module, updated), false);
    }

    /** Removes the definition; the transition history of records is kept. */
    @Transactional
    public void delete(TenantContext tenant, String moduleKey, long expectedVersion) {
        ModuleSchema module = module(tenant.organizationId(), moduleKey);
        DefinitionRow row = repository.find(tenant.organizationId(), module.id(), true)
                .orElseThrow(() -> ApiException.notFound("Workflow"));
        if (row.version() != expectedVersion) {
            throw ApiException.versionConflict(row.version());
        }
        repository.delete(tenant.organizationId(), row.id());
        audit.record(AuditEntry.of(tenant.organizationId(), tenant.userId(), "workflow.deleted", "workflow", row.id())
                .with("module", module.apiKey()));
    }

    @Override
    @Transactional
    public WorkflowDefinition create(UUID organizationId, UUID actorUserId, String moduleKey, String fieldKey,
            JsonNode definition) {
        ModuleSchema module = module(organizationId, moduleKey);
        if (repository.find(organizationId, module.id(), true).isPresent()) {
            throw workflowExists(moduleKey);
        }
        return insert(organizationId, actorUserId, module, fieldKey, definition);
    }

    /** The workflow of a module, used on every record write. */
    public Optional<WorkflowDefinition> definition(UUID organizationId, ModuleSchema module) {
        return repository.find(organizationId, module.id(), false).map(row -> view(module, row));
    }

    // ---- transitions ---------------------------------------------------------------------------------------

    @Override
    @Transactional
    public TransitionOutcome transition(TenantContext tenant, String moduleKey, UUID recordId, String to,
            String comment) {
        return transition(null, tenant, module(tenant.organizationId(), moduleKey), recordId, null, to, comment);
    }

    @Override
    @Transactional
    public TransitionOutcome transition(ModuleGrant grant, UUID recordId, Long expectedVersion, String to,
            String comment) {
        return transition(grant, grant.tenant(), grant.module(), recordId, expectedVersion, to, comment);
    }

    /**
     * One transaction per record, so that one failure does not undo the others. The grant's record scope applies to
     * every record (records outside it fail with {@code not_found}).
     */
    public List<BulkResult> bulk(ModuleGrant grant, List<UUID> recordIds, String to, String comment) {
        if (recordIds == null || recordIds.isEmpty() || recordIds.size() > MAX_BULK
                || recordIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw ApiException.validation("recordIds", "size", "recordIds must contain 1-" + MAX_BULK + " record IDs");
        }
        WorkflowDefinition definition = definition(grant.tenant().organizationId(), grant.module())
                .orElseThrow(() -> ApiException.notFound("Workflow"));
        requireState(definition, to);
        List<BulkResult> results = new ArrayList<>();
        for (UUID recordId : new LinkedHashSet<>(recordIds)) {
            try {
                TransitionOutcome outcome = perRecord.execute(status -> transition(grant, grant.tenant(),
                        grant.module(), recordId, null, to, comment));
                results.add(new BulkResult(recordId, true, outcome.record().version(), null));
            } catch (ApiException ex) {
                results.add(new BulkResult(recordId, false, null, new BulkError(ex.code(), ex.getMessage())));
            } catch (RuntimeException ex) {
                log.error("Bulk transition of record {} failed", recordId, ex);
                results.add(new BulkResult(recordId, false, null,
                        new BulkError("internal_error", "An unexpected error occurred")));
            }
        }
        return results;
    }

    /**
     * Transitions the holder of {@code readGrant} may perform now: requires update access to the record (scope
     * included) and to the workflow field, and a role listed on the transition (admins: every transition).
     */
    @Transactional(readOnly = true)
    public Available available(ModuleGrant readGrant, UUID recordId) {
        WorkflowDefinition definition = definition(readGrant.tenant().organizationId(), readGrant.module())
                .orElseThrow(() -> ApiException.notFound("Workflow"));
        RecordView record = reader.findActive(readGrant, recordId).orElseThrow(() -> ApiException.notFound("Record"));
        String field = definition.fieldKey();
        if (readGrant.fields().hidden(field)) {
            throw fieldHidden();
        }
        String current = WorkflowGuard.text(record.data().get(field));
        List<Transition> transitions = access.find(readGrant.tenant(), readGrant.module().apiKey(), RecordAction.UPDATE)
                .filter(update -> !update.fields().hidden(field) && !update.fields().readOnly(field))
                .filter(update -> reader.findActive(update, recordId).isPresent())
                .map(update -> definition.transitionsFrom(current).stream()
                        .filter(transition -> update.admin() || transition.permits(update.roleKey()))
                        .toList())
                .orElse(List.of());
        return new Available(recordId, record.version(), field, current, transitions);
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkflowTransition> history(ModuleGrant grant, UUID recordId, PageQuery page) {
        UUID org = grant.tenant().organizationId();
        reader.findActive(grant, recordId).orElseThrow(() -> ApiException.notFound("Record"));
        definition(org, grant.module()).ifPresent(definition -> {
            if (grant.fields().hidden(definition.fieldKey())) {
                throw fieldHidden();
            }
        });
        return PageResponse.of(repository.transitions(org, recordId, page.offset(), page.size()), page,
                repository.countTransitions(org, recordId));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** {@code grant} {@code null} = trusted (record engine without scope, field policy or role checks). */
    private TransitionOutcome transition(ModuleGrant grant, TenantContext tenant, ModuleSchema module, UUID recordId,
            Long expectedVersion, String to, String comment) {
        WorkflowDefinition definition = definition(tenant.organizationId(), module)
                .orElseThrow(() -> ApiException.notFound("Workflow"));
        requireState(definition, to);
        String note = comment == null || comment.isBlank() ? null : comment.strip();
        if (note != null && note.length() > MAX_COMMENT) {
            throw ApiException.validation("comment", "size", "comment must be at most " + MAX_COMMENT + " characters");
        }
        RecordView current = (grant == null ? operations.get(tenant, module.apiKey(), recordId)
                : reader.findActive(grant, recordId)).orElseThrow(() -> ApiException.notFound("Record"));
        String from = WorkflowGuard.text(current.data().get(definition.fieldKey()));
        if (to.equals(from)) {
            throw ApiException.conflict(WorkflowGuard.TRANSITION_NOT_ALLOWED, "Record is already in state '" + to + "'");
        }
        ObjectNode patch = json.newObject().put(definition.fieldKey(), to);
        Request request = PendingTransitions.request(note);
        RecordView updated = PendingTransitions.with(recordId, request, () -> grant == null
                ? operations.patch(tenant, module.apiKey(), recordId, expectedVersion, patch)
                : writer.patch(grant, recordId, expectedVersion, patch));
        WorkflowTransition entry = request.recorded()
                .orElseThrow(() -> new IllegalStateException("workflow guard did not record the transition"));
        return new TransitionOutcome(updated, entry);
    }

    private WorkflowDefinition insert(UUID organizationId, UUID actor, ModuleSchema module, String fieldKey,
            JsonNode definition) {
        ObjectNode canonical = canonical(validator.validate(module, fieldKey, definition));
        Instant now = SqlValues.now(clock);
        DefinitionRow row = new DefinitionRow(UUID.randomUUID(), organizationId, module.id(), fieldKey, canonical, 1,
                now, actor, now, actor);
        try {
            repository.insert(row);
        } catch (DuplicateKeyException ex) {
            throw workflowExists(module.apiKey());
        }
        audit.record(AuditEntry.of(organizationId, actor, "workflow.created", "workflow", row.id())
                .with("module", module.apiKey()).with("field", fieldKey));
        return view(module, row);
    }

    private static void requireState(WorkflowDefinition definition, String to) {
        if (to == null || definition.state(to).isEmpty()) {
            throw ApiException.validation("to", "unknown_state", "to must be one of the workflow's states");
        }
    }

    private ModuleSchema module(UUID organizationId, String moduleKey) {
        return schemas.find(organizationId, moduleKey).orElseThrow(() -> ApiException.notFound("Module"));
    }

    private ObjectNode canonical(Document document) {
        return json.mapper().valueToTree(document);
    }

    private WorkflowDefinition view(ModuleSchema module, DefinitionRow row) {
        Document document = json.mapper().treeToValue(row.definition(), Document.class);
        return new WorkflowDefinition(row.id(), row.organizationId(), row.moduleId(), module.apiKey(), row.fieldKey(),
                document.initial(), document.states(), document.transitions(), row.version(), row.createdAt(),
                row.createdBy(), row.updatedAt(), row.updatedBy());
    }

    private static ApiException workflowExists(String moduleKey) {
        return ApiException.conflict("workflow_exists", "Module '" + moduleKey + "' already has a workflow");
    }

    private static ApiException fieldHidden() {
        return ApiException.forbidden("field_hidden", "The workflow field is not accessible");
    }
}
