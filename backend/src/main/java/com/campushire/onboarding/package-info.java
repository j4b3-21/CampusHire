/**
 * Onboarding: students signing in with their college account find their institute by e-mail domain and claim their
 * student record (membership with the {@code student} role, record ownership); student memberships follow department
 * and batch changes of their record; {@code /me} describes the caller's CampusHire persona.
 */
@ApplicationModule(id = "onboarding", displayName = "CampusHire onboarding",
        allowedDependencies = { "shared", "users", "tenancy", "access", "metadata", "records", "audit", "common",
                "apppack" })
package com.campushire.onboarding;

import org.springframework.modulith.ApplicationModule;
