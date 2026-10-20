package com.bm4a.platform.modules.tenancy;

import java.util.UUID;

import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.users.CurrentUser;

/**
 * Turns an organization ID taken from a request into a verified {@link TenantContext}.
 * <ul>
 * <li>no active membership (including revoked, or the organization does not exist) - 404 {@code not_found}</li>
 * <li>organization suspended or archived - 403 {@code organization_inactive}</li>
 * <li>permission not granted - 403 {@code access_denied}</li>
 * <li>authorization backend unavailable - 503 {@code authorization_unavailable}</li>
 * </ul>
 */
public interface TenantAccess {

    TenantContext require(CurrentUser user, UUID organizationId, Permission permission);

    /**
     * Steps 1 and 2 of {@link #require}: an active membership in an active organization, read from the database on
     * every call. No permission is checked; callers authorize the operation themselves (for example the access
     * module's module-level check).
     */
    ActiveMembership requireMembership(UUID userId, UUID organizationId);
}
