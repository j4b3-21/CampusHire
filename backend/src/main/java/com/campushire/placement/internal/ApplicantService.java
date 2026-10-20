package com.campushire.placement.internal;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.files.Files;
import com.bm4a.platform.modules.files.StoredFileView;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordScopes;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.common.CampusMember;
import com.campushire.common.CampusMembers;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;
import com.campushire.placement.internal.EligibilityService.Verdict;

/**
 * Applicants of a drive joined with a snapshot of their student record, for staff and recruiters. The caller's READ
 * scope on applications applies (recruiters: their company; coordinators: their departments) and the drive itself
 * must be readable; contact and personal fields beyond the snapshot (phone, date of birth) are never returned.
 */
@Service
public class ApplicantService {

    private static final Map<String, String> SORTS = Map.of(
            "rollNumber", "s.roll_number",
            "fullName", "s.full_name",
            "cgpa", "s.cgpa",
            "appliedAt", "r.created_at",
            "updatedAt", "r.updated_at",
            "stage", "r.data ->> 'stage'");

    private final CampusMembers members;
    private final ModuleAccess access;
    private final RecordReader reader;
    private final EligibilityService eligibility;
    private final PlacementRepository repository;
    private final PlacementSettings settings;
    private final Files files;
    private final AuditLog audit;

    ApplicantService(CampusMembers members, ModuleAccess access, RecordReader reader, EligibilityService eligibility,
            PlacementRepository repository, PlacementSettings settings, Files files, AuditLog audit) {
        this.members = members;
        this.access = access;
        this.reader = reader;
        this.eligibility = eligibility;
        this.repository = repository;
        this.settings = settings;
        this.files = files;
        this.audit = audit;
    }

    public record Resume(String fileId, String name) {
    }

    public record StudentSnapshot(UUID id, String rollNumber, String fullName, String email, UUID departmentId,
            UUID batchId, String program, BigDecimal cgpa, Integer activeBacklogs, Integer totalBacklogs,
            BigDecimal tenthPercentage, BigDecimal twelfthPercentage, String placementStatus, String skills,
            Resume resume) {
    }

    /**
     * @param eligibleNow the drive's rules evaluated against the student's current record (a warning badge for
     *        applicants whose record changed after applying)
     */
    public record Applicant(UUID applicationId, long version, String stage, Instant appliedAt, Instant updatedAt,
            StudentSnapshot student, Verdict eligibleNow) {
    }

    private record Row(UUID applicationId, long version, String stage, Instant appliedAt, Instant updatedAt,
            StudentSnapshot student) {
    }

