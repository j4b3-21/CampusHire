/**
 * Append-only audit trail: {@link AuditLog} records events inside the caller's transaction (an audited mutation
 * and its audit row commit or roll back together); {@link AuditQuery} reads them back per organization. Tenancy
 * changes are captured by listening to {@code TenancyEvent}s synchronously, inside the publishing transaction.
 */
@ApplicationModule(id = "audit", displayName = "Audit",
        allowedDependencies = { "shared", "users", "authorization", "tenancy" })
package com.bm4a.platform.modules.audit;

import org.springframework.modulith.ApplicationModule;
