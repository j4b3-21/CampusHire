package com.bm4a.platform.modules.access.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.access.RoleDefinitions;
import com.bm4a.platform.modules.access.internal.RoleService.RoleInput;

import tools.jackson.databind.JsonNode;

@Service
class RoleDefinitionsService implements RoleDefinitions {

    private final RoleService roles;

    RoleDefinitionsService(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public Optional<RoleDefinition> find(UUID organizationId, String roleKey) {
        return roles.find(organizationId, roleKey).map(RoleDefinitionsService::definition);
    }

    @Override
    public RoleDefinition create(UUID organizationId, UUID actorUserId, String apiKey, String label,
            String description, JsonNode permissions) {
        return definition(roles.create(organizationId, actorUserId, new RoleInput(apiKey, label, description,
                permissions)));
    }

    private static RoleDefinition definition(RoleRow row) {
        return new RoleDefinition(row.id(), row.apiKey(), row.label(), row.description(), row.permissions(),
                row.version());
    }
}
