/**
 * Application packs: a generic, idempotent installer for declarative pack documents (modules with fields and layouts,
 * org roles, relationships, workflows and organization setting defaults). Installs are additive - existing
 * definitions are never modified, except that missing select options are appended - and versioned per organization.
 * Applications install their pack through {@link PackInstaller}.
 */
@ApplicationModule(id = "packs", displayName = "Packs",
        allowedDependencies = { "shared", "users", "authorization", "tenancy", "audit", "metadata", "access",
                "relationships", "workflow" })
package com.bm4a.platform.modules.packs;

import org.springframework.modulith.ApplicationModule;
