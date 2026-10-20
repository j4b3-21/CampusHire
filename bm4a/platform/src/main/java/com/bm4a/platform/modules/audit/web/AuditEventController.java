package com.bm4a.platform.modules.audit.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.audit.AuditQuery;
import com.bm4a.platform.modules.audit.AuditQuery.AuditEventView;
import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/audit-events")
class AuditEventController {

    private final AuditQuery audit;
    private final TenantAccess access;

    AuditEventController(AuditQuery audit, TenantAccess access) {
        this.audit = audit;
        this.access = access;
    }

    @GetMapping
    PageResponse<AuditEventView> list(CurrentUser user, @PathVariable UUID orgId,
            @RequestParam(required = false) String resourceType, @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String action, @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        TenantContext tenant = access.require(user, orgId, Permission.AUDIT_VIEW);
        return audit.find(tenant.organizationId(), new AuditQuery.Filter(resourceType, resourceId, action, actorUserId),
                PageQuery.of(page, size));
    }
}
