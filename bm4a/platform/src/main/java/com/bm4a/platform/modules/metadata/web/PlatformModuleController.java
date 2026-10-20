package com.bm4a.platform.modules.metadata.web;

import java.net.URI;
import java.util.List;

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
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.web.ETags;
import com.bm4a.platform.modules.users.CurrentUser;

import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;

/**
 * Platform-global module definitions, visible to every tenant. Only platform administrators (realm role
 * {@code bm4a-platform-admin}) can manage them. Records of global modules are still stored per tenant.
 */
@RestController
@RequestMapping("/api/v1/platform/modules")
class PlatformModuleController {

    private final MetadataService metadata;

    PlatformModuleController(MetadataService metadata) {
        this.metadata = metadata;
    }

    @GetMapping
    List<ModuleResponse> list(CurrentUser user, @RequestParam(defaultValue = "false") boolean includeArchived) {
        requireAdmin(user);
        return metadata.listGlobal(includeArchived).stream().map(ModuleResponse::of).toList();
    }

    @PostMapping
    ResponseEntity<ModuleResponse> create(CurrentUser user, @Valid @RequestBody CreateModuleRequest request) {
        requireAdmin(user);
        ModuleView view = metadata.createModule(MetadataScope.global(), user.id(), request.input(), request.fields());
        return ResponseEntity.created(URI.create("/api/v1/platform/modules/" + view.module().apiKey()))
                .eTag(ETags.of(view.module().version()))
                .body(ModuleResponse.of(view));
    }

    @GetMapping("/{moduleKey}")
    ResponseEntity<ModuleResponse> get(CurrentUser user, @PathVariable String moduleKey) {
        requireAdmin(user);
        return MetadataDtos.module(metadata.getOwned(MetadataScope.global(), moduleKey));
    }

    @PatchMapping("/{moduleKey}")
    ResponseEntity<ModuleResponse> update(CurrentUser user, @PathVariable String moduleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateModuleRequest request) {
        requireAdmin(user);
        return MetadataDtos.module(metadata.updateModule(MetadataScope.global(), user.id(), moduleKey,
                ETags.requireVersion(ifMatch), request.input()));
    }

    @DeleteMapping("/{moduleKey}")
    ResponseEntity<ModuleResponse> archive(CurrentUser user, @PathVariable String moduleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        requireAdmin(user);
        return MetadataDtos.module(metadata.setModuleStatus(MetadataScope.global(), user.id(), moduleKey,
                ETags.requireVersion(ifMatch), ModuleRow.ARCHIVED));
    }

    @PutMapping("/{moduleKey}/layout")
    ResponseEntity<ModuleResponse> layout(CurrentUser user, @PathVariable String moduleKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody JsonNode layout) {
        requireAdmin(user);
        return MetadataDtos.module(metadata.updateLayout(MetadataScope.global(), user.id(), moduleKey,
                ETags.requireVersion(ifMatch), layout));
    }

    @PostMapping("/{moduleKey}/fields")
    ResponseEntity<FieldResponse> addField(CurrentUser user, @PathVariable String moduleKey,
            @RequestBody FieldInput request) {
        requireAdmin(user);
        FieldRow field = metadata.addField(MetadataScope.global(), user.id(), moduleKey, request);
        return ResponseEntity.created(URI.create("/api/v1/platform/modules/" + moduleKey + "/fields/" + field.apiKey()))
                .eTag(ETags.of(field.version()))
                .body(FieldResponse.of(field));
    }

    @PatchMapping("/{moduleKey}/fields/{fieldKey}")
    ResponseEntity<FieldResponse> updateField(CurrentUser user, @PathVariable String moduleKey,
            @PathVariable String fieldKey, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody FieldInput request) {
        requireAdmin(user);
        return MetadataDtos.field(metadata.updateField(MetadataScope.global(), user.id(), moduleKey, fieldKey,
                ETags.requireVersion(ifMatch), request));
    }

    @PostMapping("/{moduleKey}/fields/{fieldKey}/{transition:deprecate|archive|activate}")
    ResponseEntity<FieldResponse> fieldStatus(CurrentUser user, @PathVariable String moduleKey,
            @PathVariable String fieldKey, @PathVariable String transition,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        requireAdmin(user);
        return MetadataDtos.field(metadata.setFieldStatus(MetadataScope.global(), user.id(), moduleKey, fieldKey,
                ETags.requireVersion(ifMatch), ModuleController.statusFor(transition)));
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.platformAdmin()) {
            throw ApiException.forbidden("access_denied", "Platform administrator role required");
        }
    }
}
