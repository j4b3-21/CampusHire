package com.bm4a.platform.modules.tenancy.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.authorization.RelationshipTuple;
import com.bm4a.platform.modules.authorization.RelationshipTuplesChanged;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenancyEvent;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.UserDirectory;
import com.bm4a.platform.modules.users.UserDirectory.UserSummary;

/**
 * Membership rules (in addition to the OpenFGA permission checked by the caller):
 * <ul>
 * <li>admins may add and revoke {@code MEMBER}s; granting, changing or revoking {@code ADMIN}/{@code OWNER} requires
 * an owner; changing roles requires an owner</li>
 * <li>any member may leave (revoke themselves)</li>
 * <li>the last active owner can be neither revoked nor demoted</li>
 * </ul>
 * All changes lock the organization row, which serializes concurrent membership changes per tenant.
 */
@Service
public class MembershipService {

    private final TenancyRepository repository;
    private final UserDirectory users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    MembershipService(TenancyRepository repository, UserDirectory users, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<Member> list(TenantContext tenant, PageQuery page) {
        List<MembershipRow> rows = repository.findActiveMemberships(tenant.organizationId(), page.offset(),
                page.size());
        Map<UUID, UserSummary> summaries = users.findAll(rows.stream().map(MembershipRow::userId).toList());
        List<Member> members = rows.stream().map(row -> Member.of(row, summaries.get(row.userId()))).toList();
        return PageResponse.of(members, page, repository.countActiveMemberships(tenant.organizationId()));
    }

    @Transactional
    public Member add(TenantContext tenant, UUID userId, MembershipRole role) {
        lockOrganization(tenant);
        if (role != MembershipRole.MEMBER) {
            requireOwner(tenant, "Only owners can grant " + role);
        }
        UserSummary user = users.findActive(userId).orElseThrow(() -> ApiException.notFound("User"));
        repository.findMembership(tenant.organizationId(), userId).filter(MembershipRow::active).ifPresent(existing -> {
            throw ApiException.conflict("already_member", "User is already a member");
        });
        Instant now = SqlValues.now(clock);
        repository.upsertMembership(tenant.organizationId(), userId, role, MembershipRow.ACTIVE, tenant.userId(), now);
        events.publishEvent(new TenancyEvent.MembershipGranted(tenant.organizationId(), tenant.userId(), userId, role));
        events.publishEvent(RelationshipTuplesChanged.write(
                RelationshipTuple.organization(userId, role.relation(), tenant.organizationId())));
        return Member.of(repository.findMembership(tenant.organizationId(), userId).orElseThrow(), user);
    }

    @Transactional
    public Member changeRole(TenantContext tenant, UUID userId, MembershipRole newRole) {
        lockOrganization(tenant);
        requireOwner(tenant, "Only owners can change roles");
        MembershipRow current = activeMembership(tenant, userId);
        if (current.role() == newRole) {
            return Member.of(current, users.findAll(List.of(userId)).get(userId));
        }
        if (current.role() == MembershipRole.OWNER) {
            requireAnotherOwner(tenant);
        }
        repository.upsertMembership(tenant.organizationId(), userId, newRole, MembershipRow.ACTIVE, tenant.userId(),
                SqlValues.now(clock));
        events.publishEvent(new TenancyEvent.MembershipRoleChanged(tenant.organizationId(), tenant.userId(), userId,
                current.role(), newRole));
        events.publishEvent(RelationshipTuplesChanged.replace(
                RelationshipTuple.organization(userId, current.role().relation(), tenant.organizationId()),
                RelationshipTuple.organization(userId, newRole.relation(), tenant.organizationId())));
        return Member.of(repository.findMembership(tenant.organizationId(), userId).orElseThrow(),
                users.findAll(List.of(userId)).get(userId));
    }

    @Transactional
    public void revoke(TenantContext tenant, UUID userId) {
        lockOrganization(tenant);
        MembershipRow current = activeMembership(tenant, userId);
        boolean self = userId.equals(tenant.userId());
        if (!self && current.role() != MembershipRole.MEMBER) {
            requireOwner(tenant, "Only owners can revoke " + current.role());
        }
        if (current.role() == MembershipRole.OWNER) {
            requireAnotherOwner(tenant);
        }
        repository.upsertMembership(tenant.organizationId(), userId, current.role(), MembershipRow.REVOKED,
                tenant.userId(), SqlValues.now(clock));
        events.publishEvent(new TenancyEvent.MembershipRevoked(tenant.organizationId(), tenant.userId(), userId,
                current.role()));
        events.publishEvent(RelationshipTuplesChanged.delete(
                RelationshipTuple.organization(userId, current.role().relation(), tenant.organizationId())));
    }

    private void lockOrganization(TenantContext tenant) {
        repository.lockOrganization(tenant.organizationId()).orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private MembershipRow activeMembership(TenantContext tenant, UUID userId) {
        return repository.findMembership(tenant.organizationId(), userId)
                .filter(MembershipRow::active)
                .orElseThrow(() -> ApiException.notFound("Member"));
    }

    /** Uses the role from the database (re-read under the organization lock), not the possibly stale context. */
    private void requireOwner(TenantContext tenant, String message) {
        MembershipRole actorRole = repository.findMembership(tenant.organizationId(), tenant.userId())
                .filter(MembershipRow::active)
                .map(MembershipRow::role)
                .orElseThrow(() -> ApiException.notFound("Organization"));
        if (actorRole != MembershipRole.OWNER) {
            throw ApiException.forbidden("access_denied", message);
        }
    }

    private void requireAnotherOwner(TenantContext tenant) {
        if (repository.countActiveOwners(tenant.organizationId()) <= 1) {
            throw ApiException.conflict("last_owner", "An organization must keep at least one owner");
        }
    }

    public record Member(UUID userId, String email, String displayName, MembershipRole role, String status,
            Instant createdAt, Instant updatedAt) {

        static Member of(MembershipRow row, UserSummary user) {
            return new Member(row.userId(), user == null ? null : user.email(), user == null ? null : user.displayName(),
                    row.role(), row.status(), row.createdAt(), row.updatedAt());
        }
    }
}
