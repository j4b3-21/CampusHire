package com.campushire.analytics.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only aggregate queries over the CampusHire projections. Every query is bound to one organization; the student
 * filter (department, batch) is applied through the {@code s} CTE that offers and applications join against.
 */
@Repository
class ProjectionQueries {

    /** Offers that count for statistics; declined and revoked offers do not. */
    private static final String COUNTED_OFFER = "o.organization_id = :org AND NOT o.deleted"
            + " AND o.status IN ('extended', 'accepted')";
    private static final String NOT_PARTICIPATING = "('opted_out', 'higher_studies')";

    private final JdbcClient jdbc;

    ProjectionQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record OverviewRow(long totalStudents, long participatingStudents, long placedStudents, long offers,
            long acceptedOffers, BigDecimal highestCtc, BigDecimal averageCtc, Double medianCtc, long applications,
            long activeDrives, long companiesVisited) {
    }

    record DepartmentRow(UUID departmentId, long totalStudents, long participatingStudents, long placedStudents,
            long offers, BigDecimal highestCtc, BigDecimal averageCtc, Double medianCtc) {
    }

    record CompanyRow(UUID companyId, long offers, BigDecimal highestCtc, BigDecimal averageCtc, long drives) {
    }

    record TrendRow(String kind, String period, long count) {
    }

    record DriveRow(UUID driveId, String title, UUID companyId) {
    }

    record OfferRow(String rollNumber, String studentName, UUID departmentId, UUID companyId, BigDecimal ctc,
            String offerType, String status, OffsetDateTime createdAt) {
    }

    OverviewRow overview(ProjectionFilter filter) {
        String sql = """
                WITH s AS (%s),
                o AS (
                    SELECT o.student_id, o.status, o.ctc_amount
                    FROM campushire_offer_projection o
                    JOIN s ON s.student_id = o.student_id
                    WHERE %s%s
                )
                SELECT (SELECT count(*) FROM s) AS total_students,
                       (SELECT count(*) FROM s WHERE s.placement_status NOT IN %s) AS participating_students,
                       oa.placed_students, oa.offers, oa.accepted_offers, oa.highest_ctc, oa.average_ctc, oa.median_ctc,
                       (SELECT count(*)
                        FROM campushire_application_projection a
                        JOIN s ON s.student_id = a.student_id
                        WHERE a.organization_id = :org AND NOT a.deleted%s) AS applications,
                       (SELECT count(*)
                        FROM campushire_drive_projection d
                        WHERE d.organization_id = :org AND NOT d.deleted
                          AND d.status IN ('published', 'registration_closed', 'in_progress')) AS active_drives,
                       (SELECT count(DISTINCT d.company_id)
                        FROM campushire_drive_projection d
                        WHERE d.organization_id = :org AND NOT d.deleted
                          AND d.status IN ('in_progress', 'completed')) AS companies_visited
                FROM (SELECT count(DISTINCT o.student_id) AS placed_students,
                             count(*) AS offers,
                             count(*) FILTER (WHERE o.status = 'accepted') AS accepted_offers,
                             max(o.ctc_amount) AS highest_ctc,
                             avg(o.ctc_amount) AS average_ctc,
                             percentile_cont(0.5) WITHIN GROUP (ORDER BY o.ctc_amount) AS median_ctc
                      FROM o) oa
                """.formatted(filter.students("s.student_id, s.placement_status"), COUNTED_OFFER,
                filter.created("o"), NOT_PARTICIPATING, filter.created("a"));
        return jdbc.sql(sql).params(filter.params())
                .query((rs, row) -> new OverviewRow(rs.getLong("total_students"), rs.getLong("participating_students"),
                        rs.getLong("placed_students"), rs.getLong("offers"), rs.getLong("accepted_offers"),
                        rs.getBigDecimal("highest_ctc"), rs.getBigDecimal("average_ctc"),
                        rs.getObject("median_ctc", Double.class), rs.getLong("applications"),
                        rs.getLong("active_drives"), rs.getLong("companies_visited")))
                .single();
    }

