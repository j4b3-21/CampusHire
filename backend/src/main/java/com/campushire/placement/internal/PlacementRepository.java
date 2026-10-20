package com.campushire.placement.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.shared.persistence.SqlValues;

/** Reads the CampusHire projections and owns {@code campushire_drive_registration}. */
@Repository
class PlacementRepository {

    record OfferAggregate(int activeOffers, BigDecimal highestCtc) {

        static final OfferAggregate NONE = new OfferAggregate(0, null);
    }

    /** @param applicationId {@code null} only inside the transaction that registered */
    record Registration(UUID driveId, UUID studentId, UUID applicationId, Instant registeredAt) {
    }

    record RollMatch(UUID applicationId, String rollNumber) {
    }

    private final JdbcClient jdbc;

    PlacementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    Optional<StudentFacts> studentFacts(UUID organizationId, UUID studentId) {
        return jdbc.sql("""
                SELECT s.student_id, s.cgpa, s.active_backlogs, s.department_id, s.batch_id, s.program,
                       s.tenth_percentage, s.twelfth_percentage, s.placement_status,
                       coalesce(o.active_offers, 0) AS active_offers, o.highest_ctc
                FROM campushire_student_projection s
                LEFT JOIN (SELECT student_id, count(*) AS active_offers, max(ctc_amount) AS highest_ctc
                           FROM campushire_offer_projection
                           WHERE organization_id = :org AND student_id = :student AND NOT deleted
                             AND status IN ('extended', 'accepted')
                           GROUP BY student_id) o ON o.student_id = s.student_id
                WHERE s.organization_id = :org AND s.student_id = :student AND NOT s.deleted
                """)
                .param("org", organizationId).param("student", studentId)
                .query((rs, n) -> facts(rs))
                .optional();
    }

    Map<UUID, StudentFacts> studentFacts(UUID organizationId, Collection<UUID> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, StudentFacts> result = new HashMap<>();
        jdbc.sql("""
                SELECT s.student_id, s.cgpa, s.active_backlogs, s.department_id, s.batch_id, s.program,
                       s.tenth_percentage, s.twelfth_percentage, s.placement_status,
                       coalesce(o.active_offers, 0) AS active_offers, o.highest_ctc
                FROM campushire_student_projection s
                LEFT JOIN (SELECT student_id, count(*) AS active_offers, max(ctc_amount) AS highest_ctc
                           FROM campushire_offer_projection
                           WHERE organization_id = :org AND student_id IN (:students) AND NOT deleted
                             AND status IN ('extended', 'accepted')
                           GROUP BY student_id) o ON o.student_id = s.student_id
                WHERE s.organization_id = :org AND s.student_id IN (:students) AND NOT s.deleted
                """)
                .param("org", organizationId).param("students", studentIds)
                .query((rs, n) -> facts(rs))
                .list()
                .forEach(facts -> result.put(facts.studentId(), facts));
        return result;
    }

    OfferAggregate offers(UUID organizationId, UUID studentId) {
        return jdbc.sql("""
                SELECT count(*) AS active_offers, max(ctc_amount) AS highest_ctc
                FROM campushire_offer_projection
                WHERE organization_id = :org AND student_id = :student AND NOT deleted
                  AND status IN ('extended', 'accepted')
                """)
                .param("org", organizationId).param("student", studentId)
                .query((rs, n) -> new OfferAggregate(rs.getInt("active_offers"), rs.getBigDecimal("highest_ctc")))
                .single();
    }

    Optional<Registration> registration(UUID organizationId, UUID driveId, UUID studentId) {
        return jdbc.sql("""
                SELECT drive_id, student_id, application_id, registered_at FROM campushire_drive_registration
                WHERE organization_id = :org AND drive_id = :drive AND student_id = :student
                """)
                .param("org", organizationId).param("drive", driveId).param("student", studentId)
                .query((rs, n) -> new Registration(SqlValues.uuid(rs, "drive_id"), SqlValues.uuid(rs, "student_id"),
                        SqlValues.uuid(rs, "application_id"), SqlValues.instant(rs, "registered_at")))
                .optional();
    }

    /** {@code false} when the student is registered already (a concurrent registration waits for its commit). */
    boolean register(UUID organizationId, UUID driveId, UUID studentId, Instant at, UUID userId) {
        return !jdbc.sql("""
                INSERT INTO campushire_drive_registration (organization_id, drive_id, student_id, registered_at,
                    registered_by)
                VALUES (:org, :drive, :student, :at, :user)
                ON CONFLICT (drive_id, student_id) DO NOTHING
                RETURNING drive_id
                """)
                .param("org", organizationId).param("drive", driveId).param("student", studentId)
                .param("at", SqlValues.ts(at)).param("user", userId)
                .query(UUID.class)
                .list()
                .isEmpty();
    }

