package com.bm4a.platform.modules.records;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantContext;

import tools.jackson.databind.JsonNode;

/**
 * Tenant-scoped record operations for trusted application code (importers, app packs, workflows). <strong>No
 * permission checks, record scopes or field policies are applied</strong>: callers must authorize the operation
 * themselves (for example with {@code ModuleAccess}). Everything else is identical to the record API: payload
 * validation, lookups, uniqueness, history, audit (actor = {@code tenant.userId()}), {@link RecordEvent}s and
 * {@link RecordWriteGuard}s ({@link RecordWrite#trusted()} is {@code true}). Unknown modules and records are 404.
 */
public interface RecordOperations {

    /** @param ownerUserId owner for {@code OWN} scopes; {@code null} means the acting user */
    RecordView create(TenantContext tenant, String moduleKey, JsonNode data, UUID ownerUserId);

    /**
     * JSON Merge Patch of {@code data}.
     *
     * @param expectedVersion optimistic concurrency check; {@code null} skips it
     */
    RecordView patch(TenantContext tenant, String moduleKey, UUID id, Long expectedVersion, JsonNode data);

    /** An active (not deleted) record. */
    Optional<RecordView> get(TenantContext tenant, String moduleKey, UUID id);

    /**
     * The oldest active record whose {@code fieldKey} equals {@code value} (canonicalized like record input; for
     * multi-select fields: contains). Intended for unique fields; served by the JSONB containment index.
     */
    Optional<RecordView> findActiveByField(UUID organizationId, String moduleKey, String fieldKey, JsonNode value);

    /**
     * The active record holding {@code value} in the {@code unique} field {@code fieldKey}, compared exactly as
     * uniqueness is enforced (text, e-mail, phone and URL values case-insensitively). Unknown fields are 400, fields
     * that are not unique 400 {@code field_not_unique}.
     */
    Optional<RecordView> findActiveByUniqueValue(UUID organizationId, String moduleKey, String fieldKey,
            JsonNode value);

    /**
     * Same query language as the record API ({@code filter[field][op]}, {@code sort}, {@code q}), restricted to
     * {@code scope} ({@link RecordScope#all()} for everything).
     */
    PageResponse<RecordView> list(TenantContext tenant, String moduleKey, Map<String, List<String>> filters,
            String sort, String q, RecordScope scope, PageQuery page);

    /** Makes {@code newOwnerUserId} (an active user) the owner; a new version without data changes. */
    RecordView transferOwner(TenantContext tenant, String moduleKey, UUID id, UUID newOwnerUserId);

    /** Soft delete. @param expectedVersion {@code null} skips the concurrency check */
    RecordView delete(TenantContext tenant, String moduleKey, UUID id, Long expectedVersion);
}
