/**
 * Tenancy: organizations, settings and memberships, and the resolution of a verified {@link TenantContext} for every
 * tenant-scoped request ({@link TenantAccess}). Publishes {@link TenancyEvent}s for audit and OpenFGA tuple changes.
 */
@ApplicationModule(id = "tenancy", displayName = "Tenancy",
        allowedDependencies = { "shared", "users", "authorization" })
package com.bm4a.platform.modules.tenancy;

import org.springframework.modulith.ApplicationModule;