    public PageResponse<Applicant> list(CurrentUser user, UUID organizationId, UUID driveId, String stage, String q,
            String sort, PageQuery page) {
        CampusMember member = members.require(user, organizationId);
        if (!member.staff() && !member.recruiter()) {
            throw ApiException.forbidden("access_denied", "Only placement staff and recruiters can see applicants");
        }
        ModuleGrant driveGrant = access.require(member.context(), CampusModules.DRIVE, RecordAction.READ);
        RecordView drive = reader.findActive(driveGrant, driveId).orElseThrow(() -> ApiException.notFound("Drive"));
        ModuleGrant grant = access.require(member.context(), CampusModules.APPLICATION, RecordAction.READ);
        RecordScopes.Predicate scope = RecordScopes.predicate(grant.scope(), grant.module(), "r", "scope_");

        Map<String, Object> params = new HashMap<>(scope.params());
        params.put("org", organizationId);
        params.put("drive", driveId);
        params.put("module", grant.module().id());
        StringBuilder where = new StringBuilder("""
                FROM campushire_application_projection a
                JOIN record r ON r.id = a.application_id AND r.organization_id = a.organization_id
                    AND r.module_id = :module AND r.deleted_at IS NULL
                LEFT JOIN campushire_student_projection s ON s.student_id = a.student_id
                    AND s.organization_id = a.organization_id
                WHERE a.organization_id = :org AND a.drive_id = :drive AND NOT a.deleted AND\s""")
                .append(scope.sql());
        if (stage != null && !stage.isBlank()) {
            where.append(" AND r.data ->> 'stage' = :stage");
            params.put("stage", stage.strip());
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (s.roll_number ILIKE :q ESCAPE '\\' OR s.full_name ILIKE :q ESCAPE '\\'"
                    + " OR s.email ILIKE :q ESCAPE '\\')");
            params.put("q", "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        long total = repository.jdbc().sql("SELECT count(*) " + where).params(params).query(Long.class).single();
        List<Row> rows = repository.jdbc().sql("""
                SELECT a.application_id, r.version, r.data ->> 'stage' AS stage, r.created_at, r.updated_at,
                       a.student_id, s.roll_number, s.full_name, s.email, s.department_id, s.batch_id, s.program,
                       s.cgpa, s.active_backlogs, s.total_backlogs, s.tenth_percentage, s.twelfth_percentage,
                       s.placement_status, s.skills, s.resume_file_id, s.resume_name
                """ + where + " ORDER BY " + orderBy(sort) + ", a.application_id LIMIT :limit OFFSET :offset")
                .params(params).param("limit", page.size()).param("offset", page.offset())
                .query((rs, n) -> {
                    boolean projected = rs.getString("roll_number") != null;
                    String resumeId = rs.getString("resume_file_id");
                    return new Row(SqlValues.uuid(rs, "application_id"), rs.getLong("version"),
                            rs.getString("stage"), SqlValues.instant(rs, "created_at"),
                            SqlValues.instant(rs, "updated_at"),
                            new StudentSnapshot(SqlValues.uuid(rs, "student_id"), rs.getString("roll_number"),
                                    rs.getString("full_name"), rs.getString("email"),
                                    SqlValues.uuid(rs, "department_id"), SqlValues.uuid(rs, "batch_id"),
                                    rs.getString("program"), rs.getBigDecimal("cgpa"),
                                    projected ? rs.getInt("active_backlogs") : null,
                                    projected ? rs.getInt("total_backlogs") : null,
                                    rs.getBigDecimal("tenth_percentage"), rs.getBigDecimal("twelfth_percentage"),
                                    rs.getString("placement_status"), rs.getString("skills"),
                                    resumeId == null ? null : new Resume(resumeId, rs.getString("resume_name"))));
                })
                .list();

        Map<UUID, StudentFacts> facts = eligibility.facts(organizationId,
                rows.stream().map(row -> row.student().id()).distinct().toList());
        PlacementPolicy policy = settings.policy(organizationId);
        BigDecimal ctc = eligibility.jobCtc(organizationId, drive);
        List<Applicant> applicants = new ArrayList<>();
        for (Row row : rows) {
            StudentFacts studentFacts = facts.get(row.student().id());
            applicants.add(new Applicant(row.applicationId(), row.version(), row.stage(), row.appliedAt(),
                    row.updatedAt(), row.student(),
                    studentFacts == null ? null : eligibility.evaluate(drive, policy, studentFacts, ctc)));
        }
        return PageResponse.of(applicants, page, total);
    }

    public record ResumeDownload(StoredFileView file, InputStream content) {
    }

    /**
     * The resume of an applicant the caller may see (same rules as {@link #list}), read from the current student
     * record. Recruiters cannot read student records, so the platform's file download would refuse them. Audited.
     */
    public ResumeDownload resume(CurrentUser user, UUID organizationId, UUID driveId, UUID applicationId) {
        CampusMember member = members.require(user, organizationId);
        if (!member.staff() && !member.recruiter()) {
            throw ApiException.forbidden("access_denied", "Only placement staff and recruiters can see applicants");
        }
        ModuleGrant driveGrant = access.require(member.context(), CampusModules.DRIVE, RecordAction.READ);
        reader.findActive(driveGrant, driveId).orElseThrow(() -> ApiException.notFound("Drive"));
        ModuleGrant grant = access.require(member.context(), CampusModules.APPLICATION, RecordAction.READ);
        RecordView application = reader.findActive(grant, applicationId)
                .filter(record -> driveId.equals(RecordValues.uuid(record.data(), "drive")))
                .orElseThrow(() -> ApiException.notFound("Application"));
        UUID studentId = RecordValues.uuid(application.data(), "student");
        UUID fileId = studentId == null ? null
                : reader.findActive(organizationId, List.of(studentId)).stream().findFirst()
                        .map(student -> RecordValues.fileId(student.data(), "resume"))
                        .map(ApplicantService::uuid)
                        .orElse(null);
        StoredFileView file = fileId == null ? null : files.find(organizationId, fileId).orElse(null);
        if (file == null) {
            throw ApiException.notFound("Resume");
        }
        audit.record(AuditEntry.of(organizationId, user.id(), "campushire.resume.downloaded", "file", file.id())
                .with("application", applicationId.toString()).with("student", studentId.toString()));
        return new ResumeDownload(file, files.open(organizationId, file.id()));
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** {@code sort}: one of {@link #SORTS}, {@code -} prefix for descending; default {@code rollNumber}. */
    static String orderBy(String sort) {
        if (sort == null || sort.isBlank()) {
            return "s.roll_number ASC NULLS LAST";
        }
        String key = sort.strip();
        boolean descending = key.startsWith("-");
        String column = SORTS.get(descending ? key.substring(1) : key);
        if (column == null) {
            throw ApiException.badRequest("invalid_sort", "sort must be one of " + SORTS.keySet()
                    + " (prefix - for descending)");
        }
        return column + (descending ? " DESC NULLS LAST" : " ASC NULLS LAST");
    }
}
