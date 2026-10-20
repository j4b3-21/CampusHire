package com.bm4a.platform.modules.access.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.access.internal.RolePermissions.ModulePolicy;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.authorization.ModuleRelation;
import com.bm4a.platform.modules.authorization.RelationshipTuple;
import com.bm4a.platform.modules.authorization.RelationshipTuplesChanged;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.tenancy.MembershipAdministration;
import com.bm4a.platform.modules.tenancy.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Org role definitions. Every change is versioned (If-Match), audited, and synchronized to OpenFGA through the
 * outbox: {@code role#organization}, plus {@code module#reader} for modules the role can read and
 * {@code module#writer} for modules it can create, update or delete in.
 */
@Service
public class RoleService {

    static final int MAX_ROLES_PER_ORGANIZATION = 100;
    private static final Pattern API_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final RoleRepository repository;
    private final RoleDocumentValidator validator;
    private final ModuleSchemas schemas;
    private final MembershipAdministration memberships;
    private final AuditLog audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    RoleService(RoleRepository repository, RoleDocumentValidator validator, ModuleSchemas schemas,
            MembershipAdministration memberships, AuditLog audit, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.validator = validator;
        this.schemas = schemas;
        this.memberships = memberships;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** Role input; on update {@code apiKey} must be omitted or equal to the current key. */
    public record RoleInput(String apiKey, String label, String description, JsonNode permissions) {
    }

    @Transactional(readOnly = true)
    public List<RoleRow> list(TenantContext tenant) {
        return repository.list(tenant.organizationId());
    }

    @Transactional(readOnly = true)
    public RoleRow get(TenantContext tenant, String apiKey) {
        return repository.find(tenant.organizationId(), apiKey, false).orElseThrow(() -> ApiException.notFound("Role"));
    }

    @Transactional
    public RoleRow create(TenantContext tenant, RoleInput in) {
        return create(tenant.organizationId(), tenant.userId(), in);
    }

    /** Creation on behalf of {@code actor} without a request context (trusted callers). */
    @Transactional
    public RoleRow create(UUID organizationId, UUID actor, RoleInput in) {
        List<FieldViolation> violations = new ArrayList<>();
        if (in.apiKey() == null || !API_KEY.matcher(in.apiKey()).matches()) {
            violations.add(new FieldViolation("apiKey", "format", "apiKey must match ^[a-z][a-z0-9_]{0,62}$"));
        }
        validateLabels(in.label(), in.description(), violations);
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        ObjectNode permissions = validator.validate(organizationId, in.permissions());
        if (repository.count(organizationId) >= MAX_ROLES_PER_ORGANIZATION) {
            throw ApiException.conflict("limit_exceeded", "At most " + MAX_ROLES_PER_ORGANIZATION + " roles");
        }
        Instant now = SqlValues.now(clock);
        RoleRow role = new RoleRow(UUID.randomUUID(), organizationId, in.apiKey(), in.label().strip(),
                in.description(), permissions, 1, now, actor, now, actor);
        if (repository.find(organizationId, in.apiKey(), false).isPresent()) {
            throw roleExists(in.apiKey());
        }
        try {
            repository.insert(role);
        } catch (DuplicateKeyException ex) {
            throw roleExists(in.apiKey());
        }
        publish(RelationshipTuplesChanged.diff(Set.of(), tuples(role)));
        audit.record(AuditEntry.of(organizationId, actor, "access.role.created", "role", role.id())
                .with("apiKey", role.apiKey()).with("modules", moduleKeys(permissions)));
        return role;
    }

    @Transactional(readOnly = true)
    public Optional<RoleRow> find(UUID organizationId, String apiKey) {
        return repository.find(organizationId, apiKey, false);
    }

    /** Full replacement of label, description and permissions. */
    @Transactional
    public RoleRow update(TenantContext tenant, String apiKey, long expectedVersion, RoleInput in) {
        RoleRow current = lockForWrite(tenant, apiKey, expectedVersion);
        if (in.apiKey() != null && !in.apiKey().equals(current.apiKey())) {
            throw ApiException.validation("apiKey", "immutable", "apiKey cannot be changed");
        }
        List<FieldViolation> violations = new ArrayList<>();
        validateLabels(in.label(), in.description(), violations);
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        ObjectNode permissions = validator.validate(tenant.organizationId(), in.permissions());
        RoleRow updated = new RoleRow(current.id(), current.organizationId(), current.apiKey(), in.label().strip(),
                in.description(), permissions, current.version() + 1, current.createdAt(), current.createdBy(),
                SqlValues.now(clock), tenant.userId());
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!Objects.equals(current.label(), updated.label())) {
            changes.put("label", Map.of("from", current.label(), "to", updated.label()));
        }
        if (!Objects.equals(current.description(), updated.description())) {
            changes.put("description", "changed");
        }
        if (!current.permissions().equals(permissions)) {
            changes.put("permissions", changedModules(current.permissions(), permissions));
        }
        if (changes.isEmpty()) {
            return current;
        }
        repository.update(updated);
        publish(RelationshipTuplesChanged.diff(tuples(current), tuples(updated)));
        audit.record(AuditEntry.of(tenant.organizationId(), tenant.userId(), "access.role.updated", "role",
                current.id()).with("apiKey", current.apiKey()).with("changes", changes));
        return updated;
    }

    /** Refused with 409 {@code role_in_use} while active memberships or pending invitations hold the role. */
    @Transactional
    public void delete(TenantContext tenant, String apiKey, long expectedVersion) {
        RoleRow current = lockForWrite(tenant, apiKey, expectedVersion);
        long assignments = memberships.countRoleAssignments(tenant.organizationId(), current.id());
        if (assignments > 0) {
            throw ApiException.conflict("role_in_use", "Role is assigned to " + assignments
                    + " member(s) or pending invitation(s); reassign them first");
        }
        repository.delete(tenant.organizationId(), current.id());
        publish(RelationshipTuplesChanged.diff(tuples(current), Set.of()));
        audit.record(AuditEntry.of(tenant.organizationId(), tenant.userId(), "access.role.deleted", "role",
                current.id()).with("apiKey", current.apiKey()));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** OpenFGA tuples derived from a role (ADR-004). */
    private Set<RelationshipTuple> tuples(RoleRow role) {
        Set<RelationshipTuple> tuples = new LinkedHashSet<>();
        tuples.add(RelationshipTuple.roleOrganization(role.id(), role.organizationId()));
        RolePermissions.parse(role.permissions()).modules().forEach((moduleKey, policy) -> schemas
                .find(role.organizationId(), moduleKey)
                .ifPresent(module -> addModuleTuples(tuples, module.id(), role.id(), policy)));
        return tuples;
    }

    private static void addModuleTuples(Set<RelationshipTuple> tuples, UUID moduleId, UUID roleId,
            ModulePolicy policy) {
        if (policy.read().grants()) {
            tuples.add(RelationshipTuple.moduleRole(ModuleRelation.READER, moduleId, roleId));
        }
        if (policy.writes()) {
            tuples.add(RelationshipTuple.moduleRole(ModuleRelation.WRITER, moduleId, roleId));
        }
    }

    private void publish(RelationshipTuplesChanged change) {
        if (change.hasChanges()) {
            events.publishEvent(change);
        }
    }

    private RoleRow lockForWrite(TenantContext tenant, String apiKey, long expectedVersion) {
        RoleRow current = repository.find(tenant.organizationId(), apiKey, true)
                .orElseThrow(() -> ApiException.notFound("Role"));
        if (current.version() != expectedVersion) {
            throw ApiException.versionConflict(current.version());
        }
        return current;
    }

    private static void validateLabels(String label, String description, List<FieldViolation> violations) {
        if (label == null || label.isBlank() || label.length() > 200) {
            violations.add(new FieldViolation("label", "size", "label must be 1-200 characters"));
        }
        if (description != null && description.length() > 2000) {
            violations.add(new FieldViolation("description", "size", "description must be at most 2000 characters"));
        }
    }

    private static List<String> moduleKeys(ObjectNode permissions) {
        return List.copyOf(permissions.path("modules").propertyNames());
    }

    /** Module keys whose policy was added, removed or changed. */
    private static List<String> changedModules(ObjectNode before, ObjectNode after) {
        Set<String> keys = new LinkedHashSet<>(before.path("modules").propertyNames());
        keys.addAll(after.path("modules").propertyNames());
        return keys.stream()
                .filter(key -> !Objects.equals(before.path("modules").get(key), after.path("modules").get(key)))
                .sorted()
                .toList();
    }

    private static ApiException roleExists(String apiKey) {
        return ApiException.conflict("role_exists", "A role with apiKey '" + apiKey + "' already exists");
    }
}
