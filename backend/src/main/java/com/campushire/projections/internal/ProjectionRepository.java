package com.campushire.projections.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.shared.persistence.SqlValues;

/**
 * Writes the projection tables. Every upsert only replaces a row whose {@code record_version} is not newer than the
 * incoming one, so events applied late or twice never roll a projection back.
 */
@Repository
class ProjectionRepository {

    enum Table {
        STUDENT("campushire_student_projection", "student_id"),
        DRIVE("campushire_drive_projection", "drive_id"),
        APPLICATION("campushire_application_projection", "application_id"),
        OFFER("campushire_offer_projection", "offer_id");

        final String table;
        final String id;

        Table(String table, String id) {
            this.table = table;
            this.id = id;
        }
    }

    record StudentRow(UUID studentId, UUID organizationId, UUID ownerUserId, String rollNumber, String fullName,
            String email, UUID departmentId, UUID batchId, String program, BigDecimal cgpa, int activeBacklogs,
            int totalBacklogs, BigDecimal tenthPercentage, BigDecimal twelfthPercentage, String placementStatus,
            boolean profileVerified, String skills, String resumeFileId, String resumeName, long version,
            Instant updatedAt) {
    }

    record DriveRow(UUID driveId, UUID organizationId, UUID companyId, UUID jobId, String title, String status,
            Instant registrationOpensAt, Instant registrationClosesAt, LocalDate driveDate, String eligibility,
            BigDecimal jobCtc, long version, Instant updatedAt) {
    }

    record ApplicationRow(UUID applicationId, UUID organizationId, UUID driveId, UUID studentId, UUID companyId,
            UUID departmentId, String status, long version, Instant createdAt, Instant updatedAt) {
    }

