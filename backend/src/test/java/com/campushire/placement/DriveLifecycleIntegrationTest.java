package com.campushire.placement;

import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.IntegrationTest;
import com.campushire.support.Institute;

@IntegrationTest
class DriveLifecycleIntegrationTest {

    static final String DRIVE = "/api/v1/orgs/{org}/campushire/drives/{id}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    Institute institute;
    String cse;
    String ece;
    String batch;
    String company;
    String job;

    @BeforeEach
    void setUp() throws Exception {
        institute = Institute.create(mvc, jdbc);
        cse = institute.department("CSE");
        ece = institute.department("ECE");
        batch = institute.batch("2026");
        company = institute.company("Acme");
        job = institute.job(company, "SDE", 900_000);
    }

    @Test
    void previewCountsEligibleStudentsExplainsTheRestAndScopesCoordinators() throws Exception {
        String a = institute.student("PV001", cse, batch, "8.5", 0, null);
        String b = institute.student("PV002", cse, batch, "6.5", 0, null);
        String c = institute.student("PV003", ece, batch, "9.0", 1, null);
        String d = institute.student("PV004", ece, batch, "7.2", 0, ", \"placement_status\": \"opted_out\"");
        for (String student : new String[] { a, b, c, d }) {
            institute.awaitStudent(student);
        }
        String drive = institute.drive(company, job, "{\"minCgpa\": 7, \"maxActiveBacklogs\": 0}", Duration.ofDays(1));

        institute.owner.get(DRIVE + "/eligibility/preview", institute.org, drive)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleCount").value(1))
                .andExpect(jsonPath("$.totalConsidered").value(4))
                .andExpect(jsonPath("$.sample", hasSize(1)))
                .andExpect(jsonPath("$.sample[0].rollNumber").value("PV001"))
                .andExpect(jsonPath("$.reasons.cgpa_below_minimum").value(1))
                .andExpect(jsonPath("$.reasons.too_many_backlogs").value(1))
                .andExpect(jsonPath("$.reasons.not_participating").value(1))
                .andExpect(jsonPath("$.breakdown.byDepartment[*].departmentId", containsInAnyOrder(cse, ece)))
                .andExpect(jsonPath("$.driveCtc").value(900000));

        institute.coordinator(ece).caller().get(DRIVE + "/eligibility/preview", institute.org, drive)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalConsidered").value(2))
                .andExpect(jsonPath("$.eligibleCount").value(0))
                .andExpect(jsonPath("$.breakdown.byDepartment[*].departmentId", containsInAnyOrder(ece)));
        institute.recruiter(company).caller().get(DRIVE + "/eligibility/preview", institute.org, drive)
                .andExpect(status().isForbidden());

        institute.owner.post(DRIVE + "/eligibility/preview", "{\"eligibility\": {}}", institute.org, drive)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleCount").value(3))
                .andExpect(jsonPath("$.reasons.not_participating").value(1))
                .andExpect(jsonPath("$.totalConsidered").value(4));
        institute.owner.post(DRIVE + "/eligibility/preview", "{\"eligibility\": {\"minCgpa\": 11}}", institute.org,
                drive).andExpect(status().isBadRequest());
        institute.owner.get(DRIVE + "/eligibility/preview", institute.org, drive)
                .andExpect(jsonPath("$.eligibleCount").value(1));
    }

    @Test
    void publishValidatesRulesAndWindowAndFollowsTheLifecycle() throws Exception {
        String invalid = institute.drive(company, job, "{\"minCgpa\": 12, \"salary\": 1}", Duration.ofDays(1));
        institute.owner.post(DRIVE + "/publish", "{}", institute.org, invalid)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("eligibility.minCgpa", "eligibility.salary")));

        String past = institute.drive(company, job, null, Duration.ofSeconds(-60));
        institute.owner.post(DRIVE + "/publish", "{}", institute.org, past)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("registration_closes_at"));

        String drive = institute.drive(company, job, "{\"minCgpa\": \"7.50\"}", Duration.ofDays(1));
        institute.coordinator(cse).caller().post(DRIVE + "/publish", "{}", institute.org, drive)
                .andExpect(status().isForbidden());
        institute.owner.post(DRIVE + "/close", "{}", institute.org, drive)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("drive_status_conflict"));
        institute.owner.post(DRIVE + "/publish", "{}", institute.org, drive)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driveStatus").value("published"))
                .andExpect(jsonPath("$.notifiedStudents").value(0));
        institute.owner.get("/api/v1/orgs/{org}/modules/drive/records/{id}", institute.org, drive)
                .andExpect(jsonPath("$.data.drive_status").value("published"))
                .andExpect(jsonPath("$.data.eligibility.minCgpa").value(7.5));
        institute.owner.post(DRIVE + "/publish", "{}", institute.org, drive).andExpect(status().isConflict());
        institute.owner.postWithIfMatch(DRIVE + "/close", "\"1\"", "{}", institute.org, drive)
                .andExpect(status().isPreconditionFailed());
        institute.owner.post(DRIVE + "/close", "{}", institute.org, drive)
                .andExpect(jsonPath("$.driveStatus").value("registration_closed"));
        institute.owner.post(DRIVE + "/start", "{}", institute.org, drive)
                .andExpect(jsonPath("$.driveStatus").value("in_progress"));
        institute.owner.post(DRIVE + "/complete", "{}", institute.org, drive)
                .andExpect(jsonPath("$.driveStatus").value("completed"));
        institute.owner.post(DRIVE + "/cancel", "{}", institute.org, drive).andExpect(status().isConflict());

        String cancelled = institute.publishedDrive(company, job, null);
        institute.owner.post(DRIVE + "/cancel", "{}", institute.org, cancelled)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driveStatus").value("cancelled"));
    }

    @Test
    void publishingNotifiesEligibleLinkedStudents() throws Exception {
        String linked = institute.student("PN001", cse, batch, "8.0", 0, null);
        institute.student("PN002", cse, batch, "8.0", 0, null);
        String ineligible = institute.student("PN003", cse, batch, "5.0", 0, null);
        Institute.Account notified = institute.claim("PN001");
        institute.claim("PN003");
        institute.awaitProjected("campushire_student_projection", "student_id", linked, 2);
        institute.awaitProjected("campushire_student_projection", "student_id", ineligible, 2);
        String drive = institute.drive(company, job, "{\"minCgpa\": 6}", Duration.ofDays(1));

        institute.owner.post(DRIVE + "/publish", "{}", institute.org, drive)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifiedStudents").value(1));

        notified.caller().get("/api/v1/notifications").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("campushire.drive.published"))
                .andExpect(jsonPath("$.items[0].link").value("/app/" + institute.org + "/m/drive/" + drive));
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE type = 'campushire.drive.published'"
                + " AND organization_id = :org").param("org", UUID.fromString(institute.org)).query(Long.class)
                .single()).isEqualTo(1);

        String application = read(notified.caller().post(DRIVE + "/apply", "{}", institute.org, drive)
                .andExpect(status().isCreated()).andReturn(), "$.id");
        institute.move(application, "shortlisted");
        Awaitility.await().atMost(Institute.PROJECTION_WAIT).untilAsserted(() -> assertThat(jdbc.sql(
                "SELECT count(*) FROM notification WHERE type = 'campushire.application.stage_changed'"
                        + " AND organization_id = :org").param("org", UUID.fromString(institute.org))
                .query(Long.class).single()).isEqualTo(1));
    }
}
