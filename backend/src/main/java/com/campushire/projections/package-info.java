/**
 * Typed projections of student, drive, application and offer records (ADR-003): maintained by asynchronous,
 * outbox-backed {@code RecordEvent} listeners, guarded by record versions, rebuildable from records, plus the
 * scheduled refresh of the {@code campushire_placement_stats} materialized view.
 */
@ApplicationModule(id = "projections", displayName = "CampusHire projections",
        allowedDependencies = { "shared", "users", "authorization", "tenancy", "access", "metadata", "records",
                "common" })
package com.campushire.projections;

import org.springframework.modulith.ApplicationModule;
