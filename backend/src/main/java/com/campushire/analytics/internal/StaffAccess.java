package com.campushire.analytics.internal;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.users.CurrentUser;

/**
 * Analytics are for placement staff: organization owners and admins, and members with the {@code tpo} or
 * {@code coordinator} role. The caller's read grant on students decides the scope; a coordinator's
 * {@code ATTRIBUTE department} scope restricts every query to those departments. Any other scope fails closed.
 */
@Component
class StaffAccess {

    static final String STUDENT_MODULE = "student";
    static final String DEPARTMENT_FIELD = "department";
    private static final Set<String> STAFF_ROLES = Set.of("tpo", "coordinator");

    private final ModuleAccess access;

    StaffAccess(ModuleAccess access) {
        this.access = access;
    }

    AnalyticsScope require(CurrentUser user, UUID organizationId) {
        ModuleGrant grant = access.require(user, organizationId, STUDENT_MODULE, RecordAction.READ);
        if (grant.admin()) {
            return AnalyticsScope.organization(organizationId);
        }
        if (grant.roleKey() == null || !STAFF_ROLES.contains(grant.roleKey())) {
            throw denied();
        }
        RecordScope scope = grant.scope();
        if (scope.unrestricted()) {
            return AnalyticsScope.organization(organizationId);
        }
        if (scope.kind() == RecordScope.Kind.ATTRIBUTE && DEPARTMENT_FIELD.equals(scope.field())
                && scope.where().isEmpty()) {
            return new AnalyticsScope(organizationId, departmentIds(scope));
        }
        throw denied();
    }

    private static Set<UUID> departmentIds(RecordScope scope) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (String value : scope.values()) {
            try {
                ids.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                // A malformed attribute value matches no department record either.
            }
        }
        return ids;
    }

    private static ApiException denied() {
        return ApiException.forbidden("access_denied", "Analytics are available to placement staff only");
    }
}
