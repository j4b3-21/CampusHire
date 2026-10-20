package com.bm4a.platform.modules.records;

import static com.bm4a.platform.support.Fixtures.addMember;
import static com.bm4a.platform.support.Fixtures.addMemberWithRole;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRecord;
import static com.bm4a.platform.support.Fixtures.createRole;
import static com.bm4a.platform.support.TestApi.read;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

/**
 * Record scopes (OWN, ATTRIBUTE incl. arrays and {@code $id}, {@code where}) and field security (hidden and read-only
 * fields) through the record API, related records and {@code /me/access} (ADR-004).
 */
@IntegrationTest
class ScopedRecordAccessIntegrationTest {

    static final String STUDENTS = "/api/v1/orgs/{org}/modules/student/records";
    static final String STUDENT = STUDENTS + "/{id}";
    static final String DEPARTMENT = """
            {"apiKey": "department", "labelSingular": "Department", "labelPlural": "Departments",
             "settings": {"accessMode": "ROLES"},
             "fields": [{"apiKey": "name", "label": "Name", "type": "TEXT", "required": true}]}
            """;
    static final String COMPANY = """
            {"apiKey": "company", "labelSingular": "Company", "labelPlural": "Companies",
             "settings": {"accessMode": "ROLES"},
             "fields": [{"apiKey": "name", "label": "Name", "type": "TEXT", "required": true}]}
            """;
    static final String STUDENT_MODULE = """
            {"apiKey": "student", "labelSingular": "Student", "labelPlural": "Students",
             "settings": {"accessMode": "ROLES"},
             "fields": [
               {"apiKey": "full_name", "label": "Name", "type": "TEXT", "required": true, "searchable": true, "sortable": true},
               {"apiKey": "department", "label": "Department", "type": "LOOKUP", "filterable": true,
                "config": {"targetModuleKey": "department"}},
               {"apiKey": "cgpa", "label": "CGPA", "type": "DECIMAL", "filterable": true, "config": {"scale": 2}},
               {"apiKey": "phone", "label": "Phone", "type": "PHONE"},
               {"apiKey": "category", "label": "Category", "type": "SINGLE_SELECT", "filterable": true,
                "config": {"options": [{"value": "general", "label": "General"}, {"value": "reserved", "label": "Reserved"}]}},
               {"apiKey": "remarks", "label": "Remarks", "type": "LONG_TEXT", "searchable": true},
               {"apiKey": "stage", "label": "Stage", "type": "SINGLE_SELECT", "filterable": true,
                "config": {"options": [{"value": "draft", "label": "Draft"}, {"value": "published", "label": "Published"}]}}]}
            """;
    static final String STUDENT_ROLE = """
            {"apiKey": "student", "label": "Student", "permissions": {"modules": {"student": {
              "read": "OWN", "update": "OWN", "readOnlyFields": ["cgpa"], "hiddenFields": ["remarks", "category"]}}}}
            """;
    static final String COORDINATOR_ROLE = """
            {"apiKey": "coordinator", "label": "Coordinator", "permissions": {"modules": {
              "department": {"read": "ALL"},
              "student": {"read": {"scope": "ATTRIBUTE", "field": "department", "attribute": "department"},
                          "update": {"scope": "ATTRIBUTE", "field": "department", "attribute": "department"}}}}}
            """;
    static final String RECRUITER_ROLE = """
            {"apiKey": "recruiter", "label": "Recruiter", "permissions": {"modules": {
              "company": {"read": {"scope": "ATTRIBUTE", "field": "$id", "attribute": "company"}},
              "student": {"read": {"scope": "ALL", "where": {"stage": "published"}}, "hiddenFields": ["phone"]}}}}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordOperations operations;

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
        createModule(owner, org, COMPANY);
        createModule(owner, org, STUDENT_MODULE);
        createRole(owner, org, STUDENT_ROLE);
        createRole(owner, org, COORDINATOR_ROLE);
        createRole(owner, org, RECRUITER_ROLE);
        cse = createRecord(owner, org, "department", "{\"name\":\"CSE\"}");
        ece = createRecord(owner, org, "department", "{\"name\":\"ECE\"}");
    }

    @Test
    void ownScopeHidesOtherRecordsAndEnforcesFieldSecurity() throws Exception {
        Caller student = api.newUser();
        addMemberWithRole(owner, org, student, "student", null);
        String mine = student(cse, "Asha", "draft");
        String other = student(cse, "Ravi", "draft");
        operations.transferOwner(ownerContext(), "student", UUID.fromString(mine), UUID.fromString(student.id()));

        student.get(STUDENTS, org)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(mine))
                .andExpect(jsonPath("$.items[0].ownerUserId").value(student.id()))
                .andExpect(jsonPath("$.items[0].data.cgpa").value("8.5"))
                .andExpect(jsonPath("$.items[0].data.remarks").doesNotExist())
                .andExpect(jsonPath("$.items[0].data.category").doesNotExist());
        student.get(STUDENT, org, other).andExpect(status().isNotFound());
        student.patch(STUDENT, "\"1\"", "{\"data\":{\"phone\":\"+91 99999 00000\"}}", org, other)
                .andExpect(status().isNotFound());
        student.get(STUDENT + "/versions", org, other).andExpect(status().isNotFound());
        student.delete(STUDENT, "\"2\"", org, mine).andExpect(status().isForbidden());
        student.post(STUDENTS, "{\"data\":{\"full_name\":\"New\"}}", org).andExpect(status().isForbidden());

        // Hidden fields: absent from responses and history, rejected on write, not filterable or searchable.
        student.get(STUDENT + "/versions", org, mine)
                .andExpect(jsonPath("$.items[*].data.remarks").isEmpty())
                .andExpect(jsonPath("$.items[0].data.full_name").value("Asha"));
        student.patch(STUDENT, "\"2\"", "{\"data\":{\"remarks\":\"hello\"}}", org, mine)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("field_hidden"))
                .andExpect(jsonPath("$.errors[0].field").value("data.remarks"));
        student.get(STUDENTS + "?filter[category]=general", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_filter"));
        student.get(STUDENTS + "?q=confidential", org).andExpect(jsonPath("$.totalElements").value(0));

        // Read-only fields: changes rejected, unchanged values (and omission in PUT) allowed.
        student.patch(STUDENT, "\"2\"", "{\"data\":{\"cgpa\":\"9.9\"}}", org, mine)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("field_read_only"));
        student.put(STUDENT, "\"2\"", "{\"data\":{\"full_name\":\"Asha K\",\"cgpa\":\"8.5\",\"department\":\"" + cse
                + "\",\"stage\":\"draft\"}}", org, mine)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cgpa").value("8.5"));
        student.put(STUDENT, "\"3\"", "{\"data\":{\"full_name\":\"Asha K.\",\"department\":\"" + cse
                + "\",\"stage\":\"draft\"}}", org, mine)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cgpa").value("8.5"));
        // Full replacement never clears what the caller cannot see.
        owner.get(STUDENT, org, mine)
                .andExpect(jsonPath("$.data.remarks").value("confidential"))
                .andExpect(jsonPath("$.data.category").value("general"));
    }

    @Test
    void attributeScopesFollowMembershipAttributes() throws Exception {
        Caller coordinator = api.newUser();
        addMemberWithRole(owner, org, coordinator, "coordinator", "{\"department\":\"" + cse + "\"}");
        String asha = student(cse, "Asha", "draft");
        String ravi = student(ece, "Ravi", "draft");

        coordinator.get(STUDENTS, org)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(asha));
        coordinator.get(STUDENT, org, ravi).andExpect(status().isNotFound());
        coordinator.patch(STUDENT, "\"1\"", "{\"data\":{\"phone\":\"+91 1234\"}}", org, ravi)
                .andExpect(status().isNotFound());
        // Moving a record out of the caller's scope is refused.
        coordinator.patch(STUDENT, "\"1\"", "{\"data\":{\"department\":\"" + ece + "\"}}", org, asha)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("scope_violation"));
        coordinator.patch(STUDENT, "\"1\"", "{\"data\":{\"phone\":\"+91 1234\"}}", org, asha)
                .andExpect(status().isOk());

        // Array attributes mean "any of".
        owner.patch("/api/v1/orgs/{org}/members/{user}", null, "{\"attributes\":{\"department\":[\"" + cse + "\",\""
                + ece + "\"]}}", org, coordinator.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.department.length()").value(2));
        coordinator.get(STUDENTS, org).andExpect(jsonPath("$.items[*].id", containsInAnyOrder(asha, ravi)));

        // Without the attribute nothing matches (fail closed).
        owner.patch("/api/v1/orgs/{org}/members/{user}", null, "{\"attributes\":{}}", org, coordinator.id())
                .andExpect(status().isOk());
        coordinator.get(STUDENTS, org).andExpect(jsonPath("$.totalElements").value(0));
        coordinator.get(STUDENT, org, asha).andExpect(status().isNotFound());
    }

    @Test
    void idAttributeAndWhereScopes() throws Exception {
        String acme = createRecord(owner, org, "company", "{\"name\":\"Acme\"}");
        String globex = createRecord(owner, org, "company", "{\"name\":\"Globex\"}");
        Caller recruiter = api.newUser();
        addMemberWithRole(owner, org, recruiter, "recruiter", "{\"company\":\"" + acme.toUpperCase() + "\"}");
        String published = student(cse, "Asha", "published");
        String draft = student(cse, "Ravi", "draft");

        recruiter.get("/api/v1/orgs/{org}/modules/company/records", org)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(acme));
        recruiter.get("/api/v1/orgs/{org}/modules/company/records/{id}", org, globex).andExpect(status().isNotFound());

        recruiter.get(STUDENTS, org)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(published))
                .andExpect(jsonPath("$.items[0].data.phone").doesNotExist())
                .andExpect(jsonPath("$.items[0].data.remarks").value("confidential"));
        recruiter.get(STUDENT, org, draft).andExpect(status().isNotFound());
        recruiter.get(STUDENTS + "?deleted=true", org).andExpect(jsonPath("$.totalElements").value(0));
        recruiter.patch(STUDENT, "\"1\"", "{\"data\":{\"full_name\":\"x\"}}", org, published)
                .andExpect(status().isForbidden());
    }

    @Test
    void relatedRecordsRespectTheTargetModuleScope() throws Exception {
        Caller coordinator = api.newUser();
        addMemberWithRole(owner, org, coordinator, "coordinator", "{\"department\":\"" + cse + "\"}");
        String asha = student(cse, "Asha", "draft");
        String ravi = student(ece, "Ravi", "draft");
        owner.post("/api/v1/orgs/{org}/relationships", """
                {"apiKey": "mentees", "label": "Mentees", "sourceModule": "department", "targetModule": "student",
                 "cardinality": "MANY_TO_MANY"}
                """, org).andExpect(status().isCreated());
        link(cse, asha);
        String raviLink = link(cse, ravi);

        String related = "/api/v1/orgs/{org}/modules/department/records/{id}/related/mentees";
        owner.get(related, org, cse).andExpect(jsonPath("$.totalElements").value(2));
        coordinator.get(related, org, cse)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].recordId").value(asha));
        // Linking needs update access to the source (department is read-only for coordinators).
        coordinator.post("/api/v1/orgs/{org}/relationships/mentees/links", "{\"sourceRecordId\":\"" + ece
                + "\",\"targetRecordId\":\"" + asha + "\"}", org).andExpect(status().isForbidden());
        coordinator.delete("/api/v1/orgs/{org}/relationships/mentees/links/{id}", null, org, raviLink)
                .andExpect(status().isForbidden());

        Caller student = api.newUser();
        addMemberWithRole(owner, org, student, "student", null);
        student.get(related, org, cse).andExpect(status().isForbidden());
    }

    @Test
    void myAccessDescribesRoleAttributesAndModuleCapabilities() throws Exception {
        Caller student = api.newUser();
        addMemberWithRole(owner, org, student, "student", "{\"student\":\"s-1\",\"department\":\"" + cse + "\"}");
        student.get("/api/v1/orgs/{org}/me/access", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(org))
                .andExpect(jsonPath("$.membershipRole").value("MEMBER"))
                .andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.role.key").value("student"))
                .andExpect(jsonPath("$.role.label").value("Student"))
                .andExpect(jsonPath("$.attributes.student").value("s-1"))
                .andExpect(jsonPath("$.attributes.department").value(cse))
                .andExpect(jsonPath("$.modules.student.read").value(true))
                .andExpect(jsonPath("$.modules.student.create").value(false))
                .andExpect(jsonPath("$.modules.student.update").value(true))
                .andExpect(jsonPath("$.modules.student.delete").value(false))
                .andExpect(jsonPath("$.modules.student.readScope").value("OWN"))
                .andExpect(jsonPath("$.modules.student.hiddenFields", containsInAnyOrder("category", "remarks")))
                .andExpect(jsonPath("$.modules.student.readOnlyFields", containsInAnyOrder("cgpa")))
                .andExpect(jsonPath("$.modules.department").doesNotExist())
                .andExpect(jsonPath("$.modules.company").doesNotExist());

        owner.get("/api/v1/orgs/{org}/me/access", org)
                .andExpect(jsonPath("$.membershipRole").value("OWNER"))
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.modules.company.delete").value(true))
                .andExpect(jsonPath("$.modules.student.readScope").value("ALL"))
                .andExpect(jsonPath("$.modules.student.hiddenFields").isEmpty());

        Caller plain = api.newUser();
        addMember(owner, org, plain, "MEMBER");
        plain.get("/api/v1/orgs/{org}/me/access", org)
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.modules.student").doesNotExist());
        api.newUser().get("/api/v1/orgs/{org}/me/access", org).andExpect(status().isNotFound());
    }

    private String student(String department, String name, String status) throws Exception {
        return createRecord(owner, org, "student", "{\"full_name\":\"" + name + "\",\"department\":\"" + department
                + "\",\"cgpa\":\"8.5\",\"phone\":\"+91 98765 43210\",\"category\":\"general\","
                + "\"remarks\":\"confidential\",\"stage\":\"" + status + "\"}");
    }

    private String link(String source, String target) throws Exception {
        return read(owner.post("/api/v1/orgs/{org}/relationships/mentees/links", "{\"sourceRecordId\":\"" + source
                + "\",\"targetRecordId\":\"" + target + "\"}", org).andExpect(status().isCreated()).andReturn(), "$.id");
    }

    private TenantContext ownerContext() throws Exception {
        return new TenantContext(UUID.fromString(org), UUID.fromString(owner.id()), MembershipRole.OWNER);
    }
}
