package com.bm4a.platform.modules.workflow.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.bm4a.platform.modules.workflow.WorkflowTransition;

/**
 * Explicit transition requests (with their comment) on their way through the record engine to the workflow guard.
 * The record write SPI carries no request context, so the request is bound to the current transaction's thread for
 * exactly the duration of the record write; the guard reads it and reports the history entry it wrote.
 */
final class PendingTransitions {

    private static final Object RESOURCE_KEY = new Object();

    private PendingTransitions() {
    }

    static final class Request {

        private final String comment;
        private WorkflowTransition recorded;

        private Request(String comment) {
            this.comment = comment;
        }

        String comment() {
            return comment;
        }

        Optional<WorkflowTransition> recorded() {
            return Optional.ofNullable(recorded);
        }

        void recorded(WorkflowTransition transition) {
            this.recorded = transition;
        }
    }

    /** Runs {@code write} (which must run in the caller's transaction) with a pending request for {@code recordId}. */
    static <T> T with(UUID recordId, Request request, Supplier<T> write) {
        @SuppressWarnings("unchecked")
        Map<UUID, Request> pending = (Map<UUID, Request>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        boolean bound = pending == null;
        if (bound) {
            pending = new HashMap<>();
            TransactionSynchronizationManager.bindResource(RESOURCE_KEY, pending);
        }
        pending.put(recordId, request);
        try {
            return write.get();
        } finally {
            pending.remove(recordId);
            if (bound) {
                TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
            }
        }
    }

    static Request request(String comment) {
        return new Request(comment);
    }

    static Optional<Request> find(UUID recordId) {
        @SuppressWarnings("unchecked")
        Map<UUID, Request> pending = (Map<UUID, Request>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        return pending == null ? Optional.empty() : Optional.ofNullable(pending.get(recordId));
    }
}
