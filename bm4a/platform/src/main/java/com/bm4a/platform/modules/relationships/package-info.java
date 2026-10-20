/**
 * Relationship engine: tenant-defined, cardinality-checked links between records of two modules (one-to-one,
 * one-to-many, many-to-many). Many-to-one references are covered by lookup fields in the record engine. Plugs into
 * record deletion through {@code RecordDeletionGuard} to restrict or detach links. Trusted application code (app
 * packs) defines relationships through {@link RelationshipDefinitions}.
 */
@ApplicationModule(id = "relationships", displayName = "Relationships",
        allowedDependencies = { "shared", "users", "authorization", "tenancy", "audit", "metadata", "records",
                "access" })
package com.bm4a.platform.modules.relationships;

import org.springframework.modulith.ApplicationModule;
