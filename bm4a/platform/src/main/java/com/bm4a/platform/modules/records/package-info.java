/**
 * Generic record engine: one API and one table serve every configured module. Validates payloads against metadata,
 * stores canonical JSONB, and provides optimistic concurrency, history, soft deletion and restore. The record API is
 * authorized per module through the access module's {@code ModuleAccess}; record scopes become SQL predicates
 * ({@link RecordScopes}) and field policies filter responses and writes. Other modules read records through
 * {@link RecordReader}, write them as trusted code through {@link RecordOperations}, and can veto changes through
 * {@link RecordWriteGuard} and {@link RecordDeletionGuard}.
 */
@ApplicationModule(id = "records", displayName = "Records",
        allowedDependencies = { "shared", "users", "authorization", "tenancy", "audit", "metadata", "access" })
package com.bm4a.platform.modules.records;

import org.springframework.modulith.ApplicationModule;
