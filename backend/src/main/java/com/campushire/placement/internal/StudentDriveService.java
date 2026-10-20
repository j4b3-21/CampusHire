package com.campushire.placement.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordScopes;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.common.CampusMember;
import com.campushire.common.CampusMembers;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;
import com.campushire.placement.PlacementInsights;
import com.campushire.placement.internal.EligibilityService.Verdict;

import tools.jackson.databind.node.ObjectNode;

/** Drives as a student sees them: visible drives with the student's eligibility verdict and application. */
@Service
public class StudentDriveService implements PlacementInsights {

    public enum Filter {
        OPEN, UPCOMING, ALL;

        static Filter parse(String value) {
            if (value == null || value.isBlank()) {
                return OPEN;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw ApiException.validation("filter", "invalid", "filter must be open, upcoming or all");
            }
        }
    }

    public record DriveCompany(UUID id, String name) {
    }

    public record DriveJob(UUID id, String title, String jobType, BigDecimal ctc, String location) {
    }

    public record ApplicationRef(UUID id, String stage) {
    }

    public record StudentDrive(UUID id, String title, String driveStatus, DriveCompany company, DriveJob job,
            Instant registrationOpensAt, Instant registrationClosesAt, LocalDate driveDate, String mode, String venue,
            String description, boolean registrationOpen, Verdict eligibility, ApplicationRef application) {
    }

    private record DriveRow(RecordView drive, BigDecimal jobCtc) {
    }

    private static final String VISIBLE = "'published', 'registration_closed', 'in_progress', 'completed'";

    private final CampusMembers members;
    private final ModuleAccess access;
    private final ModuleSchemas schemas;
    private final RecordReader reader;
    private final EligibilityService eligibility;
    private final PlacementRepository repository;
    private final PlacementSettings settings;
    private final JsonColumns json;

    StudentDriveService(CampusMembers members, ModuleAccess access, ModuleSchemas schemas, RecordReader reader,
            EligibilityService eligibility, PlacementRepository repository, PlacementSettings settings,
            JsonColumns json) {
        this.members = members;
        this.access = access;
        this.schemas = schemas;
        this.reader = reader;
        this.eligibility = eligibility;
        this.repository = repository;
        this.settings = settings;
        this.json = json;
    }

