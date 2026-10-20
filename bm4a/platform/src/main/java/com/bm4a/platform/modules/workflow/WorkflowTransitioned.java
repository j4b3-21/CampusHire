package com.bm4a.platform.modules.workflow;

import java.util.UUID;

/**
 * A record moved from one workflow state to another. Published inside the transaction of the record change (not for
 * the initial state of new records, which {@code RecordEvent.Created} covers); {@code @ApplicationModuleListener}s
 * receive it after commit.
 */
public record WorkflowTransitioned(UUID organizationId, UUID moduleId, String moduleKey, UUID recordId,
        String fromState, String toState, UUID actorUserId, String comment, String transitionKey) {
}
