package com.bm4a.platform.modules.tenancy.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.authorization.RelationshipTuple;
import com.bm4a.platform.modules.authorization.RelationshipTuplesChanged;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.MembershipAdministration;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.OrgRoleCatalog;
import com.bm4a.platform.modules.tenancy.OrgRoleCatalog.OrgRoleRef;
import com.bm4a.platform.modules.tenancy.TenancyEvent;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.UserDirectory;
import com.bm4a.platform.modules.users.UserDirectory.UserSummary;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Membership rules (in addition to the OpenFGA permission checked by the caller):
 * <ul>
 * <li>admins may add and revoke {@code MEMBER}s and set their org role and attributes; granting, changing or revoking
 * {@code ADMIN}/{@code OWNER}, changing membership roles, and editing admins' or owners' org role or attributes
 * requires an owner</li>
 * <li>any member may leave (revoke themselves)</li>
 * <li>the last active owner can be neither revoked nor demoted</li>
 * </ul>
 * All changes lock the organization row, which serializes concurrent membership changes per tenant. OpenFGA tuples
 * ({@code organization#<role>} and {@code role#assignee}) are derived from the stored row and synchronized through
 * the outbox.
 */
@Service
public class MembershipService implements MembershipAdministration {

    private final TenancyRepository repository;
    private final UserDirectory users;
    private final OrgRoleCatalog roles;
    private final ApplicationEventPublisher events;
    private final JsonColumns json;
    private final Clock clock;

    MembershipService(TenancyRepository repository, UserDirectory users, OrgRoleCatalog roles,
            ApplicationEventPublisher events, JsonColumns json, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.roles = roles;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /**
     * A change of an active membership; {@code null} components are left unchanged.
     *
     * @param roleKeySet whether {@code roleKey} is part of the change ({@code roleKey == null} then removes the role)
     */
    public record MembershipUpdate(MembershipRole role, boolean roleKeySet, String roleKey, JsonNode attributes) {
    }

    @Transactional(readOnly = true)
    public PageResponse<Member> list(TenantContext tenant, PageQuery page) {
        List<MembershipRow> rows = repository.findActiveMemberships(tenant.organizationId(), page.offset(),
                page.size());
        Map<UUID, UserSummary> summaries = users.findAll(rows.stream().map(MembershipRow::userId).toList());
        Map<UUID, OrgRoleRef> orgRoles = roles.findAll(tenant.organizationId(),
                rows.stream().map(MembershipRow::roleId).filter(Objects::nonNull).distinct().toList());
        List<Member> members = rows.stream()
                .map(row -> Member.of(row, summaries.get(row.userId()), orgRoles.get(row.roleId())))
                .toList();
        return PageResponse.of(members, page, repository.countActiveMemberships(tenant.organizationId()));
    }

    @Transactional
    public Member add(TenantContext tenant, UUID userId, MembershipRole role, String roleKey, JsonNode attributes) {
        lockOrganization(tenant.organizationId());
        if (role != MembershipRole.MEMBER) {
            requireOwner(tenant, "Only owners can grant " + role);
        }
        users.findActive(userId).orElseThrow(() -> ApiException.notFound("User"));
        Optional<MembershipRow> existing = repository.findMembership(tenant.organizationId(), userId);
        if (existing.filter(MembershipRow::active).isPresent()) {
            throw ApiException.conflict("already_member", "User is already a member");
        }
        OrgRoleRef orgRole = resolveRole(tenant.organizationId(), roleKey, "roleKey");
        ObjectNode canonical = MembershipAttributes.canonical(attributes, "attributes");
        store(tenant.organizationId(), userId, existing.orElse(null), role, orgRole, canonical, tenant.userId());
        return member(tenant.organizationId(), userId);
    }

    @Transactional
    public Member update(TenantContext tenant, UUID userId, MembershipUpdate update) {
        lockOrganization(tenant.organizationId());
        MembershipRow current = activeMembership(tenant.organizationId(), userId);
        MembershipRole newRole = update.role() == null ? current.role() : update.role();
        if (newRole != current.role()) {
            requireOwner(tenant, "Only owners can change roles");
            if (current.role() == MembershipRole.OWNER) {
                requireAnotherOwner(tenant.organizationId());
            }
        }
        boolean accessChange = update.roleKeySet() || update.attributes() != null;
        if (accessChange && current.role() != MembershipRole.MEMBER) {
            requireOwner(tenant, "Only owners can change the access of " + current.role() + "s");
        }
        OrgRoleRef orgRole = update.roleKeySet() ? resolveRole(tenant.organizationId(), update.roleKey(), "roleKey")
                : currentRole(tenant.organizationId(), current);
        ObjectNode attributes = update.attributes() == null ? current.attributes()
                : MembershipAttributes.canonical(update.attributes(), "attributes");
        store(tenant.organizationId(), userId, current, newRole, orgRole, attributes, tenant.userId());
        return member(tenant.organizationId(), userId);
    }

    @Transactional
    public void revoke(TenantContext tenant, UUID userId) {
        lockOrganization(tenant.organizationId());
        MembershipRow current = activeMembership(tenant.organizationId(), userId);
        boolean self = userId.equals(tenant.userId());
        if (!self && current.role() != MembershipRole.MEMBER) {
            requireOwner(tenant, "Only owners can revoke " + current.role());
        }
        if (current.role() == MembershipRole.OWNER) {
            requireAnotherOwner(tenant.organizationId());
        }
        repository.upsertMembership(tenant.organizationId(), userId, current.role(), MembershipRow.REVOKED,
                current.roleId(), current.attributes(), tenant.userId(), SqlValues.now(clock));
        events.publishEvent(new TenancyEvent.MembershipRevoked(tenant.organizationId(), tenant.userId(), userId,
                current.role()));
        events.publishEvent(RelationshipTuplesChanged.diff(tuples(tenant.organizationId(), current), Set.of()));
    }

    // ---- trusted administration (MembershipAdministration) ---------------------------------------------------

    @Override
    @Transactional
    public MembershipView grant(UUID organizationId, UUID userId, MembershipRole role, String roleKey,
            Map<String, Object> attributes, UUID actorUserId) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(actorUserId, "actorUserId");
        OrganizationRow organization = lockOrganization(organizationId);
        if (!organization.active()) {
            throw ApiException.forbidden("organization_inactive", "Organization is " + organization.status().toLowerCase());
        }
        users.findActive(userId).orElseThrow(() -> ApiException.notFound("User"));
        MembershipRow current = repository.findMembership(organizationId, userId).orElse(null);
        if (current != null && current.active() && current.role() == MembershipRole.OWNER
                && role != MembershipRole.OWNER) {
            requireAnotherOwner(organizationId);
        }
        OrgRoleRef orgRole = resolveRole(organizationId, roleKey, "roleKey");
        ObjectNode canonical = MembershipAttributes.canonical(toJson(attributes), "attributes");
        store(organizationId, userId, current, role, orgRole, canonical, actorUserId);
        return view(organizationId, repository.findMembership(organizationId, userId).orElseThrow());
    }

    @Override
    @Transactional
    public MembershipView updateAttributes(UUID organizationId, UUID userId, Map<String, Object> attributes,
            UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        lockOrganization(organizationId);
        MembershipRow current = activeMembership(organizationId, userId);
        ObjectNode canonical = MembershipAttributes.canonical(toJson(attributes), "attributes");
        store(organizationId, userId, current, current.role(), currentRole(organizationId, current), canonical,
                actorUserId);
        return view(organizationId, repository.findMembership(organizationId, userId).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MembershipView> find(UUID organizationId, UUID userId) {
        return repository.findMembership(organizationId, userId).map(row -> view(organizationId, row));
    }

    @Override
    @Transactional(readOnly = true)
    public long countRoleAssignments(UUID organizationId, UUID roleId) {
        return repository.countRoleAssignments(organizationId, roleId, SqlValues.now(clock));
    }

    /**
     * Activates a membership on behalf of an accepted invitation. The caller holds the organization lock and has
     * verified that the user is not an active member.
     */
    void activateFromInvitation(UUID organizationId, UUID userId, MembershipRole role, UUID roleId,
            ObjectNode attributes) {
        OrgRoleRef orgRole = roleId == null ? null : roles.findAll(organizationId, List.of(roleId)).get(roleId);
        store(organizationId, userId, repository.findMembership(organizationId, userId).orElse(null), role, orgRole,
                attributes, userId);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /**
     * Writes the membership as {@code ACTIVE} and publishes the audit events and tuple changes that lead from
     * {@code current} (absent, revoked or active) to the new state. No-op when nothing changes.
     */
    private void store(UUID organizationId, UUID userId, MembershipRow current, MembershipRole role,
            OrgRoleRef orgRole, ObjectNode attributes, UUID actor) {
        UUID roleId = orgRole == null ? null : orgRole.id();
        boolean wasActive = current != null && current.active();
        if (wasActive && current.role() == role && Objects.equals(current.roleId(), roleId)
                && current.attributes().equals(attributes)) {
            return;
        }
        repository.upsertMembership(organizationId, userId, role, MembershipRow.ACTIVE, roleId, attributes, actor,
                SqlValues.now(clock));
        String roleKey = orgRole == null ? null : orgRole.key();
        if (!wasActive) {
            events.publishEvent(new TenancyEvent.MembershipGranted(organizationId, actor, userId, role, roleKey,
                    MembershipAttributes.asMap(attributes)));
        } else {
            if (current.role() != role) {
                events.publishEvent(new TenancyEvent.MembershipRoleChanged(organizationId, actor, userId,
                        current.role(), role));
            }
            if (!Objects.equals(current.roleId(), roleId) || !current.attributes().equals(attributes)) {
                OrgRoleRef previous = currentRole(organizationId, current);
                events.publishEvent(new TenancyEvent.MembershipAccessChanged(organizationId, actor, userId,
                        previous == null ? null : previous.key(), roleKey, MembershipAttributes.asMap(current.attributes()),
                        MembershipAttributes.asMap(attributes)));
            }
        }
        RelationshipTuplesChanged change = RelationshipTuplesChanged.diff(
                wasActive ? tuples(organizationId, current) : Set.of(), tuples(organizationId, userId, role, roleId));
        if (change.hasChanges()) {
            events.publishEvent(change);
        }
    }

    private static Set<RelationshipTuple> tuples(UUID organizationId, MembershipRow row) {
        return tuples(organizationId, row.userId(), row.role(), row.roleId());
    }

    private static Set<RelationshipTuple> tuples(UUID organizationId, UUID userId, MembershipRole role, UUID roleId) {
        Set<RelationshipTuple> tuples = new LinkedHashSet<>();
        tuples.add(RelationshipTuple.organization(userId, role.relation(), organizationId));
        if (roleId != null) {
            tuples.add(RelationshipTuple.roleAssignee(userId, roleId));
        }
        return tuples;
    }

    /** {@code null} key means "no org role"; unknown keys are a validation error. */
    OrgRoleRef resolveRole(UUID organizationId, String roleKey, String field) {
        if (roleKey == null) {
            return null;
        }
        return roles.findForAssignment(organizationId, roleKey)
                .orElseThrow(() -> ApiException.validation(field, "unknown_role", "no role with this key"));
    }

    private OrgRoleRef currentRole(UUID organizationId, MembershipRow row) {
        return row.roleId() == null ? null : roles.findAll(organizationId, List.of(row.roleId())).get(row.roleId());
    }

    private JsonNode toJson(Map<String, Object> attributes) {
        return attributes == null ? null : json.mapper().valueToTree(attributes);
    }

    private Member member(UUID organizationId, UUID userId) {
        MembershipRow row = repository.findMembership(organizationId, userId).orElseThrow();
        return Member.of(row, users.findAll(List.of(userId)).get(userId), currentRole(organizationId, row));
    }

    private MembershipView view(UUID organizationId, MembershipRow row) {
        OrgRoleRef orgRole = currentRole(organizationId, row);
        return new MembershipView(organizationId, row.userId(), row.role(), row.status(),
                orgRole == null ? null : orgRole.key(), MembershipAttributes.asMap(row.attributes()), row.createdAt(),
                row.updatedAt());
    }

    OrganizationRow lockOrganization(UUID organizationId) {
        return repository.lockOrganization(organizationId).orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private MembershipRow activeMembership(UUID organizationId, UUID userId) {
        return repository.findMembership(organizationId, userId)
                .filter(MembershipRow::active)
                .orElseThrow(() -> ApiException.notFound("Member"));
    }

    /** Uses the role from the database (re-read under the organization lock), not the possibly stale context. */
    void requireOwner(TenantContext tenant, String message) {
        MembershipRole actorRole = repository.findMembership(tenant.organizationId(), tenant.userId())
                .filter(MembershipRow::active)
                .map(MembershipRow::role)
                .orElseThrow(() -> ApiException.notFound("Organization"));
        if (actorRole != MembershipRole.OWNER) {
            throw ApiException.forbidden("access_denied", message);
        }
    }

    private void requireAnotherOwner(UUID organizationId) {
        if (repository.countActiveOwners(organizationId) <= 1) {
            throw ApiException.conflict("last_owner", "An organization must keep at least one owner");
        }
    }

    /** @param roleKey org role key, {@code null} when none is assigned */
    public record Member(UUID userId, String email, String displayName, MembershipRole role, String roleKey,
            Map<String, Object> attributes, String status, Instant createdAt, Instant updatedAt) {

        static Member of(MembershipRow row, UserSummary user, OrgRoleRef orgRole) {
            return new Member(row.userId(), user == null ? null : user.email(), user == null ? null : user.displayName(),
                    row.role(), orgRole == null ? null : orgRole.key(), MembershipAttributes.asMap(row.attributes()),
                    row.status(), row.createdAt(), row.updatedAt());
        }
    }
}
