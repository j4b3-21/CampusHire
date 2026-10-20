/**
 * Placement analytics and reports for staff (story T7, C2): overview, per-department and per-company statistics, CTC
 * distribution, monthly trend and drive funnels computed from the typed projections, plus the Excel and PDF
 * placement report. Coordinators only ever see their own departments. Responses are cached in Redis for a short time
 * when it is reachable; Redis is never a source of truth.
 */
@ApplicationModule(id = "analytics", displayName = "Analytics",
        allowedDependencies = { "shared", "users", "access", "tenancy", "records", "metadata" })
package com.campushire.analytics;

import org.springframework.modulith.ApplicationModule;
