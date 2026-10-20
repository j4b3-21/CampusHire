package com.bm4a.platform.modules.audit.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.tenancy.TenancyEvent;

/**
 * Synchronous listener: runs inside the publishing transaction, so an organization or membership change and its
 * audit row commit (or roll back) together.
 */
@Component
class TenancyAuditListener {

    private final AuditLog audit;

    TenancyAuditListener(AuditLog audit) {
        this.audit = audit;
    }

    @EventListener
    void on(TenancyEvent event) {
        audit.record(switch (event) {
            case TenancyEvent.OrganizationCreated e -> organization(e, "organization.created").with("name", e.name());
            case TenancyEvent.OrganizationUpdated e -> organization(e, "organization.updated").with("changes", e.changes());
            case TenancyEvent.OrganizationStatusChanged e -> organization(e, "organization.status_changed")
                    .with("from", e.from()).with("to", e.to());
            case TenancyEvent.MembershipGranted e -> membership(e, e.userId().toString(), "membership.granted")
                    .with("role", e.role().name()).with("roleKey", e.roleKey()).with("attributes", e.attributes());
            case TenancyEvent.MembershipRoleChanged e -> membership(e, e.userId().toString(), "membership.role_changed")
                    .with("from", e.from().name()).with("to", e.to().name());
            case TenancyEvent.MembershipAccessChanged e -> membership(e, e.userId().toString(),
                    "membership.access_changed")
                    .with("roleKey", fromTo(e.fromRoleKey(), e.toRoleKey()))
                    .with("attributes", fromTo(e.fromAttributes(), e.toAttributes()));
            case TenancyEvent.MembershipRevoked e -> membership(e, e.userId().toString(), "membership.revoked")
                    .with("role", e.role().name());
            case TenancyEvent.InvitationCreated e -> invitation(e, e.invitationId().toString(), "invitation.created")
                    .with("email", e.email()).with("role", e.role().name()).with("roleKey", e.roleKey());
            case TenancyEvent.InvitationRevoked e -> invitation(e, e.invitationId().toString(), "invitation.revoked")
                    .with("email", e.email());
            case TenancyEvent.InvitationAccepted e -> invitation(e, e.invitationId().toString(), "invitation.accepted")
                    .with("email", e.email());
            case TenancyEvent.TenantAccessDenied e -> new AuditEntry(e.organizationId(), e.actorUserId(),
                    "security.access_denied", "organization", e.organizationId().toString(),
                    Map.of("permission", e.permission(), "reason", e.reason()));
        });
    }

    private static AuditEntry organization(TenancyEvent event, String action) {
        return AuditEntry.of(event.organizationId(), event.actorUserId(), action, "organization", event.organizationId());
    }

    private static AuditEntry membership(TenancyEvent event, String userId, String action) {
        return AuditEntry.of(event.organizationId(), event.actorUserId(), action, "membership", userId);
    }

    private static Map<String, Object> fromTo(Object from, Object to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    private static AuditEntry invitation(TenancyEvent event, String invitationId, String action) {
        return AuditEntry.of(event.organizationId(), event.actorUserId(), action, "invitation", invitationId);
    }
}
