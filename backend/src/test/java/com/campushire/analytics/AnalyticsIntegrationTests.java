package com.campushire.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * Every metric against a small data set whose expected values are counted by hand:
 * <pre>
 * Computer Science: s1 (B1), s2 (B1), s3 (B1), s4 (B1, opted out), s5 (B2, higher studies), s9 (deleted)
 * Electronics:      s6 (B1), s7 (B1), s8 (B2), s10 (B2)
 * Counted offers:   s1 Acme 12 L accepted, s2 Acme 8 L extended, s2 Beta 10 L accepted, s6 Beta 4.5 L extended,
 *                   s7 Acme 26 L accepted; not counted: s3 declined, s8 revoked, s9 (deleted student)
 * </pre>
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsIntegrationTests {

    private static final String BASE = "/api/v1/orgs/{org}/campushire/analytics";
    private static final String REPORT = "/api/v1/orgs/{org}/campushire/reports/placement";

    @Autowired
    MockMvc mvc;
    @Autowired
    PackInstaller packs;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;

    Caller owner;
    Caller tpo;
    Caller coordinator;
    Caller student;
    Caller recruiter;
    String org;
    String cse;
    String ece;
    String batch1;
    String acme;
    String beta;
    UUID driveA;
    UUID driveB;

    @BeforeAll
    void seed() throws Exception {
        TestApi api = new TestApi(mvc);
        owner = api.newUser();
        AnalyticsFixtures data = AnalyticsFixtures.institute(owner, packs, json, jdbc);
        org = data.org().toString();

        cse = Fixtures.createRecord(owner, org, "department", "{\"name\":\"Computer Science\",\"code\":\"CSE\"}");
        ece = Fixtures.createRecord(owner, org, "department", "{\"name\":\"Electronics\",\"code\":\"ECE\"}");
        batch1 = Fixtures.createRecord(owner, org, "batch", "{\"name\":\"B.Tech 2026\",\"graduation_year\":2026}");
        String batch2 = Fixtures.createRecord(owner, org, "batch",
                "{\"name\":\"B.Tech 2027\",\"graduation_year\":2027}");
        acme = Fixtures.createRecord(owner, org, "company", "{\"name\":\"Acme Systems\"}");
        beta = Fixtures.createRecord(owner, org, "company", "{\"name\":\"Beta Labs\"}");
        UUID gamma = UUID.fromString(Fixtures.createRecord(owner, org, "company", "{\"name\":\"Gamma Works\"}"));

        UUID d1 = UUID.fromString(cse);
        UUID d2 = UUID.fromString(ece);
        UUID b1 = UUID.fromString(batch1);
        UUID b2 = UUID.fromString(batch2);
        UUID a = UUID.fromString(acme);
        UUID b = UUID.fromString(beta);

        UUID s1 = data.student("CS001", "Asha Rao", d1, b1, "placed", false);
        UUID s2 = data.student("CS002", "Bala K", d1, b1, "placed", false);
        UUID s3 = data.student("CS003", "Chitra N", d1, b1, "unplaced", false);
        data.student("CS004", "Dev P", d1, b1, "opted_out", false);
        UUID s5 = data.student("CS005", "Esha M", d1, b2, "higher_studies", false);
        UUID s9 = data.student("CS009", "Gone Student", d1, b1, "placed", true);
        UUID s6 = data.student("EC006", "Farhan S", d2, b1, "placed", false);
        UUID s7 = data.student("EC007", "Gita V", d2, b1, "placed", false);
        UUID s8 = data.student("EC008", "=HYPERLINK(\"http://evil.test\")", d2, b2, "unplaced", false);
        UUID s10 = data.student("EC010", "Hari T", d2, b2, "unplaced", false);

        driveA = data.drive("Acme SDE", a, "completed");
        data.drive("Acme draft", a, "draft");
        driveB = data.drive("Beta Analyst", b, "in_progress");
        data.drive("Beta cancelled", b, "cancelled");
        data.drive("Gamma Graduate", gamma, "published");

        data.application(driveA, s1, a, "joined", "2026-03-01T06:30:00Z", false);
        data.application(driveA, s2, a, "offered", "2026-03-02T06:30:00Z", false);
        data.application(driveA, s3, a, "rejected", "2026-03-03T06:30:00Z", false);
        data.application(driveA, s5, a, "withdrawn", "2026-03-04T06:30:00Z", false);
        data.application(driveA, s6, a, "interview", "2026-03-05T06:30:00Z", false);
        data.application(driveA, s7, a, "offered", "2026-04-01T06:30:00Z", false);
        data.application(driveA, s8, a, "applied", "2026-04-02T06:30:00Z", false);
        data.application(driveA, s9, a, "offered", "2026-03-06T06:30:00Z", false);
        data.application(driveB, s2, b, "offered", "2026-05-01T06:30:00Z", false);
        data.application(driveB, s6, b, "offered", "2026-05-02T06:30:00Z", false);
        data.application(driveB, s3, b, "assessment", "2026-05-03T06:30:00Z", false);
        data.application(driveB, s10, b, "shortlisted", "2026-05-04T06:30:00Z", false);
        data.application(driveB, s1, b, "applied", "2026-05-05T06:30:00Z", true);

        data.offer(s1, a, driveA, 1_200_000, "accepted", "2026-03-10T06:30:00Z");
        data.offer(s2, a, driveA, 800_000, "extended", "2026-04-05T06:30:00Z");
        data.offer(s2, b, driveB, 1_000_000, "accepted", "2026-05-20T06:30:00Z");
        data.offer(s6, b, driveB, 450_000, "extended", "2026-05-21T06:30:00Z");
        data.offer(s7, a, driveA, 2_600_000, "accepted", "2026-06-01T06:30:00Z");
        data.offer(s3, b, driveB, 700_000, "declined", "2026-06-02T06:30:00Z");
        data.offer(s8, a, driveA, 300_000, "revoked", "2026-06-03T06:30:00Z");
        data.offer(s9, a, driveA, 5_000_000, "accepted", "2026-06-04T06:30:00Z");

        tpo = api.newUser();
        Fixtures.addMemberWithRole(owner, org, tpo, "tpo", "null");
        coordinator = api.newUser();
        Fixtures.addMemberWithRole(owner, org, coordinator, "coordinator", "{\"department\":\"" + cse + "\"}");
        student = api.newUser();
        Fixtures.addMemberWithRole(owner, org, student, "student", "{\"student\":\"" + s1 + "\"}");
        recruiter = api.newUser();
        Fixtures.addMemberWithRole(owner, org, recruiter, "recruiter", "{\"company\":\"" + acme + "\"}");
    }

    @Test
    void overviewCountsEveryMetric() throws Exception {
        for (Caller staff : List.of(owner, tpo)) {
            staff.get(BASE + "/overview", org)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalStudents").value(9))
                    .andExpect(jsonPath("$.participatingStudents").value(7))
                    .andExpect(jsonPath("$.placedStudents").value(4))
                    .andExpect(jsonPath("$.placementRate").value(0.5714))
                    .andExpect(jsonPath("$.offers").value(5))
                    .andExpect(jsonPath("$.acceptedOffers").value(3))
                    .andExpect(jsonPath("$.highestCtc").value(2600000.0))
                    .andExpect(jsonPath("$.averageCtc").value(1210000.0))
                    .andExpect(jsonPath("$.medianCtc").value(1000000.0))
                    .andExpect(jsonPath("$.activeDrives").value(2))
                    .andExpect(jsonPath("$.applications").value(11))
                    .andExpect(jsonPath("$.companiesVisited").value(2))
                    .andExpect(jsonPath("$.refreshedAt").isString());
        }
    }

    @Test
    void overviewAppliesBatchAndDateFilters() throws Exception {
        owner.get(BASE + "/overview?batchId={batch}", org, batch1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStudents").value(6))
                .andExpect(jsonPath("$.participatingStudents").value(5))
                .andExpect(jsonPath("$.placedStudents").value(4))
                .andExpect(jsonPath("$.placementRate").value(0.8))
                .andExpect(jsonPath("$.offers").value(5));

        owner.get(BASE + "/overview?from=2026-05-01&to=2026-05-31", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStudents").value(9))
                .andExpect(jsonPath("$.offers").value(2))
                .andExpect(jsonPath("$.placedStudents").value(2))
                .andExpect(jsonPath("$.highestCtc").value(1000000.0))
                .andExpect(jsonPath("$.medianCtc").value(725000.0))
                .andExpect(jsonPath("$.applications").value(4));

        owner.get(BASE + "/overview?from=2026-06-01&to=2026-05-01", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void departmentsBreakDownWithNames() throws Exception {
        owner.get(BASE + "/departments", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments", hasSize(2)))
                .andExpect(jsonPath("$.departments[0].departmentId").value(cse))
                .andExpect(jsonPath("$.departments[0].departmentName").value("Computer Science"))
                .andExpect(jsonPath("$.departments[0].totalStudents").value(5))
                .andExpect(jsonPath("$.departments[0].participatingStudents").value(3))
                .andExpect(jsonPath("$.departments[0].placedStudents").value(2))
                .andExpect(jsonPath("$.departments[0].placementRate").value(0.6667))
                .andExpect(jsonPath("$.departments[0].offers").value(3))
                .andExpect(jsonPath("$.departments[0].highestCtc").value(1200000.0))
                .andExpect(jsonPath("$.departments[0].averageCtc").value(1000000.0))
                .andExpect(jsonPath("$.departments[0].medianCtc").value(1000000.0))
                .andExpect(jsonPath("$.departments[1].departmentName").value("Electronics"))
                .andExpect(jsonPath("$.departments[1].totalStudents").value(4))
                .andExpect(jsonPath("$.departments[1].participatingStudents").value(4))
                .andExpect(jsonPath("$.departments[1].placedStudents").value(2))
                .andExpect(jsonPath("$.departments[1].placementRate").value(0.5))
                .andExpect(jsonPath("$.departments[1].offers").value(2))
                .andExpect(jsonPath("$.departments[1].highestCtc").value(2600000.0))
                .andExpect(jsonPath("$.departments[1].averageCtc").value(1525000.0))
                .andExpect(jsonPath("$.departments[1].medianCtc").value(1525000.0));
    }

    @Test
    void companiesRankedByOffers() throws Exception {
        owner.get(BASE + "/companies", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies", hasSize(2)))
                .andExpect(jsonPath("$.companies[0].companyId").value(acme))
                .andExpect(jsonPath("$.companies[0].companyName").value("Acme Systems"))
                .andExpect(jsonPath("$.companies[0].offers").value(3))
                .andExpect(jsonPath("$.companies[0].highestCtc").value(2600000.0))
                .andExpect(jsonPath("$.companies[0].averageCtc").value(1533333.33))
                .andExpect(jsonPath("$.companies[0].drives").value(1))
                .andExpect(jsonPath("$.companies[1].companyName").value("Beta Labs"))
                .andExpect(jsonPath("$.companies[1].offers").value(2))
                .andExpect(jsonPath("$.companies[1].highestCtc").value(1000000.0))
                .andExpect(jsonPath("$.companies[1].averageCtc").value(725000.0))
                .andExpect(jsonPath("$.companies[1].drives").value(1));

        owner.get(BASE + "/companies?limit=1", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies", hasSize(1)));
        owner.get(BASE + "/companies?limit=0", org).andExpect(status().isBadRequest());
    }

    @Test
    void ctcDistributionBucketsInLpa() throws Exception {
        owner.get(BASE + "/ctc-distribution", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("LPA"))
                .andExpect(jsonPath("$.offers").value(5))
                .andExpect(jsonPath("$.buckets[*].label").value(
                        contains("0-3", "3-6", "6-10", "10-15", "15-25", "25+")))
                .andExpect(jsonPath("$.buckets[*].count").value(contains(0, 1, 1, 2, 0, 1)))
                .andExpect(jsonPath("$.buckets[5].minLpa").value(25))
                .andExpect(jsonPath("$.buckets[5].maxLpa").doesNotExist());
    }

    @Test
    void trendCoversTwelveMonths() throws Exception {
        owner.get(BASE + "/trend?interval=month&to=2026-06-30", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("month"))
                .andExpect(jsonPath("$.points", hasSize(12)))
                .andExpect(jsonPath("$.points[0].period").value("2025-07"))
                .andExpect(jsonPath("$.points[8].period").value("2026-03"))
                .andExpect(jsonPath("$.points[8].offers").value(1))
                .andExpect(jsonPath("$.points[8].applications").value(5))
                .andExpect(jsonPath("$.points[9].offers").value(1))
                .andExpect(jsonPath("$.points[9].applications").value(2))
                .andExpect(jsonPath("$.points[10].offers").value(2))
                .andExpect(jsonPath("$.points[10].applications").value(4))
                .andExpect(jsonPath("$.points[11].period").value("2026-06"))
                .andExpect(jsonPath("$.points[11].offers").value(1))
                .andExpect(jsonPath("$.points[11].applications").value(0));

        String currentMonth = YearMonth.now(ZoneId.of("Asia/Kolkata")).toString();
        owner.get(BASE + "/trend", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(12)))
                .andExpect(jsonPath("$.points[11].period").value(currentMonth));
        owner.get(BASE + "/trend?interval=week", org).andExpect(status().isBadRequest());
    }

    @Test
    void funnelFollowsWorkflowOrder() throws Exception {
        owner.get(BASE + "/drives/{drive}/funnel", org, driveA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driveTitle").value("Acme SDE"))
                .andExpect(jsonPath("$.companyName").value("Acme Systems"))
                .andExpect(jsonPath("$.applications").value(7))
                .andExpect(jsonPath("$.stages[*].stage").value(contains("applied",
                        "shortlisted", "assessment", "interview", "offered", "joined", "rejected", "withdrawn")))
                .andExpect(jsonPath("$.stages[*].count").value(contains(1, 0, 0, 1, 2, 1, 1, 1)))
                .andExpect(jsonPath("$.stages[*].reached").value(contains(7, 4, 4, 4, 3, 1, 1, 1)))
                .andExpect(jsonPath("$.stages[*].conversionRate").value(contains(1.0, 0.5714,
                        1.0, 1.0, 0.75, 0.3333, 0.1429, 0.1429)))
                .andExpect(jsonPath("$.offerRate").value(0.4286));

        owner.get(BASE + "/drives/{drive}/funnel", org, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void coordinatorSeesOnlyOwnDepartment() throws Exception {
        coordinator.get(BASE + "/overview", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStudents").value(5))
                .andExpect(jsonPath("$.participatingStudents").value(3))
                .andExpect(jsonPath("$.placedStudents").value(2))
                .andExpect(jsonPath("$.placementRate").value(0.6667))
                .andExpect(jsonPath("$.offers").value(3))
                .andExpect(jsonPath("$.acceptedOffers").value(2))
                .andExpect(jsonPath("$.highestCtc").value(1200000.0))
                .andExpect(jsonPath("$.medianCtc").value(1000000.0))
                .andExpect(jsonPath("$.applications").value(6));
        coordinator.get(BASE + "/departments", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments", hasSize(1)))
                .andExpect(jsonPath("$.departments[0].departmentId").value(cse));
        coordinator.get(BASE + "/overview?departmentId={ece}", org, ece)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStudents").value(0))
                .andExpect(jsonPath("$.offers").value(0))
                .andExpect(jsonPath("$.highestCtc").doesNotExist());
        coordinator.get(BASE + "/companies", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].companyName").value("Acme Systems"))
                .andExpect(jsonPath("$.companies[0].offers").value(2))
                .andExpect(jsonPath("$.companies[1].offers").value(1));
        coordinator.get(BASE + "/drives/{drive}/funnel", org, driveA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applications").value(4))
                .andExpect(jsonPath("$.offerRate").value(0.5));
        coordinator.get(REPORT + "?format=xlsx", org).andExpect(status().isOk());
    }

    @Test
    void studentsRecruitersAndOutsidersAreRejected() throws Exception {
        for (String path : List.of("/overview", "/departments", "/companies", "/ctc-distribution", "/trend")) {
            student.get(BASE + path, org).andExpect(status().isForbidden());
            recruiter.get(BASE + path, org).andExpect(status().isForbidden());
        }
        student.get(BASE + "/drives/{drive}/funnel", org, driveA).andExpect(status().isForbidden());
        student.get(REPORT, org).andExpect(status().isForbidden());
        recruiter.get(REPORT + "?format=pdf", org).andExpect(status().isForbidden());
        new TestApi(mvc).newUser().get(BASE + "/overview", org).andExpect(status().isNotFound());
    }

    @Test
    void excelReportHasEverySheet() throws Exception {
        byte[] body = owner.get(REPORT + "?format=xlsx", org)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", startsWith(
                        "attachment; filename=\"placement-report-" + LocalDate.now(ZoneId.of("Asia/Kolkata"))
                                .toString().replace("-", "") + ".xlsx\"")))
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            List<String> sheets = new ArrayList<>();
            workbook.forEach(sheet -> sheets.add(sheet.getSheetName()));
            assertThat(sheets).containsExactly("Summary", "Departments", "Companies", "Offers");

            Sheet offers = workbook.getSheet("Offers");
            List<String> header = new ArrayList<>();
            offers.getRow(0).forEach(cell -> header.add(cell.getStringCellValue()));
            assertThat(header).containsExactly("Roll number", "Student", "Department", "Company", "CTC (LPA)",
                    "Type", "Status", "Date");
            assertThat(offers.getLastRowNum()).isEqualTo(7);
            Row gita = find(offers, "EC007");
            assertThat(gita.getCell(1).getStringCellValue()).isEqualTo("Gita V");
            assertThat(gita.getCell(2).getStringCellValue()).isEqualTo("Electronics");
            assertThat(gita.getCell(3).getStringCellValue()).isEqualTo("Acme Systems");
            assertThat(gita.getCell(4).getNumericCellValue()).isEqualTo(26.0);
            assertThat(gita.getCell(5).getStringCellValue()).isEqualTo("Full time");
            assertThat(gita.getCell(6).getStringCellValue()).isEqualTo("Accepted");
            assertThat(gita.getCell(7).getLocalDateTimeCellValue().toLocalDate()).isEqualTo("2026-06-01");
            assertThat(find(offers, "EC008").getCell(1).getStringCellValue())
                    .isEqualTo("'=HYPERLINK(\"http://evil.test\")");

            Row placed = find(workbook.getSheet("Summary"), "Placed students");
            assertThat(placed.getCell(1).getNumericCellValue()).isEqualTo(4.0);
            Row computerScience = find(workbook.getSheet("Departments"), "Computer Science");
            assertThat(computerScience.getCell(3).getNumericCellValue()).isEqualTo(2.0);
            assertThat(find(workbook.getSheet("Companies"), "Acme Systems").getCell(1).getNumericCellValue())
                    .isEqualTo(3.0);
        }
    }

    @Test
    void pdfReportIsAPdf() throws Exception {
        byte[] body = owner.get(REPORT + "?format=pdf&batchId={batch}", org, batch1)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"placement-report-")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(body.length).isGreaterThan(1500);

        owner.get(REPORT + "?format=docx", org).andExpect(status().isBadRequest());
    }

    private static Row find(Sheet sheet, String firstCell) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && firstCell.equals(row.getCell(0).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("No row starting with " + firstCell + " in sheet " + sheet.getSheetName());
    }
}
