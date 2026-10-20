package com.bm4a.platform.modules.workflow.internal;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.records.RecordWrite;
import com.bm4a.platform.modules.records.RecordWriteGuard;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.workflow.WorkflowDefinition;
import com.bm4a.platform.modules.workflow.WorkflowDefinition.Transition;
import com.bm4a.platform.modules.workflow.WorkflowTransition;
import com.bm4a.platform.modules.workflow.WorkflowTransitioned;
import com.bm4a.platform.modules.workflow.internal.PendingTransitions.Request;

import tools.jackson.databind.JsonNode;

/**
 * Enforces workflows on every record write (record API, transitions API, imports, trusted code):
 * <ul>
 * <li>create - the workflow field must hold the initial state (the field's default);</li>
 * <li>update changing the field - from a workflow state, only along a defined transition: organization admins and
 * trusted code may use any, members only those listing their org role or {@code "*"}; a record without a state (or in
 * a state the workflow no longer has) may only be moved to the initial state; the field cannot be cleared;</li>
 * <li>transitions with {@code requireComment} only through an explicit transition request carrying a comment.</li>
 * </ul>
 * Every accepted change writes one history entry; transitions of existing records publish
 * {@link WorkflowTransitioned}. Runs after other guards so that their vetoes come first.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class WorkflowGuard implements RecordWriteGuard {

    static final String TRANSITION_NOT_ALLOWED = "transition_not_allowed";

    private final WorkflowService workflows;
    private final WorkflowRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    WorkflowGuard(WorkflowService workflows, WorkflowRepository repository, ApplicationEventPublisher events,
            Clock clock) {
        this.workflows = workflows;
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public void beforeWrite(RecordWrite write) {
        Optional<WorkflowDefinition> found = workflows.definition(write.tenant().organizationId(), write.module());
        if (found.isEmpty()) {
            return;
        }
        WorkflowDefinition definition = found.get();
        String field = definition.fieldKey();
        String to = text(write.after().get(field));
        Optional<Request> request = PendingTransitions.find(write.recordId());
        if (write.creating()) {
            if (!definition.initial().equals(to)) {
                throw notAllowed("New " + write.module().labelPlural() + " start in state '" + definition.initial()
                        + "'");
            }
            record(write, definition, null, to, null, request);
            return;
        }
        if (!write.changedFields().contains(field)) {
            return;
        }
        String from = text(write.before().data().get(field));
        if (to == null || !definition.hasState(to)) {
            throw notAllowed("'" + field + "' must hold one of the workflow's states");
        }
        if (from == null || !definition.hasState(from)) {
            if (!to.equals(definition.initial())) {
                throw notAllowed("A record without a workflow state can only be moved to the initial state '"
                        + definition.initial() + "'");
            }
            record(write, definition, from, to, null, request);
            return;
        }
        List<Transition> permitted = definition.transitionsFrom(from).stream()
                .filter(transition -> allowed(write, transition))
                .toList();
        List<Transition> matching = permitted.stream().filter(transition -> transition.to().equals(to)).toList();
        if (matching.isEmpty()) {
            throw notAllowed("Moving from '" + from + "' to '" + to + "' is not allowed. " + describe(from, permitted));
        }
        String comment = request.map(Request::comment).orElse(null);
        Transition chosen = comment != null ? matching.getFirst()
                : matching.stream().filter(transition -> !transition.requireComment()).findFirst()
                        .orElseThrow(() -> ApiException.conflict("comment_required", "Transition '"
                                + matching.getFirst().key() + "' requires a comment; perform it through the "
                                + "transitions endpoint with a comment"));
        record(write, definition, from, to, chosen.key(), request);
    }

    private static boolean allowed(RecordWrite write, Transition transition) {
        return write.trusted() || write.admin() || transition.permits(write.actorRoleKey());
    }

    private void record(RecordWrite write, WorkflowDefinition definition, String from, String to,
            String transitionKey, Optional<Request> request) {
        String comment = request.map(Request::comment).orElse(null);
        long version = write.creating() ? 1 : write.before().version() + 1;
        WorkflowTransition entry = new WorkflowTransition(UUID.randomUUID(), write.tenant().organizationId(),
                write.module().id(), write.recordId(), version, from, to, transitionKey, write.tenant().userId(),
                comment, SqlValues.now(clock));
        repository.insertTransition(entry);
        request.ifPresent(pending -> pending.recorded(entry));
        if (!write.creating()) {
            events.publishEvent(new WorkflowTransitioned(entry.organizationId(), entry.moduleId(),
                    write.module().apiKey(), entry.recordId(), from, to, entry.actorUserId(), comment, transitionKey));
        }
    }

    private static String describe(String from, List<Transition> permitted) {
        if (permitted.isEmpty()) {
            return "No transitions from '" + from + "' are available to you.";
        }
        return "Allowed from '" + from + "': " + permitted.stream()
                .map(transition -> transition.key() + " (to '" + transition.to() + "')")
                .collect(Collectors.joining(", ")) + ".";
    }

    private static ApiException notAllowed(String message) {
        return ApiException.conflict(TRANSITION_NOT_ALLOWED, message);
    }

    static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
