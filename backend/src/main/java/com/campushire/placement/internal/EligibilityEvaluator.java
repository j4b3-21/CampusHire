package com.campushire.placement.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a student may apply to a drive and explains every failed rule in words a student understands.
 */
final class EligibilityEvaluator {

    private static final Set<String> NOT_PARTICIPATING = Set.of("opted_out", "higher_studies");

    private EligibilityEvaluator() {
    }

    record Reason(String code, String message) {
    }

    record Verdict(boolean eligible, List<Reason> reasons) {

        Verdict {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * @param driveCtc CTC of the drive's job, used for the dream-upgrade policy; {@code null} when unknown
     */
    static Verdict evaluate(EligibilityCriteria criteria, PlacementPolicy policy, StudentFacts student,
            BigDecimal driveCtc) {
        List<Reason> reasons = new ArrayList<>();
        if (student.placementStatus() != null && NOT_PARTICIPATING.contains(student.placementStatus())) {
            reasons.add(new Reason("not_participating", "You have opted out of campus placements"));
        }
        if (criteria.minCgpa() != null
                && (student.cgpa() == null || student.cgpa().compareTo(criteria.minCgpa()) < 0)) {
            reasons.add(new Reason("cgpa_below_minimum", "CGPA " + display(student.cgpa())
                    + " is below the required " + criteria.minCgpa().toPlainString()));
        }
        if (criteria.maxActiveBacklogs() != null && student.activeBacklogs() > criteria.maxActiveBacklogs()) {
            reasons.add(new Reason("too_many_backlogs", student.activeBacklogs() + " active backlog(s); at most "
                    + criteria.maxActiveBacklogs() + " allowed"));
        }
        if (!criteria.departments().isEmpty()
                && (student.departmentId() == null || !criteria.departments().contains(student.departmentId()))) {
            reasons.add(new Reason("department_not_eligible", "Your department is not eligible for this drive"));
        }
        if (!criteria.batches().isEmpty()
                && (student.batchId() == null || !criteria.batches().contains(student.batchId()))) {
            reasons.add(new Reason("batch_not_eligible", "Your batch is not eligible for this drive"));
        }
        if (!criteria.programs().isEmpty()
                && (student.program() == null || !criteria.programs().contains(student.program()))) {
            reasons.add(new Reason("program_not_eligible", "Your program is not eligible for this drive"));
        }
        if (criteria.minTenth() != null && (student.tenthPercentage() == null
                || student.tenthPercentage().compareTo(criteria.minTenth()) < 0)) {
            reasons.add(new Reason("tenth_below_minimum", "10th marks " + display(student.tenthPercentage())
                    + "% are below the required " + criteria.minTenth().toPlainString() + "%"));
        }
        if (criteria.minTwelfth() != null && (student.twelfthPercentage() == null
                || student.twelfthPercentage().compareTo(criteria.minTwelfth()) < 0)) {
            reasons.add(new Reason("twelfth_below_minimum", "12th/diploma marks "
                    + display(student.twelfthPercentage()) + "% are below the required "
                    + criteria.minTwelfth().toPlainString() + "%"));
        }
        int offerLimit = criteria.excludePlaced() ? 1 : policy.maxOffersPerStudent();
        if (student.activeOffers() >= offerLimit && !dreamUpgrade(policy, student, driveCtc)) {
            reasons.add(new Reason("offer_limit_reached", student.activeOffers() == 1
                    ? "You already hold an offer; only higher-paying dream drives remain open to you"
                    : "You already hold " + student.activeOffers() + " offers"));
        }
        return new Verdict(reasons.isEmpty(), reasons);
    }

    private static boolean dreamUpgrade(PlacementPolicy policy, StudentFacts student, BigDecimal driveCtc) {
        return policy.allowDreamUpgrade() && driveCtc != null
                && driveCtc.compareTo(policy.dreamCtcThreshold()) >= 0
                && (student.highestCtc() == null || driveCtc.compareTo(student.highestCtc()) > 0);
    }

    private static String display(BigDecimal value) {
        return value == null ? "not recorded" : value.stripTrailingZeros().toPlainString();
    }
}
