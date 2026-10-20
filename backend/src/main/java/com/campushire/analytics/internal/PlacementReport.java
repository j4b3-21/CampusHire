package com.campushire.analytics.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import com.campushire.analytics.internal.AnalyticsResponses.CompanyStats;
import com.campushire.analytics.internal.AnalyticsResponses.CtcDistribution;
import com.campushire.analytics.internal.AnalyticsResponses.DepartmentStats;
import com.campushire.analytics.internal.AnalyticsResponses.Overview;

/**
 * Everything a placement report shows, read in one snapshot.
 *
 * @param filters human-readable descriptions of the scope and filters, e.g. {@code Departments: CSE}
 * @param offers every offer of the selected students, any status
 */
record PlacementReport(ZonedDateTime generatedAt, List<String> filters, Overview overview,
        List<DepartmentStats> departments, List<CompanyStats> companies, CtcDistribution distribution,
        List<OfferLine> offers) {

    /** @param ctcLpa CTC in lakh per annum */
    record OfferLine(String rollNumber, String studentName, String department, String company, BigDecimal ctcLpa,
            String offerType, String status, LocalDate date) {
    }
}
