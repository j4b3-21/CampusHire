package com.campushire.analytics.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.campushire.analytics.internal.AnalyticsResponses.Companies;
import com.campushire.analytics.internal.AnalyticsResponses.CompanyStats;
import com.campushire.analytics.internal.AnalyticsResponses.CtcBucket;
import com.campushire.analytics.internal.AnalyticsResponses.CtcDistribution;
import com.campushire.analytics.internal.AnalyticsResponses.DepartmentStats;
import com.campushire.analytics.internal.AnalyticsResponses.Departments;
import com.campushire.analytics.internal.AnalyticsResponses.DriveFunnel;
import com.campushire.analytics.internal.AnalyticsResponses.FunnelStage;
import com.campushire.analytics.internal.AnalyticsResponses.Overview;
import com.campushire.analytics.internal.AnalyticsResponses.Trend;
import com.campushire.analytics.internal.AnalyticsResponses.TrendPoint;
import com.campushire.analytics.internal.ProjectionQueries.DepartmentRow;
import com.campushire.analytics.internal.ProjectionQueries.DriveRow;
import com.campushire.analytics.internal.ProjectionQueries.OverviewRow;
import com.campushire.analytics.internal.ProjectionQueries.TrendRow;

/**
 * Placement metrics computed live from the projections, so they match a manual count exactly; responses are cached
 * for a short time per organization, department scope and parameters.
 */
@Service
class AnalyticsService {

    static final String DEPARTMENT_MODULE = "department";
    static final String COMPANY_MODULE = "company";
    static final int MAX_COMPANIES = 100;

    /** Workflow order of the forward application stages (PRD section 6). */
    static final List<String> FORWARD_STAGES = List.of("applied", "shortlisted", "assessment", "interview", "offered",
            "joined");
    static final List<String> EXIT_STAGES = List.of("rejected", "withdrawn");
    private static final String OFFERED = "offered";

    static final int[] CTC_THRESHOLDS_LPA = { 3, 6, 10, 15, 25 };
    private static final int TREND_MONTHS = 12;

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final ProjectionQueries queries;
    private final RecordNames names;
    private final AnalyticsCache cache;
    private final ZoneId zone;
    private final Clock clock;

    AnalyticsService(ProjectionQueries queries, RecordNames names, AnalyticsCache cache,
            AnalyticsProperties properties, Clock clock) {
        this.queries = queries;
        this.names = names;
        this.cache = cache;
        this.zone = properties.zone();
        this.clock = clock;
    }

    Overview overview(AnalyticsScope scope, AnalyticsQuery query) {
        return cached(scope, "overview", query.key(), Overview.class, () -> computeOverview(scope, query));
    }

    Departments departments(AnalyticsScope scope, AnalyticsQuery query) {
        return cached(scope, "departments", query.key(), Departments.class,
                () -> new Departments(computeDepartments(scope, query), now()));
    }

    Companies companies(AnalyticsScope scope, AnalyticsQuery query, int limit) {
        if (limit < 1 || limit > MAX_COMPANIES) {
            throw ApiException.validation("limit", "out_of_range", "limit must be between 1 and " + MAX_COMPANIES);
        }
        return cached(scope, "companies", query.key() + "&limit=" + limit, Companies.class,
                () -> new Companies(computeCompanies(scope, query, limit), now()));
    }

    CtcDistribution ctcDistribution(AnalyticsScope scope, AnalyticsQuery query) {
        return cached(scope, "ctc-distribution", query.key(), CtcDistribution.class,
                () -> computeCtcDistribution(scope, query));
    }

    Trend trend(AnalyticsScope scope, AnalyticsQuery query, String interval) {
        if (!"month".equals(interval)) {
            throw ApiException.validation("interval", "unsupported", "interval must be month");
        }
        YearMonth current = YearMonth.now(clock.withZone(zone));
        return cached(scope, "trend", query.key() + "&interval=month&current=" + current, Trend.class,
                () -> computeTrend(scope, query, current));
    }

    DriveFunnel funnel(AnalyticsScope scope, UUID driveId, AnalyticsQuery query) {
        return cached(scope, "funnel", query.key() + "&drive=" + driveId, DriveFunnel.class,
                () -> computeFunnel(scope, driveId, query));
    }

    Overview computeOverview(AnalyticsScope scope, AnalyticsQuery query) {
        OverviewRow row = timed("overview", () -> queries.overview(filter(scope, query)));
        return new Overview(row.totalStudents(), row.participatingStudents(), row.placedStudents(),
                rate(row.placedStudents(), row.participatingStudents()), row.offers(), row.acceptedOffers(),
                money(row.highestCtc()), money(row.averageCtc()), money(row.medianCtc()), row.activeDrives(),
                row.applications(), row.companiesVisited(), now());
    }