    List<DepartmentRow> departments(ProjectionFilter filter) {
        String sql = """
                WITH s AS (%s),
                students AS (
                    SELECT s.department_id, count(*) AS total_students,
                           count(*) FILTER (WHERE s.placement_status NOT IN %s) AS participating_students
                    FROM s
                    GROUP BY s.department_id
                ),
                offers AS (
                    SELECT s.department_id,
                           count(DISTINCT o.student_id) AS placed_students,
                           count(*) AS offers,
                           max(o.ctc_amount) AS highest_ctc,
                           avg(o.ctc_amount) AS average_ctc,
                           percentile_cont(0.5) WITHIN GROUP (ORDER BY o.ctc_amount) AS median_ctc
                    FROM campushire_offer_projection o
                    JOIN s ON s.student_id = o.student_id
                    WHERE %s%s
                    GROUP BY s.department_id
                )
                SELECT st.department_id, st.total_students, st.participating_students,
                       coalesce(ofr.placed_students, 0) AS placed_students, coalesce(ofr.offers, 0) AS offers,
                       ofr.highest_ctc, ofr.average_ctc, ofr.median_ctc
                FROM students st
                LEFT JOIN offers ofr ON ofr.department_id IS NOT DISTINCT FROM st.department_id
                """.formatted(filter.students("s.student_id, s.department_id, s.placement_status"),
                NOT_PARTICIPATING, COUNTED_OFFER, filter.created("o"));
        return jdbc.sql(sql).params(filter.params())
                .query((rs, row) -> new DepartmentRow(uuid(rs, "department_id"), rs.getLong("total_students"),
                        rs.getLong("participating_students"), rs.getLong("placed_students"), rs.getLong("offers"),
                        rs.getBigDecimal("highest_ctc"), rs.getBigDecimal("average_ctc"),
                        rs.getObject("median_ctc", Double.class)))
                .list();
    }

    List<CompanyRow> companies(ProjectionFilter filter, int limit) {
        String sql = """
                WITH s AS (%s)
                SELECT o.company_id, count(*) AS offers, max(o.ctc_amount) AS highest_ctc,
                       avg(o.ctc_amount) AS average_ctc,
                       (SELECT count(*)
                        FROM campushire_drive_projection d
                        WHERE d.organization_id = :org AND d.company_id = o.company_id AND NOT d.deleted
                          AND d.status NOT IN ('draft', 'cancelled')) AS drives
                FROM campushire_offer_projection o
                JOIN s ON s.student_id = o.student_id
                WHERE %s%s
                GROUP BY o.company_id
                ORDER BY offers DESC, highest_ctc DESC NULLS LAST, o.company_id
                LIMIT :limit
                """.formatted(filter.students("s.student_id"), COUNTED_OFFER, filter.created("o"));
        return jdbc.sql(sql).params(filter.with("limit", limit).params())
                .query((rs, row) -> new CompanyRow(uuid(rs, "company_id"), rs.getLong("offers"),
                        rs.getBigDecimal("highest_ctc"), rs.getBigDecimal("average_ctc"), rs.getLong("drives")))
                .list();
    }

    /**
     * Counted offers with a CTC per {@code width_bucket} over the LPA thresholds: 0 = below the first threshold,
     * {@code thresholds.length} = at or above the last.
     */
    Map<Integer, Long> ctcBuckets(ProjectionFilter filter, int[] thresholdsLpa) {
        StringBuilder array = new StringBuilder();
        for (int threshold : thresholdsLpa) {
            array.append(array.isEmpty() ? "" : ", ").append(threshold);
        }
        String sql = """
                WITH s AS (%s)
                SELECT width_bucket(o.ctc_amount / 100000, ARRAY[%s]::numeric[]) AS bucket, count(*) AS offers
                FROM campushire_offer_projection o
                JOIN s ON s.student_id = o.student_id
                WHERE %s AND o.ctc_amount IS NOT NULL%s
                GROUP BY 1
                """.formatted(filter.students("s.student_id"), array, COUNTED_OFFER, filter.created("o"));
        Map<Integer, Long> buckets = new LinkedHashMap<>();
        jdbc.sql(sql).params(filter.params())
                .query(rs -> {
                    buckets.put(rs.getInt("bucket"), rs.getLong("offers"));
                });
        return buckets;
    }

