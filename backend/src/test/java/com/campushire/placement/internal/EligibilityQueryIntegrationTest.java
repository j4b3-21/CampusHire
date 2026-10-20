package com.campushire.placement.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.Fixtures;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;

/**
 * Property test: the bulk SQL evaluation ({@link EligibilityQuery}) and the Java evaluator agree, rule by rule, on a
 * generated population (boundary values, missing marks, every placement status, offers with and without CTC) for
 * many generated criteria, policies and drive CTCs.
 */
@IntegrationTest
class EligibilityQueryIntegrationTest {

    static final int STUDENTS = 400;
    static final int SCENARIOS = 60;
    static final List<String> STATUSES = List.of("unplaced", "unplaced", "placed", "opted_out", "higher_studies");
    static final List<String> PROGRAMS = List.of("btech", "mtech", "mba");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EligibilityService eligibility;

    @Autowired
    PlacementRepository repository;

    final Random random = new Random(20261020L);
    final List<UUID> departments = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID());
    final List<UUID> batches = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    UUID org;
    List<UUID> students;

    @BeforeEach
    void seed() throws Exception {
        org = UUID.fromString(Fixtures.createOrg(new TestApi(mvc).newUser(), "Eligibility property test"));
        students = new ArrayList<>();
        for (int i = 0; i < STUDENTS; i++) {
            UUID id = UUID.randomUUID();
            students.add(id);
            jdbc.sql("""
                    INSERT INTO campushire_student_projection (student_id, organization_id, roll_number, full_name,
                        department_id, batch_id, program, cgpa, active_backlogs, tenth_percentage, twelfth_percentage,
                        placement_status, record_version, updated_at)
                    VALUES (:id, :org, :roll, :name, :department, :batch, :program, :cgpa, :backlogs, :tenth,
                        :twelfth, :status, 1, now())
                    """)
                    .param("id", id).param("org", org).param("roll", "R" + i).param("name", "Student " + i)
                    .param("department", maybe(0.05) ? null : pick(departments))
                    .param("batch", maybe(0.05) ? null : pick(batches))
                    .param("program", maybe(0.05) ? null : pick(PROGRAMS))
                    .param("cgpa", maybe(0.08) ? null : grade(4, 10))
                    .param("backlogs", random.nextInt(4))
                    .param("tenth", maybe(0.08) ? null : grade(40, 100))
                    .param("twelfth", maybe(0.08) ? null : grade(40, 100))
                    .param("status", pick(STATUSES))
                    .update();
            int offers = random.nextInt(10) < 6 ? 0 : 1 + random.nextInt(3);
            for (int o = 0; o < offers; o++) {
                jdbc.sql("""
                        INSERT INTO campushire_offer_projection (offer_id, organization_id, student_id, ctc_amount,
                            status, deleted, record_version, created_at, updated_at)
                        VALUES (:id, :org, :student, :ctc, :status, :deleted, 1, now(), now())
                        """)
                        .param("id", UUID.randomUUID()).param("org", org).param("student", id)
                        .param("ctc", maybe(0.1) ? null : BigDecimal.valueOf(300_000 + random.nextInt(30) * 100_000L))
                        .param("status", pick(List.of("extended", "accepted", "declined", "revoked")))
                        .param("deleted", maybe(0.1))
                        .update();
            }
        }
    }

    @Test
    void sqlAndJavaEvaluationsAgreeForEveryStudentAndRule() {
        Map<UUID, StudentFacts> facts = repository.studentFacts(org, students);
        assertThat(facts).hasSize(STUDENTS);
        int eligibleSeen = 0;
        int ineligibleSeen = 0;
        for (int scenario = 0; scenario < SCENARIOS; scenario++) {
            EligibilityCriteria criteria = criteria(facts);
            PlacementPolicy policy = new PlacementPolicy(1 + random.nextInt(3),
                    BigDecimal.valueOf(800_000 + random.nextInt(10) * 100_000L), random.nextBoolean());
            BigDecimal driveCtc = maybe(0.2) ? null : BigDecimal.valueOf(500_000 + random.nextInt(25) * 100_000L);
            List<UUID> scope = maybe(0.3) ? List.of(departments.get(0), departments.get(1)) : null;

            Map<UUID, Set<String>> sql = eligibility.evaluateInSql(EligibilityQuery.of(org, criteria, policy,
                    driveCtc, scope, null));

            for (UUID student : students) {
                StudentFacts studentFacts = facts.get(student);
                if (scope != null && (studentFacts.departmentId() == null
                        || !scope.contains(studentFacts.departmentId()))) {
                    assertThat(sql).doesNotContainKey(student);
                    continue;
                }
                EligibilityEvaluator.Verdict java = EligibilityEvaluator.evaluate(criteria, policy, studentFacts,
                        driveCtc);
                Set<String> javaCodes = new LinkedHashSet<>();
                java.reasons().forEach(reason -> javaCodes.add(reason.code()));
                assertThat(sql.get(student))
                        .as("scenario %d, student %s, criteria %s, policy %s, ctc %s", scenario, studentFacts,
                                criteria, policy, driveCtc)
                        .containsExactlyInAnyOrderElementsOf(javaCodes);
                if (java.eligible()) {
                    eligibleSeen++;
                } else {
                    ineligibleSeen++;
                }
            }
        }
        assertThat(eligibleSeen).isGreaterThan(100);
        assertThat(ineligibleSeen).isGreaterThan(100);
    }

    /** Thresholds are drawn from actual student values half of the time, to hit the inclusive boundaries. */
    private EligibilityCriteria criteria(Map<UUID, StudentFacts> facts) {
        List<StudentFacts> all = new ArrayList<>(facts.values());
        StudentFacts sample = all.get(random.nextInt(all.size()));
        BigDecimal minCgpa = maybe(0.4) ? null
                : sample.cgpa() != null && random.nextBoolean() ? sample.cgpa() : grade(5, 9);
        Integer maxBacklogs = maybe(0.5) ? null : random.nextInt(3);
        Set<UUID> departmentRule = maybe(0.6) ? Set.of() : new HashSet<>(List.of(pick(departments), pick(departments)));
        Set<UUID> batchRule = maybe(0.7) ? Set.of() : Set.of(pick(batches));
        Set<String> programRule = maybe(0.7) ? Set.of() : Set.of(pick(PROGRAMS));
        BigDecimal minTenth = maybe(0.6) ? null
                : sample.tenthPercentage() != null && random.nextBoolean() ? sample.tenthPercentage()
                        : grade(50, 90);
        BigDecimal minTwelfth = maybe(0.6) ? null : grade(50, 90);
        return new EligibilityCriteria(minCgpa, maxBacklogs, departmentRule, batchRule, programRule, minTenth,
                minTwelfth, maybe(0.3));
    }

    private BigDecimal grade(int min, int max) {
        return BigDecimal.valueOf(min + random.nextDouble() * (max - min)).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean maybe(double probability) {
        return random.nextDouble() < probability;
    }

    private <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