    /**
     * {@code open}: published and registration open now; {@code upcoming}: published, registration not open yet;
     * {@code all}: every drive visible to students. Students linked to a record only.
     */
    public PageResponse<StudentDrive> list(CurrentUser user, UUID organizationId, String filter, PageQuery page) {
        CampusMember member = members.require(user, organizationId);
        UUID studentId = member.requireStudentId();
        ModuleGrant grant = access.require(member.context(), CampusModules.DRIVE, RecordAction.READ);
        Filter parsed = Filter.parse(filter);
        Instant now = settings.now();
        RecordScopes.Predicate scope = RecordScopes.predicate(grant.scope(), grant.module(), "r", "scope_");
        String where = """
                FROM campushire_drive_projection d
                JOIN record r ON r.id = d.drive_id AND r.organization_id = d.organization_id
                    AND r.module_id = :module AND r.deleted_at IS NULL
                WHERE d.organization_id = :org AND NOT d.deleted AND d.status IN (%s) AND %s AND %s
                """.formatted(VISIBLE, scope.sql(), switch (parsed) {
                    case OPEN -> "d.status = 'published' AND (d.registration_opens_at IS NULL"
                            + " OR d.registration_opens_at <= :now) AND d.registration_closes_at > :now";
                    case UPCOMING -> "d.status = 'published' AND d.registration_opens_at > :now";
                    case ALL -> "TRUE";
                });
        String order = parsed == Filter.ALL ? "d.registration_closes_at DESC NULLS LAST, d.drive_id"
                : "d.registration_closes_at ASC NULLS LAST, d.drive_id";
        long total = repository.jdbc().sql("SELECT count(*) " + where)
                .param("org", organizationId).param("module", grant.module().id()).param("now", SqlValues.ts(now))
                .params(scope.params())
                .query(Long.class).single();
        List<DriveRow> rows = repository.jdbc().sql("SELECT r.*, d.job_ctc " + where + " ORDER BY " + order
                + " LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("module", grant.module().id()).param("now", SqlValues.ts(now))
                .params(scope.params()).param("limit", page.size()).param("offset", page.offset())
                .query((rs, n) -> new DriveRow(new RecordView(SqlValues.uuid(rs, "id"),
                        SqlValues.uuid(rs, "organization_id"), SqlValues.uuid(rs, "module_id"),
                        json.readObject(rs.getString("data")), rs.getLong("version"),
                        rs.getLong("definition_version"), SqlValues.instant(rs, "created_at"),
                        SqlValues.uuid(rs, "created_by"), SqlValues.instant(rs, "updated_at"),
                        SqlValues.uuid(rs, "updated_by"), null, null, SqlValues.uuid(rs, "owner_user_id")),
                        rs.getBigDecimal("job_ctc")))
                .list();
        return PageResponse.of(view(organizationId, studentId, rows, now), page, total);
    }

    @Override
    @Transactional(readOnly = true)
    public long eligibleOpenDrives(UUID organizationId, UUID studentId) {
        ModuleSchema students = schemas.find(organizationId, CampusModules.STUDENT).orElse(null);
        if (students == null) {
            return 0;
        }
        StudentFacts facts = eligibility.facts(organizationId, students.id(), studentId);
        PlacementPolicy policy = settings.policy(organizationId);
        Instant now = settings.now();
        List<DriveRow> open = repository.jdbc().sql("""
                SELECT r.*, d.job_ctc
                FROM campushire_drive_projection d
                JOIN record r ON r.id = d.drive_id AND r.organization_id = d.organization_id AND r.deleted_at IS NULL
                WHERE d.organization_id = :org AND NOT d.deleted AND d.status = 'published'
                  AND (d.registration_opens_at IS NULL OR d.registration_opens_at <= :now)
                  AND d.registration_closes_at > :now
                """)
                .param("org", organizationId).param("now", SqlValues.ts(now))
                .query((rs, n) -> new DriveRow(new RecordView(SqlValues.uuid(rs, "id"),
                        SqlValues.uuid(rs, "organization_id"), SqlValues.uuid(rs, "module_id"),
                        json.readObject(rs.getString("data")), rs.getLong("version"),
                        rs.getLong("definition_version"), null, null, null, null, null, null, null),
                        rs.getBigDecimal("job_ctc")))
                .list();
        return open.stream()
                .filter(row -> eligibility.evaluate(row.drive(), policy, facts, row.jobCtc()).eligible())
                .count();
    }

    private List<StudentDrive> view(UUID organizationId, UUID studentId, List<DriveRow> rows, Instant now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        ModuleSchema students = schemas.find(organizationId, CampusModules.STUDENT)
                .orElseThrow(() -> ApiException.notFound("Module"));
        StudentFacts facts = eligibility.facts(organizationId, students.id(), studentId);
        PlacementPolicy policy = settings.policy(organizationId);
        Set<UUID> related = new LinkedHashSet<>();
        for (DriveRow row : rows) {
            related.add(RecordValues.uuid(row.drive().data(), "company"));
            related.add(RecordValues.uuid(row.drive().data(), "job"));
        }
        related.remove(null);
        Map<UUID, UUID> applications = repository.applicationsOfStudent(organizationId, studentId,
                rows.stream().map(row -> row.drive().id()).toList());
        related.addAll(applications.values());
        Map<UUID, RecordView> records = new HashMap<>();
        reader.findActive(organizationId, related).forEach(record -> records.put(record.id(), record));

        List<StudentDrive> result = new ArrayList<>();
        for (DriveRow row : rows) {
            ObjectNode data = row.drive().data();
            RecordView company = records.get(RecordValues.uuid(data, "company"));
            RecordView job = records.get(RecordValues.uuid(data, "job"));
            BigDecimal ctc = job != null ? RecordValues.amount(job.data(), "ctc") : row.jobCtc();
            String status = DriveLifecycleService.currentStatus(row.drive());
            Instant opens = RecordValues.instant(data, "registration_opens_at");
            Instant closes = RecordValues.instant(data, "registration_closes_at");
            boolean open = "published".equals(status) && (opens == null || !opens.isAfter(now))
                    && closes != null && closes.isAfter(now);
            UUID applicationId = applications.get(row.drive().id());
            RecordView application = applicationId == null ? null : records.get(applicationId);
            result.add(new StudentDrive(row.drive().id(), RecordValues.text(data, "title"), status,
                    company == null ? null : new DriveCompany(company.id(), RecordValues.text(company.data(), "name")),
                    job == null ? null : new DriveJob(job.id(), RecordValues.text(job.data(), "title"),
                            RecordValues.text(job.data(), "job_type"), ctc, RecordValues.text(job.data(), "location")),
                    opens, closes, RecordValues.date(data, "drive_date"), RecordValues.text(data, "mode"),
                    RecordValues.text(data, "venue"), RecordValues.text(data, "description"), open,
                    eligibility.evaluate(row.drive(), policy, facts, ctc),
                    application == null ? null
                            : new ApplicationRef(application.id(),
                                    Objects.requireNonNullElse(RecordValues.text(application.data(), "stage"),
                                            "applied"))));
        }
        return result;
    }
}
