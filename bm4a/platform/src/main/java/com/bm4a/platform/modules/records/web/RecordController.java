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

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.records.RecordData;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.records.internal.RecordService;
import com.bm4a.platform.modules.records.internal.RecordVersion;
import com.bm4a.platform.modules.shared.web.ETags;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.users.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * The single record API for every configured module. Request bodies are {@code {"data": {...}}}; any other top-level
 * property (for example {@code id}, {@code organizationId}, {@code createdBy}) is rejected, so system-managed values
 * can never be mass-assigned. Every endpoint is authorized per module and action by {@link ModuleAccess} (ADR-004):
 * records outside the caller's scope are 404, hidden fields never appear in responses.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/modules/{moduleKey}/records")
class RecordController {

    static final String MERGE_PATCH = "application/merge-patch+json";

    private final RecordService records;
    private final ModuleAccess access;

    RecordController(RecordService records, ModuleAccess access) {
        this.records = records;
        this.access = access;
    }

    @PostMapping
    ResponseEntity<RecordResponse> create(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @Valid @RequestBody RecordWriteRequest request) {
        ModuleGrant grant = access.require(user, orgId, moduleKey, RecordAction.CREATE);
        RecordView record = records.create(grant, request.data());
        return ResponseEntity
                .created(URI.create("/api/v1/orgs/" + orgId + "/modules/" + moduleKey + "/records/" + record.id()))
                .eTag(ETags.of(record.version()))
                .body(RecordResponse.of(grant, record));
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
        ModuleGrant grant = access.require(user, orgId, moduleKey, RecordAction.READ);
        return records.list(grant, params, sort, q, deleted, PageQuery.of(page, size))
                .map(record -> RecordResponse.of(grant, record));
    }

    @GetMapping("/{id}")
    ResponseEntity<RecordResponse> get(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id) {
        ModuleGrant grant = access.require(user, orgId, moduleKey, RecordAction.READ);
        return respond(grant, records.get(grant, id));
    }

    /** Full replacement of all visible fields; omitted fields are cleared. */
    @PutMapping("/{id}")
    ResponseEntity<RecordResponse> replace(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RecordWriteRequest request) {
        ModuleGrant grant = access.require(user, orgId, moduleKey, RecordAction.UPDATE);
        long version = ETags.requireVersion(ifMatch);
        return respond(grant, records.replace(grant, id, version, request.data()));
    }

    /** JSON Merge Patch of {@code data}: present keys are set, {@code null} clears, absent keys are unchanged. */
    @PatchMapping(value = "/{id}", consumes = { MediaType.APPLICATION_JSON_VALUE, MERGE_PATCH })
    ResponseEntity<RecordResponse> patch(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RecordWriteRequest request) {
        ModuleGrant grant = access.require(user, orgId, moduleKey, RecordAction.UPDATE);
        long version = ETags.requireVersion(ifMatch);
        return respond(grant, records.patch(grant, id, version, request.data()));
    }

    /** Soft delete. Returns the deleted record so clients learn the version needed to restore it. */
    @DeleteMapping("/{id}")
    ResponseEntity<RecordResponse> delete(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        ModuleGrant grant = access.require(user, orgId, moduleKey, RecordAction.DELETE);
        long version = ETags.requireVersion(ifMatch);
        return respond(grant, records.delete(grant, id, version));
    }

    /** Undoes a soft delete; requires the delete permission on the record. */
    @PostMapping("/{id}/restore")
    ResponseEntity<RecordResponse> restore(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        ModuleGrant grant = access.require(user, orgId, moduleKey, RecordAction.DELETE);
        long version = ETags.requireVersion(ifMatch);
        return respond(grant, records.restore(grant, id, version));
    }

    @GetMapping("/{id}/versions")
    PageResponse<VersionResponse> versions(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ModuleGrant grant = access.require(user, orgId, moduleKey, RecordAction.READ);
        return records.versions(grant, id, PageQuery.of(page, size)).map(version -> VersionResponse.of(grant, version));
    }

    @GetMapping("/{id}/versions/{version}")
    VersionResponse version(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @PathVariable long version) {
        ModuleGrant grant = access.require(user, orgId, moduleKey, RecordAction.READ);
        return VersionResponse.of(grant, records.version(grant, id, version));
    }

    /** Makes the values of an earlier version current again (as a new version). */
    @PostMapping("/{id}/versions/{version}/restore")
    ResponseEntity<RecordResponse> revert(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID id, @PathVariable long version,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        ModuleGrant grant = access.require(user, orgId, moduleKey, RecordAction.UPDATE);
        long expected = ETags.requireVersion(ifMatch);
        return respond(grant, records.revert(grant, id, version, expected));
    }

    private static ResponseEntity<RecordResponse> respond(ModuleGrant grant, RecordView record) {
        return ResponseEntity.ok().eTag(ETags.of(record.version())).body(RecordResponse.of(grant, record));
    }

    record RecordWriteRequest(@NotNull JsonNode data) {
    }

    record RecordResponse(UUID id, String module, long version, JsonNode data, Instant createdAt, UUID createdBy,
            Instant updatedAt, UUID updatedBy, Instant deletedAt, UUID ownerUserId) {

        static RecordResponse of(ModuleGrant grant, RecordView record) {
            return new RecordResponse(record.id(), grant.module().apiKey(), record.version(),
                    RecordData.visible(grant.module(), record.data(), grant.fields()), record.createdAt(),
                    record.createdBy(), record.updatedAt(), record.updatedBy(), record.deletedAt(),
                    record.ownerUserId());
        }
    }

    record VersionResponse(long version, String changeType, boolean deleted, JsonNode data, Instant changedAt,
            UUID changedBy) {

        static VersionResponse of(ModuleGrant grant, RecordVersion version) {
            return new VersionResponse(version.version(), version.changeType(), version.deleted(),
                    RecordData.visible(grant.module(), version.data(), grant.fields()), version.changedAt(),
                    version.changedBy());
        }
    }
}
