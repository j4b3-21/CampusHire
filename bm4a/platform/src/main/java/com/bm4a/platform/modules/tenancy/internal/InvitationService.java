package com.bm4a.platform.modules.tenancy.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.InvitationMail;
import com.bm4a.platform.modules.tenancy.InvitationMailer;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.OrgRoleCatalog;
import com.bm4a.platform.modules.tenancy.OrgRoleCatalog.OrgRoleRef;
import com.bm4a.platform.modules.tenancy.TenancyEvent;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;
import com.bm4a.platform.modules.users.UserDirectory;
import com.bm4a.platform.modules.users.UserDirectory.UserSummary;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * E-mail invitations (ADR-004). The token is 32 random bytes (base64url), returned once to the inviter and mailed to
 * the invitee; only its SHA-256 hash is stored. An invitation is single use, expires after {@link #VALIDITY}, and can
 * only be accepted by a signed-in user whose <em>verified</em> e-mail equals the invited address. Inviting admins or
 * owners requires an owner, like granting those roles directly.
 */
@Service
public class InvitationService {

    static final Duration VALIDITY = Duration.ofDays(7);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Set<String> STATUSES = Set.of(InvitationRow.PENDING, InvitationRow.EXPIRED,
            InvitationRow.ACCEPTED, InvitationRow.REVOKED);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final InvitationRepository invitations;
    private final TenancyRepository tenancy;
    private final MembershipService memberships;
    private final OrgRoleCatalog roles;
    private final UserDirectory users;
    private final InvitationMailer mailer;
    private final InvitationProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    InvitationService(InvitationRepository invitations, TenancyRepository tenancy, MembershipService memberships,
            OrgRoleCatalog roles, UserDirectory users, InvitationMailer mailer, InvitationProperties properties,
            ApplicationEventPublisher events, Clock clock) {
        this.invitations = invitations;
        this.tenancy = tenancy;
        this.memberships = memberships;
        this.roles = roles;
        this.users = users;
        this.mailer = mailer;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    /** An invitation as shown to organization admins. */
    public record InvitationView(UUID id, String email, MembershipRole membershipRole, String roleKey,
            Map<String, Object> attributes, String status, Instant expiresAt, Instant createdAt, UUID createdBy,
            Instant acceptedAt, UUID acceptedBy, Instant revokedAt) {
    }

    /** The result of creating an invitation; {@code acceptUrl} carries the token and is never shown again. */
    public record CreatedInvitation(InvitationView invitation, String acceptUrl) {
    }

    /** What a signed-in user holding the token may see before accepting. */
    public record InvitationPreview(UUID organizationId, String organizationName, MembershipRole membershipRole,
            String roleKey, String roleLabel, String email, String status, Instant expiresAt) {
    }

    @Transactional
    public CreatedInvitation create(TenantContext tenant, String email, MembershipRole membershipRole, String roleKey,
            JsonNode attributes) {
        OrganizationRow organization = memberships.lockOrganization(tenant.organizationId());
        if (membershipRole == null) {
            throw ApiException.validation("membershipRole", "required", "membershipRole is required");
        }
        String normalizedEmail = normalizeEmail(email);
        if (membershipRole != MembershipRole.MEMBER) {
            memberships.requireOwner(tenant, "Only owners can invite " + membershipRole + "s");
        }
        OrgRoleRef orgRole = memberships.resolveRole(tenant.organizationId(), roleKey, "roleKey");
        ObjectNode canonical = MembershipAttributes.canonical(attributes, "attributes");
        Instant now = SqlValues.now(clock);
        if (invitations.pendingExists(tenant.organizationId(), normalizedEmail, now)) {
            throw ApiException.conflict("invitation_pending",
                    "A pending invitation for this e-mail exists; revoke it to send a new one");
        }
        String token = newToken();
        InvitationRow row = new InvitationRow(UUID.randomUUID(), tenant.organizationId(), normalizedEmail,
                membershipRole, orgRole == null ? null : orgRole.id(), canonical, hash(token), InvitationRow.PENDING,
                now.plus(VALIDITY), now, tenant.userId(), null, null, null, null);
        invitations.insert(row);
        events.publishEvent(new TenancyEvent.InvitationCreated(tenant.organizationId(), tenant.userId(), row.id(),
                normalizedEmail, membershipRole, orgRole == null ? null : orgRole.key()));
        String acceptUrl = properties.acceptUrl(token);
        String inviter = users.findAll(List.of(tenant.userId())).values().stream().findFirst()
                .map(user -> user.displayName() != null ? user.displayName() : user.email()).orElse(null);
        sendAfterCommit(new InvitationMail(row.id(), organization.id(), organization.name(), inviter, normalizedEmail,
                roleLabel(membershipRole, orgRole), acceptUrl, row.expiresAt()));
        return new CreatedInvitation(view(row, orgRole, now), acceptUrl);
    }

    @Transactional(readOnly = true)
    public PageResponse<InvitationView> list(TenantContext tenant, String status, PageQuery page) {
        String filter = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (filter != null && !STATUSES.contains(filter)) {
            throw ApiException.validation("status", "invalid", "status must be one of " + STATUSES);
        }
        Instant now = SqlValues.now(clock);
        List<InvitationRow> rows = invitations.list(tenant.organizationId(), filter, now, page.offset(), page.size());
        Map<UUID, OrgRoleRef> orgRoles = roles.findAll(tenant.organizationId(),
                rows.stream().map(InvitationRow::roleId).filter(Objects::nonNull).distinct().toList());
        return PageResponse.of(rows.stream().map(row -> view(row, orgRoles.get(row.roleId()), now)).toList(), page,
                invitations.count(tenant.organizationId(), filter, now));
    }

    @Transactional
    public void revoke(TenantContext tenant, UUID invitationId) {
        memberships.lockOrganization(tenant.organizationId());
        InvitationRow row = invitations.find(tenant.organizationId(), invitationId, true)
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        if (!InvitationRow.PENDING.equals(row.status())) {
            throw ApiException.conflict("invitation_not_pending", "Only pending invitations can be revoked");
        }
        if (row.membershipRole() != MembershipRole.MEMBER) {
            memberships.requireOwner(tenant, "Only owners can revoke invitations for " + row.membershipRole() + "s");
        }
        invitations.markRevoked(tenant.organizationId(), row.id(), tenant.userId(), SqlValues.now(clock));
        events.publishEvent(new TenancyEvent.InvitationRevoked(tenant.organizationId(), tenant.userId(), row.id(),
                row.email()));
    }

    @Transactional(readOnly = true)
    public InvitationPreview preview(CurrentUser user, String token) {
        InvitationRow row = byToken(token, false);
        OrganizationRow organization = tenancy.findOrganization(row.organizationId())
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        OrgRoleRef orgRole = orgRole(row);
        return new InvitationPreview(organization.id(), organization.name(), row.membershipRole(),
                orgRole == null ? null : orgRole.key(), roleLabel(row.membershipRole(), orgRole), maskEmail(row.email()),
                row.effectiveStatus(SqlValues.now(clock)), row.expiresAt());
    }

    /**
     * Accepts the invitation for the caller. Failures: unknown token 404; already accepted 409
     * {@code invitation_used}; revoked 410 {@code invitation_revoked}; expired 410 {@code invitation_expired};
     * unverified e-mail 403 {@code email_not_verified}; different e-mail 403 {@code invitation_email_mismatch};
     * already a member 409 {@code already_member}.
     */
    @Transactional
    public MembershipService.Member accept(CurrentUser user, String token) {
        InvitationRow row = byToken(token, true);
        Instant now = SqlValues.now(clock);
        switch (row.effectiveStatus(now)) {
            case InvitationRow.ACCEPTED -> throw ApiException.conflict("invitation_used",
                    "This invitation has already been used");
            case InvitationRow.REVOKED -> throw new ApiException(HttpStatus.GONE, "invitation_revoked",
                    "This invitation has been revoked");
            case InvitationRow.EXPIRED -> throw new ApiException(HttpStatus.GONE, "invitation_expired",
                    "This invitation has expired");
            default -> {
            }
        }
        if (!user.emailVerified()) {
            throw ApiException.forbidden("email_not_verified", "Verify your e-mail address to accept invitations");
        }
        if (user.email() == null || !row.email().equals(user.email().strip().toLowerCase(Locale.ROOT))) {
            throw ApiException.forbidden("invitation_email_mismatch",
                    "This invitation was sent to a different e-mail address");
        }
        OrganizationRow organization = memberships.lockOrganization(row.organizationId());
        if (!organization.active()) {
            throw ApiException.forbidden("organization_inactive", "Organization is " + organization.status().toLowerCase());
        }
        if (tenancy.findMembership(row.organizationId(), user.id()).filter(MembershipRow::active).isPresent()) {
            throw ApiException.conflict("already_member", "You are already a member of this organization");
        }
        memberships.activateFromInvitation(row.organizationId(), user.id(), row.membershipRole(), row.roleId(),
                row.attributes());
        invitations.markAccepted(row.id(), user.id(), now);
        events.publishEvent(new TenancyEvent.InvitationAccepted(row.organizationId(), user.id(), row.id(),
                row.email()));
        MembershipRow membership = tenancy.findMembership(row.organizationId(), user.id()).orElseThrow();
        OrgRoleRef orgRole = orgRole(row);
        return MembershipService.Member.of(membership, new UserSummary(user.id(), user.email(), user.displayName()),
                orgRole);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private InvitationRow byToken(String token, boolean forUpdate) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw ApiException.notFound("Invitation");
        }
        return invitations.findByTokenHash(hash(token), forUpdate).orElseThrow(() -> ApiException.notFound("Invitation"));
    }

    private OrgRoleRef orgRole(InvitationRow row) {
        return row.roleId() == null ? null : roles.findAll(row.organizationId(), List.of(row.roleId())).get(row.roleId());
    }

    /** The mail is sent only if and after the invitation commits; the token never enters the event outbox. */
    private void sendAfterCommit(InvitationMail mail) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailer.send(mail);
                } catch (RuntimeException ex) {
                    log.error("Sending invitation {} failed: {}", mail.invitationId(), ex.toString());
                }
            }
        });
    }

    private static InvitationView view(InvitationRow row, OrgRoleRef orgRole, Instant now) {
        return new InvitationView(row.id(), row.email(), row.membershipRole(), orgRole == null ? null : orgRole.key(),
                MembershipAttributes.asMap(row.attributes()), row.effectiveStatus(now), row.expiresAt(),
                row.createdAt(), row.createdBy(), row.acceptedAt(), row.acceptedBy(), row.revokedAt());
    }

    private static String roleLabel(MembershipRole role, OrgRoleRef orgRole) {
        if (orgRole != null) {
            return orgRole.label();
        }
        String name = role.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            throw ApiException.validation("email", "format", "email must be a valid e-mail address");
        }
        return normalized;
    }

    /** {@code jane.doe@example.com} becomes {@code j***@example.com}. */
    static String maskEmail(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
