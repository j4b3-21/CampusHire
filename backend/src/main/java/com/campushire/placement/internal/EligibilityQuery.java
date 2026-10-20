package com.campushire.placement.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The bulk form of {@link EligibilityEvaluator}: one SQL statement that evaluates every rule for every student of an
 * organization over the student and offer projections. Each rule becomes a boolean column {@code r_<reason code>};
 * {@code eligible} is true when none holds. Rules that the criteria do not set are the constant {@code FALSE}. The SQL
 * consists of constant fragments; every value is a bound parameter. {@code EligibilityQueryIntegrationTest} proves
 * that both forms agree.
 */
final class EligibilityQuery {

    /** Reason codes in evaluator order, each with its SQL column. */
    static final List<String> REASONS = List.of("not_participating", "cgpa_below_minimum", "too_many_backlogs",
            "department_not_eligible", "batch_not_eligible", "program_not_eligible", "tenth_below_minimum",
            "twelfth_below_minimum", "offer_limit_reached");

    private final String cte;
    private final Map<String, Object> params;

    private EligibilityQuery(String cte, Map<String, Object> params) {
        this.cte = cte;
        this.params = Map.copyOf(params);
    }

    /**
     * @param departmentScope only students of these departments ({@code null}: all)
     * @param students only these students ({@code null}: all)
     */
    static EligibilityQuery of(UUID organizationId, EligibilityCriteria criteria, PlacementPolicy policy,
            BigDecimal driveCtc, Collection<UUID> departmentScope, Collection<UUID> students) {
        Map<String, Object> params = new HashMap<>();
        params.put("org", organizationId);
        Map<String, String> reasons = new LinkedHashMap<>();
        reasons.put("not_participating", "s.placement_status IN ('opted_out', 'higher_studies')");
        reasons.put("cgpa_below_minimum", minimum("s.cgpa", "minCgpa", criteria.minCgpa(), params));
        if (criteria.maxActiveBacklogs() != null) {
            params.put("maxBacklogs", criteria.maxActiveBacklogs());
            reasons.put("too_many_backlogs", "s.active_backlogs > :maxBacklogs");
        } else {
            reasons.put("too_many_backlogs", "FALSE");
        }
        reasons.put("department_not_eligible", member("s.department_id", "departments", criteria.departments(),
                params));
        reasons.put("batch_not_eligible", member("s.batch_id", "batches", criteria.batches(), params));
        reasons.put("program_not_eligible", member("s.program", "programs", criteria.programs(), params));
        reasons.put("tenth_below_minimum", minimum("s.tenth_percentage", "minTenth", criteria.minTenth(), params));
        reasons.put("twelfth_below_minimum", minimum("s.twelfth_percentage", "minTwelfth", criteria.minTwelfth(),
                params));
        params.put("offerLimit", criteria.excludePlaced() ? 1 : policy.maxOffersPerStudent());
        String dreamUpgrade = "FALSE";
        if (policy.allowDreamUpgrade() && driveCtc != null
                && driveCtc.compareTo(policy.dreamCtcThreshold()) >= 0) {
            params.put("driveCtc", driveCtc);
            dreamUpgrade = "(o.highest_ctc IS NULL OR :driveCtc > o.highest_ctc)";
        }
        reasons.put("offer_limit_reached", "coalesce(o.active_offers, 0) >= :offerLimit AND NOT " + dreamUpgrade);

        List<String> filters = new ArrayList<>();
        filters.add("s.organization_id = :org");
        filters.add("NOT s.deleted");
        if (departmentScope != null) {
            filters.add(departmentScope.isEmpty() ? "FALSE" : "s.department_id IN (:scopeDepartments)");
            params.put("scopeDepartments", departmentScope);
        }
        if (students != null) {
            filters.add(students.isEmpty() ? "FALSE" : "s.student_id IN (:students)");
            params.put("students", students);
        }

        StringBuilder columns = new StringBuilder();
        reasons.forEach((code, condition) -> columns.append(",\n           coalesce(").append(condition)
                .append(", FALSE) AS r_").append(code));
        String anyReason = String.join(" OR ", REASONS.stream().map(code -> "r_" + code).toList());
        String cte = """
                WITH offers AS (
                    SELECT student_id, count(*) AS active_offers, max(ctc_amount) AS highest_ctc
                    FROM campushire_offer_projection
                    WHERE organization_id = :org AND NOT deleted AND status IN ('extended', 'accepted')
                    GROUP BY student_id
                ), evaluated AS (
                    SELECT s.student_id, s.owner_user_id, s.roll_number, s.full_name, s.department_id, s.batch_id,
                           s.cgpa%s
                    FROM campushire_student_projection s
                    LEFT JOIN offers o ON o.student_id = s.student_id
                    WHERE %s
                ), verdicts AS (
                    SELECT e.*, NOT (%s) AS eligible FROM evaluated e
                )
                """.formatted(columns, String.join(" AND ", filters), anyReason);
        return new EligibilityQuery(cte, params);
    }

    /** The {@code WITH ... verdicts AS (...)} prefix; append a {@code SELECT ... FROM verdicts}. */
    String cte() {
        return cte;
    }

    Map<String, Object> params() {
        return params;
    }

    private static String minimum(String column, String name, BigDecimal minimum, Map<String, Object> params) {
        if (minimum == null) {
            return "FALSE";
        }
        params.put(name, minimum);
        return "(" + column + " IS NULL OR " + column + " < :" + name + ")";
    }

    private static String member(String column, String name, Collection<?> values, Map<String, Object> params) {
        if (values.isEmpty()) {
            return "FALSE";
        }
        params.put(name, values);
        return "(" + column + " IS NULL OR " + column + " NOT IN (:" + name + "))";
    }
}
