/**
 * Building blocks shared by the CampusHire modules: the caller's CampusHire persona in an organization
 * ({@link CampusMembers}), the app pack's role and module keys, and readers for canonical record values.
 */
@ApplicationModule(id = "common", displayName = "CampusHire common",
        allowedDependencies = { "shared", "users", "tenancy", "access" })
package com.campushire.common;

import org.springframework.modulith.ApplicationModule;
