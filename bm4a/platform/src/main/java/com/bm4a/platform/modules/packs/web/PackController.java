package com.bm4a.platform.modules.packs.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.packs.InstalledPack;
import com.bm4a.platform.modules.packs.PackInstallReport;
import com.bm4a.platform.modules.packs.PackInstaller;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Installed packs (members) and pack installation ({@code METADATA_MANAGE}). */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/packs")
class PackController {

    static final int MAX_BODY_BYTES = 512 * 1024;

    private final PackInstaller packs;
    private final TenantAccess access;
    private final JsonMapper jsonMapper;

    PackController(PackInstaller packs, TenantAccess access, JsonMapper jsonMapper) {
        this.packs = packs;
        this.access = access;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping
    List<InstalledPack> list(CurrentUser user, @PathVariable UUID orgId) {
        TenantContext tenant = access.require(user, orgId, Permission.ORGANIZATION_VIEW);
        return packs.installed(tenant.organizationId());
    }

    /** The body is the pack document itself (at most 512 KiB). */
    @PostMapping("/install")
    PackInstallReport install(CurrentUser user, @PathVariable UUID orgId, HttpServletRequest request)
            throws IOException {
        TenantContext tenant = access.require(user, orgId, Permission.METADATA_MANAGE);
        return packs.install(tenant.organizationId(), tenant.userId(), body(request));
    }

    private JsonNode body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        byte[] bytes;
        try (InputStream in = request.getInputStream()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        try {
            return jsonMapper.readTree(bytes);
        } catch (JacksonException ex) {
            throw ApiException.badRequest("malformed_request", "Request body is missing or malformed");
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large",
                "A pack must not exceed " + MAX_BODY_BYTES / 1024 + " KiB");
    }
}
