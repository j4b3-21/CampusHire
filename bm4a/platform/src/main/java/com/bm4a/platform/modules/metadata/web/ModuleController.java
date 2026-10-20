package com.bm4a.platform.modules.metadata.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.internal.FieldInput;
import com.bm4a.platform.modules.metadata.internal.FieldRow;
import com.bm4a.platform.modules.metadata.internal.MetadataScope;
import com.bm4a.platform.modules.metadata.internal.MetadataService;
import com.bm4a.platform.modules.metadata.internal.ModuleRow;
import com.bm4a.platform.modules.metadata.internal.ModuleView;
import com.bm4a.platform.modules.metadata.web.MetadataDtos.CreateModuleRequest;
import com.bm4a.platform.modules.metadata.web.MetadataDtos.FieldResponse;
import com.bm4a.platform.modules.metadata.web.MetadataDtos.ModuleResponse;
import com.bm4a.platform.modules.metadata.web.MetadataDtos.UpdateModuleRequest;
import com.bm4a.platform.modules.metadata.web.MetadataDtos.VersionResponse;
import com.bm4a.platform.modules.shared.web.ETags;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;

/**
 * Tenant metadata API. Members can read definitions (their own tenant's plus platform-global ones); changes require
 * {@code METADATA_MANAGE} and are limited to the tenant's own modules.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/modules")
class ModuleController {

    private final MetadataService metadata;
    private final TenantAccess access;

    ModuleController(MetadataService metadata, TenantAccess access) {
        this.metadata = metadata;
        this.access = access;
    }

    @GetMapping
    List<ModuleResponse> list(CurrentUser user, @PathVariable UUID orgId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        return metadata.listVisible(tenant.organizationId(), includeArchived).stream().map(ModuleResponse::of).toList();
    }

    @PostMapping
    ResponseEntity<ModuleResponse> create(CurrentUser user, @PathVariable UUID orgId,
            @Valid @RequestBody CreateModuleRequest request) {
        TenantContext tenant = manage(user, orgId);
        ModuleView view = metadata.createModule(scope(tenant), tenant.userId(), request.input(), request.fields());
        return ResponseEntity.created(URI.create("/api/v1/orgs/" + orgId + "/modules/" + view.module().apiKey()))
                .eTag(ETags.of(view.module().version()))
                .body(ModuleResponse.of(view));
    }

    @GetMapping("/{moduleKey}")
    ResponseEntity<ModuleResponse> get(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        return MetadataDtos.module(metadata.getVisible(tenant.organizationId(), moduleKey));
    }

    @PatchMapping("/{moduleKey}")
    ResponseEntity<ModuleResponse> update(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateModuleRequest request) {
        TenantContext tenant = manage(user, orgId);
        return MetadataDtos.module(metadata.updateModule(scope(tenant), tenant.userId(), moduleKey,
                ETags.requireVersion(ifMatch), request.input()));
    }

    @DeleteMapping("/{moduleKey}")
    ResponseEntity<ModuleResponse> archive(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        TenantContext tenant = manage(user, orgId);
        return MetadataDtos.module(metadata.setModuleStatus(scope(tenant), tenant.userId(), moduleKey,
                ETags.requireVersion(ifMatch), ModuleRow.ARCHIVED));
    }

    @PostMapping("/{moduleKey}/restore")
    ResponseEntity<ModuleResponse> restore(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        TenantContext tenant = manage(user, orgId);
        return MetadataDtos.module(metadata.setModuleStatus(scope(tenant), tenant.userId(), moduleKey,
                ETags.requireVersion(ifMatch), ModuleRow.ACTIVE));
    }

    @PutMapping("/{moduleKey}/layout")
    ResponseEntity<ModuleResponse> layout(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody JsonNode layout) {
        TenantContext tenant = manage(user, orgId);
        return MetadataDtos.module(metadata.updateLayout(scope(tenant), tenant.userId(), moduleKey,
                ETags.requireVersion(ifMatch), layout));
    }

    @GetMapping("/{moduleKey}/versions")
    PageResponse<VersionResponse> versions(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        UUID moduleId = metadata.getVisible(tenant.organizationId(), moduleKey).module().id();
        return metadata.versions(moduleId, PageQuery.of(page, size)).map(VersionResponse::of);
    }

    @GetMapping("/{moduleKey}/versions/{version}")
    VersionResponse version(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable long version) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        UUID moduleId = metadata.getVisible(tenant.organizationId(), moduleKey).module().id();
        return VersionResponse.of(metadata.version(moduleId, version));
    }

    @GetMapping("/{moduleKey}/fields")
    List<FieldResponse> fields(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        return metadata.getVisible(tenant.organizationId(), moduleKey).fields().stream().map(FieldResponse::of)
                .toList();
    }

    @GetMapping("/{moduleKey}/fields/{fieldKey}")
    ResponseEntity<FieldResponse> field(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @PathVariable String fieldKey) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        UUID moduleId = metadata.getVisible(tenant.organizationId(), moduleKey).module().id();
        return MetadataDtos.field(metadata.getField(moduleId, fieldKey));
    }

    @PostMapping("/{moduleKey}/fields")
    ResponseEntity<FieldResponse> addField(CurrentUser user, @PathVariable UUID orgId, @PathVariable String moduleKey,
            @RequestBody FieldInput request) {
        TenantContext tenant = manage(user, orgId);
        FieldRow field = metadata.addField(scope(tenant), tenant.userId(), moduleKey, request);
        return ResponseEntity
                .created(URI.create("/api/v1/orgs/" + orgId + "/modules/" + moduleKey + "/fields/" + field.apiKey()))
                .eTag(ETags.of(field.version()))
                .body(FieldResponse.of(field));
    }

    @PatchMapping("/{moduleKey}/fields/{fieldKey}")
    ResponseEntity<FieldResponse> updateField(CurrentUser user, @PathVariable UUID orgId,
            @PathVariable String moduleKey, @PathVariable String fieldKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody FieldInput request) {
        TenantContext tenant = manage(user, orgId);
        return MetadataDtos.field(metadata.updateField(scope(tenant), tenant.userId(), moduleKey, fieldKey,
                ETags.requireVersion(ifMatch), request));
    }

    @PostMapping("/{moduleKey}/fields/{fieldKey}/{transition:deprecate|archive|activate}")
    ResponseEntity<FieldResponse> fieldStatus(CurrentUser user, @PathVariable UUID orgId,
            @PathVariable String moduleKey, @PathVariable String fieldKey, @PathVariable String transition,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        TenantContext tenant = manage(user, orgId);
        return MetadataDtos.field(metadata.setFieldStatus(scope(tenant), tenant.userId(), moduleKey, fieldKey,
                ETags.requireVersion(ifMatch), statusFor(transition)));
    }

    static FieldStatus statusFor(String transition) {
        return switch (transition) {
            case "deprecate" -> FieldStatus.DEPRECATED;
            case "archive" -> FieldStatus.ARCHIVED;
            default -> FieldStatus.ACTIVE;
        };
    }

    private TenantContext manage(CurrentUser user, UUID orgId) {
        return access.require(user, orgId, Permission.METADATA_MANAGE);
    }

    private static MetadataScope scope(TenantContext tenant) {
        return MetadataScope.tenant(tenant.organizationId());
    }
}
