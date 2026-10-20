package com.bm4a.platform.modules.access.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.access.AccessSummary;
import com.bm4a.platform.modules.access.AccessSummary.ModuleCapabilities;
import com.bm4a.platform.modules.access.AccessSummary.RoleSummary;
import com.bm4a.platform.modules.access.FieldPolicy;
import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.access.internal.RolePermissions.ModulePolicy;
import com.bm4a.platform.modules.access.internal.RolePermissions.Rule;
import com.bm4a.platform.modules.authorization.AuthorizationService;
import com.bm4a.platform.modules.authorization.ModulePermission;
import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.metadata.ModuleAccessMode;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.tenancy.ActiveMembership;
import com.bm4a.platform.modules.tenancy.TenancyEvent;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

/**
 * Implements the ADR-004 request flow. The OpenFGA module check and the role document must both allow an action:
 * OpenFGA answers "may this user use module M at all" (derived tuples), the PostgreSQL role document - read on every
 * call, so changes apply immediately - decides the action, record scope and field policy.
 * <p>
 * Platform-global modules have no per-tenant module object in OpenFGA. For them, admins and members of
 * {@code MEMBERS}-mode modules are checked with the organization-level record permissions, members of
 * {@code ROLES}-mode modules with the role tuples on the module.
 */
@Service
class ModuleAccessService implements ModuleAccess {

    private final TenantAccess tenants;
    private final ModuleSchemas schemas;
    private final RoleRepository roles;
    private final AuthorizationService authorization;
    private final ApplicationEventPublisher events;

    ModuleAccessService(TenantAccess tenants, ModuleSchemas schemas, RoleRepository roles,
            AuthorizationService authorization, ApplicationEventPublisher events) {
        this.tenants = tenants;
        this.schemas = schemas;
        this.roles = roles;
        this.authorization = authorization;
        this.events = events;
    }

    /** The outcome of evaluating a role document for one module and action. */
    private record Decision(RecordScope scope, FieldPolicy fields) {
    }

    @Override
    public ModuleGrant require(CurrentUser user, UUID organizationId, String moduleKey, RecordAction action) {
        return require(user.id(), organizationId, moduleKey, action);
    }

    @Override
    public ModuleGrant require(TenantContext tenant, String moduleKey, RecordAction action) {
        return require(tenant.userId(), tenant.organizationId(), moduleKey, action);
    }

    /** Not transactional: access-denied audit events must commit on their own. */
    @Override
    public AccessSummary describe(CurrentUser user, UUID organizationId) {
        ActiveMembership membership = tenants.requireMembership(user.id(), organizationId);
        Optional<RoleRow> role = role(membership);
        RolePermissions permissions = role.map(row -> RolePermissions.parse(row.permissions()))
                .orElse(RolePermissions.EMPTY);
        Map<String, ModuleCapabilities> modules = new LinkedHashMap<>();
        for (ModuleSchema module : schemas.listActive(organizationId)) {
            Optional<Decision> read = decide(membership, permissions, module, RecordAction.READ);
            if (read.isEmpty()) {
                continue;
            }
            FieldPolicy fields = read.get().fields();
            modules.put(module.apiKey(), new ModuleCapabilities(true,
                    decide(membership, permissions, module, RecordAction.CREATE).isPresent(),
                    decide(membership, permissions, module, RecordAction.UPDATE).isPresent(),
                    decide(membership, permissions, module, RecordAction.DELETE).isPresent(),
                    read.get().scope().kind(), sorted(fields.hiddenFields()), sorted(fields.readOnlyFields())));
        }
        return new AccessSummary(organizationId, membership.role(), membership.admin(),
                role.map(row -> new RoleSummary(row.apiKey(), row.label())).orElse(null), membership.attributes(),
                modules);
    }

    private ModuleGrant require(UUID userId, UUID organizationId, String moduleKey, RecordAction action) {
        ActiveMembership membership = tenants.requireMembership(userId, organizationId);
        ModuleSchema module = schemas.find(organizationId, moduleKey).orElseThrow(() -> ApiException.notFound("Module"));
        if (!moduleAllowed(membership, module, action)) {
            throw denied(membership, module, action);
        }
        Optional<RoleRow> role = role(membership);
        RolePermissions permissions = role.map(row -> RolePermissions.parse(row.permissions()))
                .orElse(RolePermissions.EMPTY);
        Decision decision = decide(membership, permissions, module, action)
                .orElseThrow(() -> denied(membership, module, action));
        return new ModuleGrant(membership.context(), module, action, decision.scope(), decision.fields(),
                role.map(RoleRow::apiKey).orElse(null));
    }

    private boolean moduleAllowed(ActiveMembership membership, ModuleSchema module, RecordAction action) {
        ModulePermission permission = action == RecordAction.READ ? ModulePermission.READ : ModulePermission.WRITE;
        if (module.global() && (membership.admin() || module.accessMode() == ModuleAccessMode.MEMBERS)) {
            return authorization.isAllowed(membership.userId(),
                    action == RecordAction.READ ? Permission.RECORDS_READ : Permission.RECORDS_WRITE,
                    membership.organizationId());
        }
        return authorization.isAllowed(membership.userId(), permission, module.id());
    }

    /**
     * Owners and admins: everything. Members: the role's policy for the module when it has one; otherwise
     * {@code MEMBERS} mode grants everything and {@code ROLES} mode nothing.
     */
    private static Optional<Decision> decide(ActiveMembership membership, RolePermissions permissions,
            ModuleSchema module, RecordAction action) {
        if (membership.admin()) {
            return Optional.of(new Decision(RecordScope.all(), FieldPolicy.unrestricted()));
        }
        ModulePolicy policy = permissions.modules().get(module.apiKey());
        if (policy == null) {
            return module.accessMode() == ModuleAccessMode.MEMBERS
                    ? Optional.of(new Decision(RecordScope.all(), FieldPolicy.unrestricted()))
                    : Optional.empty();
        }
        Rule rule = switch (action) {
            case READ -> policy.read();
            case CREATE -> policy.create() ? new Rule(RecordScope.Kind.ALL, null, null, Map.of()) : Rule.NONE;
            case UPDATE -> policy.update();
            case DELETE -> policy.delete();
        };
        if (!rule.grants()) {
            return Optional.empty();
        }
        RecordScope scope = switch (rule.kind()) {
            case OWN -> RecordScope.own(membership.userId());
            case ATTRIBUTE -> RecordScope.attribute(rule.field(), membership.attributeValues(rule.attribute()));
            default -> RecordScope.all();
        };
        return Optional.of(new Decision(scope.withWhere(rule.where()),
                new FieldPolicy(policy.hiddenFields(), policy.readOnlyFields())));
    }

    private Optional<RoleRow> role(ActiveMembership membership) {
        return membership.orgRoleId() == null ? Optional.empty()
                : roles.findById(membership.organizationId(), membership.orgRoleId());
    }

    private ApiException denied(ActiveMembership membership, ModuleSchema module, RecordAction action) {
        events.publishEvent(new TenancyEvent.TenantAccessDenied(membership.organizationId(), membership.userId(),
                "MODULE_" + action.name() + ":" + module.apiKey(), "permission_denied"));
        return ApiException.forbidden("access_denied",
                "Missing permission " + action.name() + " on module " + module.apiKey());
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
