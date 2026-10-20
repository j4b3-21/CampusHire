package com.campushire.placement.internal;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The eligibility-relevant facts about one student, read from the typed student and offer projections.
 *
 * @param activeOffers number of extended or accepted offers
 * @param highestCtc best CTC among active offers, {@code null} without offers
 */
record StudentFacts(UUID studentId, BigDecimal cgpa, int activeBacklogs, UUID departmentId, UUID batchId,
        String program, BigDecimal tenthPercentage, BigDecimal twelfthPercentage, String placementStatus,
        int activeOffers, BigDecimal highestCtc) {
}
