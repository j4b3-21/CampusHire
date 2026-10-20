package com.campushire.placement;

import static com.bm4a.platform.support.TestApi.read;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi.Caller;
import com.campushire.support.Institute;
import com.campushire.support.Institute.Account;

@IntegrationTest
class ApplicantsIntegrationTest {

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
    String otherCompany;
    String drive;
    String[] applications;
    Caller[] students;
    String[] resumes;

    /** Four students (two per department) applied to one published drive of {@code company}. */
    @BeforeEach
    void setUp() throws Exception {
        institute = Institute.create(mvc, jdbc);
        cse = institute.department("CSE");
        ece = institute.department("ECE");
        batch = institute.batch("2026");
        company = institute.company("Initech");
        otherCompany = institute.company("Hooli");
        String job = institute.job(company, "Engineer", 1_000_000);
        drive = institute.publishedDrive(company, job, null);
        String[][] students = { { "RA001", cse, "9.2" }, { "RA002", cse, "7.4" }, { "RA003", ece, "8.1" },
                { "RA004", ece, "6.9" } };
        applications = new String[students.length];
        this.students = new Caller[students.length];
        resumes = new String[students.length];
        for (int i = 0; i < students.length; i++) {
            resumes[i] = institute.pdf(students[i][0] + ".pdf");
            String student = institute.student(students[i][0], students[i][1], batch, students[i][2], 0,
                    ", \"skills\": \"Go\", \"resume\": {\"fileId\": \"" + resumes[i] + "\"}");
            Caller caller = institute.claim(students[i][0]).caller();
            this.students[i] = caller;
            applications[i] = read(caller.post(DRIVE + "/apply", "{}", institute.org, drive)
                    .andExpect(status().isCreated()).andReturn(), "$.id");
            institute.awaitApplication(applications[i]);
            institute.awaitProjected("campushire_student_projection", "student_id", student, 2);
        }
    }

