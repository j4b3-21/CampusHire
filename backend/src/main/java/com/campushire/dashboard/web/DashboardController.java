package com.campushire.dashboard.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.dashboard.internal.DashboardService;

import tools.jackson.databind.JsonNode;

@RestController
class DashboardController {

    private final DashboardService dashboards;

    DashboardController(DashboardService dashboards) {
        this.dashboards = dashboards;
    }

    /** The payload's {@code role} tells the client which dashboard shape it received. */
    @GetMapping("/api/v1/orgs/{orgId}/campushire/dashboard")
    JsonNode dashboard(CurrentUser user, @PathVariable UUID orgId) {
        return dashboards.dashboard(user, orgId);
    }
}
