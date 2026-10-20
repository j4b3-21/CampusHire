package com.bm4a.platform.modules.dataexchange;

import static com.bm4a.platform.support.Fixtures.addMemberWithRole;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRecord;
import static com.bm4a.platform.support.Fixtures.createRole;
import static com.bm4a.platform.support.Fixtures.uploadFile;
import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;
import com.jayway.jsonpath.JsonPath;

/**
 * Templates, previews, asynchronous imports (CREATE, UPSERT, dry run, per-row errors and error reports, lookups by
 * code, limits and permissions) and exports (scope, hidden fields, filters, formula escaping) against real
 * PostgreSQL and OpenFGA (ADR-005).
 */
@IntegrationTest
class DataExchangeIntegrationTest {

    static final String MODULE = "/api/v1/orgs/{org}/modules/student";
    static final String STUDENTS = MODULE + "/records";
    static final String IMPORTS = MODULE + "/imports";
    static final String JOB = IMPORTS + "/{job}";
    static final String EXPORT = MODULE + "/export";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final List<String> HEADERS = List.of("Roll number", "Full name", "E-mail", "Department", "CGPA",
            "Date of birth", "Active", "Category", "Skills", "Fee", "Registered at");
    static final String DEPARTMENT = """
            {"apiKey": "department", "labelSingular": "Department", "labelPlural": "Departments",
             "settings": {"accessMode": "ROLES"},
             "fields": [
               {"apiKey": "code", "label": "Code", "type": "TEXT", "required": true, "unique": true, "filterable": true},
               {"apiKey": "name", "label": "Name", "type": "TEXT", "required": true, "searchable": true}]}
            """;
    static final String STUDENT = """
            {"apiKey": "student", "labelSingular": "Student", "labelPlural": "Students",
             "settings": {"accessMode": "ROLES"},
             "fields": [
               {"apiKey": "roll_number", "label": "Roll number", "type": "TEXT", "required": true, "unique": true,
                "searchable": true, "filterable": true, "sortable": true, "position": 1},
               {"apiKey": "full_name", "label": "Full name", "type": "TEXT", "required": true, "searchable": true,
                "sortable": true, "position": 2},
               {"apiKey": "email", "label": "E-mail", "type": "EMAIL", "unique": true, "position": 3},
               {"apiKey": "department", "label": "Department", "type": "LOOKUP", "filterable": true,
                "config": {"targetModuleKey": "department"}, "position": 4},
               {"apiKey": "cgpa", "label": "CGPA", "type": "DECIMAL", "filterable": true, "sortable": true,
                "config": {"scale": 2, "min": 0, "max": 10}, "position": 5},
               {"apiKey": "date_of_birth", "label": "Date of birth", "type": "DATE", "position": 6},
               {"apiKey": "active", "label": "Active", "type": "BOOLEAN", "defaultValue": true, "position": 7},
               {"apiKey": "category", "label": "Category", "type": "SINGLE_SELECT", "filterable": true, "position": 8,
                "config": {"options": [{"value": "general", "label": "General"}, {"value": "reserved", "label": "Reserved"}]}},
               {"apiKey": "skills", "label": "Skills", "type": "MULTI_SELECT", "position": 9,
                "config": {"options": [{"value": "java", "label": "Java"}, {"value": "python", "label": "Python"},
                                       {"value": "sql", "label": "SQL"}]}},
               {"apiKey": "fee", "label": "Fee", "type": "CURRENCY", "position": 10},
               {"apiKey": "registered_at", "label": "Registered at", "type": "DATETIME", "position": 11},
               {"apiKey": "remarks", "label": "Remarks", "type": "LONG_TEXT", "position": 12},
               {"apiKey": "legacy_id", "label": "Legacy ID", "type": "TEXT", "readOnly": true, "position": 13}]}
            """;
    static final String COORDINATOR = """
            {"apiKey": "coordinator", "label": "Coordinator", "permissions": {"modules": {
              "department": {"read": "ALL"},
              "student": {"read": {"scope": "ATTRIBUTE", "field": "department", "attribute": "department"},
                          "create": true,
                          "update": {"scope": "ATTRIBUTE", "field": "department", "attribute": "department"},
                          "readOnlyFields": ["cgpa"], "hiddenFields": ["remarks"]}}}}
            """;
    static final String STUDENT_ROLE = """
            {"apiKey": "student", "label": "Student", "permissions": {"modules": {"student": {"read": "OWN"}}}}
            """;
    static final String REGISTRAR = """
            {"apiKey": "registrar", "label": "Registrar", "permissions": {"modules": {
              "department": {"read": "ALL"}, "student": {"read": "ALL", "create": true}}}}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RecordOperations records;

    TestApi api;
    Caller owner;
    String org;
    String cse;
    String ece;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        org = createOrg(owner, "Campus");
        createModule(owner, org, DEPARTMENT);
        createModule(owner, org, STUDENT);
        createRole(owner, org, COORDINATOR);
        createRole(owner, org, STUDENT_ROLE);
        createRole(owner, org, REGISTRAR);
        cse = createRecord(owner, org, "department", "{\"code\":\"CSE\",\"name\":\"Computer Science\"}");
        ece = createRecord(owner, org, "department", "{\"code\":\"ECE\",\"name\":\"Electronics\"}");
    }

    // ---- templates and previews ----------------------------------------------------------------------------

    @Test
    void templatesListImportableFieldsAndDocumentThem() throws Exception {
        MvcResult xlsx = owner.get(MODULE + "/import-template?format=xlsx", org)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Content-Disposition", containsString("student-import-template.xlsx")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        byte[] workbook = xlsx.getResponse().getContentAsByteArray();
        List<String> expected = new ArrayList<>(HEADERS);
        expected.add("Remarks");
        assertThat(Sheets.readXlsx(workbook).getFirst()).containsExactlyElementsOf(expected);
        assertThat(Sheets.sheetName(workbook, 1)).isEqualTo("Fields");
        List<List<String>> fields = Sheets.readXlsx(workbook, 1);
        assertThat(fields.getFirst()).containsExactly("Column", "Field key", "Type", "Required", "Unique",
                "Allowed values", "Example");
        assertThat(fields).anySatisfy(row -> assertThat(row).startsWith("Roll number", "roll_number", "TEXT", "yes",
                "yes"));
        assertThat(fields).anySatisfy(row -> assertThat(row).containsSequence("Department", "department", "LOOKUP")
                .last().asString().contains("Code of a Department"));
        assertThat(fields).anySatisfy(row -> assertThat(row).startsWith("Category", "category", "SINGLE_SELECT")
                .contains("General [general], Reserved [reserved]"));
        assertThat(fields).anySatisfy(row -> assertThat(row).startsWith("Active", "active", "BOOLEAN", "no"));

        byte[] csv = owner.get(MODULE + "/import-template?format=csv", org)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(Sheets.readCsv(csv)).containsExactly(expected);

        Caller coordinator = api.newUser();
        addMemberWithRole(owner, org, coordinator, "coordinator", "{\"department\":\"" + cse + "\"}");
        byte[] restricted = coordinator.get(MODULE + "/import-template?format=csv", org)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(Sheets.readCsv(restricted).getFirst()).doesNotContain("CGPA", "Remarks", "Legacy ID")
                .contains("Roll number");
        owner.get(MODULE + "/import-template?format=pdf", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_format"));
    }

    @Test
    void previewShowsHeadersRowsAndASuggestedMapping() throws Exception {
        StringBuilder csv = new StringBuilder("\uFEFFRoll Number;full_name;E-Mail;Dept;Unknown column\r\n");
        for (int i = 1; i <= 25; i++) {
            csv.append("21CS%04d;Student %d;s%d@college.test;CSE;x\r\n".formatted(i, i, i));
        }
        csv.append(";;;;\r\n");
        owner.multipart(IMPORTS + "/preview", List.of(file("students.csv", "text/csv", Sheets.csv(csv.toString()))),
                org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("students.csv"))
                .andExpect(jsonPath("$.format").value("CSV"))
                .andExpect(jsonPath("$.headers[0]").value("Roll Number"))
                .andExpect(jsonPath("$.headers.length()").value(5))
                .andExpect(jsonPath("$.rows.length()").value(20))
                .andExpect(jsonPath("$.rows[0][0]").value("21CS0001"))
                .andExpect(jsonPath("$.rows[19][1]").value("Student 20"))
                .andExpect(jsonPath("$.totalRows").value(25))
                .andExpect(jsonPath("$.maxRows").value(50000))
                .andExpect(jsonPath("$.suggestedMapping['Roll Number']").value("roll_number"))
                .andExpect(jsonPath("$.suggestedMapping.full_name").value("full_name"))
                .andExpect(jsonPath("$.suggestedMapping['E-Mail']").value("email"))
                .andExpect(jsonPath("$.suggestedMapping.Dept").doesNotExist())
                .andExpect(jsonPath("$.fields[?(@.key == 'department')].header").value(hasItem("Department")))
                .andExpect(jsonPath("$.fields[?(@.key == 'roll_number')].required").value(hasItem(true)))
                .andExpect(jsonPath("$.fields[?(@.key == 'legacy_id')]").isEmpty());

        byte[] xlsx = Sheets.xlsx(List.of("Roll number", "Date of birth", "CGPA"),
                List.of(List.of("21CS0001", LocalDate.of(2004, 5, 17), 8.25)));
        owner.multipart(IMPORTS + "/preview", List.of(file("s.xlsx", XLSX, xlsx)), org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("XLSX"))
                .andExpect(jsonPath("$.rows[0][1]").value("2004-05-17"))
                .andExpect(jsonPath("$.rows[0][2]").value("8.25"))
                .andExpect(jsonPath("$.suggestedMapping.length()").value(3));

        owner.multipart(IMPORTS + "/preview", List.of(file("x.png", "image/png", com.bm4a.platform.support.Fixtures.PNG)),
                org)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_file_type"));
        owner.multipart(IMPORTS + "/preview", List.of(file("dup.csv", "text/csv", Sheets.csv("A,B,A\n1,2,3\n"))), org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("duplicate_header"));
    }

    // ---- imports -------------------------------------------------------------------------------------------

    @Test
    void importCreatesRecordsInBatchesAndResolvesLookupsByCode() throws Exception {
        byte[] file = Sheets.xlsx(HEADERS, students(1200, "21CS"));
        MvcResult accepted = owner.multipart(IMPORTS, List.of(file("students.xlsx", XLSX, file)), org)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.mode").value("CREATE"))
                .andExpect(jsonPath("$.format").value("XLSX"))
                .andExpect(jsonPath("$.mapping['Roll number']").value("roll_number"))
                .andExpect(jsonPath("$.mapping.length()").value(HEADERS.size()))
                .andReturn();
        String job = read(accepted, "$.id");
        assertThat(accepted.getResponse().getHeader("Location")).endsWith("/modules/student/imports/" + job);

        String done = awaitJob(owner, job);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((Integer) JsonPath.read(done, "$.totalRows")).isEqualTo(1200);
        assertThat((Integer) JsonPath.read(done, "$.processedRows")).isEqualTo(1200);
        assertThat((Integer) JsonPath.read(done, "$.createdRows")).isEqualTo(1200);
        assertThat((Integer) JsonPath.read(done, "$.failedRows")).isZero();
        assertThat((Boolean) JsonPath.read(done, "$.errorReportAvailable")).isFalse();
        assertThat(count()).isEqualTo(1200);

        owner.get(STUDENTS + "?filter[roll_number]=21CS0002", org)
                .andExpect(jsonPath("$.items[0].data.full_name").value("Student 2"))
                .andExpect(jsonPath("$.items[0].data.department").value(cse))
                .andExpect(jsonPath("$.items[0].data.cgpa").value("6.2"))
                .andExpect(jsonPath("$.items[0].data.date_of_birth").value("2004-01-03"))
                .andExpect(jsonPath("$.items[0].data.active").value(true))
                .andExpect(jsonPath("$.items[0].data.category").value("general"))
                .andExpect(jsonPath("$.items[0].data.skills[0]").value("java"))
                .andExpect(jsonPath("$.items[0].data.skills[1]").value("sql"))
                .andExpect(jsonPath("$.items[0].data.fee.amount").value("1200.50"))
                .andExpect(jsonPath("$.items[0].data.fee.currency").value("INR"))
                .andExpect(jsonPath("$.items[0].data.registered_at").value("2026-10-20T10:15:00Z"))
                .andExpect(jsonPath("$.items[0].createdBy").value(owner.id()))
                .andExpect(jsonPath("$.items[0].ownerUserId").value(owner.id()));
        owner.get(STUDENTS + "?filter[roll_number]=21CS0003", org)
                .andExpect(jsonPath("$.items[0].data.department").value(ece))
                .andExpect(jsonPath("$.items[0].data.category").value("reserved"));
        owner.get("/api/v1/orgs/{org}/audit-events?resourceId={job}", org, job)
                .andExpect(jsonPath("$.items[*].action", hasItem("import.completed")));
        owner.get(IMPORTS, org)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(job));
    }

    @Test
    void fiveThousandRowsImportWellUnderAMinute() throws Exception {
        byte[] file = Sheets.xlsx(HEADERS, students(5000, "22EC"));
        long start = System.nanoTime();
        String job = read(owner.multipart(IMPORTS, List.of(file("students.xlsx", XLSX, file)), org)
                .andExpect(status().isAccepted()).andReturn(), "$.id");
        String done = awaitJob(owner, job);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        System.out.printf("5,000-row XLSX import (%d KiB) finished in %d ms%n", file.length / 1024,
                elapsed.toMillis());
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((Integer) JsonPath.read(done, "$.createdRows")).isEqualTo(5000);
        assertThat(count()).isEqualTo(5000);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(60));
    }

    @Test
    void upsertMatchesTheUniqueFieldCaseInsensitivelyAndPatchesOnlyMappedColumns() throws Exception {
        String asha = createRecord(owner, org, "student", "{\"roll_number\":\"21CS0001\",\"full_name\":\"Asha\","
                + "\"cgpa\":\"7.0\",\"remarks\":\"keep me\",\"department\":\"" + cse + "\"}");
        String csv = "Roll number,Full name,CGPA\n21cs0001,Asha K,9.1\n21CS0003,New Student,8\n";
        String options = "{\"mode\":\"UPSERT\",\"matchField\":\"roll_number\"}";
        String done = awaitJob(owner, startImport(owner, "update.csv", Sheets.csv(csv), options));
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((Integer) JsonPath.read(done, "$.updatedRows")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(done, "$.createdRows")).isEqualTo(1);
        owner.get(STUDENTS + "/{id}", org, asha)
                .andExpect(jsonPath("$.data.roll_number").value("21CS0001"))
                .andExpect(jsonPath("$.data.full_name").value("Asha K"))
                .andExpect(jsonPath("$.data.cgpa").value("9.1"))
                .andExpect(jsonPath("$.data.remarks").value("keep me"))
                .andExpect(jsonPath("$.data.department").value(cse))
                .andExpect(jsonPath("$.version").value(2));

        // Running the same file again changes nothing and creates no duplicates.
        String again = awaitJob(owner, startImport(owner, "update.csv", Sheets.csv(csv), options));
        assertThat((Integer) JsonPath.read(again, "$.updatedRows")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(again, "$.createdRows")).isZero();
        assertThat(count()).isEqualTo(2);
        owner.get(STUDENTS + "/{id}", org, asha).andExpect(jsonPath("$.version").value(2));

        owner.multipart(IMPORTS, List.of(file("u.csv", "text/csv", Sheets.csv(csv)),
                options("{\"mode\":\"UPSERT\",\"matchField\":\"full_name\"}")), org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("matchField"))
                .andExpect(jsonPath("$.errors[0].code").value("match_field"));
    }

    @Test
    void failedRowsAreReportedWithoutAbortingTheBatch() throws Exception {
        createRecord(owner, org, "student", "{\"roll_number\":\"21CS0100\",\"full_name\":\"Existing\"}");
        String csv = """
                Roll number,Full name,E-mail,Department,Date of birth,Category
                21CS0001,Asha,asha@college.test,CSE,2004-05-17,General
                21CS0002,Ravi,not-an-email,CSE,,
                21CS0003,Meena,,XYZ,,
                21cs0001,Asha again,,CSE,,
                21CS0005,Kiran,,ECE,31/02/2004,
                21CS0006,,,,,
                21CS0100,Duplicate of existing,,,,
                21CS0008,Divya,divya@college.test,Computer Science,20/10/2004,reserved
                21CS0009,Arjun,,,,Unknown
                21CS0010,=1+1,,,,
                """;
        String job = startImport(owner, "students.csv", Sheets.csv(csv), null);
        String done = awaitJob(owner, job);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((Integer) JsonPath.read(done, "$.processedRows")).isEqualTo(10);
        assertThat((Integer) JsonPath.read(done, "$.createdRows")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(done, "$.failedRows")).isEqualTo(7);
        assertThat((Boolean) JsonPath.read(done, "$.errorReportAvailable")).isTrue();
        assertThat(count()).isEqualTo(4);
        owner.get(STUDENTS + "?filter[roll_number]=21CS0008", org)
                .andExpect(jsonPath("$.items[0].data.department").value(cse))
                .andExpect(jsonPath("$.items[0].data.date_of_birth").value("2004-10-20"));

        owner.get(JOB, org, job)
                .andExpect(jsonPath("$.errors.length()").value(7))
                .andExpect(jsonPath("$.errors[0].row").value(3))
                .andExpect(jsonPath("$.errors[0].column").value("E-mail"))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].code").value("format"))
                .andExpect(jsonPath("$.errors[?(@.row == 4)].code").value(hasItem("invalid_reference")))
                .andExpect(jsonPath("$.errors[?(@.row == 5)].code").value(hasItem("duplicate_in_file")))
                .andExpect(jsonPath("$.errors[?(@.row == 5)].message").value(hasItem("the same value is used in row 2")))
                .andExpect(jsonPath("$.errors[?(@.row == 6)].column").value(hasItem("Date of birth")))
                .andExpect(jsonPath("$.errors[?(@.row == 7)].code").value(hasItem("required")))
                .andExpect(jsonPath("$.errors[?(@.row == 8)].code").value(hasItem("unique")))
                .andExpect(jsonPath("$.errors[?(@.row == 8)].column").value(hasItem("Roll number")))
                .andExpect(jsonPath("$.errors[?(@.row == 10)].code").value(hasItem("option")));

        MvcResult report = owner.get(JOB + "/errors?format=csv", org, job)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", matchesPattern(
                        "attachment; filename=\"student-import-errors-\\d{8}-\\d{4}\\.csv\".*")))
                .andReturn();
        List<List<String>> errors = Sheets.readCsv(report.getResponse().getContentAsByteArray());
        assertThat(errors.getFirst()).containsExactly("Roll number", "Full name", "E-mail", "Department",
                "Date of birth", "Category", "row", "error");
        assertThat(errors).hasSize(8);
        assertThat(errors.get(1)).containsExactly("21CS0002", "Ravi", "not-an-email", "CSE", "", "", "3",
                "E-mail: must be a valid e-mail address");
        assertThat(errors).anySatisfy(row -> assertThat(row).startsWith("21CS0003", "Meena")
                .last().asString().isEqualTo("Department: no Department matches 'XYZ'"));
        assertThat(errors).anySatisfy(row -> assertThat(row).startsWith("21CS0100")
                .last().asString().isEqualTo("Roll number: value is already used by another record"));

        byte[] xlsx = owner.get(JOB + "/errors?format=xlsx", org, job)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = Sheets.readXlsx(xlsx);
        assertThat(rows).hasSize(8);
        assertThat(rows.getFirst()).endsWith("row", "error");

        // The error report can be fixed and re-uploaded as is: the extra columns are not mapped.
        owner.multipart(IMPORTS + "/preview", List.of(file("errors.xlsx", XLSX, xlsx)), org)
                .andExpect(jsonPath("$.totalRows").value(7))
                .andExpect(jsonPath("$.suggestedMapping.row").doesNotExist());
    }

    @Test
    void dryRunValidatesEveryRowAndWritesNothing() throws Exception {
        createRecord(owner, org, "student", "{\"roll_number\":\"21CS0100\",\"full_name\":\"Existing\"}");
        String csv = "Roll number,Full name\n21CS0001,A\n21CS0002,B\n21CS0100,Clash\n21CS0003,C\n";
        String done = awaitJob(owner, startImport(owner, "s.csv", Sheets.csv(csv), "{\"dryRun\":true}"));
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((Boolean) JsonPath.read(done, "$.dryRun")).isTrue();
        assertThat((Integer) JsonPath.read(done, "$.createdRows")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(done, "$.failedRows")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(done, "$.errors[0].row")).isEqualTo(4);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void onlyOneQueuedOrRunningImportPerModule() throws Exception {
        String fileId = uploadFile(owner, org, "x.csv", "text/csv", Sheets.csv("Roll number\nX\n"));
        UUID moduleId = UUID.fromString(read(owner.get(MODULE, org).andReturn(), "$.id"));
        UUID running = UUID.randomUUID();
        insertJob(running, moduleId, fileId, "RUNNING");

        owner.multipart(IMPORTS, List.of(file("s.csv", "text/csv", Sheets.csv("Roll number,Full name\nA,B\n"))), org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("import_in_progress"));
        assertThatThrownBy(() -> insertJob(UUID.randomUUID(), moduleId, fileId, "QUEUED"))
                .isInstanceOf(DuplicateKeyException.class);
        // Other modules are not blocked.
        owner.multipart("/api/v1/orgs/{org}/modules/department/imports", List.of(file("d.csv", "text/csv",
                Sheets.csv("Code,Name\nME,Mechanical\n"))), org).andExpect(status().isAccepted());

        jdbc.sql("UPDATE import_job SET status = 'FAILED' WHERE id = :id").param("id", running).update();
        awaitJob(owner, startImport(owner, "s.csv", Sheets.csv("Roll number,Full name\nA,B\n"), null));
    }

    @Test
    void cancellingStopsTheJobAndReleasesTheModule() throws Exception {
        String job = startImport(owner, "s.xlsx", XLSX, Sheets.xlsx(HEADERS, students(3000, "23ME")), null);
        owner.postIfMatch(JOB + "/cancel", null, org, job)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.finishedAt").exists());
        owner.postIfMatch(JOB + "/cancel", null, org, job)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("import_finished"));
        String next = startImport(owner, "s.csv", Sheets.csv("Roll number,Full name\n24XX0001,Late\n"), null);
        awaitJob(owner, next);
        owner.get(JOB, org, job).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(count()).isLessThan(3001);
    }

    @Test
    void importsRequireCreatePermissionAndRespectFieldPolicies() throws Exception {
        byte[] csv = Sheets.csv("Roll number,Full name,CGPA,Remarks\n21CS0001,Asha,8.1,hello\n");
        Caller student = api.newUser();
        addMemberWithRole(owner, org, student, "student", null);
        student.get(MODULE + "/import-template", org).andExpect(status().isForbidden());
        student.multipart(IMPORTS + "/preview", List.of(file("s.csv", "text/csv", csv)), org)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access_denied"));
        student.multipart(IMPORTS, List.of(file("s.csv", "text/csv", csv)), org).andExpect(status().isForbidden());
        student.get(IMPORTS, org).andExpect(status().isForbidden());
        api.newUser().get(IMPORTS, org).andExpect(status().isNotFound());

        Caller coordinator = api.newUser();
        addMemberWithRole(owner, org, coordinator, "coordinator", "{\"department\":\"" + cse + "\"}");
        coordinator.multipart(IMPORTS, List.of(file("s.csv", "text/csv", csv), options(
                "{\"mapping\":{\"Roll number\":\"roll_number\",\"Full name\":\"full_name\",\"CGPA\":\"cgpa\"}}")), org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("mapping.CGPA"))
                .andExpect(jsonPath("$.errors[0].code").value("field_read_only"));
        coordinator.multipart(IMPORTS, List.of(file("s.csv", "text/csv", csv), options(
                "{\"mapping\":{\"Roll number\":\"roll_number\",\"Full name\":\"full_name\",\"Remarks\":\"remarks\"}}")),
                org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_field"));
        // Without a mapping, the suggestion leaves out what the coordinator may not write.
        String job = read(coordinator.multipart(IMPORTS, List.of(file("s.csv", "text/csv", csv)), org)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mapping.CGPA").doesNotExist())
                .andExpect(jsonPath("$.mapping.Remarks").doesNotExist())
                .andReturn(), "$.id");
        assertThat((Integer) JsonPath.read(awaitJob(coordinator, job), "$.createdRows")).isEqualTo(1);
        owner.get(IMPORTS, org).andExpect(jsonPath("$.items[0].createdBy").value(coordinator.id()));
        owner.get(JOB, org, job).andExpect(status().isOk());

        String ownerJob = startImport(owner, "o.csv", Sheets.csv("Roll number,Full name\n21CS0002,Ravi\n"), null);
        awaitJob(owner, ownerJob);
        coordinator.get(JOB, org, ownerJob).andExpect(status().isNotFound());
        coordinator.get(IMPORTS, org).andExpect(jsonPath("$.totalElements").value(1));

        Caller registrar = api.newUser();
        addMemberWithRole(owner, org, registrar, "registrar", null);
        registrar.multipart(IMPORTS, List.of(file("s.csv", "text/csv", csv),
                options("{\"mode\":\"UPSERT\",\"matchField\":\"roll_number\"}")), org)
                .andExpect(status().isForbidden());
        owner.multipart(IMPORTS, List.of(file("s.csv", "text/csv", csv), options("{\"mode\":\"MERGE\"}")), org)
                .andExpect(status().isBadRequest());
    }

    @Test
    void rowLimitFailsTheJob() throws Exception {
        StringBuilder csv = new StringBuilder("Roll number,Full name\n");
        for (int i = 0; i <= 50_000; i++) {
            csv.append('R').append(i).append(",N\n");
        }
        String done = awaitJob(owner, startImport(owner, "big.csv", Sheets.csv(csv.toString()), null));
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(done, "$.errorMessage")).contains("more than 50000 data rows");
        assertThat(count()).isZero();
    }

    // ---- exports -------------------------------------------------------------------------------------------

    @Test
    void exportsHonorScopeHiddenFieldsFiltersAndEscapeFormulas() throws Exception {
        String formula = createRecord(owner, org, "student", "{\"roll_number\":\"21CS0001\",\"full_name\":"
                + "\"=HYPERLINK(\\\"http://evil.test\\\")\",\"department\":\"" + cse + "\",\"category\":\"general\","
                + "\"remarks\":\"secret\",\"cgpa\":\"8.5\",\"date_of_birth\":\"2004-05-17\","
                + "\"fee\":{\"amount\":\"1200\",\"currency\":\"INR\"},\"skills\":[\"java\",\"sql\"]}");
        createRecord(owner, org, "student", "{\"roll_number\":\"21CS0002\",\"full_name\":\"+cmd\",\"department\":\""
                + cse + "\",\"category\":\"reserved\"}");
        createRecord(owner, org, "student", "{\"roll_number\":\"21CS0003\",\"full_name\":\"Asha\",\"department\":\""
                + cse + "\",\"category\":\"general\"}");
        createRecord(owner, org, "student", "{\"roll_number\":\"21EC0001\",\"full_name\":\"Ravi\",\"department\":\""
                + ece + "\",\"category\":\"general\"}");

        MvcResult csv = owner.get(EXPORT + "?format=csv&filter[category]=general&sort=-roll_number", org)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=\"student-\\d{8}-\\d{4}\\.csv\".*")))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn();
        List<List<String>> rows = Sheets.readCsv(csv.getResponse().getContentAsByteArray());
        assertThat(rows.getFirst()).startsWith("Roll number", "Full name").contains("Remarks")
                .endsWith("Record ID", "Created at", "Updated at");
        assertThat(rows).extracting(row -> row.getFirst()).containsExactly("Roll number", "21EC0001", "21CS0003",
                "21CS0001");
        List<String> first = rows.get(3);
        int department = rows.getFirst().indexOf("Department");
        assertThat(first.get(1)).isEqualTo("'=HYPERLINK(\"http://evil.test\")");
        assertThat(first.get(department)).isEqualTo("CSE");
        assertThat(first.get(rows.getFirst().indexOf("Category"))).isEqualTo("General");
        assertThat(first.get(rows.getFirst().indexOf("Skills"))).isEqualTo("Java, SQL");
        assertThat(first.get(rows.getFirst().indexOf("Fee"))).isEqualTo("1200.00 INR");
        assertThat(first.get(rows.getFirst().indexOf("Remarks"))).isEqualTo("secret");
        assertThat(first.get(rows.getFirst().indexOf("Record ID"))).isEqualTo(formula);

        owner.get(EXPORT + "?format=csv&q=asha", org).andExpect(status().isOk());
        assertThat(Sheets.readCsv(owner.get(EXPORT + "?format=csv&q=asha", org).andReturn().getResponse()
                .getContentAsByteArray())).hasSize(2);
        owner.get(EXPORT + "?filter[remarks]=x", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_filter"));

        Caller coordinator = api.newUser();
        addMemberWithRole(owner, org, coordinator, "coordinator", "{\"department\":\"" + cse + "\"}");
        byte[] xlsx = coordinator.get(EXPORT + "?format=xlsx&sort=roll_number", org)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=\"student-\\d{8}-\\d{4}\\.xlsx\".*")))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> sheet = Sheets.readXlsx(xlsx);
        assertThat(sheet).hasSize(4);
        assertThat(sheet.getFirst()).doesNotContain("Remarks").contains("CGPA", "Department");
        assertThat(sheet).extracting(row -> row.getFirst()).containsExactly("Roll number", "21CS0001", "21CS0002",
                "21CS0003");
        assertThat(sheet.get(1).get(1)).isEqualTo("'=HYPERLINK(\"http://evil.test\")");
        assertThat(sheet.get(2).get(1)).isEqualTo("'+cmd");
        int dob = sheet.getFirst().indexOf("Date of birth");
        int cgpa = sheet.getFirst().indexOf("CGPA");
        assertThat(sheet.get(1).get(dob)).isEqualTo("2004-05-17");
        assertThat(Sheets.cell(xlsx, 1, dob).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(Sheets.cell(xlsx, 1, cgpa).getNumericCellValue()).isEqualTo(8.5);
        assertThat(Sheets.cell(xlsx, 1, 1).getCellType()).isEqualTo(CellType.STRING);

        Caller student = api.newUser();
        addMemberWithRole(owner, org, student, "student", null);
        records.transferOwner(ownerContext(), "student", UUID.fromString(formula), UUID.fromString(student.id()));
        assertThat(Sheets.readCsv(student.get(EXPORT + "?format=csv", org).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray())).hasSize(2);
        api.newUser().get(EXPORT, org).andExpect(status().isNotFound());
    }

    @Test
    void exportedFilesImportAgain() throws Exception {
        createRecord(owner, org, "student", "{\"roll_number\":\"21CS0001\",\"full_name\":\"-Asha-\",\"department\":\""
                + cse + "\",\"cgpa\":\"8.5\",\"date_of_birth\":\"2004-05-17\",\"category\":\"reserved\","
                + "\"skills\":[\"python\"],\"fee\":{\"amount\":\"99.5\",\"currency\":\"USD\"},"
                + "\"registered_at\":\"2026-10-20T10:15:00Z\",\"active\":false}");
        for (String format : List.of("xlsx", "csv")) {
            byte[] exported = owner.get(EXPORT + "?format=" + format, org).andReturn().getResponse()
                    .getContentAsByteArray();
            String done = awaitJob(owner, startImport(owner, "export." + format,
                    format.equals("xlsx") ? XLSX : "text/csv", exported,
                    "{\"mode\":\"UPSERT\",\"matchField\":\"roll_number\"}"));
            assertThat((Integer) JsonPath.read(done, "$.updatedRows")).as(format).isEqualTo(1);
            assertThat((Integer) JsonPath.read(done, "$.failedRows")).as(format).isZero();
        }
        owner.get(STUDENTS + "?filter[roll_number]=21CS0001", org)
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[0].data.full_name").value("-Asha-"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** Generated student rows matching {@link #HEADERS}; departments alternate CSE / ece (by code, any case). */
    static List<List<Object>> students(int count, String prefix) {
        List<List<Object>> rows = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String roll = prefix + "%04d".formatted(i);
            List<Object> row = new ArrayList<>();
            row.add(roll);
            row.add("Student " + i);
            row.add(roll.toLowerCase() + "@college.test");
            row.add(i % 2 == 0 ? "CSE" : "ece");
            row.add(6 + (i % 40) / 10.0);
            row.add(LocalDate.of(2004, 1, 1).plusDays(i % 365));
            row.add("yes");
            row.add(i % 3 == 0 ? "Reserved" : "general");
            row.add("Java, SQL");
            row.add(1200.5);
            row.add("20/10/2026 10:15");
            rows.add(row);
        }
        return rows;
    }

    private String startImport(Caller caller, String name, byte[] content, String options) throws Exception {
        return startImport(caller, name, "text/csv", content, options);
    }

    private String startImport(Caller caller, String name, String type, byte[] content, String options)
            throws Exception {
        List<MockMultipartFile> parts = new ArrayList<>(List.of(file(name, type, content)));
        if (options != null) {
            parts.add(options(options));
        }
        return read(caller.multipart(IMPORTS, parts, org).andExpect(status().isAccepted()).andReturn(), "$.id");
    }

    private String awaitJob(Caller caller, String job) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (true) {
            String body = caller.get(JOB, org, job).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
            String state = JsonPath.read(body, "$.status");
            if (!state.equals("QUEUED") && !state.equals("RUNNING")) {
                return body;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Import did not finish: " + body);
            }
            Thread.sleep(100);
        }
    }

    private long count() throws Exception {
        return ((Number) read(owner.get(STUDENTS + "?size=1", org).andReturn(), "$.totalElements")).longValue();
    }

    private void insertJob(UUID id, UUID moduleId, String fileId, String status) {
        jdbc.sql("""
                INSERT INTO import_job (id, organization_id, module_id, module_key, file_id, file_name, format, mode,
                                        mapping, dry_run, status, created_by, created_at)
                VALUES (:id, :org, :module, 'student', :file, 'x.csv', 'CSV', 'CREATE', '{}'::jsonb, false, :status,
                        :user, now())
                """)
                .param("id", id)
                .param("org", UUID.fromString(org))
                .param("module", moduleId)
                .param("file", UUID.fromString(fileId))
                .param("status", status)
                .param("user", uuid(owner))
                .update();
    }

    private static UUID uuid(Caller caller) {
        try {
            return UUID.fromString(caller.id());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private TenantContext ownerContext() throws Exception {
        return new TenantContext(UUID.fromString(org), UUID.fromString(owner.id()), MembershipRole.OWNER);
    }

    private static MockMultipartFile file(String name, String type, byte[] content) {
        return new MockMultipartFile("file", name, type, content);
    }

    private static MockMultipartFile options(String json) {
        return new MockMultipartFile("options", "", MediaType.APPLICATION_JSON_VALUE,
                json.getBytes(StandardCharsets.UTF_8));
    }
}
