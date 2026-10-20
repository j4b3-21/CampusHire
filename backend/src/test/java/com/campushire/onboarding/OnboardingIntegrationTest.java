package com.campushire.onboarding;

import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi.Caller;
import com.bm4a.platform.support.TestJwts;
import com.campushire.support.Institute;

@IntegrationTest
class OnboardingIntegrationTest {

    static final String CLAIM = "/api/v1/campushire/onboarding/claim";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    Institute institute;
    String cse;
    String ece;
    String batch;

    @BeforeEach
    void setUp() throws Exception {
        institute = Institute.create(mvc, jdbc);
        cse = institute.department("CSE");
        ece = institute.department("ECE");
        batch = institute.batch("B.Tech 2022-2026");
    }

    private String claimBody() {
        return "{\"organizationId\": \"" + institute.org + "\"}";
    }

    @Test
    void studentFindsTheInstituteAndClaimsTheirRecord() throws Exception {
        String student = institute.student("CS22B001", cse, batch, "8.1", 0, null);
        institute.awaitStudent(student);
        Caller caller = institute.api.as(TestJwts.newUser().email("CS22B001@" + institute.domain));

        caller.get("/api/v1/campushire/onboarding").andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(institute.domain))
                .andExpect(jsonPath("$.organizations", hasSize(1)))
                .andExpect(jsonPath("$.organizations[0].organizationId").value(institute.org))
                .andExpect(jsonPath("$.organizations[0].studentRecordFound").value(true))
                .andExpect(jsonPath("$.organizations[0].member").value(false));

        caller.post(CLAIM, claimBody()).andExpect(status().isOk())
                .andExpect(jsonPath("$.studentId").value(student))
                .andExpect(jsonPath("$.roleKey").value("student"))
                .andExpect(jsonPath("$.attributes.student").value(student))
                .andExpect(jsonPath("$.attributes.department").value(cse))
                .andExpect(jsonPath("$.attributes.batch").value(batch))
                .andExpect(jsonPath("$.alreadyLinked").value(false));

        caller.get("/api/v1/orgs/{org}/modules/student/records/{id}", institute.org, student)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").value(caller.id()));
        caller.get("/api/v1/orgs/{org}/campushire/me", institute.org).andExpect(status().isOk())
                .andExpect(jsonPath("$.role.key").value("student"))
                .andExpect(jsonPath("$.student.id").value(student))
                .andExpect(jsonPath("$.student.rollNumber").value("CS22B001"))
                .andExpect(jsonPath("$.departmentIds[0]").value(cse))
                .andExpect(jsonPath("$.onboarding.status").value("complete"))
                .andExpect(jsonPath("$.capabilities.student.readScope").value("OWN"));
        caller.post(CLAIM, claimBody()).andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyLinked").value(true));
        caller.get("/api/v1/campushire/onboarding")
                .andExpect(jsonPath("$.organizations[0].member").value(true))
                .andExpect(jsonPath("$.organizations[0].linked").value(true));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE organization_id = :org"
                + " AND action = 'campushire.student.claimed'").param("org", java.util.UUID.fromString(institute.org))
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void refusesOtherDomainsUnverifiedEmailsAndUnknownStudents() throws Exception {
        institute.student("CS22B002", cse, batch, "7.0", 0, null);

        institute.api.as(TestJwts.newUser().email("cs22b002@gmail.com")).post(CLAIM, claimBody())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("domain_not_allowed"));
        institute.api.as(TestJwts.newUser().email(institute.email("CS22B002")).emailVerified(false))
                .post(CLAIM, claimBody())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_not_verified"));
        institute.api.as(TestJwts.newUser().email(institute.email("nobody"))).post(CLAIM, claimBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("student_not_found"));
        institute.api.as(TestJwts.newUser().email("x@gmail.com")).get("/api/v1/campushire/onboarding")
                .andExpect(jsonPath("$.organizations", hasSize(0)));
    }

    @Test
    void concurrentClaimsLinkOnceAndAnotherAccountCannotTakeTheRecord() throws Exception {
        String student = institute.student("CS22B003", cse, batch, "7.5", 0, null);
        Caller caller = institute.api.as(TestJwts.newUser().email(institute.email("CS22B003")));
        caller.id();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<Integer> claim = () -> caller.post(CLAIM, claimBody()).andReturn().getResponse()
                        .getStatus();
                results.add(pool.submit(claim));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE organization_id = :org"
                + " AND action = 'campushire.student.claimed'").param("org", java.util.UUID.fromString(institute.org))
                .query(Long.class).single()).isEqualTo(1);

        institute.api.as(TestJwts.newUser().email(institute.email("CS22B003"))).post(CLAIM, claimBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("student_already_claimed"));
        String ownerId = read(institute.owner.get("/api/v1/orgs/{org}/modules/student/records/{id}", institute.org,
                student).andReturn(), "$.ownerUserId");
        assertThat(ownerId).isEqualTo(caller.id());
    }

    @Test
    void membershipAttributesFollowTheStudentsDepartment() throws Exception {
        String student = institute.student("CS22B004", cse, batch, "7.5", 0, null);
        Caller caller = institute.claim("CS22B004").caller();
        String etag = com.bm4a.platform.support.TestApi.etag(institute.owner.get(
                "/api/v1/orgs/{org}/modules/student/records/{id}", institute.org, student).andReturn());

        institute.owner.patch("/api/v1/orgs/{org}/modules/student/records/{id}", etag,
                "{\"data\": {\"department\": \"" + ece + "\"}}", institute.org, student).andExpect(status().isOk());

        Awaitility.await().atMost(Institute.PROJECTION_WAIT).untilAsserted(() ->
                caller.get("/api/v1/orgs/{org}/me/access", institute.org)
                        .andExpect(jsonPath("$.attributes.department").value(ece))
                        .andExpect(jsonPath("$.attributes.student").value(student)));
    }

    @Test
    void staffMembersCannotClaimAStudentRole() throws Exception {
        institute.student("CS22B005", cse, batch, "7.5", 0, null);
        TestJwts.Builder identity = TestJwts.newUser().email(institute.email("CS22B005"));
        Caller caller = institute.api.as(identity);
        com.bm4a.platform.support.Fixtures.addMemberWithRole(institute.owner, institute.org, caller, "coordinator",
                "{\"department\": \"" + cse + "\"}");

        caller.post(CLAIM, claimBody()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_member"));
    }
}
