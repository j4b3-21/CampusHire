package com.bm4a.platform.modules.workflow;

import java.time.Instant;
import java.util.UUID;

/**
 * One history entry: a change of a record's workflow field.
 *
 * @param recordVersion the record version that the change produced
 * @param fromState {@code null} when the record was created (or had no state before)
 * @param transitionKey the definition's transition, {@code null} for the initial state of a new record
 */
public record WorkflowTransition(UUID id, UUID organizationId, UUID moduleId, UUID recordId, long recordVersion,
        String fromState, String toState, String transitionKey, UUID actorUserId, String comment, Instant createdAt) {
}
