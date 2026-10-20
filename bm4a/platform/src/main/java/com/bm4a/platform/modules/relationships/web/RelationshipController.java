package com.bm4a.platform.modules.relationships.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.records.RecordData;
import com.bm4a.platform.modules.relationships.internal.LinkRow;
import com.bm4a.platform.modules.relationships.internal.RelationshipRow;
import com.bm4a.platform.modules.relationships.internal.RelationshipRow.Cardinality;
import com.bm4a.platform.modules.relationships.internal.RelationshipRow.OnDelete;
import com.bm4a.platform.modules.relationships.internal.RelationshipService;
import com.bm4a.platform.modules.relationships.internal.RelationshipService.RelatedRecord;
import com.bm4a.platform.modules.relationships.internal.RelationshipService.RelationshipInput;
import com.bm4a.platform.modules.relationships.internal.RelationshipService.RelationshipView;
import com.bm4a.platform.modules.shared.web.ETags;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

import tools.jackson.databind.JsonNode;

/**
 * Relationship definitions ({@code METADATA_MANAGE} to change), links and related records. Links and related-record
 * listings are authorized per module by the relationship service (ADR-004).
 */
@RestController
class RelationshipController {

    private static final String BASE = "/api/v1/orgs/{orgId}/relationships";

    private final RelationshipService relationships;
    private final TenantAccess access;
    private final ModuleAccess modules;

    RelationshipController(RelationshipService relationships, TenantAccess access, ModuleAccess modules) {
        this.relationships = relationships;
        this.access = access;
        this.modules = modules;
    }

    @PostMapping(BASE)
    ResponseEntity<RelationshipResponse> create(CurrentUser user, @PathVariable UUID orgId,
            @RequestBody RelationshipInput request) {
        TenantContext tenant = access.require(user, orgId, Permission.METADATA_MANAGE);
        RelationshipView view = relationships.create(tenant, request);
        return ResponseEntity.created(URI.create("/api/v1/orgs/" + orgId + "/relationships/" + request.apiKey()))
                .eTag(ETags.of(view.relationship().version()))
                .body(RelationshipResponse.of(view));
    }

    @GetMapping(BASE)
    List<RelationshipResponse> list(CurrentUser user, @PathVariable UUID orgId) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        return relationships.list(tenant).stream().map(RelationshipResponse::of).toList();
    }

    @GetMapping(BASE + "/{key}")
    ResponseEntity<RelationshipResponse> get(CurrentUser user, @PathVariable UUID orgId, @PathVariable String key) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        RelationshipView view = relationships.get(tenant, key);
        return ResponseEntity.ok().eTag(ETags.of(view.relationship().version())).body(RelationshipResponse.of(view));
    }

    @DeleteMapping(BASE + "/{key}")
    ResponseEntity<RelationshipResponse> archive(CurrentUser user, @PathVariable UUID orgId, @PathVariable String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        TenantContext tenant = access.require(user, orgId, Permission.METADATA_MANAGE);
        RelationshipView view = relationships.archive(tenant, key, ETags.requireVersion(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(view.relationship().version())).body(RelationshipResponse.of(view));
    }

    @PostMapping(BASE + "/{key}/links")
    @ResponseStatus(HttpStatus.CREATED)
    LinkResponse link(CurrentUser user, @PathVariable UUID orgId, @PathVariable String key,
            @RequestBody LinkRequest request) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        return LinkResponse.of(relationships.link(tenant, key, request.sourceRecordId(), request.targetRecordId()));
    }

    @DeleteMapping(BASE + "/{key}/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(CurrentUser user, @PathVariable UUID orgId, @PathVariable String key, @PathVariable UUID linkId) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        relationships.unlink(tenant, key, linkId);
    }

    @GetMapping("/api/v1/orgs/{orgId}/modules/{moduleKey}/records/{recordId}/related/{key}")
    PageResponse<RelatedResponse> related(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable UUID recordId, @PathVariable String key,
            @RequestParam(defaultValue = "outgoing") String direction, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ModuleGrant grant = modules.require(user, orgId, moduleKey, RecordAction.READ);
        return relationships.related(grant, recordId, key, "incoming".equals(direction), PageQuery.of(page, size))
                .map(RelatedResponse::of);
    }

    record LinkRequest(UUID sourceRecordId, UUID targetRecordId) {
    }

    record RelationshipResponse(UUID id, String apiKey, String label, String inverseLabel, String sourceModule,
            String targetModule, Cardinality cardinality, OnDelete onDelete, String status, long version,
            Instant createdAt) {

        static RelationshipResponse of(RelationshipView view) {
            RelationshipRow r = view.relationship();
            return new RelationshipResponse(r.id(), r.apiKey(), r.label(), r.inverseLabel(), view.sourceModule(),
                    view.targetModule(), r.cardinality(), r.onDelete(), r.status(), r.version(), r.createdAt());
        }
    }

    record LinkResponse(UUID id, UUID sourceRecordId, UUID targetRecordId, Instant createdAt, UUID createdBy) {

        static LinkResponse of(LinkRow link) {
            return new LinkResponse(link.id(), link.sourceRecordId(), link.targetRecordId(), link.createdAt(),
                    link.createdBy());
        }
    }

    record RelatedResponse(UUID linkId, String module, UUID recordId, long version, JsonNode data) {

        static RelatedResponse of(RelatedRecord related) {
            return new RelatedResponse(related.link().id(), related.module().apiKey(), related.record().id(),
                    related.record().version(),
                    RecordData.visible(related.module(), related.record().data(), related.fields()));
        }
    }
}
