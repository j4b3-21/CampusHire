package com.bm4a.platform.modules.workflow.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.records.RecordData;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.web.ETags;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;
import com.bm4a.platform.modules.workflow.WorkflowDefinition;
import com.bm4a.platform.modules.workflow.WorkflowDefinition.Category;
import com.bm4a.platform.modules.workflow.WorkflowDefinition.State;
import com.bm4a.platform.modules.workflow.WorkflowDefinition.Transition;
import com.bm4a.platform.modules.workflow.WorkflowTransition;
import com.bm4a.platform.modules.workflow.Workflows.TransitionOutcome;
import com.bm4a.platform.modules.workflow.internal.WorkflowService;
import com.bm4a.platform.modules.workflow.internal.WorkflowService.Available;
import com.bm4a.platform.modules.workflow.internal.WorkflowService.BulkResult;
import com.bm4a.platform.modules.workflow.internal.WorkflowService.Saved;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * Workflow definitions (members read, {@code METADATA_MANAGE} changes) and record transitions, authorized per module
 * and record scope by {@link ModuleAccess} (history and available transitions: read; transitions: update).
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/modules/{moduleKey}")
class WorkflowController {

    private final WorkflowService workflows;
    private final TenantAccess tenants;
    private final ModuleAccess modules;

    WorkflowController(WorkflowService workflows, TenantAccess tenants, ModuleAccess modules) {
        this.workflows = workflows;
        this.tenants = tenants;
        this.modules = modules;
    }

    @GetMapping("/workflow")
    ResponseEntity<WorkflowResponse> get(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey) {
        TenantContext tenant = tenants.require(user, orgId, Permission.ORGANIZATION_VIEW);
        return respond(workflows.get(tenant, moduleKey));
    }

    /** Creates (no If-Match, 201) or replaces (If-Match with the current version) the module's workflow. */
    @PutMapping("/workflow")
    ResponseEntity<WorkflowResponse> put(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody PutWorkflowRequest request) {
        TenantContext tenant = tenants.require(user, orgId, Permission.METADATA_MANAGE);
        Long expected = ifMatch == null ? null : ETags.requireVersion(ifMatch);
        Saved saved = workflows.put(tenant, moduleKey, expected, request.field(), request.definition());
        if (saved.created()) {
            return ResponseEntity.created(URI.create("/api/v1/orgs/" + orgId + "/modules/" + moduleKey + "/workflow"))
                    .eTag(ETags.of(saved.definition().version()))
                    .body(WorkflowResponse.of(saved.definition()));
        }
        return respond(saved.definition());
    }

    @DeleteMapping("/workflow")
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        TenantContext tenant = tenants.require(user, orgId, Permission.METADATA_MANAGE);
        workflows.delete(tenant, moduleKey, ETags.requireVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }

    /** Newest first. */
    @GetMapping("/records/{recordId}/transitions")
    PageResponse<TransitionResponse> history(CurrentUser user, @PathVariable UUID orgId,
            @PathVariable String moduleKey, @PathVariable UUID recordId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ModuleGrant grant = modules.require(user, orgId, moduleKey, RecordAction.READ);
        return workflows.history(grant, recordId, PageQuery.of(page, size)).map(TransitionResponse::of);
    }

    @GetMapping("/records/{recordId}/transitions/available")
    AvailableResponse available(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID recordId) {
        ModuleGrant grant = modules.require(user, orgId, moduleKey, RecordAction.READ);
        WorkflowDefinition definition = workflows.get(grant.tenant(), moduleKey);
        return AvailableResponse.of(definition, workflows.available(grant, recordId));
    }

    @PostMapping("/records/{recordId}/transitions")
    ResponseEntity<TransitionOutcomeResponse> transition(CurrentUser user, @PathVariable UUID orgId,
            @PathVariable String moduleKey, @PathVariable UUID recordId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody TransitionRequest request) {
        ModuleGrant grant = modules.require(user, orgId, moduleKey, RecordAction.UPDATE);
        long expected = ETags.requireVersion(ifMatch);
        TransitionOutcome outcome = workflows.transition(grant, recordId, expected, request.to(), request.comment());
        return ResponseEntity.ok().eTag(ETags.of(outcome.record().version()))
                .body(new TransitionOutcomeResponse(RecordResponse.of(grant, outcome.record()),
                        TransitionResponse.of(outcome.transition())));
    }

