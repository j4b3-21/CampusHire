package com.campushire.analytics.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.campushire.analytics.internal.PlacementReport.OfferLine;
import com.campushire.analytics.internal.ProjectionQueries.OfferRow;

/** Assembles the placement report from the same computations as the analytics endpoints, uncached. */
@Service
class PlacementReportService {

    static final String BATCH_MODULE = "batch";
    private static final BigDecimal LAKH = BigDecimal.valueOf(100_000);

    private final AnalyticsService analytics;
    private final ProjectionQueries queries;
    private final RecordNames names;
    private final ZoneId zone;
    private final Clock clock;

    PlacementReportService(AnalyticsService analytics, ProjectionQueries queries, RecordNames names,
            AnalyticsProperties properties, Clock clock) {
        this.analytics = analytics;
        this.queries = queries;
        this.names = names;
        this.zone = properties.zone();
        this.clock = clock;
    }

    /** One repeatable-read snapshot, so the sheets of a report agree with each other. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    PlacementReport build(AnalyticsScope scope, AnalyticsQuery query) {
        UUID org = scope.organizationId();
        List<OfferRow> rows = queries.offers(ProjectionFilter.of(scope, query, zone));
        Set<UUID> departmentIds = new HashSet<>();
        Set<UUID> companyIds = new HashSet<>();
        for (OfferRow row : rows) {
            departmentIds.add(row.departmentId());
            companyIds.add(row.companyId());
        }
        if (scope.restricted()) {
            departmentIds.addAll(scope.departments());
        }
        if (query.departmentId() != null) {
            departmentIds.add(query.departmentId());
        }
        Map<UUID, String> departmentNames = names.of(org, AnalyticsService.DEPARTMENT_MODULE, departmentIds);
        Map<UUID, String> companyNames = names.of(org, AnalyticsService.COMPANY_MODULE, companyIds);

        List<OfferLine> offers = rows.stream()
                .map(row -> new OfferLine(row.rollNumber(), row.studentName(), departmentNames.get(row.departmentId()),
                        companyNames.get(row.companyId()), lpa(row.ctc()), label(row.offerType()), label(row.status()),
                        row.createdAt() == null ? null : row.createdAt().atZoneSameInstant(zone).toLocalDate()))
                .toList();

        return new PlacementReport(ZonedDateTime.now(clock.withZone(zone)),
                describe(scope, query, departmentNames), analytics.computeOverview(scope, query),
                analytics.computeDepartments(scope, query),
                analytics.computeCompanies(scope, query, Integer.MAX_VALUE),
                analytics.computeCtcDistribution(scope, query), offers);
    }

    private List<String> describe(AnalyticsScope scope, AnalyticsQuery query, Map<UUID, String> departmentNames) {
        List<String> filters = new ArrayList<>();
        if (query.departmentId() != null) {
            filters.add("Department: " + departmentNames.getOrDefault(query.departmentId(), "Unknown"));
        } else if (scope.restricted()) {
            filters.add("Departments: " + (scope.departments().isEmpty() ? "none"
                    : String.join(", ", scope.departments().stream()
                            .map(id -> departmentNames.getOrDefault(id, "Unknown")).sorted().toList())));
        } else {
            filters.add("Departments: all");
        }
        if (query.batchId() != null) {
            filters.add("Batch: " + names.of(scope.organizationId(), BATCH_MODULE, List.of(query.batchId()))
                    .getOrDefault(query.batchId(), "Unknown"));
        }
        if (query.from() != null || query.to() != null) {
            filters.add("Offers and applications created " + (query.from() == null ? "" : "from " + query.from())
                    + (query.from() != null && query.to() != null ? " " : "")
                    + (query.to() == null ? "" : "to " + query.to()));
        }
        return filters;
    }

    static BigDecimal lpa(BigDecimal inr) {
        return inr == null ? null : inr.divide(LAKH, 2, RoundingMode.HALF_UP);
    }

    /** {@code full_time} becomes {@code Full time}. */
    static String label(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String words = value.replace('_', ' ');
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }
}