    List<DepartmentStats> computeDepartments(AnalyticsScope scope, AnalyticsQuery query) {
        List<DepartmentRow> rows = timed("departments", () -> queries.departments(filter(scope, query)));
        Map<UUID, String> departmentNames = names.of(scope.organizationId(), DEPARTMENT_MODULE,
                rows.stream().map(DepartmentRow::departmentId).toList());
        return rows.stream()
                .map(row -> new DepartmentStats(row.departmentId(), departmentNames.get(row.departmentId()),
                        row.totalStudents(), row.participatingStudents(), row.placedStudents(),
                        rate(row.placedStudents(), row.participatingStudents()), row.offers(),
                        money(row.highestCtc()), money(row.averageCtc()), money(row.medianCtc())))
                .sorted(Comparator.comparing(DepartmentStats::departmentName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    List<CompanyStats> computeCompanies(AnalyticsScope scope, AnalyticsQuery query, int limit) {
        List<ProjectionQueries.CompanyRow> rows = timed("companies",
                () -> queries.companies(filter(scope, query), limit));
        Map<UUID, String> companyNames = names.of(scope.organizationId(), COMPANY_MODULE,
                rows.stream().map(ProjectionQueries.CompanyRow::companyId).toList());
        return rows.stream()
                .map(row -> new CompanyStats(row.companyId(), companyNames.get(row.companyId()), row.offers(),
                        money(row.highestCtc()), money(row.averageCtc()), row.drives()))
                .toList();
    }

    CtcDistribution computeCtcDistribution(AnalyticsScope scope, AnalyticsQuery query) {
        Map<Integer, Long> counts = timed("ctc-distribution",
                () -> queries.ctcBuckets(filter(scope, query), CTC_THRESHOLDS_LPA));
        List<CtcBucket> buckets = new ArrayList<>();
        long total = 0;
        for (int index = 0; index <= CTC_THRESHOLDS_LPA.length; index++) {
            int min = index == 0 ? 0 : CTC_THRESHOLDS_LPA[index - 1];
            Integer max = index == CTC_THRESHOLDS_LPA.length ? null : CTC_THRESHOLDS_LPA[index];
            long count = counts.getOrDefault(index, 0L);
            total += count;
            buckets.add(new CtcBucket(max == null ? min + "+" : min + "-" + max, min, max, count));
        }
        return new CtcDistribution("LPA", total, buckets, now());
    }

    Trend computeTrend(AnalyticsScope scope, AnalyticsQuery query, YearMonth current) {
        YearMonth last = query.to() == null ? current : YearMonth.from(query.to());
        YearMonth first = last.minusMonths(TREND_MONTHS - 1L);
        List<TrendRow> rows = timed("trend", () -> queries.monthlyTrend(filter(scope, query),
                ProjectionFilter.startOf(first.atDay(1), zone),
                ProjectionFilter.startOf(last.plusMonths(1).atDay(1), zone), zone.getId()));
        Map<String, long[]> byMonth = new LinkedHashMap<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            byMonth.put(month.toString(), new long[2]);
        }
        for (TrendRow row : rows) {
            long[] counts = byMonth.get(row.period());
            if (counts != null) {
                counts["offer".equals(row.kind()) ? 0 : 1] += row.count();
            }
        }
        List<TrendPoint> points = byMonth.entrySet().stream()
                .map(entry -> new TrendPoint(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();
        return new Trend("month", points, now());
    }

    DriveFunnel computeFunnel(AnalyticsScope scope, UUID driveId, AnalyticsQuery query) {
        DriveRow drive = queries.drive(scope.organizationId(), driveId)
                .orElseThrow(() -> ApiException.notFound("Drive"));
        Map<String, Long> counts = new HashMap<>(
                timed("funnel", () -> queries.stageCounts(filter(scope, query), driveId)));
        long applications = counts.values().stream().mapToLong(Long::longValue).sum();

        List<FunnelStage> stages = new ArrayList<>();
        long reached = applications;
        long previousReached = applications;
        long offered = 0;
        for (int index = 0; index < FORWARD_STAGES.size(); index++) {
            String stage = FORWARD_STAGES.get(index);
            if (index > 0) {
                reached = 0;
                for (String later : FORWARD_STAGES.subList(index, FORWARD_STAGES.size())) {
                    reached += counts.getOrDefault(later, 0L);
                }
            }
            if (stage.equals(OFFERED)) {
                offered = reached;
            }
            stages.add(new FunnelStage(stage, counts.getOrDefault(stage, 0L), reached,
                    index == 0 ? (applications == 0 ? 0 : 1) : rate(reached, previousReached)));
            previousReached = reached;
        }
        List<String> exits = new ArrayList<>(EXIT_STAGES);
        counts.keySet().stream()
                .filter(stage -> !FORWARD_STAGES.contains(stage) && !EXIT_STAGES.contains(stage))
                .sorted()
                .forEach(exits::add);
        for (String stage : exits) {
            long count = counts.getOrDefault(stage, 0L);
            stages.add(new FunnelStage(stage, count, count, rate(count, applications)));
        }
        String companyName = drive.companyId() == null ? null
                : names.of(scope.organizationId(), COMPANY_MODULE, List.of(drive.companyId())).get(drive.companyId());
        return new DriveFunnel(drive.driveId(), drive.title(), drive.companyId(), companyName, applications, stages,
                rate(offered, applications), now());
    }

    private <T> T cached(AnalyticsScope scope, String endpoint, String params, Class<T> type, Supplier<T> loader) {
        return cache.get(AnalyticsCache.key(scope.organizationId(), endpoint, scope.key() + "|" + params), type,
                loader);
    }

    private ProjectionFilter filter(AnalyticsScope scope, AnalyticsQuery query) {
        return ProjectionFilter.of(scope, query, zone);
    }

    private Instant now() {
        return clock.instant();
    }

    private static <T> T timed(String name, Supplier<T> query) {
        long started = System.nanoTime();
        T result = query.get();
        if (log.isDebugEnabled()) {
            log.debug("Analytics query {} took {} ms", name, (System.nanoTime() - started) / 1_000_000.0);
        }
        return result;
    }

    /** {@code part / whole} rounded to four decimals, capped at 1; 0 when {@code whole} is 0. */
    static double rate(long part, long whole) {
        if (whole <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(Math.min(part, whole)).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    static BigDecimal money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal money(Double amount) {
        return amount == null ? null : money(BigDecimal.valueOf(amount));
    }
}
