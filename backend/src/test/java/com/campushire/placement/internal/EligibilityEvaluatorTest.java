package com.campushire.placement.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bm4a.platform.modules.shared.error.ApiException;

import tools.jackson.databind.json.JsonMapper;

class EligibilityEvaluatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID CSE = UUID.randomUUID();
    private static final UUID ECE = UUID.randomUUID();
    private static final UUID BATCH_2026 = UUID.randomUUID();

    private static StudentFacts student(String cgpa, int backlogs, UUID department, int offers, String highestCtc) {
        return new StudentFacts(UUID.randomUUID(), cgpa == null ? null : new BigDecimal(cgpa), backlogs, department,
                BATCH_2026, "btech", new BigDecimal("85"), new BigDecimal("78.5"), offers > 0 ? "placed" : "unplaced",
                offers, highestCtc == null ? null : new BigDecimal(highestCtc));
    }

    private static EligibilityCriteria criteria(String json) {
        return EligibilityCriteria.parse(JSON.readTree(json));
    }

    @Test
    void openCriteriaAdmitEveryParticipatingStudentWithoutOffers() {
        var verdict = EligibilityEvaluator.evaluate(EligibilityCriteria.OPEN, PlacementPolicy.DEFAULT,
                student("5.1", 3, ECE, 0, null), null);

        assertThat(verdict.eligible()).isTrue();
        assertThat(verdict.reasons()).isEmpty();
    }

    @Test
    void explainsEveryFailedRule() {
        var rules = criteria("""
                {"minCgpa": 7.0, "maxActiveBacklogs": 0, "departments": ["%s"], "minTenth": 90, "minTwelfth": "80"}
                """.formatted(CSE));

        var verdict = EligibilityEvaluator.evaluate(rules, PlacementPolicy.DEFAULT, student("6.8", 1, ECE, 0, null),
                null);

        assertThat(verdict.eligible()).isFalse();
        assertThat(verdict.reasons()).extracting(EligibilityEvaluator.Reason::code).containsExactly(
                "cgpa_below_minimum", "too_many_backlogs", "department_not_eligible", "tenth_below_minimum",
                "twelfth_below_minimum");
        assertThat(verdict.reasons().getFirst().message()).isEqualTo("CGPA 6.8 is below the required 7.0");
    }

    @Test
    void boundaryValuesAreInclusive() {
        var rules = criteria("""
                {"minCgpa": 7.5, "maxActiveBacklogs": 1, "batches": ["%s"], "programs": ["btech"]}
                """.formatted(BATCH_2026));

        assertThat(EligibilityEvaluator.evaluate(rules, PlacementPolicy.DEFAULT, student("7.50", 1, CSE, 0, null),
                null).eligible()).isTrue();
    }

    @Test
    void missingCgpaDoesNotSatisfyAMinimum() {
        var verdict = EligibilityEvaluator.evaluate(criteria("{\"minCgpa\": 6}"), PlacementPolicy.DEFAULT,
                student(null, 0, CSE, 0, null), null);

        assertThat(verdict.reasons()).extracting(EligibilityEvaluator.Reason::message)
                .containsExactly("CGPA not recorded is below the required 6");
    }

    @Test
    void offerLimitBlocksRegularDrivesButAllowsBetterDreamDrives() {
        var placed = student("8.2", 0, CSE, 1, "900000");
        var policy = new PlacementPolicy(1, new BigDecimal("1200000"), true);

        assertThat(EligibilityEvaluator.evaluate(EligibilityCriteria.OPEN, policy, placed, new BigDecimal("800000"))
                .reasons()).extracting(EligibilityEvaluator.Reason::code).containsExactly("offer_limit_reached");
        assertThat(EligibilityEvaluator.evaluate(EligibilityCriteria.OPEN, policy, placed, new BigDecimal("1500000"))
                .eligible()).isTrue();
        assertThat(EligibilityEvaluator.evaluate(EligibilityCriteria.OPEN,
                new PlacementPolicy(1, new BigDecimal("1200000"), false), placed, new BigDecimal("1500000"))
                .eligible()).isFalse();
    }

    @Test
    void dreamUpgradeRequiresMoreThanTheCurrentBestOffer() {
        var placedAtDream = student("9.1", 0, CSE, 1, "2000000");

        assertThat(EligibilityEvaluator.evaluate(EligibilityCriteria.OPEN, PlacementPolicy.DEFAULT, placedAtDream,
                new BigDecimal("1500000")).eligible()).isFalse();
    }

    @Test
    void excludePlacedOverridesAHigherInstitutePolicyLimit() {
        var policy = new PlacementPolicy(3, new BigDecimal("1200000"), false);
        var oneOffer = student("8.0", 0, CSE, 1, "600000");

        assertThat(EligibilityEvaluator.evaluate(EligibilityCriteria.OPEN, policy, oneOffer, null).eligible()).isTrue();
        assertThat(EligibilityEvaluator.evaluate(criteria("{\"excludePlaced\": true}"), policy, oneOffer, null)
                .eligible()).isFalse();
    }

    @Test
    void optedOutStudentsAreNotParticipating() {
        var optedOut = new StudentFacts(UUID.randomUUID(), new BigDecimal("9"), 0, CSE, BATCH_2026, "btech", null,
                null, "opted_out", 0, null);

        assertThat(EligibilityEvaluator.evaluate(EligibilityCriteria.OPEN, PlacementPolicy.DEFAULT, optedOut, null)
                .reasons()).extracting(EligibilityEvaluator.Reason::code).containsExactly("not_participating");
    }

    @Test
    void rejectsMalformedCriteriaWithFieldErrors() {
        assertThatThrownBy(() -> criteria("""
                {"minCgpa": 11, "maxActiveBacklogs": -1, "departments": ["not-a-uuid"], "salary": 5}
                """)).isInstanceOf(ApiException.class);
    }

    @Test
    void roundTripsToCanonicalJson() {
        var rules = criteria("""
                {"minCgpa": "7.25", "departments": ["%s", "%s"], "excludePlaced": true}
                """.formatted(CSE, ECE));

        var reparsed = EligibilityCriteria.parse(rules.toJson());

        assertThat(reparsed).isEqualTo(rules);
        assertThat(reparsed.departments()).isEqualTo(Set.of(CSE, ECE));
    }

    @Test
    void policyFallsBackToDefaultsForMissingOrInvalidSettings() {
        assertThat(PlacementPolicy.from(null)).isEqualTo(PlacementPolicy.DEFAULT);
        assertThat(PlacementPolicy.from(JSON.readTree("""
                {"campushire": {"policy": {"maxOffersPerStudent": 2, "dreamCtcThreshold": "x"}}}
                """))).isEqualTo(new PlacementPolicy(2, PlacementPolicy.DEFAULT.dreamCtcThreshold(), true));
    }
}
