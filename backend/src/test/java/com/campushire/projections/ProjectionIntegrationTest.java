package com.campushire.projections;

import static com.bm4a.platform.support.TestApi.etag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.modules.records.RecordEvent;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.support.IntegrationTest;
import com.campushire.projections.internal.Projector;
import com.campushire.support.Institute;

import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
class ProjectionIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Projector projector;

    @Autowired
    RecordReader reader;

    Institute institute;
    String cse;
    String batch;

    @BeforeEach
    void setUp() throws Exception {
        institute = Institute.create(mvc, jdbc);
        cse = institute.department("CSE");
        batch = institute.batch("2026");
    }

    private Map<String, Object> studentRow(String id) {
        return jdbc.sql("SELECT * FROM campushire_student_projection WHERE student_id = :id")
                .param("id", UUID.fromString(id)).query().singleRow();
    }

    @Test
    void projectsStudentsWithCanonicalValuesAndFollowsUpdatesAndDeletes() throws Exception {
        String resume = institute.pdf("cv.pdf");
        String student = institute.student("PJ001", cse, batch, "8.25", 2,
                ", \"total_backlogs\": 3, \"skills\": \"Java, SQL\", \"resume\": {\"fileId\": \"" + resume
                        + "\", \"name\": \"cv.pdf\"}");
        institute.awaitStudent(student);
        Map<String, Object> row = studentRow(student);
        assertThat(row).containsEntry("roll_number", "PJ001")
                .containsEntry("department_id", UUID.fromString(cse))
                .containsEntry("batch_id", UUID.fromString(batch))
                .containsEntry("active_backlogs", 2)
                .containsEntry("total_backlogs", 3)
                .containsEntry("placement_status", "unplaced")
                .containsEntry("resume_file_id", resume)
                .containsEntry("deleted", false);
        assertThat((BigDecimal) row.get("cgpa")).isEqualByComparingTo("8.25");
        assertThat((BigDecimal) row.get("tenth_percentage")).isEqualByComparingTo("85");

        String path = "/api/v1/orgs/{org}/modules/student/records/{id}";
        String etag = etag(institute.owner.get(path, institute.org, student).andReturn());
        institute.owner.patch(path, etag, "{\"data\": {\"cgpa\": \"9.1\"}}", institute.org, student)
                .andExpect(status().isOk());
        institute.awaitProjected("campushire_student_projection", "student_id", student, 2);
        assertThat((BigDecimal) studentRow(student).get("cgpa")).isEqualByComparingTo("9.1");

        etag = etag(institute.owner.get(path, institute.org, student).andReturn());
        institute.owner.delete(path, etag, institute.org, student).andExpect(status().is2xxSuccessful());
        Awaitility.await().atMost(Institute.PROJECTION_WAIT)
                .untilAsserted(() -> assertThat(studentRow(student)).containsEntry("deleted", true));
    }

    @Test
    void neverRollsBackToAnOlderVersion() throws Exception {
        String student = institute.student("PJ002", cse, batch, "7.0", 0, null);
        String path = "/api/v1/orgs/{org}/modules/student/records/{id}";
        String etag = etag(institute.owner.get(path, institute.org, student).andReturn());
        institute.owner.patch(path, etag, "{\"data\": {\"full_name\": \"Renamed\"}}", institute.org, student)
                .andExpect(status().isOk());
        institute.awaitProjected("campushire_student_projection", "student_id", student, 2);

        RecordView current = reader.findActive(UUID.fromString(institute.org), List.of(UUID.fromString(student)))
                .getFirst();
        ObjectNode stale = current.data().deepCopy().put("full_name", "Stale name");
        projector.apply("student", new RecordView(current.id(), current.organizationId(), current.moduleId(), stale,
                1, current.definitionVersion(), current.createdAt(), current.createdBy(), current.createdAt(),
                current.createdBy(), null, null, current.ownerUserId()), job -> null);
        projector.handle(new RecordEvent.Created(current.organizationId(), current.moduleId(), current.id(), 1,
                current.createdBy()));

        assertThat(studentRow(student)).containsEntry("full_name", "Renamed").containsEntry("record_version", 2L);

        etag = etag(institute.owner.get(path, institute.org, student).andReturn());
        institute.owner.delete(path, etag, institute.org, student).andExpect(status().is2xxSuccessful());
        Awaitility.await().atMost(Institute.PROJECTION_WAIT)
                .untilAsserted(() -> assertThat(studentRow(student)).containsEntry("deleted", true));
        projector.handle(new RecordEvent.Updated(current.organizationId(), current.moduleId(), current.id(), 2,
                current.createdBy(), List.of("full_name")));
        assertThat(studentRow(student)).containsEntry("deleted", true).containsEntry("record_version", 3L);
    }

    @Test
    void projectsDrivesWithTheirJobCtcAndApplicationsAndRebuilds() throws Exception {
        String company = institute.company("Acme");
        String job = institute.job(company, "SDE", 1_500_000);
        String drive = institute.drive(company, job, "{\"minCgpa\": 7}", Duration.ofDays(1));
        institute.awaitProjected("campushire_drive_projection", "drive_id", drive, 1);
        assertThat((BigDecimal) jdbc.sql("SELECT job_ctc FROM campushire_drive_projection WHERE drive_id = :id")
                .param("id", UUID.fromString(drive)).query(BigDecimal.class).single()).isEqualByComparingTo("1500000");

        String jobPath = "/api/v1/orgs/{org}/modules/job/records/{id}";
        String etag = etag(institute.owner.get(jobPath, institute.org, job).andReturn());
        institute.owner.patch(jobPath, etag, "{\"data\": {\"ctc\": {\"amount\": \"1800000\", \"currency\": \"INR\"}}}",
                institute.org, job).andExpect(status().isOk());
        Awaitility.await().atMost(Institute.PROJECTION_WAIT).untilAsserted(() -> assertThat(jdbc.sql(
                "SELECT job_ctc FROM campushire_drive_projection WHERE drive_id = :id")
                .param("id", UUID.fromString(drive)).query(BigDecimal.class).single())
                .isEqualByComparingTo("1800000"));

        String student = institute.student("PJ003", cse, batch, "8.0", 0, null);
        String application = institute.record("application", """
                {"drive": "%s", "student": "%s", "company": "%s", "department": "%s"}
                """.formatted(drive, student, company, cse));
        institute.awaitApplication(application);

        jdbc.sql("DELETE FROM campushire_application_projection WHERE application_id = :id")
                .param("id", UUID.fromString(application)).update();
        jdbc.sql("UPDATE campushire_student_projection SET full_name = 'garbage', record_version = 0"
                + " WHERE student_id = :id").param("id", UUID.fromString(student)).update();
        UUID ghost = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO campushire_drive_projection (drive_id, organization_id, title, status, record_version,
                    updated_at) VALUES (:id, :org, 'ghost', 'published', 1, now())
                """).param("id", ghost).param("org", UUID.fromString(institute.org)).update();

        institute.owner.post("/api/v1/orgs/{org}/campushire/projections/rebuild", "{}", institute.org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projected.student").value(1))
                .andExpect(jsonPath("$.projected.drive").value(1))
                .andExpect(jsonPath("$.projected.application").value(1))
                .andExpect(jsonPath("$.removed.drive").value(1))
                .andExpect(jsonPath("$.statsRefreshed").value(true));

        assertThat(studentRow(student)).containsEntry("full_name", "Student PJ003");
        assertThat(jdbc.sql("SELECT count(*) FROM campushire_application_projection WHERE application_id = :id")
                .param("id", UUID.fromString(application)).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT deleted FROM campushire_drive_projection WHERE drive_id = :id")
                .param("id", ghost).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT total_students FROM campushire_placement_stats WHERE organization_id = :org")
                .param("org", UUID.fromString(institute.org)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void rebuildIsForAdminsOnly() throws Exception {
        var coordinator = institute.coordinator(cse);
        coordinator.caller().post("/api/v1/orgs/{org}/campushire/projections/rebuild", "{}", institute.org)
                .andExpect(status().isForbidden());
    }
}
