package com.bm4a.platform.modules.access.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.access.AccessSummary;
import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.users.CurrentUser;

@RestController
class MyAccessController {

    private final ModuleAccess access;

    MyAccessController(ModuleAccess access) {
        this.access = access;
    }

    /** The caller's role, attributes and per-module capabilities, so UIs can render menus and forms. */
    @GetMapping("/api/v1/orgs/{orgId}/me/access")
    AccessSummary myAccess(CurrentUser user, @PathVariable UUID orgId) {
        return access.describe(user, orgId);
    }
}
