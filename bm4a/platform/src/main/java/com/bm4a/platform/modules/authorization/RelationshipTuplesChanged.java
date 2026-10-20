package com.bm4a.platform.modules.authorization;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Published inside the business transaction that changed the source of truth (for example a membership row).
 * The authorization module applies it to OpenFGA after commit. The event is persisted by the Spring Modulith event
 * publication registry, so failed applications are retried.
 */
public record RelationshipTuplesChanged(List<RelationshipTuple> writes, List<RelationshipTuple> deletes) {

    public RelationshipTuplesChanged {
        writes = List.copyOf(writes);
        deletes = List.copyOf(deletes);
    }

    public static RelationshipTuplesChanged write(RelationshipTuple tuple) {
        return new RelationshipTuplesChanged(List.of(tuple), List.of());
    }

    public static RelationshipTuplesChanged delete(RelationshipTuple tuple) {
        return new RelationshipTuplesChanged(List.of(), List.of(tuple));
    }

    public static RelationshipTuplesChanged replace(RelationshipTuple oldTuple, RelationshipTuple newTuple) {
        return new RelationshipTuplesChanged(List.of(newTuple), List.of(oldTuple));
    }

    /** Moves from the {@code before} tuple set to {@code after}: writes what is new, deletes what is gone. */
    public static RelationshipTuplesChanged diff(Set<RelationshipTuple> before, Set<RelationshipTuple> after) {
        Set<RelationshipTuple> writes = new LinkedHashSet<>(after);
        writes.removeAll(before);
        Set<RelationshipTuple> deletes = new LinkedHashSet<>(before);
        deletes.removeAll(after);
        return new RelationshipTuplesChanged(List.copyOf(writes), List.copyOf(deletes));
    }

    public boolean hasChanges() {
        return !writes.isEmpty() || !deletes.isEmpty();
    }
}
