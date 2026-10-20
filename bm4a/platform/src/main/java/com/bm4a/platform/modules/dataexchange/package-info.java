/**
 * Bulk data exchange (ADR-005): import templates, file previews with suggested column mappings, asynchronous
 * XLSX/CSV import jobs (streaming readers, batches of 500 rows, per-row error reports, dry runs, {@code UPSERT} on a
 * unique field) and streaming XLSX/CSV exports of record lists. Every record is written through the records module's
 * {@code RecordOperations}, so validation, uniqueness, history and audit are those of the record API; permissions,
 * record scopes and field policies come from {@code ModuleAccess}.
 */
@ApplicationModule(id = "dataexchange", displayName = "Data exchange",
        allowedDependencies = { "shared", "users", "tenancy", "metadata", "access", "records", "files", "audit" })
package com.bm4a.platform.modules.dataexchange;

import org.springframework.modulith.ApplicationModule;
