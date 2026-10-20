package com.bm4a.platform.modules.workflow;

import java.util.Optional;
import java.util.UUID;

import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.tenancy.TenantContext;

import tools.jackson.databind.JsonNode;

/**
 * Workflows for application code. Transitions run through the record engine (validation, history, audit,
 * {@code RecordEvent}s) and the workflow guard; the history entry carries the comment. Failures are
 * {@code ApiException}s: 404 (no workflow, unknown record), 400 {@code validation_failed} (unknown target state),
 * 409 {@code transition_not_allowed} / {@code comment_required}.
 */
public interface Workflows {

    /** The workflow of a module visible to the organization. */
    Optional<WorkflowDefinition> find(UUID organizationId, String moduleKey);

    /**
     * Trusted creation (no permission check, {@code actorUserId} is audited). The definition is validated exactly
     * like {@code PUT .../workflow}; 409 {@code workflow_exists} when the module already has one.
     */
    WorkflowDefinition create(UUID organizationId, UUID actorUserId, String moduleKey, String fieldKey,
            JsonNode definition);

    /**
     * Trusted transition: must follow a defined transition, but the transition's roles are not checked (the caller
     * authorized the operation). Actor = {@code tenant.userId()}.
     */
    TransitionOutcome transition(TenantContext tenant, String moduleKey, UUID recordId, String to, String comment);

    /**
     * Transition on behalf of the holder of an {@code UPDATE} grant: the grant's record scope and field policy apply
     * and the transition's roles must include the holder's org role (organization admins may use every transition).
     *
     * @param expectedVersion optimistic concurrency check; {@code null} skips it
     */
    TransitionOutcome transition(ModuleGrant grant, UUID recordId, Long expectedVersion, String to, String comment);

    record TransitionOutcome(RecordView record, WorkflowTransition transition) {
    }
}
