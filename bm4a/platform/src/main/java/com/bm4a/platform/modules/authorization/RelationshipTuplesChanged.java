package com.bm4a.platform.modules.authorization;

import java.util.List;

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
}
