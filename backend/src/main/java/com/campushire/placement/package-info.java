/**
 * Placement lifecycle: eligibility engine, drives, applying and withdrawing, pipeline bulk operations, offers and the
 * institute's placement policy. {@link PlacementInsights} answers placement questions for other modules.
 */
@ApplicationModule(id = "placement", displayName = "Placement",
        allowedDependencies = { "shared", "users", "tenancy", "access", "metadata", "records", "workflow", "audit",
                "files", "notifications", "common" })
package com.campushire.placement;

import org.springframework.modulith.ApplicationModule;
