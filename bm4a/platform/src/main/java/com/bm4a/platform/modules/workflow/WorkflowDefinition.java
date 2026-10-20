package com.bm4a.platform.modules.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The state machine attached to one {@code SINGLE_SELECT} field of a module.
 *
 * @param fieldKey the workflow field; every state is one of its options
 * @param initial the state new records start in (equal to the field's default value)
 */
public record WorkflowDefinition(UUID id, UUID organizationId, UUID moduleId, String moduleKey, String fieldKey,
        String initial, List<State> states, List<Transition> transitions, long version, Instant createdAt,
        UUID createdBy, Instant updatedAt, UUID updatedBy) {

    public WorkflowDefinition {
        states = List.copyOf(states);
        transitions = List.copyOf(transitions);
    }

    public enum Category {
        OPEN, IN_PROGRESS, WON, LOST
    }

    /** @param color optional presentation hint such as {@code emerald} or {@code #10b981} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record State(String key, String label, Category category, String color) {
    }

    /** @param roles org role keys allowed to perform the transition, or {@code ["*"]} for every member */
    public record Transition(String key, String label, List<String> from, String to, List<String> roles,
            boolean requireComment) {

        public static final String ANY_ROLE = "*";

        public Transition {
            from = List.copyOf(from);
            roles = List.copyOf(roles);
        }

        public boolean leaves(String state) {
            return state != null && from.contains(state);
        }

        /** Whether a member with org role {@code roleKey} ({@code null} for none) may use it. */
        public boolean permits(String roleKey) {
            return roles.contains(ANY_ROLE) || roleKey != null && roles.contains(roleKey);
        }
    }

    public Optional<State> state(String key) {
        return states.stream().filter(state -> state.key().equals(key)).findFirst();
    }

    /** Whether {@code state} is one of the workflow's states. */
    public boolean hasState(String state) {
        return state(state).isPresent();
    }

    /** Transitions that can be taken from {@code state}, in definition order. */
    public List<Transition> transitionsFrom(String state) {
        return transitions.stream().filter(transition -> transition.leaves(state)).toList();
    }
}