    @Test
    void recruitersSeeTheirCompanysApplicantsWithoutPersonalFields() throws Exception {
        Caller recruiter = institute.recruiter(company).caller();
        recruiter.get(DRIVE + "/applicants?sort=-cgpa", institute.org, drive)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.items[0].student.rollNumber").value("RA001"))
                .andExpect(jsonPath("$.items[0].student.resume.fileId").value(resumes[0]))
                .andExpect(jsonPath("$.items[0].student.skills").value("Go"))
                .andExpect(jsonPath("$.items[0].stage").value("applied"))
                .andExpect(jsonPath("$.items[0].eligibleNow.eligible").value(true))
                .andExpect(jsonPath("$.items[0].student.phone").doesNotExist())
                .andExpect(jsonPath("$.items[0].student.dateOfBirth").doesNotExist())
                .andExpect(jsonPath("$.items[0].student.date_of_birth").doesNotExist());
        recruiter.get(DRIVE + "/applicants?q=ra00&size=2&sort=rollNumber", institute.org, drive)
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[*].student.rollNumber", containsInAnyOrder("RA001", "RA002")));

        institute.recruiter(otherCompany).caller().get(DRIVE + "/applicants", institute.org, drive)
                .andExpect(status().isNotFound());
        institute.coordinator(ece).caller().get(DRIVE + "/applicants", institute.org, drive)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].student.rollNumber", containsInAnyOrder("RA003", "RA004")));
        students[0].get(DRIVE + "/applicants", institute.org, drive)
                .andExpect(status().isForbidden());
        recruiter.get(DRIVE + "/applicants?sort=phone", institute.org, drive)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_sort"));
    }

    @Test
    void recruitersDownloadResumesOfTheirApplicantsOnly() throws Exception {
        Caller recruiter = institute.recruiter(company).caller();
        recruiter.get("/api/v1/orgs/{org}/files/{id}", institute.org, resumes[0]).andExpect(status().isNotFound());

        recruiter.get(DRIVE + "/applicants/{application}/resume", institute.org, drive, applications[0])
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("RA001.pdf")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("%PDF-")));

        institute.recruiter(otherCompany).caller().get(DRIVE + "/applicants/{application}/resume", institute.org,
                drive, applications[0]).andExpect(status().isNotFound());
        institute.coordinator(ece).caller().get(DRIVE + "/applicants/{application}/resume", institute.org, drive,
                applications[0]).andExpect(status().isNotFound());
        recruiter.get(DRIVE + "/applicants/{application}/resume", institute.org, drive, UUID.randomUUID())
                .andExpect(status().isNotFound());
    }

    @Test
    void bulkTransitionsReportEveryApplicantAndRespectScopes() throws Exception {
        Caller recruiter = institute.recruiter(company).caller();
        String bogus = UUID.randomUUID().toString();
        recruiter.post(DRIVE + "/applicants/transition", """
                {"applicationIds": ["%s", "%s"], "rollNumbers": ["ra003", "NOPE"], "to": "shortlisted",
                 "comment": "Round 1 cleared"}
                """.formatted(applications[0], bogus), institute.org, drive)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.unknownRollNumbers", containsInAnyOrder("NOPE")))
                .andExpect(jsonPath("$.results[?(@.applicationId == '" + bogus + "')].error.code").value("not_found"))
                .andExpect(jsonPath("$.results[?(@.rollNumber == 'ra003')].ok").value(true));

        recruiter.post(DRIVE + "/applicants/transition",
                "{\"applicationIds\": [\"" + applications[0] + "\"], \"to\": \"joined\"}", institute.org, drive)
                .andExpect(jsonPath("$.results[0].error.code").value("transition_not_allowed"));
        recruiter.post(DRIVE + "/applicants/transition", "{\"to\": \"shortlisted\"}", institute.org, drive)
                .andExpect(status().isBadRequest());
        recruiter.post(DRIVE + "/applicants/transition",
                "{\"applicationIds\": [\"" + applications[1] + "\"], \"to\": \"hired\"}", institute.org, drive)
                .andExpect(status().isBadRequest());

        institute.coordinator(ece).caller().post(DRIVE + "/applicants/transition",
                "{\"applicationIds\": [\"" + applications[1] + "\", \"" + applications[3] + "\"], \"to\": \"rejected\"}",
                institute.org, drive)
                .andExpect(jsonPath("$.results[0].error.code").value("not_found"))
                .andExpect(jsonPath("$.results[1].ok").value(true));
        institute.recruiter(otherCompany).caller().post(DRIVE + "/applicants/transition",
                "{\"applicationIds\": [\"" + applications[1] + "\"], \"to\": \"rejected\"}", institute.org, drive)
                .andExpect(status().isNotFound());

        Awaitility.await().atMost(Institute.PROJECTION_WAIT).untilAsserted(() ->
                recruiter.get(DRIVE + "/applicants?stage=shortlisted", institute.org, drive)
                        .andExpect(jsonPath("$.items", hasSize(2))));
        institute.owner.get("/api/v1/orgs/{org}/modules/application/records/{id}/transitions", institute.org,
                applications[0])
                .andExpect(jsonPath("$.items[0].toState").value("shortlisted"))
                .andExpect(jsonPath("$.items[0].comment").value("Round 1 cleared"));
    }

    @Test
    void shortlistsCanBeUploadedAsCsvOrExcel() throws Exception {
        Account recruiter = institute.recruiter(company);
        String csv = "Name;Roll No.\nA;RA001\nB;ra002\n;\nC;GHOST\n";
        mvc.perform(multipart(DRIVE + "/applicants/transition/upload", institute.org, drive)
                .file(new MockMultipartFile("file", "shortlist.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .param("to", "shortlisted")
                .header(HttpHeaders.AUTHORIZATION, recruiter.identity().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(2))
                .andExpect(jsonPath("$.unknownRollNumbers", containsInAnyOrder("GHOST")));

        ByteArrayOutputStream xlsx = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Shortlist");
            sheet.createRow(0).createCell(0).setCellValue("Interview shortlist");
            sheet.createRow(2).createCell(1).setCellValue("Roll Number");
            sheet.createRow(3).createCell(1).setCellValue("RA001");
            sheet.createRow(4).createCell(1).setCellValue("RA003");
            workbook.write(xlsx);
        }
        mvc.perform(multipart(DRIVE + "/applicants/transition/upload", institute.org, drive)
                .file(new MockMultipartFile("file", "shortlist.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx.toByteArray()))
                .param("to", "interview")
                .param("comment", "Technical round")
                .header(HttpHeaders.AUTHORIZATION, recruiter.identity().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.results[?(@.rollNumber == 'RA003')].error.code")
                        .value("transition_not_allowed"));

        mvc.perform(multipart(DRIVE + "/applicants/transition/upload", institute.org, drive)
                .file(new MockMultipartFile("file", "x.csv", "text/csv", "Name\nA\n".getBytes(StandardCharsets.UTF_8)))
                .param("to", "shortlisted")
                .header(HttpHeaders.AUTHORIZATION, recruiter.identity().bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("roll_column_missing"));
    }
}
