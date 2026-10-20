package com.bm4a.platform.modules.records.web;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.records.RecordData;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.records.internal.RecordService;
import com.bm4a.platform.modules.records.internal.RecordVersion;
import com.bm4a.platform.modules.shared.web.ETags;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * The single record API for every configured module. Request bodies are {@code {"data": {...}}}; any other top-level
 * property (for example {@code id}, {@code organizationId}, {@code createdBy}) is rejected, so system-managed values
 * can never be mass-assigned.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/modules/{moduleKey}/records")
class RecordController {

    static final String MERGE_PATCH = "application/merge-patch+json";

    private final RecordService records;
    private final TenantAccess access;

    RecordController(RecordService records, TenantAccess access) {
        this.records = records;
        this.access = access;
    }

    @PostMapping
    ResponseEntity<RecordResponse> create(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @Valid @RequestBody RecordWriteRequest request) {
        TenantContext tenant = access.require(user, orgId, Permission.RECORDS_WRITE);
        RecordView record = records.create(tenant, moduleKey, request.data());
        return ResponseEntity
                .created(URI.create("/api/v1/orgs/" + orgId + "/modules/" + moduleKey + "/records/" + record.id()))
                .eTag(ETags.of(record.version()))
                .body(RecordResponse.of(records.module(tenant, moduleKey), record));
    }

    /**
     * Query parameters: {@code page}, {@code size} (max 100), {@code sort} (e.g. {@code -price,name}), {@code q}
     * (search), {@code deleted=true} (trash), and {@code filter[field]} / {@code filter[field][op]}.
     */
    @GetMapping
    PageResponse<RecordResponse> list(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean deleted, @RequestParam MultiValueMap<String, String> params) {
        TenantContext tenant = access.require(user, orgId, Permission.RECORDS_READ);
        ModuleSchema module = records.module(tenant, moduleKey);
        return records.list(tenant, moduleKey, params, sort, q, deleted, PageQuery.of(page, size))
                .map(record -> RecordResponse.of(module, record));
    }

    @GetMapping("/{id}")
    ResponseEntity<RecordResponse> get(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id) {
        TenantContext tenant = access.require(user, orgId, Permission.RECORDS_READ);
        return respond(tenant, moduleKey, records.get(tenant, moduleKey, id));
    }

    /** Full replacement of all visible fields; omitted fields are cleared. */
    @PutMapping("/{id}")
    ResponseEntity<RecordResponse> replace(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RecordWriteRequest request) {
        TenantContext tenant = access.require(user, orgId, Permission.RECORDS_WRITE);
        long version = ETags.requireVersion(ifMatch);
        return respond(tenant, moduleKey, records.replace(tenant, moduleKey, id, version, request.data()));
    }

    /** JSON Merge Patch of {@code data}: present keys are set, {@code null} clears, absent keys are unchanged. */
    @PatchMapping(value = "/{id}", consumes = { MediaType.APPLICATION_JSON_VALUE, MERGE_PATCH })
    ResponseEntity<RecordResponse> patch(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RecordWriteRequest request) {
        TenantContext tenant = access.require(user, orgId, Permission.RECORDS_WRITE);
        long version = ETags.requireVersion(ifMatch);
        return respond(tenant, moduleKey, records.patch(tenant, moduleKey, id, version, request.data()));
    }

    /** Soft delete. Returns the deleted record so clients learn the version needed to restore it. */
    @DeleteMapping("/{id}")
    ResponseEntity<RecordResponse> delete(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        TenantContext tenant = access.require(user, orgId, Permission.RECORDS_WRITE);
        long version = ETags.requireVersion(ifMatch);
        return respond(tenant, moduleKey, records.delete(tenant, moduleKey, id, version));
    }

    @PostMapping("/{id}/restore")
    ResponseEntity<RecordResponse> restore(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        TenantContext tenant = access.require(user, orgId, Permission.RECORDS_WRITE);
        long version = ETags.requireVersion(ifMatch);
        return respond(tenant, moduleKey, records.restore(tenant, moduleKey, id, version));
    }

    @GetMapping("/{id}/versions")
    PageResponse<VersionResponse> versions(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        TenantContext tenant = access.require(user, orgId, Permission.RECORDS_READ);
        ModuleSchema module = records.module(tenant, moduleKey);
        return records.versions(tenant, moduleKey, id, PageQuery.of(page, size))
                .map(version -> VersionResponse.of(module, version));
    }

    @GetMapping("/{id}/versions/{version}")
    VersionResponse version(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @PathVariable long version) {
        TenantContext tenant = access.require(user, orgId, Permission.RECORDS_READ);
        return VersionResponse.of(records.module(tenant, moduleKey), records.version(tenant, moduleKey, id, version));
    }

    /** Makes the values of an earlier version current again (as a new version). */
    @PostMapping("/{id}/versions/{version}/restore")
    ResponseEntity<RecordResponse> revert(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @PathVariable long version,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        TenantContext tenant = access.require(user, orgId, Permission.RECORDS_WRITE);
        long expected = ETags.requireVersion(ifMatch);
        return respond(tenant, moduleKey, records.revert(tenant, moduleKey, id, version, expected));
    }

    private ResponseEntity<RecordResponse> respond(TenantContext tenant, String moduleKey, RecordView record) {
        return ResponseEntity.ok().eTag(ETags.of(record.version()))
                .body(RecordResponse.of(records.module(tenant, moduleKey), record));
    }

    record RecordWriteRequest(@NotNull JsonNode data) {
    }

    record RecordResponse(UUID id, String module, long version, JsonNode data, Instant createdAt, UUID createdBy,
            Instant updatedAt, UUID updatedBy, Instant deletedAt) {

        static RecordResponse of(ModuleSchema module, RecordView record) {
            return new RecordResponse(record.id(), module.apiKey(), record.version(),
                    RecordData.visible(module, record.data()), record.createdAt(), record.createdBy(),
                    record.updatedAt(), record.updatedBy(), record.deletedAt());
        }
    }

    record VersionResponse(long version, String changeType, boolean deleted, JsonNode data, Instant changedAt,
            UUID changedBy) {

        static VersionResponse of(ModuleSchema module, RecordVersion version) {
            return new VersionResponse(version.version(), version.changeType(), version.deleted(),
                    RecordData.visible(module, version.data()), version.changedAt(), version.changedBy());
        }
    }
}