    /** Each record in its own transaction; the response lists every record's outcome (HTTP 200). */
    @PostMapping("/transitions/bulk")
    BulkResponse bulk(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestBody BulkTransitionRequest request) {
        ModuleGrant grant = modules.require(user, orgId, moduleKey, RecordAction.UPDATE);
        List<BulkResult> results = workflows.bulk(grant, request.recordIds(), request.to(), request.comment());
        int succeeded = (int) results.stream().filter(BulkResult::ok).count();
        return new BulkResponse(results.stream().map(BulkItem::of).toList(), succeeded, results.size() - succeeded);
    }

    private static ResponseEntity<WorkflowResponse> respond(WorkflowDefinition definition) {
        return ResponseEntity.ok().eTag(ETags.of(definition.version())).body(WorkflowResponse.of(definition));
    }

    record PutWorkflowRequest(String field, JsonNode definition) {
    }

    record TransitionRequest(String to, String comment) {
    }

    record BulkTransitionRequest(List<UUID> recordIds, String to, String comment) {
    }

    record DefinitionBody(String initial, List<State> states, List<Transition> transitions) {
    }

    record WorkflowResponse(UUID id, String module, String field, long version, DefinitionBody definition,
            Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {

        static WorkflowResponse of(WorkflowDefinition d) {
            return new WorkflowResponse(d.id(), d.moduleKey(), d.fieldKey(), d.version(),
                    new DefinitionBody(d.initial(), d.states(), d.transitions()), d.createdAt(), d.createdBy(),
                    d.updatedAt(), d.updatedBy());
        }
    }

    record TransitionResponse(UUID id, long recordVersion, String fromState, String toState, String transitionKey,
            String comment, UUID actorUserId, Instant createdAt) {

        static TransitionResponse of(WorkflowTransition t) {
            return new TransitionResponse(t.id(), t.recordVersion(), t.fromState(), t.toState(), t.transitionKey(),
                    t.comment(), t.actorUserId(), t.createdAt());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AvailableTransition(String key, String label, String to, String toLabel, Category category, String color,
            boolean requireComment) {
    }

    record AvailableResponse(UUID recordId, long version, String field, String currentState,
            List<AvailableTransition> transitions) {

        static AvailableResponse of(WorkflowDefinition definition, Available available) {
            return new AvailableResponse(available.recordId(), available.version(), available.field(),
                    available.currentState(), available.transitions().stream().map(transition -> {
                        State target = definition.state(transition.to()).orElseThrow();
                        return new AvailableTransition(transition.key(), transition.label(), transition.to(),
                                target.label(), target.category(), target.color(), transition.requireComment());
                    }).toList());
        }
    }

    record RecordResponse(UUID id, String module, long version, JsonNode data, Instant createdAt, UUID createdBy,
            Instant updatedAt, UUID updatedBy, UUID ownerUserId) {

        static RecordResponse of(ModuleGrant grant, RecordView record) {
            return new RecordResponse(record.id(), grant.module().apiKey(), record.version(),
                    RecordData.visible(grant.module(), record.data(), grant.fields()), record.createdAt(),
                    record.createdBy(), record.updatedAt(), record.updatedBy(), record.ownerUserId());
        }
    }

    record TransitionOutcomeResponse(RecordResponse record, TransitionResponse transition) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BulkItem(UUID recordId, boolean ok, Long version, Error error) {

        record Error(String code, String message) {
        }

        static BulkItem of(BulkResult result) {
            return new BulkItem(result.recordId(), result.ok(), result.version(),
                    result.error() == null ? null : new Error(result.error().code(), result.error().message()));
        }
    }

    record BulkResponse(List<BulkItem> results, int succeeded, int failed) {
    }
}
