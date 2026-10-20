package com.campushire.placement.internal;

import java.math.BigDecimal;

import tools.jackson.databind.JsonNode;

/**
 * Institute-wide placement policy from organization settings ({@code settings.campushire.policy}).
 *
 * @param maxOffersPerStudent active (extended or accepted) offers after which a student is no longer eligible
 * @param dreamCtcThreshold CTC from which a drive counts as a "dream" opportunity
 * @param allowDreamUpgrade whether students at the offer limit may still apply to dream drives paying more than their
 *        best offer
 */
record PlacementPolicy(int maxOffersPerStudent, BigDecimal dreamCtcThreshold, boolean allowDreamUpgrade) {

    static final PlacementPolicy DEFAULT = new PlacementPolicy(1, BigDecimal.valueOf(1_200_000), true);

    static PlacementPolicy from(JsonNode organizationSettings) {
        JsonNode policy = organizationSettings == null ? null : organizationSettings.path("campushire").path("policy");
        if (policy == null || !policy.isObject()) {
            return DEFAULT;
        }
        JsonNode max = policy.get("maxOffersPerStudent");
        JsonNode threshold = policy.get("dreamCtcThreshold");
        JsonNode upgrade = policy.get("allowDreamUpgrade");
        return new PlacementPolicy(
                max != null && max.isIntegralNumber() && max.intValue() > 0 ? max.intValue() : DEFAULT.maxOffersPerStudent,
                threshold != null && threshold.isNumber() ? threshold.decimalValue() : DEFAULT.dreamCtcThreshold,
                upgrade != null && upgrade.isBoolean() ? upgrade.booleanValue() : DEFAULT.allowDreamUpgrade);
    }
}
