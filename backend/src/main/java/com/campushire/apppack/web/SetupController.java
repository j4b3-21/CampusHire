package com.campushire.apppack.web;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.apppack.internal.AppPackService;
import com.campushire.apppack.internal.AppPackService.SetupResult;
import com.campushire.apppack.internal.AppPackService.SetupStatus;

/** Installs CampusHire into an organization ({@code METADATA_MANAGE}) and reports whether it is installed (member). */
@RestController
class SetupController {

    private static final String PATH = "/api/v1/orgs/{orgId}/campushire/setup";

    private final AppPackService service;

    SetupController(AppPackService service) {
        this.service = service;
    }

    @PostMapping(PATH)
    SetupResult setup(CurrentUser user, @PathVariable UUID orgId, @RequestBody(required = false) SetupRequest request) {
        return service.setup(user, orgId, request == null ? null : request.allowedEmailDomains());
    }

    @GetMapping(PATH)
    SetupStatus status(CurrentUser user, @PathVariable UUID orgId) {
        return service.status(user, orgId);
    }

    record SetupRequest(List<String> allowedEmailDomains) {
    }
}
