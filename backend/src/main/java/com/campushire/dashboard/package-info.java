/**
 * Role-specific dashboards: institute or department figures for placement staff, profile and application status for
 * students, drive pipelines for recruiters. Staff dashboards are cached briefly in Redis when it is available.
 */
@ApplicationModule(id = "dashboard", displayName = "CampusHire dashboards",
        allowedDependencies = { "shared", "users", "tenancy", "access", "metadata", "records", "common",
                "placement" })
package com.campushire.dashboard;

import org.springframework.modulith.ApplicationModule;
