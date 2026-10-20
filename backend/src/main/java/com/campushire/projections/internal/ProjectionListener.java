package com.campushire.projections.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.records.RecordEvent;

/**
 * Keeps the projections current: runs after the record's transaction committed, asynchronously and in its own
 * transaction; the publication stays in the event publication registry until it succeeded and is retried by
 * {@link CampusHireEventResubmission}.
 */
@Component
class ProjectionListener {

    private final Projector projector;

    ProjectionListener(Projector projector) {
        this.projector = projector;
    }

    @ApplicationModuleListener
    void on(RecordEvent event) {
        projector.handle(event);
    }
}
