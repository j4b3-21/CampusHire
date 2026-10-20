package com.campushire.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.modules.packs.PackInstaller;
import com.bm4a.platform.support.Fixtures;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-006 scale: 10,000 students in 6 departments and 4 batches, a year's 200,000 applications over 60 drives and
 * 3,000 offers. Every warm request (full HTTP path: security, authorization, queries, name lookups, serialization) must answer
 * within 300 ms. Each measured request uses distinct parameters, so a reachable Redis could not serve it from cache.
 */
@IntegrationTest
class AnalyticsPerformanceTests {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsPerformanceTests.class);
    private static final long BUDGET_MILLIS = 300;
    private static final int WARMUP = 5;
    private static final int MEASURED = 10;

    @Autowired
    MockMvc mvc;
    @Autowired
    PackInstaller packs;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;

    @Test
    void overviewAndDepartmentsAnswerWithinBudgetAtTenThousandStudents() throws Exception {
        TestApi api = new TestApi(mvc);
        Caller owner = api.newUser();
        AnalyticsFixtures data = AnalyticsFixtures.institute(owner, packs, json, jdbc);
        String org = data.org().toString();
        List<UUID> departments = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            departments.add(UUID.fromString(Fixtures.createRecord(owner, org, "department",
                    "{\"name\":\"Department " + i + "\",\"code\":\"D" + i + "\"}")));
        }
        Caller coordinator = api.newUser();
        Fixtures.addMemberWithRole(owner, org, coordinator, "coordinator",
                "{\"department\":\"" + departments.getFirst() + "\"}");

        long seeding = System.nanoTime();
        seed(data.org(), departments);
        log.info("Seeded 10,000 students, 200,000 applications and 3,000 offers in {} ms",
                (System.nanoTime() - seeding) / 1_000_000);

        owner.get("/api/v1/orgs/{org}/campushire/analytics/overview", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStudents").value(10_000))
                .andExpect(jsonPath("$.offers").value(3_000))
                .andExpect(jsonPath("$.applications").value(200_000));

        List<Long> overview = measure(owner, org, "/overview");
        List<Long> departmentStats = measure(owner, org, "/departments");
        List<Long> coordinatorOverview = measure(coordinator, org, "/overview");
        log.info("Warm timings in ms at 10k students - overview {}, departments {}, coordinator overview {}",
                overview, departmentStats, coordinatorOverview);

        assertThat(overview).allSatisfy(millis -> assertThat(millis).isLessThan(BUDGET_MILLIS));
        assertThat(departmentStats).allSatisfy(millis -> assertThat(millis).isLessThan(BUDGET_MILLIS));
        assertThat(coordinatorOverview).allSatisfy(millis -> assertThat(millis).isLessThan(BUDGET_MILLIS));
    }

    private List<Long> measure(Caller caller, String org, String path) throws Exception {
        String url = "/api/v1/orgs/{org}/campushire/analytics" + path + "?to={to}";
        for (int i = 0; i < WARMUP; i++) {
            caller.get(url, org, "2099-01-" + String.format("%02d", i + 1)).andExpect(status().isOk());
        }
        List<Long> timings = new ArrayList<>();
        for (int i = 0; i < MEASURED; i++) {
            long started = System.nanoTime();
            caller.get(url, org, "2099-02-" + String.format("%02d", i + 1)).andExpect(status().isOk());
            timings.add((System.nanoTime() - started) / 1_000_000);
        }
        return timings;
    }

    private void seed(UUID org, List<UUID> departments) {
        List<UUID> batches = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.sql("""
                INSERT INTO campushire_student_projection (student_id, organization_id, roll_number, full_name, email,
                    department_id, batch_id, program, cgpa, placement_status, record_version, updated_at)
                SELECT gen_random_uuid(), :org, 'R' || lpad(g::text, 6, '0'), 'Student ' || g, 's' || g || '@x.test',
                       CASE g % 6 WHEN 0 THEN :d0 WHEN 1 THEN :d1 WHEN 2 THEN :d2 WHEN 3 THEN :d3 WHEN 4 THEN :d4
                                  ELSE :d5 END,
                       CASE g % 4 WHEN 0 THEN :b0 WHEN 1 THEN :b1 WHEN 2 THEN :b2 ELSE :b3 END,
                       'btech', 6 + (g % 400) / 100.0,
                       CASE WHEN g % 50 = 0 THEN 'opted_out' WHEN g % 10 < 3 THEN 'placed' ELSE 'unplaced' END,
                       1, now()
                FROM generate_series(1, 10000) AS g
                """)
                .param("org", org)
                .param("d0", departments.get(0)).param("d1", departments.get(1)).param("d2", departments.get(2))
                .param("d3", departments.get(3)).param("d4", departments.get(4)).param("d5", departments.get(5))
                .param("b0", batches.get(0)).param("b1", batches.get(1)).param("b2", batches.get(2))
                .param("b3", batches.get(3))
                .update();
        jdbc.sql("""
                INSERT INTO campushire_drive_projection (drive_id, organization_id, company_id, title, status,
                    record_version, updated_at)
                SELECT gen_random_uuid(), :org, md5('company' || (g % 20))::uuid, 'Drive ' || g,
                       CASE g % 4 WHEN 0 THEN 'completed' WHEN 1 THEN 'in_progress' WHEN 2 THEN 'published'
                                  ELSE 'draft' END,
                       1, now()
                FROM generate_series(1, 60) AS g
                """)
                .param("org", org)
                .update();
        jdbc.sql("""
                WITH s AS (
                    SELECT student_id, row_number() OVER (ORDER BY roll_number) AS n
                    FROM campushire_student_projection WHERE organization_id = :org
                ), d AS (
                    SELECT drive_id, company_id, row_number() OVER (ORDER BY title) AS n
                    FROM campushire_drive_projection WHERE organization_id = :org
                )
                INSERT INTO campushire_application_projection (application_id, organization_id, drive_id, student_id,
                    company_id, status, record_version, created_at, updated_at)
                SELECT gen_random_uuid(), :org, d.drive_id, s.student_id, d.company_id,
                       (ARRAY['applied', 'shortlisted', 'assessment', 'interview', 'offered', 'joined', 'rejected',
                              'withdrawn'])[1 + (s.n + k) % 8],
                       1, now() - ((s.n + k) % 360) * interval '1 day', now()
                FROM s
                CROSS JOIN generate_series(0, 19) AS k
                JOIN d ON d.n = 1 + (s.n * 7 + k * 13) % 60
                """)
                .param("org", org)
                .update();
        jdbc.sql("""
                WITH s AS (
                    SELECT student_id, department_id, row_number() OVER (ORDER BY roll_number) AS n
                    FROM campushire_student_projection WHERE organization_id = :org
                )
                INSERT INTO campushire_offer_projection (offer_id, organization_id, student_id, company_id,
                    department_id, ctc_amount, offer_type, status, record_version, created_at, updated_at)
                SELECT gen_random_uuid(), :org, s.student_id, md5('company' || (s.n % 20))::uuid, s.department_id,
                       300000 + (s.n * 7919) % 3000000, 'full_time',
                       CASE WHEN s.n % 3 = 0 THEN 'accepted' ELSE 'extended' END,
                       1, now() - (s.n % 330) * interval '1 day', now()
                FROM s
                WHERE s.n % 10 < 3
                """)
                .param("org", org)
                .update();
        jdbc.sql("ANALYZE campushire_student_projection").update();
        jdbc.sql("ANALYZE campushire_application_projection").update();
        jdbc.sql("ANALYZE campushire_offer_projection").update();
        jdbc.sql("ANALYZE campushire_drive_projection").update();
    }
}
