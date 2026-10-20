package com.campushire.placement.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordEvent;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.workflow.WorkflowTransitioned;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;

/**
 * Notifies students of application stage changes (by anyone but themselves) and of newly scheduled interviews. Runs
 * after commit, asynchronously, retried from the event publication registry.
 */
@Component
class PlacementEventListener {

    private final ModuleSchemas schemas;
    private final RecordReader reader;
    private final PlacementRepository repository;
    private final PlacementNotifications notifications;

    PlacementEventListener(ModuleSchemas schemas, RecordReader reader, PlacementRepository repository,
            PlacementNotifications notifications) {
        this.schemas = schemas;
        this.reader = reader;
        this.repository = repository;
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    void on(WorkflowTransitioned event) {
        if (!CampusModules.APPLICATION.equals(event.moduleKey())) {
            return;
        }
        reader.findActive(event.organizationId(), event.moduleId(), event.recordId()).ifPresent(application -> {
            UUID studentId = RecordValues.uuid(application.data(), "student");
            String driveTitle = title(event.organizationId(), RecordValues.uuid(application.data(), "drive"));
            studentUser(event.organizationId(), studentId)
                    .filter(userId -> !userId.equals(event.actorUserId()))
                    .ifPresent(userId -> notifications.stageChanged(event.organizationId(), application.id(),
                            driveTitle, event.fromState(), event.toState(), userId));
        });
    }

    @ApplicationModuleListener
    void on(RecordEvent.Created event) {
        boolean interview = schemas.findById(event.organizationId(), event.moduleId()).map(ModuleSchema::apiKey)
                .filter(CampusModules.INTERVIEW::equals).isPresent();
        if (!interview) {
            return;
        }
        reader.findActive(event.organizationId(), event.moduleId(), event.recordId()).ifPresent(record ->
                studentUser(event.organizationId(), RecordValues.uuid(record.data(), "student")).ifPresent(userId ->
                        notifications.interviewScheduled(event.organizationId(), record.id(),
                                title(event.organizationId(), RecordValues.uuid(record.data(), "drive")),
                                RecordValues.text(record.data(), "round_name"),
                                RecordValues.instant(record.data(), "scheduled_at"), userId)));
    }

    private Optional<UUID> studentUser(UUID organizationId, UUID studentId) {
        return studentId == null ? Optional.empty()
                : repository.linkedUsers(organizationId, List.of(studentId)).stream().findFirst();
    }

    private String title(UUID organizationId, UUID driveId) {
        if (driveId == null) {
            return null;
        }
        return reader.findActive(organizationId, List.of(driveId)).stream().findFirst()
                .map(RecordView::data).map(data -> RecordValues.text(data, "title")).orElse(null);
    }
}
