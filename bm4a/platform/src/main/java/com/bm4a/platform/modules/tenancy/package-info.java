/**
 * Tenancy: organizations, settings, memberships (with org role and attributes) and invitations, and the resolution of
 * a verified {@link TenantContext} for every tenant-scoped request ({@link TenantAccess}). Publishes
 * {@link TenancyEvent}s for audit and OpenFGA tuple changes. Trusted code manages memberships through
 * {@link MembershipAdministration}; org roles are looked up through {@link OrgRoleCatalog}, implemented by the access
 * module (which depends on tenancy, not the other way round); invitation mail is delivered through the
 * {@link InvitationMailer} SPI, implemented by the notifications module, and applications extend organization
 * settings through {@link OrganizationSettings}.
 */
@ApplicationModule(id = "tenancy", displayName = "Tenancy",
        allowedDependencies = { "shared", "users", "authorization" })
package com.bm4a.platform.modules.tenancy;

import org.springframework.modulith.ApplicationModule;
