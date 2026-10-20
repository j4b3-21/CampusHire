/**
 * Metadata engine: tenant-owned and platform-global module definitions, typed field definitions, layouts and
 * definition versions. Exposes read-only {@link ModuleSchema}s through {@link ModuleSchemas} and the canonical value
 * rules in {@link FieldValues}, which the record engine uses for validation. Trusted application code (app packs)
 * defines modules through {@link ModuleDefinitions}.
 */
@ApplicationModule(id = "metadata", displayName = "Metadata",
        allowedDependencies = { "shared", "users", "authorization", "tenancy", "audit" })
package com.bm4a.platform.modules.metadata;

import org.springframework.modulith.ApplicationModule;
