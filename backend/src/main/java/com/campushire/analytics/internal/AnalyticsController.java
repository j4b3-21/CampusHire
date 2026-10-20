package com.campushire.analytics.internal;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.analytics.internal.AnalyticsResponses.Companies;
import com.campushire.analytics.internal.AnalyticsResponses.CtcDistribution;
import com.campushire.analytics.internal.AnalyticsResponses.Departments;
import com.campushire.analytics.internal.AnalyticsResponses.DriveFunnel;
import com.campushire.analytics.internal.AnalyticsResponses.Overview;
import com.campushire.analytics.internal.AnalyticsResponses.Trend;

/**
 * Staff analytics (PRD T7, C2). Every endpoint accepts the optional filters {@code batchId}, {@code departmentId},
 * {@code from} and {@code to} (ISO dates, inclusive); coordinators are restricted to their departments.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/campushire/analytics")
class AnalyticsController {

    private final StaffAccess staff;
    private final AnalyticsService analytics;

    AnalyticsController(StaffAccess staff, AnalyticsService analytics) {
        this.staff = staff;
        this.analytics = analytics;
    }

    @GetMapping("/overview")
    Overview overview(CurrentUser user, @PathVariable UUID orgId, @ModelAttribute AnalyticsQuery query) {
        return analytics.overview(staff.require(user, orgId), query.validated());
    }

    @GetMapping("/departments")
    Departments departments(CurrentUser user, @PathVariable UUID orgId, @ModelAttribute AnalyticsQuery query) {
        return analytics.departments(staff.require(user, orgId), query.validated());
    }

    @GetMapping("/companies")
    Companies companies(CurrentUser user, @PathVariable UUID orgId, @ModelAttribute AnalyticsQuery query,
            @RequestParam(defaultValue = "10") int limit) {
        return analytics.companies(staff.require(user, orgId), query.validated(), limit);
    }

    @GetMapping("/ctc-distribution")
    CtcDistribution ctcDistribution(CurrentUser user, @PathVariable UUID orgId,
            @ModelAttribute AnalyticsQuery query) {
        return analytics.ctcDistribution(staff.require(user, orgId), query.validated());
    }

    @GetMapping("/trend")
    Trend trend(CurrentUser user, @PathVariable UUID orgId, @ModelAttribute AnalyticsQuery query,
            @RequestParam(defaultValue = "month") String interval) {
        return analytics.trend(staff.require(user, orgId), query.validated(), interval);
    }

    @GetMapping("/drives/{driveId}/funnel")
    DriveFunnel funnel(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @ModelAttribute AnalyticsQuery query) {
        return analytics.funnel(staff.require(user, orgId), driveId, query.validated());
    }
}
