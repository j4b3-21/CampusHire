/**
 * Workflow engine (ADR-007): a per-organization state machine on one {@code SINGLE_SELECT} field of a module. Every
 * write path that changes the field (record API, transitions API, imports, trusted application code) is checked by a
 * {@code RecordWriteGuard} and leaves exactly one {@link WorkflowTransition} history entry; actual transitions
 * publish {@link WorkflowTransitioned}. Application code reads definitions and performs transitions through
 * {@link Workflows}.
 */
@ApplicationModule(id = "workflow", displayName = "Workflow",
        allowedDependencies = { "shared", "users", "authorization", "tenancy", "audit", "metadata", "access",
                "records" })
package com.bm4a.platform.modules.workflow;

import org.springframework.modulith.ApplicationModule;