    /** Counted offers ({@code kind = offer}) and applications per calendar month within the window. */
    List<TrendRow> monthlyTrend(ProjectionFilter filter, OffsetDateTime windowStart, OffsetDateTime windowEnd,
            String zone) {
        String sql = """
                WITH s AS (%s)
                SELECT 'offer' AS kind, to_char(o.created_at AT TIME ZONE :zone, 'YYYY-MM') AS period, count(*) AS total
                FROM campushire_offer_projection o
                JOIN s ON s.student_id = o.student_id
                WHERE %s AND o.created_at >= :windowStart AND o.created_at < :windowEnd%s
                GROUP BY 2
                UNION ALL
                SELECT 'application', to_char(a.created_at AT TIME ZONE :zone, 'YYYY-MM'), count(*)
                FROM campushire_application_projection a
                JOIN s ON s.student_id = a.student_id
                WHERE a.organization_id = :org AND NOT a.deleted
                  AND a.created_at >= :windowStart AND a.created_at < :windowEnd%s
                GROUP BY 2
                """.formatted(filter.students("s.student_id"), COUNTED_OFFER, filter.created("o"),
                filter.created("a"));
        return jdbc.sql(sql)
                .params(filter.with("windowStart", windowStart).with("windowEnd", windowEnd).with("zone", zone)
                        .params())
                .query((rs, row) -> new TrendRow(rs.getString("kind"), rs.getString("period"), rs.getLong("total")))
                .list();
    }

    Optional<DriveRow> drive(UUID organizationId, UUID driveId) {
        return jdbc.sql("""
                SELECT d.drive_id, d.title, d.company_id
                FROM campushire_drive_projection d
                WHERE d.organization_id = :org AND d.drive_id = :drive AND NOT d.deleted
                """)
                .param("org", organizationId)
                .param("drive", driveId)
                .query((rs, row) -> new DriveRow(uuid(rs, "drive_id"), rs.getString("title"), uuid(rs, "company_id")))
                .optional();
    }

    /** Applications of one drive per current stage. */
    Map<String, Long> stageCounts(ProjectionFilter filter, UUID driveId) {
        String sql = """
                WITH s AS (%s)
                SELECT a.status, count(*) AS total
                FROM campushire_application_projection a
                JOIN s ON s.student_id = a.student_id
                WHERE a.organization_id = :org AND a.drive_id = :drive AND NOT a.deleted%s
                GROUP BY a.status
                """.formatted(filter.students("s.student_id"), filter.created("a"));
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql(sql).params(filter.with("drive", driveId).params())
                .query(rs -> {
                    counts.put(rs.getString("status"), rs.getLong("total"));
                });
        return counts;
    }

    /** Every offer (any status) of the filtered students, newest first. */
    List<OfferRow> offers(ProjectionFilter filter) {
        String sql = """
                WITH s AS (%s)
                SELECT s.roll_number, s.full_name, s.department_id, o.company_id, o.ctc_amount, o.offer_type,
                       o.status, o.created_at
                FROM campushire_offer_projection o
                JOIN s ON s.student_id = o.student_id
                WHERE o.organization_id = :org AND NOT o.deleted%s
                ORDER BY o.created_at DESC, s.roll_number
                """.formatted(filter.students("s.student_id, s.roll_number, s.full_name, s.department_id"),
                filter.created("o"));
        return jdbc.sql(sql).params(filter.params())
                .query((rs, row) -> new OfferRow(rs.getString("roll_number"), rs.getString("full_name"),
                        uuid(rs, "department_id"), uuid(rs, "company_id"), rs.getBigDecimal("ctc_amount"),
                        rs.getString("offer_type"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