    record OfferRow(UUID offerId, UUID organizationId, UUID applicationId, UUID studentId, UUID companyId,
            UUID driveId, UUID departmentId, BigDecimal ctcAmount, String offerType, String status,
            LocalDate respondBy, long version, Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    ProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void upsertStudent(StudentRow row) {
        jdbc.sql("""
                INSERT INTO campushire_student_projection (student_id, organization_id, owner_user_id, roll_number,
                    full_name, email, department_id, batch_id, program, cgpa, active_backlogs, total_backlogs,
                    tenth_percentage, twelfth_percentage, placement_status, profile_verified, skills, resume_file_id,
                    resume_name, deleted, record_version, updated_at)
                VALUES (:id, :org, :owner, :roll, :name, :email, :department, :batch, :program, :cgpa, :active,
                    :total, :tenth, :twelfth, :placement, :verified, :skills, :resumeId, :resumeName, false,
                    :version, :updatedAt)
                ON CONFLICT (student_id) DO UPDATE SET owner_user_id = EXCLUDED.owner_user_id,
                    roll_number = EXCLUDED.roll_number, full_name = EXCLUDED.full_name, email = EXCLUDED.email,
                    department_id = EXCLUDED.department_id, batch_id = EXCLUDED.batch_id,
                    program = EXCLUDED.program, cgpa = EXCLUDED.cgpa, active_backlogs = EXCLUDED.active_backlogs,
                    total_backlogs = EXCLUDED.total_backlogs, tenth_percentage = EXCLUDED.tenth_percentage,
                    twelfth_percentage = EXCLUDED.twelfth_percentage, placement_status = EXCLUDED.placement_status,
                    profile_verified = EXCLUDED.profile_verified, skills = EXCLUDED.skills,
                    resume_file_id = EXCLUDED.resume_file_id, resume_name = EXCLUDED.resume_name, deleted = false,
                    record_version = EXCLUDED.record_version, updated_at = EXCLUDED.updated_at
                WHERE campushire_student_projection.record_version <= EXCLUDED.record_version
                """)
                .param("id", row.studentId()).param("org", row.organizationId()).param("owner", row.ownerUserId())
                .param("roll", row.rollNumber()).param("name", row.fullName()).param("email", row.email())
                .param("department", row.departmentId()).param("batch", row.batchId())
                .param("program", row.program()).param("cgpa", row.cgpa()).param("active", row.activeBacklogs())
                .param("total", row.totalBacklogs()).param("tenth", row.tenthPercentage())
                .param("twelfth", row.twelfthPercentage()).param("placement", row.placementStatus())
                .param("verified", row.profileVerified()).param("skills", row.skills())
                .param("resumeId", row.resumeFileId()).param("resumeName", row.resumeName())
                .param("version", row.version()).param("updatedAt", SqlValues.ts(row.updatedAt()))
                .update();
    }

    void upsertDrive(DriveRow row) {
        jdbc.sql("""
                INSERT INTO campushire_drive_projection (drive_id, organization_id, company_id, job_id, title, status,
                    registration_opens_at, registration_closes_at, drive_date, eligibility, job_ctc, deleted,
                    record_version, updated_at)
                VALUES (:id, :org, :company, :job, :title, :status, :opens, :closes, :driveDate,
                    CAST(:eligibility AS jsonb), :jobCtc, false, :version, :updatedAt)
                ON CONFLICT (drive_id) DO UPDATE SET company_id = EXCLUDED.company_id, job_id = EXCLUDED.job_id,
                    title = EXCLUDED.title, status = EXCLUDED.status,
                    registration_opens_at = EXCLUDED.registration_opens_at,
                    registration_closes_at = EXCLUDED.registration_closes_at, drive_date = EXCLUDED.drive_date,
                    eligibility = EXCLUDED.eligibility, job_ctc = EXCLUDED.job_ctc, deleted = false,
                    record_version = EXCLUDED.record_version, updated_at = EXCLUDED.updated_at
                WHERE campushire_drive_projection.record_version <= EXCLUDED.record_version
                """)
                .param("id", row.driveId()).param("org", row.organizationId()).param("company", row.companyId())
                .param("job", row.jobId()).param("title", row.title()).param("status", row.status())
                .param("opens", SqlValues.ts(row.registrationOpensAt()))
                .param("closes", SqlValues.ts(row.registrationClosesAt()))
                .param("driveDate", row.driveDate()).param("eligibility", row.eligibility())
                .param("jobCtc", row.jobCtc()).param("version", row.version())
                .param("updatedAt", SqlValues.ts(row.updatedAt()))
                .update();
    }

    void upsertApplication(ApplicationRow row) {
        jdbc.sql("""
                INSERT INTO campushire_application_projection (application_id, organization_id, drive_id, student_id,
                    company_id, department_id, status, deleted, record_version, created_at, updated_at)
                VALUES (:id, :org, :drive, :student, :company, :department, :status, false, :version, :createdAt,
                    :updatedAt)
                ON CONFLICT (application_id) DO UPDATE SET drive_id = EXCLUDED.drive_id,
                    student_id = EXCLUDED.student_id, company_id = EXCLUDED.company_id,
                    department_id = EXCLUDED.department_id, status = EXCLUDED.status, deleted = false,
                    record_version = EXCLUDED.record_version, updated_at = EXCLUDED.updated_at
                WHERE campushire_application_projection.record_version <= EXCLUDED.record_version
                """)
                .param("id", row.applicationId()).param("org", row.organizationId()).param("drive", row.driveId())
                .param("student", row.studentId()).param("company", row.companyId())
                .param("department", row.departmentId()).param("status", row.status())
                .param("version", row.version()).param("createdAt", SqlValues.ts(row.createdAt()))
                .param("updatedAt", SqlValues.ts(row.updatedAt()))
                .update();
    }

    void upsertOffer(OfferRow row) {
        jdbc.sql("""
                INSERT INTO campushire_offer_projection (offer_id, organization_id, application_id, student_id,
                    company_id, drive_id, department_id, ctc_amount, offer_type, status, respond_by, deleted,
                    record_version, created_at, updated_at)
                VALUES (:id, :org, :application, :student, :company, :drive, :department, :ctc, :offerType, :status,
                    :respondBy, false, :version, :createdAt, :updatedAt)
                ON CONFLICT (offer_id) DO UPDATE SET application_id = EXCLUDED.application_id,
                    student_id = EXCLUDED.student_id, company_id = EXCLUDED.company_id, drive_id = EXCLUDED.drive_id,
                    department_id = EXCLUDED.department_id, ctc_amount = EXCLUDED.ctc_amount,
                    offer_type = EXCLUDED.offer_type, status = EXCLUDED.status, respond_by = EXCLUDED.respond_by,
                    deleted = false, record_version = EXCLUDED.record_version, updated_at = EXCLUDED.updated_at
                WHERE campushire_offer_projection.record_version <= EXCLUDED.record_version
                """)
                .param("id", row.offerId()).param("org", row.organizationId())
                .param("application", row.applicationId()).param("student", row.studentId())
                .param("company", row.companyId()).param("drive", row.driveId())
                .param("department", row.departmentId()).param("ctc", row.ctcAmount())
                .param("offerType", row.offerType()).param("status", row.status())
                .param("respondBy", row.respondBy()).param("version", row.version())
                .param("createdAt", SqlValues.ts(row.createdAt())).param("updatedAt", SqlValues.ts(row.updatedAt()))
                .update();
    }

    /** Marks a row deleted unless a newer version has been projected meanwhile. */
    void markDeleted(Table table, UUID organizationId, UUID id, long version, Instant at) {
        jdbc.sql("UPDATE " + table.table + " SET deleted = true, record_version = :version, updated_at = :at"
                + " WHERE " + table.id + " = :id AND organization_id = :org AND record_version <= :version")
                .param("id", id).param("org", organizationId).param("version", version).param("at", SqlValues.ts(at))
                .update();
    }

    int markDeleted(Table table, UUID organizationId, Collection<UUID> ids, Instant at) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE " + table.table + " SET deleted = true, updated_at = :at"
                + " WHERE organization_id = :org AND " + table.id + " IN (:ids) AND NOT deleted")
                .param("org", organizationId).param("ids", ids).param("at", SqlValues.ts(at))
                .update();
    }

    List<UUID> activeIds(Table table, UUID organizationId) {
        return jdbc.sql("SELECT " + table.id + " FROM " + table.table + " WHERE organization_id = :org AND NOT deleted")
                .param("org", organizationId)
                .query(UUID.class)
                .list();
    }

    void updateJobCtc(UUID organizationId, UUID jobId, BigDecimal ctc) {
        jdbc.sql("UPDATE campushire_drive_projection SET job_ctc = :ctc"
                + " WHERE organization_id = :org AND job_id = :job AND job_ctc IS DISTINCT FROM :ctc")
                .param("org", organizationId).param("job", jobId).param("ctc", ctc)
                .update();
    }
}
