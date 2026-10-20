/**
 * Generic record engine: one API and one table serve every configured module. Validates payloads against metadata,
 * stores canonical JSONB, and provides optimistic concurrency, history, soft deletion and restore. Other modules read
 * records through {@link RecordReader} and can veto or react to deletions through {@link RecordDeletionGuard}.
 */
@ApplicationModule(id = "records", displayName = "Records",
        allowedDependencies = { "shared", "users", "authorization", "tenancy", "audit", "metadata" })
package com.bm4a.platform.modules.records;

import org.springframework.modulith.ApplicationModule;
