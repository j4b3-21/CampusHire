package com.bm4a.platform.modules.records;

/**
 * Extension point invoked inside the write transaction of every record create, update (PUT and PATCH) and revert,
 * after payload validation and access checks and before anything is stored. Implementations veto a change by throwing
 * an {@code ApiException} (for example 409 for a forbidden workflow transition); no-op updates do not reach guards.
 * Guards must not modify {@link RecordWrite#after()}.
 */
public interface RecordWriteGuard {

    void beforeWrite(RecordWrite write);
}
