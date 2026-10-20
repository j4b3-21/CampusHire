package com.bm4a.platform.modules.tenancy.internal;

import com.bm4a.platform.modules.tenancy.MembershipRole;

/** An organization as seen by one member. */
public record OrganizationWithRole(OrganizationRow organization, MembershipRole role) {
}
