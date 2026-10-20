package com.campushire.analytics.internal;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The data a staff member may analyze: one organization, and for department coordinators only their departments.
 *
 * @param departments {@code null} for every department; otherwise the only departments (possibly none) visible
 */
record AnalyticsScope(UUID organizationId, Set<UUID> departments) {

    AnalyticsScope {
        Objects.requireNonNull(organizationId, "organizationId");
        departments = departments == null ? null : Set.copyOf(departments);
    }

    static AnalyticsScope organization(UUID organizationId) {
        return new AnalyticsScope(organizationId, null);
    }

    boolean restricted() {
        return departments != null;
    }

    /** Stable identity of the scope for cache keys. */
    String key() {
        if (departments == null) {
            return "all";
        }
        return departments.stream().map(UUID::toString).sorted().collect(Collectors.joining(",", "departments:", ""));
    }
}
