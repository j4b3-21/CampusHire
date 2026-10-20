package com.bm4a.platform.modules.tenancy.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.authorization.RelationshipTuple;
import com.bm4a.platform.modules.authorization.RelationshipTuplesChanged;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenancyEvent;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OrganizationService {

    static final int MAX_SETTINGS_BYTES = 16 * 1024;

    private final TenancyRepository repository;
    private final ApplicationEventPublisher events;
    private final JsonColumns json;
    private final Clock clock;

    OrganizationService(TenancyRepository repository, ApplicationEventPublisher events, JsonColumns json, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** Any authenticated user may create an organization and becomes its owner. */
    @Transactional
    public OrganizationWithRole create(CurrentUser user, String name, JsonNode settings) {
        Instant now = SqlValues.now(clock);
        OrganizationRow org = new OrganizationRow(UUID.randomUUID(), normalizeName(name), OrganizationRow.ACTIVE,
                validSettings(settings), 1, now, user.id(), now, user.id());
        repository.insertOrganization(org);
        repository.upsertMembership(org.id(), user.id(), MembershipRole.OWNER, MembershipRow.ACTIVE, null,
                json.newObject(), user.id(), now);
        events.publishEvent(new TenancyEvent.OrganizationCreated(org.id(), user.id(), org.name()));
        events.publishEvent(new TenancyEvent.MembershipGranted(org.id(), user.id(), user.id(), MembershipRole.OWNER,
                null, Map.of()));
        events.publishEvent(RelationshipTuplesChanged.write(
                RelationshipTuple.organization(user.id(), MembershipRole.OWNER.relation(), org.id())));
        return new OrganizationWithRole(org, MembershipRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<OrganizationWithRole> listForUser(CurrentUser user) {
        return repository.findOrganizationsOfUser(user.id());
    }

    @Transactional(readOnly = true)
    public OrganizationWithRole get(TenantContext tenant) {
        OrganizationRow org = repository.findOrganization(tenant.organizationId())
                .orElseThrow(() -> ApiException.notFound("Organization"));
        return new OrganizationWithRole(org, tenant.role());
    }

    /** Partial update; {@code null} arguments leave values unchanged. Settings are replaced as a whole. */
    @Transactional
    public OrganizationWithRole update(TenantContext tenant, long expectedVersion, String name, JsonNode settings) {
        OrganizationRow current = lockForWrite(tenant.organizationId(), expectedVersion);
        String newName = name == null ? current.name() : normalizeName(name);
        ObjectNode newSettings = settings == null ? current.settings() : validSettings(settings);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!Objects.equals(newName, current.name())) {
            changes.put("name", Map.of("from", current.name(), "to", newName));
        }
        if (!Objects.equals(newSettings, current.settings())) {
            changes.put("settings", "changed");
        }
        if (changes.isEmpty()) {
            return new OrganizationWithRole(current, tenant.role());
        }
        repository.updateOrganization(current.id(), newName, current.status(), json.write(newSettings),
                current.version() + 1, tenant.userId(), SqlValues.now(clock));
        events.publishEvent(new TenancyEvent.OrganizationUpdated(current.id(), tenant.userId(), changes));
        return get(tenant);
    }

    @Transactional
    public OrganizationWithRole archive(TenantContext tenant, long expectedVersion) {
        OrganizationRow current = lockForWrite(tenant.organizationId(), expectedVersion);
        changeStatus(current, OrganizationRow.ARCHIVED, tenant.userId());
        return get(tenant);
    }

    /** Platform administration: suspend or reactivate any organization (no membership required). */
    @Transactional
    public OrganizationRow setStatusAsPlatformAdmin(CurrentUser admin, UUID organizationId, String status) {
        if (!admin.platformAdmin()) {
            throw ApiException.forbidden("access_denied", "Platform administrator role required");
        }
        OrganizationRow current = repository.lockOrganization(organizationId)
                .orElseThrow(() -> ApiException.notFound("Organization"));
        changeStatus(current, status, admin.id());
        return repository.findOrganization(organizationId).orElseThrow();
    }

    private void changeStatus(OrganizationRow current, String status, UUID actor) {
        if (current.status().equals(status)) {
            return;
        }
        repository.updateOrganization(current.id(), current.name(), status, json.write(current.settings()),
                current.version() + 1, actor, SqlValues.now(clock));
        events.publishEvent(new TenancyEvent.OrganizationStatusChanged(current.id(), actor, current.status(), status));
    }

    private OrganizationRow lockForWrite(UUID organizationId, long expectedVersion) {
        OrganizationRow current = repository.lockOrganization(organizationId)
                .orElseThrow(() -> ApiException.notFound("Organization"));
        if (current.version() != expectedVersion) {
            throw ApiException.versionConflict(current.version());
        }
        return current;
    }

    private static String normalizeName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 200) {
            throw ApiException.validation("name", "size", "name must be 1-200 characters");
        }
        return trimmed;
    }

    private ObjectNode validSettings(JsonNode settings) {
        if (settings == null || settings.isNull()) {
            return json.newObject();
        }
        if (!(settings instanceof ObjectNode object)) {
            throw ApiException.validation("settings", "type", "settings must be a JSON object");
        }
        if (json.write(object).length() > MAX_SETTINGS_BYTES) {
            throw ApiException.validation("settings", "size", "settings must not exceed 16 KiB");
        }
        return object;
    }
}
