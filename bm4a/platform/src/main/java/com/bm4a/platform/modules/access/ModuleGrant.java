package com.bm4a.platform.modules.access;

import java.util.Objects;

import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantContext;

/**
 * Permission to perform {@code action} on records of {@code module}, as decided by {@link ModuleAccess}: the verified
 * tenant, the record scope and the field policy that the record engine must apply.
 *
 * @param roleKey the caller's org role, {@code null} for none (and for organization admins without one)
 */
public record ModuleGrant(TenantContext tenant, ModuleSchema module, RecordAction action, RecordScope scope,
        FieldPolicy fields, String roleKey) {

    public ModuleGrant {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fields, "fields");
    }

    /**
     * Unrestricted grant for trusted application code that has authorized the operation itself (for example the
     * record engine's {@code RecordOperations}). Never derive it from request input.
     */
    public static ModuleGrant trusted(TenantContext tenant, ModuleSchema module, RecordAction action) {
        return new ModuleGrant(tenant, module, action, RecordScope.all(), FieldPolicy.unrestricted(), null);
    }

    /** Organization owners and admins. */
    public boolean admin() {
        return tenant.role() != MembershipRole.MEMBER;
    }
}
