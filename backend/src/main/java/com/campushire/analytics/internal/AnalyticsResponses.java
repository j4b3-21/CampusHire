package com.campushire.analytics.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of the analytics API. Amounts are INR (scale 2) unless the name says LPA (lakh per annum); rates
 * are fractions between 0 and 1 (four decimals). An offer counts when it is {@code extended} or {@code accepted}.
 * {@code refreshedAt} is when the numbers were computed (a cached response keeps its original time).
 */
final class AnalyticsResponses {

    private AnalyticsResponses() {
    }

    record Overview(long totalStudents, long participatingStudents, long placedStudents, double placementRate,
            long offers, long acceptedOffers, BigDecimal highestCtc, BigDecimal averageCtc, BigDecimal medianCtc,
            long activeDrives, long applications, long companiesVisited, Instant refreshedAt) {
    }

    record DepartmentStats(UUID departmentId, String departmentName, long totalStudents, long participatingStudents,
            long placedStudents, double placementRate, long offers, BigDecimal highestCtc, BigDecimal averageCtc,
            BigDecimal medianCtc) {
    }

    record Departments(List<DepartmentStats> departments, Instant refreshedAt) {
    }

    record CompanyStats(UUID companyId, String companyName, long offers, BigDecimal highestCtc, BigDecimal averageCtc,
            long drives) {
    }

    record Companies(List<CompanyStats> companies, Instant refreshedAt) {
    }

    /** @param maxLpa exclusive upper bound, {@code null} for the open last bucket */
    record CtcBucket(String label, int minLpa, Integer maxLpa, long count) {
    }

    record CtcDistribution(String unit, long offers, List<CtcBucket> buckets, Instant refreshedAt) {
    }

    /** @param period calendar month {@code yyyy-MM} in the institute's time zone */
    record TrendPoint(String period, long offers, long applications) {
    }

    record Trend(String interval, List<TrendPoint> points, Instant refreshedAt) {
    }

    /**
     * Forward stages: {@code reached} = applications currently at this stage or a later forward stage,
     * {@code conversionRate} = reached / reached of the previous forward stage. Exit stages ({@code rejected},
     * {@code withdrawn}, custom states): {@code reached} = count, {@code conversionRate} = share of all applications.
     */
    record FunnelStage(String stage, long count, long reached, double conversionRate) {
    }

    record DriveFunnel(UUID driveId, String driveTitle, UUID companyId, String companyName, long applications,
            List<FunnelStage> stages, double offerRate, Instant refreshedAt) {
    }
}