    void attachApplication(UUID organizationId, UUID driveId, UUID studentId, UUID applicationId) {
        jdbc.sql("""
                UPDATE campushire_drive_registration SET application_id = :application
                WHERE organization_id = :org AND drive_id = :drive AND student_id = :student
                """)
                .param("org", organizationId).param("drive", driveId).param("student", studentId)
                .param("application", applicationId)
                .update();
    }

    /** Applications of the student among {@code driveIds}, by drive. */
    Map<UUID, UUID> applicationsOfStudent(UUID organizationId, UUID studentId, Collection<UUID> driveIds) {
        if (driveIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT drive_id, application_id FROM campushire_drive_registration
                WHERE organization_id = :org AND student_id = :student AND drive_id IN (:drives)
                  AND application_id IS NOT NULL
                UNION
                SELECT drive_id, application_id FROM campushire_application_projection
                WHERE organization_id = :org AND student_id = :student AND drive_id IN (:drives) AND NOT deleted
                """)
                .param("org", organizationId).param("student", studentId).param("drives", driveIds)
                .query((rs, n) -> Map.entry(SqlValues.uuid(rs, "drive_id"), SqlValues.uuid(rs, "application_id")))
                .list()
                .forEach(entry -> result.putIfAbsent(entry.getKey(), entry.getValue()));
        return result;
    }

    /** Applications of a drive whose student has one of the (lower-cased) roll numbers. */
    List<RollMatch> applicationsByRollNumber(UUID organizationId, UUID driveId, Collection<String> rollNumbers) {
        if (rollNumbers.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT a.application_id, lower(s.roll_number) AS roll
                FROM (SELECT application_id, student_id FROM campushire_drive_registration
                      WHERE organization_id = :org AND drive_id = :drive AND application_id IS NOT NULL
                      UNION
                      SELECT application_id, student_id FROM campushire_application_projection
                      WHERE organization_id = :org AND drive_id = :drive AND NOT deleted) a
                JOIN campushire_student_projection s ON s.student_id = a.student_id AND s.organization_id = :org
                WHERE NOT s.deleted AND lower(s.roll_number) IN (:rolls)
                """)
                .param("org", organizationId).param("drive", driveId).param("rolls", rollNumbers)
                .query((rs, n) -> new RollMatch(SqlValues.uuid(rs, "application_id"), rs.getString("roll")))
                .list();
    }

    Set<UUID> applicantStudentIds(UUID organizationId, UUID driveId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT student_id FROM campushire_drive_registration
                WHERE organization_id = :org AND drive_id = :drive
                UNION
                SELECT student_id FROM campushire_application_projection
                WHERE organization_id = :org AND drive_id = :drive AND NOT deleted
                """)
                .param("org", organizationId).param("drive", driveId)
                .query(UUID.class)
                .list());
    }

    /**
     * User IDs of the accounts linked to the students: the record owner holding an active membership whose
     * {@code student} attribute is the record. Read-only join with the platform's {@code membership} table, so that
     * thousands of recipients cost one query instead of one membership lookup each.
     */
    List<UUID> linkedUsers(UUID organizationId, Collection<UUID> studentIds) {
        if (studentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT s.owner_user_id
                FROM campushire_student_projection s
                JOIN membership m ON m.organization_id = s.organization_id AND m.user_id = s.owner_user_id
                WHERE s.organization_id = :org AND s.student_id IN (:students) AND NOT s.deleted
                  AND m.status = 'ACTIVE'
                  AND m.attributes @> jsonb_build_object('student', s.student_id::text)
                """)
                .param("org", organizationId).param("students", studentIds)
                .query(UUID.class)
                .list();
    }

    /** Linked accounts of the students eligible according to {@code query}; see {@link #linkedUsers}. */
    List<UUID> eligibleLinkedUsers(EligibilityQuery query) {
        return jdbc.sql(query.cte() + """
                SELECT DISTINCT v.owner_user_id
                FROM verdicts v
                JOIN membership m ON m.organization_id = :org AND m.user_id = v.owner_user_id
                WHERE v.eligible AND m.status = 'ACTIVE'
                  AND m.attributes @> jsonb_build_object('student', v.student_id::text)
                """)
                .params(query.params())
                .query(UUID.class)
                .list();
    }

    private static StudentFacts facts(ResultSet rs) throws SQLException {
        return new StudentFacts(SqlValues.uuid(rs, "student_id"), rs.getBigDecimal("cgpa"),
                rs.getInt("active_backlogs"), SqlValues.uuid(rs, "department_id"), SqlValues.uuid(rs, "batch_id"),
                rs.getString("program"), rs.getBigDecimal("tenth_percentage"), rs.getBigDecimal("twelfth_percentage"),
                rs.getString("placement_status"), rs.getInt("active_offers"), rs.getBigDecimal("highest_ctc"));
    }
}
