package com.campushire.dashboard.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.shared.persistence.SqlValues;

/** Dashboard figures from the projections and the placement statistics view. */
@Repository
class DashboardRepository {

    record PlacementTotals(long totalStudents, long participatingStudents, long placedStudents, long offers,
            BigDecimal highestCtc, BigDecimal averageCtc, Instant refreshedAt) {
    }

    record DriveRow(UUID id, String title, UUID companyId, String status, LocalDate driveDate,
            Instant registrationClosesAt) {
    }

    record OfferRow(UUID id, UUID companyId, BigDecimal ctc, String offerType, String status, LocalDate respondBy) {
    }

    private final JdbcClient jdbc;

    DashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long students(UUID organizationId, Collection<UUID> departments) {
        return count("SELECT count(*) FROM campushire_student_projection WHERE organization_id = :org AND NOT deleted"
                + departmentFilter(departments), organizationId, departments, null);
    }

    long openDrives(UUID organizationId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM campushire_drive_projection
                WHERE organization_id = :org AND NOT deleted AND status = 'published'
                  AND (registration_opens_at IS NULL OR registration_opens_at <= :now)
                  AND registration_closes_at > :now
                """)
                .param("org", organizationId).param("now", SqlValues.ts(now))
                .query(Long.class).single();
    }

    long applicationsSince(UUID organizationId, Collection<UUID> departments, Instant since) {
        return count("SELECT count(*) FROM campushire_application_projection WHERE organization_id = :org"
                + " AND NOT deleted AND created_at >= :since" + departmentFilter(departments), organizationId,
                departments, since);
    }

    long activeOffers(UUID organizationId, Collection<UUID> departments) {
        return count("SELECT count(*) FROM campushire_offer_projection WHERE organization_id = :org AND NOT deleted"
                + " AND status IN ('extended', 'accepted')" + departmentFilter(departments), organizationId,
                departments, null);
    }

    PlacementTotals placement(UUID organizationId, Collection<UUID> departments) {
        return jdbc.sql("""
                SELECT coalesce(sum(total_students), 0) AS total, coalesce(sum(participating_students), 0) AS
                       participating, coalesce(sum(placed_students), 0) AS placed, coalesce(sum(offers), 0) AS offers,
                       max(highest_ctc) AS highest,
                       sum(average_ctc * offers) / nullif(sum(offers) FILTER (WHERE average_ctc IS NOT NULL), 0)
                           AS average,
                       max(refreshed_at) AS refreshed_at
                FROM campushire_placement_stats
                WHERE organization_id = :org""" + departmentFilter(departments))
                .param("org", organizationId).params(departmentParams(departments))
                .query((rs, n) -> new PlacementTotals(rs.getLong("total"), rs.getLong("participating"),
                        rs.getLong("placed"), rs.getLong("offers"), rs.getBigDecimal("highest"),
                        rs.getBigDecimal("average"), SqlValues.instant(rs, "refreshed_at")))
                .single();
    }

    List<DriveRow> upcomingDrives(UUID organizationId, LocalDate today, Instant now, int limit) {
        return jdbc.sql("""
                SELECT drive_id, title, company_id, status, drive_date, registration_closes_at
                FROM campushire_drive_projection
                WHERE organization_id = :org AND NOT deleted
                  AND status IN ('published', 'registration_closed', 'in_progress')
                  AND (drive_date >= :today OR (drive_date IS NULL AND registration_closes_at > :now))
                ORDER BY coalesce(drive_date, CAST(registration_closes_at AS date)), drive_id
                LIMIT :limit
                """)
                .param("org", organizationId).param("today", today).param("now", SqlValues.ts(now))
                .param("limit", limit)
                .query((rs, n) -> new DriveRow(SqlValues.uuid(rs, "drive_id"), rs.getString("title"),
                        SqlValues.uuid(rs, "company_id"), rs.getString("status"),
                        rs.getObject("drive_date", LocalDate.class), SqlValues.instant(rs, "registration_closes_at")))
                .list();
    }

    List<DriveRow> companyDrives(UUID organizationId, Collection<UUID> companies, int limit) {
        if (companies.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT drive_id, title, company_id, status, drive_date, registration_closes_at
                FROM campushire_drive_projection
                WHERE organization_id = :org AND NOT deleted AND company_id IN (:companies)
                ORDER BY drive_date DESC NULLS LAST, registration_closes_at DESC NULLS LAST, drive_id
                LIMIT :limit
                """)
                .param("org", organizationId).param("companies", companies).param("limit", limit)
                .query((rs, n) -> new DriveRow(SqlValues.uuid(rs, "drive_id"), rs.getString("title"),
                        SqlValues.uuid(rs, "company_id"), rs.getString("status"),
                        rs.getObject("drive_date", LocalDate.class), SqlValues.instant(rs, "registration_closes_at")))
                .list();
    }

    /** Applicant counts by drive and stage, restricted to applications of the given companies. */
    Map<UUID, Map<String, Long>> applicantsByStage(UUID organizationId, Collection<UUID> drives,
            Collection<UUID> companies) {
        Map<UUID, Map<String, Long>> result = new HashMap<>();
        if (drives.isEmpty() || companies.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                SELECT drive_id, status, count(*) AS applicants FROM campushire_application_projection
                WHERE organization_id = :org AND NOT deleted AND drive_id IN (:drives) AND company_id IN (:companies)
                GROUP BY drive_id, status
                """)
                .param("org", organizationId).param("drives", drives).param("companies", companies)
                .query(rs -> {
                    result.computeIfAbsent(SqlValues.uuid(rs, "drive_id"), id -> new LinkedHashMap<>())
                            .put(rs.getString("status"), rs.getLong("applicants"));
                });
        return result;
    }

    Map<String, Long> studentApplicationsByStage(UUID organizationId, UUID studentId) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT status, count(*) AS applications FROM campushire_application_projection
                WHERE organization_id = :org AND student_id = :student AND NOT deleted
                GROUP BY status ORDER BY status
                """)
                .param("org", organizationId).param("student", studentId)
                .query(rs -> {
                    result.put(rs.getString("status"), rs.getLong("applications"));
                });
        return result;
    }

    List<OfferRow> studentOffers(UUID organizationId, UUID studentId) {
        return jdbc.sql("""
                SELECT offer_id, company_id, ctc_amount, offer_type, status, respond_by
                FROM campushire_offer_projection
                WHERE organization_id = :org AND student_id = :student AND NOT deleted
                ORDER BY created_at DESC
                """)
                .param("org", organizationId).param("student", studentId)
                .query((rs, n) -> new OfferRow(SqlValues.uuid(rs, "offer_id"), SqlValues.uuid(rs, "company_id"),
                        rs.getBigDecimal("ctc_amount"), rs.getString("offer_type"), rs.getString("status"),
                        rs.getObject("respond_by", LocalDate.class)))
                .list();
    }

    private long count(String sql, UUID organizationId, Collection<UUID> departments, Instant since) {
        var spec = jdbc.sql(sql).param("org", organizationId).params(departmentParams(departments));
        if (since != null) {
            spec = spec.param("since", SqlValues.ts(since));
        }
        return spec.query(Long.class).single();
    }

    private static String departmentFilter(Collection<UUID> departments) {
        if (departments == null) {
            return "";
        }
        return departments.isEmpty() ? " AND FALSE" : " AND department_id IN (:departments)";
    }

    private static Map<String, Object> departmentParams(Collection<UUID> departments) {
        return departments == null || departments.isEmpty() ? Map.of() : Map.of("departments", departments);
    }
}
