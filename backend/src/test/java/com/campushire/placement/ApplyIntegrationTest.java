package com.campushire.placement;

import static com.bm4a.platform.support.TestApi.etag;
import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi.Caller;
import com.campushire.support.Institute;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class ApplyIntegrationTest {

    static final String DRIVE = "/api/v1/orgs/{org}/campushire/drives/{id}";
    static final String STUDENT_DRIVES = "/api/v1/orgs/{org}/campushire/student/drives";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    Institute institute;
    String cse;
    String batch;
    String company;
    String job;

    @BeforeEach
    void setUp() throws Exception {
        institute = Institute.create(mvc, jdbc);
        cse = institute.department("CSE");
        batch = institute.batch("2026");
        company = institute.company("Globex");
        job = institute.job(company, "Analyst", 700_000);
    }

    @Test
    void studentsSeeVisibleDrivesWithTheirEligibilityAndReasons() throws Exception {
        String student = institute.student("AP001", cse, batch, "6.8", 0, null);
        Caller caller = institute.claim("AP001").caller();
        institute.awaitStudent(student);
        String strict = institute.publishedDrive(company, job, "{\"minCgpa\": 7.0}");
        String open = institute.publishedDrive(company, job, null);
        String draft = institute.drive(company, job, null, Duration.ofDays(1));
        String upcoming = institute.drive(company, job, null, Duration.ofDays(3));
        String opensLater = Instant.now().plus(Duration.ofDays(1)).toString();
        String etag = etag(institute.owner.get("/api/v1/orgs/{org}/modules/drive/records/{id}", institute.org,
                upcoming).andReturn());
        institute.owner.patch("/api/v1/orgs/{org}/modules/drive/records/{id}", etag,
                "{\"data\": {\"registration_opens_at\": \"" + opensLater + "\"}}", institute.org, upcoming)
                .andExpect(status().isOk());
        institute.owner.post(DRIVE + "/publish", "{}", institute.org, upcoming).andExpect(status().isOk());
        institute.awaitProjected("campushire_drive_projection", "drive_id", strict, 2);
        institute.awaitProjected("campushire_drive_projection", "drive_id", open, 2);
        institute.awaitProjected("campushire_drive_projection", "drive_id", draft, 1);
        institute.awaitProjected("campushire_drive_projection", "drive_id", upcoming, 3);

        String openDrives = caller.get(STUDENT_DRIVES + "?filter=open", institute.org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> strictItem = item(openDrives, strict);
        assertThat(JsonPath.<Boolean>read(strictItem, "$.eligibility.eligible")).isFalse();
        assertThat(JsonPath.<String>read(strictItem, "$.eligibility.reasons[0].code")).isEqualTo("cgpa_below_minimum");
        assertThat(JsonPath.<String>read(strictItem, "$.eligibility.reasons[0].message"))
                .isEqualTo("CGPA 6.8 is below the required 7.0");
        Map<String, Object> openItem = item(openDrives, open);
        assertThat(JsonPath.<Boolean>read(openItem, "$.eligibility.eligible")).isTrue();
        assertThat(JsonPath.<Number>read(openItem, "$.job.ctc").longValue()).isEqualTo(700_000L);
        assertThat(JsonPath.<String>read(openItem, "$.company.id")).isEqualTo(company);
        assertThat(JsonPath.<Boolean>read(openItem, "$.registrationOpen")).isTrue();
        assertThat(openItem.get("application")).isNull();
        caller.get(STUDENT_DRIVES + "?filter=upcoming", institute.org)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(upcoming));
        caller.get(STUDENT_DRIVES + "?filter=all", institute.org).andExpect(jsonPath("$.totalElements").value(3));
        caller.post(DRIVE + "/apply", "{}", institute.org, draft).andExpect(status().isNotFound());
        caller.post(DRIVE + "/apply", "{}", institute.org, strict)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_eligible"))
                .andExpect(jsonPath("$.errors[0].code").value("cgpa_below_minimum"));
        caller.post(DRIVE + "/apply", "{}", institute.org, upcoming)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("registration_not_open"));

        String application = read(caller.post(DRIVE + "/apply", "{}", institute.org, open)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage").value("applied"))
                .andReturn(), "$.id");
        Map<String, Object> applied = item(caller.get(STUDENT_DRIVES + "?filter=open", institute.org).andReturn()
                .getResponse().getContentAsString(), open);
        assertThat(JsonPath.<String>read(applied, "$.application.id")).isEqualTo(application);
        assertThat(JsonPath.<String>read(applied, "$.application.stage")).isEqualTo("applied");
        institute.owner.get(STUDENT_DRIVES, institute.org).andExpect(status().isForbidden());
    }

    private static Map<String, Object> item(String page, String id) {
        List<Map<String, Object>> items = JsonPath.read(page, "$.items[?(@.id == '" + id + "')]");
        assertThat(items).as("drive %s in %s", id, page).hasSize(1);
        return items.getFirst();
    }

    @Test
    void parallelApplicationsCreateExactlyOneRegistrationAndApplication() throws Exception {
        institute.student("AP002", cse, batch, "8.0", 0, null);
        Caller caller = institute.claim("AP002").caller();
        String drive = institute.publishedDrive(company, job, "{\"minCgpa\": 7}");

        int calls = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < calls; i++) {
                Callable<MockHttpServletResponse> apply = () -> {
                    start.await();
                    return caller.post(DRIVE + "/apply", "{}", institute.org, drive).andReturn().getResponse();
                };
                futures.add(pool.submit(apply));
            }
            start.countDown();
            int created = 0;
            Set<String> ids = new HashSet<>();
            for (Future<MockHttpServletResponse> future : futures) {
                MockHttpServletResponse response = future.get();
                assertThat(response.getStatus()).as(response.getContentAsString()).isIn(200, 201);
                created += response.getStatus() == 201 ? 1 : 0;
                ids.add(JsonPath.read(response.getContentAsString(), "$.id"));
            }
            assertThat(created).isEqualTo(1);
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM campushire_drive_registration WHERE drive_id = :drive")
                .param("drive", UUID.fromString(drive)).query(Long.class).single()).isEqualTo(1);
        institute.owner.get("/api/v1/orgs/{org}/modules/application/records?filter[drive]={drive}", institute.org,
                drive).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void registrationWindowIsCheckedAndWithdrawalFollowsTheWorkflow() throws Exception {
        institute.student("AP003", cse, batch, "8.0", 0, null);
        Caller caller = institute.claim("AP003").caller();
        String closed = institute.publishedDrive(company, job, null);
        String etag = etag(institute.owner.get("/api/v1/orgs/{org}/modules/drive/records/{id}", institute.org,
                closed).andReturn());
        institute.owner.patch("/api/v1/orgs/{org}/modules/drive/records/{id}", etag,
                "{\"data\": {\"registration_closes_at\": \"" + Instant.now().minusSeconds(5) + "\"}}", institute.org,
                closed).andExpect(status().isOk());
        caller.post(DRIVE + "/apply", "{}", institute.org, closed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("registration_closed"));

        String first = institute.publishedDrive(company, job, null);
        caller.post(DRIVE + "/withdraw", "{}", institute.org, first).andExpect(status().isNotFound());
        caller.post(DRIVE + "/apply", "{}", institute.org, first).andExpect(status().isCreated());
        caller.post(DRIVE + "/withdraw", "{\"comment\": \"Going for higher studies\"}", institute.org, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("withdrawn"));
        caller.post(DRIVE + "/withdraw", "{}", institute.org, first)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("transition_not_allowed"));
        caller.post(DRIVE + "/apply", "{}", institute.org, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("withdrawn"));

        String second = institute.publishedDrive(company, job, null);
        String application = read(caller.post(DRIVE + "/apply", "{}", institute.org, second)
                .andExpect(status().isCreated()).andReturn(), "$.id");
        institute.move(application, "shortlisted");
        institute.move(application, "interview");
        caller.post(DRIVE + "/withdraw", "{}", institute.org, second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("transition_not_allowed"));
    }
}
