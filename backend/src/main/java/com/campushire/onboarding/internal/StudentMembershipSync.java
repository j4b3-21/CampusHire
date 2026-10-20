package com.campushire.onboarding.internal;

import java.util.Map;
import java.util.Objects;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordEvent;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.tenancy.MembershipAdministration;
import com.bm4a.platform.modules.tenancy.MembershipAdministration.MembershipView;
import com.campushire.common.CampusModules;
import com.campushire.common.CampusRoles;

/**
 * Keeps the {@code department} and {@code batch} attributes of a student's membership equal to their student record,
 * so that department-scoped access follows a department change. The member is the record's owner (set on claim).
 */
@Component
class StudentMembershipSync {

    private final ModuleSchemas schemas;
    private final RecordReader reader;
    private final MembershipAdministration memberships;

    StudentMembershipSync(ModuleSchemas schemas, RecordReader reader, MembershipAdministration memberships) {
        this.schemas = schemas;
        this.reader = reader;
        this.memberships = memberships;
    }

    @ApplicationModuleListener
    void on(RecordEvent.Updated event) {
        if (!event.changedFields().contains("department") && !event.changedFields().contains("batch")) {
            return;
        }
        boolean student = schemas.findById(event.organizationId(), event.moduleId()).map(ModuleSchema::apiKey)
                .filter(CampusModules.STUDENT::equals).isPresent();
        if (!student) {
            return;
        }
        RecordView record = reader.findActive(event.organizationId(), event.moduleId(), event.recordId())
                .orElse(null);
        if (record == null || record.ownerUserId() == null) {
            return;
        }
        MembershipView membership = memberships.find(event.organizationId(), record.ownerUserId())
                .filter(MembershipView::active)
                .filter(view -> CampusRoles.STUDENT.equals(view.roleKey()))
                .filter(view -> record.id().toString().equals(view.attributes().get(CampusRoles.ATTR_STUDENT)))
                .orElse(null);
        if (membership == null) {
            return;
        }
        Map<String, Object> attributes = OnboardingService.attributes(membership.attributes(), record);
        if (!Objects.equals(attributes, membership.attributes())) {
            memberships.updateAttributes(event.organizationId(), record.ownerUserId(), attributes,
                    event.actorUserId());
        }
    }
}
