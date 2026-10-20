package com.campushire.onboarding.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.access.AccessSummary;
import com.bm4a.platform.modules.access.AccessSummary.ModuleCapabilities;
import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.apppack.CampusHirePack;
import com.campushire.common.CampusModules;
import com.campushire.common.CampusRoles;
import com.campushire.common.RecordValues;

import tools.jackson.databind.node.ObjectNode;

/** The caller's CampusHire persona for the web app's navigation and onboarding screens. */
@Service
public class MeService {

    public static final String STATUS_SETUP_REQUIRED = "setup_required";
    public static final String STATUS_STUDENT_NOT_LINKED = "student_not_linked";
    public static final String STATUS_COMPLETE = "complete";

    private final ModuleAccess access;
    private final CampusHirePack pack;
    private final ModuleSchemas schemas;
    private final RecordReader reader;

    MeService(ModuleAccess access, CampusHirePack pack, ModuleSchemas schemas, RecordReader reader) {
        this.access = access;
        this.pack = pack;
        this.schemas = schemas;
        this.reader = reader;
    }

    public record Role(String key, String label) {
    }

    public record StudentSummary(UUID id, String rollNumber, String fullName, String email, UUID departmentId,
            UUID batchId, String program, String placementStatus, boolean profileVerified, long version) {
    }

    /** @param status {@code setup_required}, {@code student_not_linked} or {@code complete} */
    public record Onboarding(boolean packInstalled, Integer packVersion, String status) {
    }

    public record Me(UUID organizationId, MembershipRole membershipRole, boolean admin, Role role,
            StudentSummary student, List<UUID> departmentIds, List<UUID> companyIds,
            Map<String, Object> attributes, Map<String, ModuleCapabilities> capabilities, Onboarding onboarding) {
    }

    public Me me(CurrentUser user, UUID organizationId) {
        AccessSummary summary = access.describe(user, organizationId);
        String roleKey = summary.role() == null ? null : summary.role().key();
        StudentSummary student = CampusRoles.STUDENT.equals(roleKey) ? linkedStudent(summary) : null;
        Integer version = pack.installation(organizationId).map(CampusHirePack.Installation::version).orElse(null);
        String status = version == null ? STATUS_SETUP_REQUIRED
                : CampusRoles.STUDENT.equals(roleKey) && !summary.admin() && student == null
                        ? STATUS_STUDENT_NOT_LINKED
                        : STATUS_COMPLETE;
        return new Me(organizationId, summary.membershipRole(), summary.admin(),
                summary.role() == null ? null : new Role(summary.role().key(), summary.role().label()), student,
                ids(summary, CampusRoles.ATTR_DEPARTMENT), ids(summary, CampusRoles.ATTR_COMPANY),
                summary.attributes(), summary.modules(), new Onboarding(version != null, version, status));
    }

    private StudentSummary linkedStudent(AccessSummary summary) {
        UUID studentId = ids(summary, CampusRoles.ATTR_STUDENT).stream().findFirst().orElse(null);
        if (studentId == null) {
            return null;
        }
        return schemas.find(summary.organizationId(), CampusModules.STUDENT)
                .flatMap(module -> reader.findActive(summary.organizationId(), module.id(), studentId))
                .map(MeService::summary)
                .orElse(null);
    }

    private static StudentSummary summary(RecordView record) {
        ObjectNode data = record.data();
        return new StudentSummary(record.id(), RecordValues.text(data, "roll_number"),
                RecordValues.text(data, "full_name"), RecordValues.text(data, "email"),
                RecordValues.uuid(data, "department"), RecordValues.uuid(data, "batch"),
                RecordValues.text(data, "program"), RecordValues.text(data, "placement_status"),
                RecordValues.bool(data, "profile_verified"), record.version());
    }

    private static List<UUID> ids(AccessSummary summary, String attribute) {
        Object value = summary.attributes().get(attribute);
        List<?> values = value instanceof List<?> list ? list : value == null ? List.of() : List.of(value);
        return values.stream().filter(String.class::isInstance).map(String.class::cast).map(MeService::uuid)
                .filter(Objects::nonNull).toList();
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
