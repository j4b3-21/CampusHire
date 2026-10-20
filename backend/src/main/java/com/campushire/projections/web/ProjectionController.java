package com.campushire.projections.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.projections.internal.ProjectionRebuildService;
import com.campushire.projections.internal.ProjectionRebuildService.RebuildResult;

@RestController
class ProjectionController {

    private final ProjectionRebuildService rebuild;

    ProjectionController(ProjectionRebuildService rebuild) {
        this.rebuild = rebuild;
    }

    @PostMapping("/api/v1/orgs/{orgId}/campushire/projections/rebuild")
    RebuildResult rebuild(CurrentUser user, @PathVariable UUID orgId) {
        return rebuild.rebuild(user, orgId);
    }
}
