package com.campushire.analytics.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a scope and query into SQL predicates over the projections. Fragments are constants with named parameters;
 * request values are only ever bound. Students are filtered by organization, department and batch; offers and
 * applications are joined to the filtered students (so they follow the student's current department, like the
 * {@code campushire_placement_stats} view) and filtered by creation time.
 */
final class ProjectionFilter {

    private final Map<String, Object> params = new HashMap<>();
    private final StringBuilder studentPredicates = new StringBuilder();

    private ProjectionFilter() {
    }

    static ProjectionFilter of(AnalyticsScope scope, AnalyticsQuery query, ZoneId zone) {
        ProjectionFilter filter = new ProjectionFilter();
        filter.params.put("org", scope.organizationId());
        List<UUID> departments = departments(scope, query);
        if (departments != null && departments.isEmpty()) {
            filter.studentPredicates.append(" AND false");
        } else if (departments != null) {
            filter.studentPredicates.append(" AND s.department_id IN (:departments)");
            filter.params.put("departments", departments);
        }
        if (query.batchId() != null) {
            filter.studentPredicates.append(" AND s.batch_id = :batch");
            filter.params.put("batch", query.batchId());
        }
        if (query.from() != null) {
            filter.params.put("createdFrom", startOf(query.from(), zone));
        }
        if (query.to() != null) {
            filter.params.put("createdTo", startOf(query.to().plusDays(1), zone));
        }
        return filter;
    }

    /** The visible departments for SQL: {@code null} = no restriction, empty = nothing visible. */
    private static List<UUID> departments(AnalyticsScope scope, AnalyticsQuery query) {
        Set<UUID> visible = scope.departments();
        if (query.departmentId() == null) {
            return visible == null ? null : List.copyOf(visible);
        }
        return visible == null || visible.contains(query.departmentId()) ? List.of(query.departmentId()) : List.of();
    }

    static OffsetDateTime startOf(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toOffsetDateTime();
    }

    /** Filtered students (alias {@code s}) with the given columns, for use as a CTE. */
    String students(String columns) {
        return "SELECT " + columns + " FROM campushire_student_projection s WHERE s.organization_id = :org"
                + " AND NOT s.deleted" + studentPredicates;
    }

    /** Date predicates on {@code alias.created_at}, each starting with {@code AND}; empty without date filters. */
    String created(String alias) {
        String predicate = params.containsKey("createdFrom") ? " AND " + alias + ".created_at >= :createdFrom" : "";
        return params.containsKey("createdTo") ? predicate + " AND " + alias + ".created_at < :createdTo" : predicate;
    }

    ProjectionFilter with(String name, Object value) {
        params.put(name, value);
        return this;
    }

    Map<String, Object> params() {
        return params;
    }
}
