package com.bm4a.platform.modules.relationships;

import static com.bm4a.platform.support.Fixtures.addMember;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRecord;
import static com.bm4a.platform.support.TestApi.read;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

/**
 * "Student", "Department" and "Course" are tenant configuration here, not platform classes: the same engine would
 * link Employees to Managers or Orders to Customers.
 */
@IntegrationTest
class RelationshipIntegrationTest {

    static final String REL = "/api/v1/orgs/{org}/relationships";

    @Autowired
    MockMvc mvc;

    TestApi api;
    Caller owner;
    String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        org = createOrg(owner, "Campus");
        createModule(owner, org, module("department"));
        createModule(owner, org, module("course"));
        createModule(owner, org, """
                {"apiKey": "student", "labelSingular": "Student", "labelPlural": "Students", "fields": [
                  {"apiKey": "name", "label": "Name", "type": "TEXT", "required": true},
                  {"apiKey": "department", "label": "Department", "type": "LOOKUP", "config": {"targetModuleKey": "department"}}]}
                """);
        relationship("department_students", "department", "student", "ONE_TO_MANY", "RESTRICT");
        relationship("student_courses", "student", "course", "MANY_TO_MANY", "DETACH");
        relationship("student_mentor", "student", "student", "ONE_TO_ONE", "DETACH");
    }

    @Test
    void lookupFieldsReferenceConfiguredModules() throws Exception {
        String cs = createRecord(owner, org, "department", "{\"name\":\"Computer Science\"}");
        String student = createRecord(owner, org, "student", "{\"name\":\"Asha\",\"department\":\"" + cs + "\"}");
        owner.get("/api/v1/orgs/{org}/modules/student/records/{id}", org, student)
                .andExpect(jsonPath("$.data.department").value(cs));
        String course = createRecord(owner, org, "course", "{\"name\":\"Algorithms\"}");
        owner.post("/api/v1/orgs/{org}/modules/student/records", "{\"data\":{\"name\":\"X\",\"department\":\"" + course + "\"}}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("invalid_reference"));
    }

    @Test
    void cardinalityIsEnforced() throws Exception {
        String cs = createRecord(owner, org, "department", "{\"name\":\"CS\"}");
        String math = createRecord(owner, org, "department", "{\"name\":\"Math\"}");
        String asha = createRecord(owner, org, "student", "{\"name\":\"Asha\"}");
        String ravi = createRecord(owner, org, "student", "{\"name\":\"Ravi\"}");

        link("department_students", cs, asha).andExpect(status().isCreated());
        link("department_students", cs, ravi).andExpect(status().isCreated());
        link("department_students", cs, asha)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("link_exists"));
        link("department_students", math, asha)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("cardinality_violation"));

        String algo = createRecord(owner, org, "course", "{\"name\":\"Algorithms\"}");
        String db = createRecord(owner, org, "course", "{\"name\":\"Databases\"}");
        link("student_courses", asha, algo).andExpect(status().isCreated());
        link("student_courses", asha, db).andExpect(status().isCreated());
        link("student_courses", ravi, algo).andExpect(status().isCreated());

        link("student_mentor", asha, ravi).andExpect(status().isCreated());
        String meera = createRecord(owner, org, "student", "{\"name\":\"Meera\"}");
        link("student_mentor", asha, meera).andExpect(status().isConflict());
        link("student_mentor", meera, ravi).andExpect(status().isConflict());

        owner.get("/api/v1/orgs/{org}/modules/department/records/{id}/related/department_students", org, cs)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].data.name", containsInAnyOrder("Asha", "Ravi")));
        owner.get("/api/v1/orgs/{org}/modules/student/records/{id}/related/department_students", org, asha)
                .andExpect(jsonPath("$.items[0].data.name").value("CS"));
        owner.get("/api/v1/orgs/{org}/modules/course/records/{id}/related/student_courses?size=1", org, algo)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));
        owner.get("/api/v1/orgs/{org}/modules/student/records/{id}/related/student_mentor?direction=incoming", org, ravi)
                .andExpect(jsonPath("$.items[0].data.name").value("Asha"));
        owner.get("/api/v1/orgs/{org}/modules/course/records/{id}/related/department_students", org, algo)
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidTargetsAndForeignTenantsAreRejected() throws Exception {
        String cs = createRecord(owner, org, "department", "{\"name\":\"CS\"}");
        String course = createRecord(owner, org, "course", "{\"name\":\"Algorithms\"}");
        link("department_students", cs, course)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("targetRecordId"));

        Caller other = api.newUser();
        String otherOrg = createOrg(other, "Other");
        createModule(other, otherOrg, module("department"));
        createModule(other, otherOrg, module("student"));
        String foreignStudent = createRecord(other, otherOrg, "student", "{\"name\":\"Foreign\"}");
        link("department_students", cs, foreignStudent)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("invalid_reference"));

        other.post(REL + "/department_students/links", "{\"sourceRecordId\":\"" + cs + "\",\"targetRecordId\":\""
                + foreignStudent + "\"}", otherOrg).andExpect(status().isNotFound());
        other.get(REL, org).andExpect(status().isNotFound());
        owner.post(REL, """
                {"apiKey": "bad", "label": "Bad", "sourceModule": "department", "targetModule": "nope", "cardinality": "ONE_TO_MANY"}
                """, org).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_module"));
        owner.post(REL, """
                {"apiKey": "department_students", "label": "Dup", "sourceModule": "department", "targetModule": "student", "cardinality": "ONE_TO_MANY"}
                """, org).andExpect(status().isConflict());
    }

    @Test
    void deleteBehaviorRestrictOrDetach() throws Exception {
        String cs = createRecord(owner, org, "department", "{\"name\":\"CS\"}");
        String asha = createRecord(owner, org, "student", "{\"name\":\"Asha\"}");
        String algo = createRecord(owner, org, "course", "{\"name\":\"Algorithms\"}");
        String linkId = read(link("department_students", cs, asha).andReturn(), "$.id");
        link("student_courses", asha, algo).andExpect(status().isCreated());

        owner.delete("/api/v1/orgs/{org}/modules/department/records/{id}", "\"1\"", org, cs)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("record_linked"));
        owner.delete(REL + "/department_students/links/{link}", null, org, linkId).andExpect(status().isNoContent());
        owner.delete("/api/v1/orgs/{org}/modules/department/records/{id}", "\"1\"", org, cs).andExpect(status().isOk());

        // DETACH: deleting the course removes its links instead of failing.
        owner.delete("/api/v1/orgs/{org}/modules/course/records/{id}", "\"1\"", org, algo).andExpect(status().isOk());
        owner.get("/api/v1/orgs/{org}/modules/student/records/{id}/related/student_courses", org, asha)
                .andExpect(jsonPath("$.totalElements").value(0));
        // Links to deleted records cannot be created.
        link("student_courses", asha, algo).andExpect(status().isBadRequest());
    }

    @Test
    void membersLinkRecordsButOnlyAdminsDefineRelationships() throws Exception {
        Caller member = api.newUser();
        addMember(owner, org, member, "MEMBER");
        String cs = createRecord(member, org, "department", "{\"name\":\"CS\"}");
        String asha = createRecord(member, org, "student", "{\"name\":\"Asha\"}");
        member.post(REL + "/department_students/links", "{\"sourceRecordId\":\"" + cs + "\",\"targetRecordId\":\""
                + asha + "\"}", org).andExpect(status().isCreated());
        member.post(REL, """
                {"apiKey": "x", "label": "X", "sourceModule": "department", "targetModule": "student", "cardinality": "MANY_TO_MANY"}
                """, org).andExpect(status().isForbidden());

        owner.delete(REL + "/department_students", "\"1\"", org).andExpect(jsonPath("$.status").value("ARCHIVED"));
        String ravi = createRecord(member, org, "student", "{\"name\":\"Ravi\"}");
        member.post(REL + "/department_students/links", "{\"sourceRecordId\":\"" + cs + "\",\"targetRecordId\":\""
                + ravi + "\"}", org).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("relationship_archived"));
    }

    private org.springframework.test.web.servlet.ResultActions link(String relationship, String source, String target)
            throws Exception {
        return owner.post(REL + "/{rel}/links", "{\"sourceRecordId\":\"" + source + "\",\"targetRecordId\":\"" + target
                + "\"}", org, relationship);
    }

    private void relationship(String key, String source, String target, String cardinality, String onDelete)
            throws Exception {
        owner.post(REL, "{\"apiKey\":\"" + key + "\",\"label\":\"" + key + "\",\"sourceModule\":\"" + source
                + "\",\"targetModule\":\"" + target + "\",\"cardinality\":\"" + cardinality + "\",\"onDelete\":\""
                + onDelete + "\"}", org).andExpect(status().isCreated());
    }

    private static String module(String key) {
        return "{\"apiKey\":\"" + key + "\",\"labelSingular\":\"" + key + "\",\"labelPlural\":\"" + key + "s\","
                + "\"fields\":[{\"apiKey\":\"name\",\"label\":\"Name\",\"type\":\"TEXT\",\"required\":true}]}";
    }
}
