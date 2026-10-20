package com.bm4a.platform.modules.access;

import java.util.Optional;
import java.util.UUID;

import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

/**
 * Module-level authorization for record operations (ADR-004). {@code require}:
 * <ol>
 * <li>active membership of an active organization (404 / 403 {@code organization_inactive}, as
 * {@code TenantAccess});</li>
 * <li>the module exists for the tenant (404);</li>
 * <li>OpenFGA {@code can_read}/{@code can_write} on the module - fails closed (503
 * {@code authorization_unavailable});</li>
 * <li>the org role's rule for the action: owners and admins get {@code ALL} without field restrictions; members
 * without a rule for the module get {@code ALL} in {@code MEMBERS} access mode and 403 {@code access_denied} in
 * {@code ROLES} mode; a rule of {@code NONE} (or {@code create: false}) is 403 {@code access_denied}.</li>
 * </ol>
 */
public interface ModuleAccess {

    ModuleGrant require(CurrentUser user, UUID organizationId, String moduleKey, RecordAction action);

    /** For a further module within an operation that already holds a verified context (membership is re-read). */
    ModuleGrant require(TenantContext tenant, String moduleKey, RecordAction action);

    /**
     * Like {@link #require(TenantContext, String, RecordAction)}, but an action the member may not perform yields
     * an empty result instead of 403 (and no access-denied audit event). For capability probes such as "which
     * workflow transitions may I perform"; membership and module failures still throw.
     */
    Optional<ModuleGrant> find(TenantContext tenant, String moduleKey, RecordAction action);

    /** The caller's capabilities in the organization, computed from the same role documents without OpenFGA calls. */
    AccessSummary describe(CurrentUser user, UUID organizationId);
}
