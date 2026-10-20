/**
 * Organization roles, module permissions and record scopes (ADR-004). Owns the {@code org_role} table and its
 * permission documents, synchronizes the derived OpenFGA tuples, and decides per request through
 * {@link ModuleAccess} whether a member may use a module and with which {@link RecordScope} and {@link FieldPolicy}.
 * Implements tenancy's {@code OrgRoleCatalog}, so tenancy can assign roles without depending on this module. Trusted
 * application code (app packs) defines roles through {@link RoleDefinitions}.
 */
@ApplicationModule(id = "access", displayName = "Access",
        allowedDependencies = { "shared", "users", "authorization", "tenancy", "metadata", "audit" })
package com.bm4a.platform.modules.access;

import org.springframework.modulith.ApplicationModule;
