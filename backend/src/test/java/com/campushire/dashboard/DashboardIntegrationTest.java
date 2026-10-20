package com.campushire.dashboard;

import static com.bm4a.platform.support.TestApi.read;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi.Caller;
import com.campushire.projections.internal.PlacementStatsRefresher;
import com.campushire.support.Institute;

@IntegrationTest
class DashboardIntegrationTest {

    static final String DASHBOARD = "/api/v1/orgs/{org}/campushire/dashboard";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlacementStatsRefresher stats;

    @Test
    void everyPersonaGetsItsOwnDashboard() throws Exception {
        Institute institute = Institute.create(mvc, jdbc);
        String cse = institute.department("CSE");
        String ece = institute.department("ECE");
        String batch = institute.batch("2026");
        String company = institute.company("Wayne");
        String job = institute.job(company, "Engineer", 600_000);
        String drive = institute.publishedDrive(company, job, null);
        String placed = institute.student("DB001", cse, batch, "8.0", 0, null);
        String seeking = institute.student("DB002", ece, batch, "7.0", 0, null);
        Caller placedCaller = institute.claim("DB001").caller();
        Caller seekingCaller = institute.claim("DB002").caller();
        String placedApplication = read(placedCaller.post("/api/v1/orgs/{org}/campushire/drives/{id}/apply", "{}",
                institute.org, drive).andExpect(status().isCreated()).andReturn(), "$.id");
        String seekingApplication = read(seekingCaller.post("/api/v1/orgs/{org}/campushire/drives/{id}/apply", "{}",
                institute.org, drive).andExpect(status().isCreated()).andReturn(), "$.id");
        institute.move(placedApplication, "shortlisted");
        String offer = read(institute.owner.post("/api/v1/orgs/{org}/campushire/offers",
                "{\"applicationId\": \"" + placedApplication + "\", \"ctc\": 600000}", institute.org)
                .andExpect(status().isCreated()).andReturn(), "$.id");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        institute.record("follow_up", "{\"title\": \"Call HR\", \"company\": \"" + company + "\", \"due_date\": \""
                + today + "\"}");
        institute.record("follow_up", "{\"title\": \"Send brochure\", \"company\": \"" + company
                + "\", \"due_date\": \"" + today.minusDays(3) + "\"}");
        institute.record("follow_up", "{\"title\": \"Visit\", \"company\": \"" + company + "\", \"due_date\": \""
                + today.plusDays(3) + "\"}");

        institute.awaitProjected("campushire_application_projection", "application_id", placedApplication, 3);
        institute.awaitApplication(seekingApplication);
        institute.awaitProjected("campushire_offer_projection", "offer_id", offer, 1);
        institute.awaitProjected("campushire_student_projection", "student_id", placed, 3);
        institute.awaitProjected("campushire_student_projection", "student_id", seeking, 2);
        stats.refresh();

        institute.owner.get(DASHBOARD, institute.org).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("tpo"))
                .andExpect(jsonPath("$.counts.students").value(2))
                .andExpect(jsonPath("$.counts.openDrives").value(1))
                .andExpect(jsonPath("$.counts.applicationsThisWeek").value(2))
                .andExpect(jsonPath("$.counts.activeOffers").value(1))
                .andExpect(jsonPath("$.companiesByStage.lead").value(1))
                .andExpect(jsonPath("$.companiesByStage.visited").value(0))
                .andExpect(jsonPath("$.placement.participatingStudents").value(2))
                .andExpect(jsonPath("$.placement.placedStudents").value(1))
                .andExpect(jsonPath("$.placement.placementPercentage").value(50.0))
                .andExpect(jsonPath("$.upcomingDrives[0].id").value(drive))
                .andExpect(jsonPath("$.upcomingDrives[0].companyName").isNotEmpty())
                .andExpect(jsonPath("$.followUps.dueToday").value(1))
                .andExpect(jsonPath("$.followUps.overdue").value(1))
                .andExpect(jsonPath("$.followUps.items[*].title", containsInAnyOrder("Call HR", "Send brochure")));

        institute.coordinator(ece).caller().get(DASHBOARD, institute.org).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("coordinator"))
                .andExpect(jsonPath("$.departmentIds[0]").value(ece))
                .andExpect(jsonPath("$.counts.students").value(1))
                .andExpect(jsonPath("$.counts.applicationsThisWeek").value(1))
                .andExpect(jsonPath("$.counts.activeOffers").value(0))
                .andExpect(jsonPath("$.placement.placedStudents").value(0))
                .andExpect(jsonPath("$.followUps").doesNotExist());

        seekingCaller.get(DASHBOARD, institute.org).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("student"))
                .andExpect(jsonPath("$.student.rollNumber").value("DB002"))
                .andExpect(jsonPath("$.profile.completeness").value(25))
                .andExpect(jsonPath("$.profile.missingFields", hasItem("resume")))
                .andExpect(jsonPath("$.eligibleOpenDrives").value(1))
                .andExpect(jsonPath("$.applicationsByStage.applied").value(1));
        placedCaller.get(DASHBOARD, institute.org)
                .andExpect(jsonPath("$.eligibleOpenDrives").value(0))
                .andExpect(jsonPath("$.applicationsByStage.offered").value(1))
                .andExpect(jsonPath("$.offers[0].id").value(offer))
                .andExpect(jsonPath("$.offers[0].offerStatus").value("extended"));

        institute.recruiter(company).caller().get(DASHBOARD, institute.org).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("recruiter"))
                .andExpect(jsonPath("$.drives[0].id").value(drive))
                .andExpect(jsonPath("$.drives[0].applicants").value(2))
                .andExpect(jsonPath("$.drives[0].applicantsByStage.offered").value(1))
                .andExpect(jsonPath("$.drives[0].applicantsByStage.applied").value(1));
    }
}
