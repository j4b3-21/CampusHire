package com.bm4a.platform.modules.tenancy.internal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.tenancy.OrganizationSettings;
import com.bm4a.platform.modules.tenancy.TenancyEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
class OrganizationSettingsService implements OrganizationSettings {

    private final TenancyRepository repository;
    private final ApplicationEventPublisher events;
    private final JsonColumns json;
    private final Clock clock;

    OrganizationSettingsService(TenancyRepository repository, ApplicationEventPublisher events, JsonColumns json,
            Clock clock) {
        this.repository = repository;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ObjectNode get(UUID organizationId) {
        return repository.findOrganization(organizationId).map(OrganizationRow::settings)
                .orElseThrow(() -> ApiException.notFound("Organization"));
    }

    @Override
    @Transactional
    public List<String> mergeDefaults(UUID organizationId, UUID actorUserId, JsonNode defaults) {
        if (defaults == null || defaults.isNull()) {
            return List.of();
        }
        if (!(defaults instanceof ObjectNode additions)) {
            throw ApiException.validation("settings", "type", "settings must be a JSON object");
        }
        OrganizationRow current = repository.lockOrganization(organizationId)
                .orElseThrow(() -> ApiException.notFound("Organization"));
        ObjectNode merged = current.settings().deepCopy();
        List<String> added = new ArrayList<>();
        merge(merged, additions, "", added);
        if (added.isEmpty()) {
            return List.of();
        }
        if (json.write(merged).length() > OrganizationService.MAX_SETTINGS_BYTES) {
            throw ApiException.validation("settings", "size", "settings must not exceed 16 KiB");
        }
        repository.updateOrganization(current.id(), current.name(), current.status(), json.write(merged),
                current.version() + 1, actorUserId, SqlValues.now(clock));
        events.publishEvent(new TenancyEvent.OrganizationUpdated(current.id(), actorUserId,
                Map.of("settings", Map.of("added", List.copyOf(added)))));
        return List.copyOf(added);
    }

    private static void merge(ObjectNode target, ObjectNode additions, String prefix, List<String> added) {
        for (Map.Entry<String, JsonNode> entry : additions.properties()) {
            JsonNode existing = target.get(entry.getKey());
            if (existing == null) {
                target.set(entry.getKey(), entry.getValue().deepCopy());
                added.add(prefix + entry.getKey());
            } else if (existing instanceof ObjectNode nested && entry.getValue() instanceof ObjectNode more) {
                merge(nested, more, prefix + entry.getKey() + ".", added);
            }
        }
    }
}
