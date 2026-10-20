package com.bm4a.platform.modules.packs;

import static com.bm4a.platform.support.Fixtures.addMemberWithRole;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRecord;
import static com.bm4a.platform.support.TestApi.etag;
import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.bm4a.platform.modules.packs.PackInstallReport.Status;
import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Installs the real CampusHire pack (a copy of {@code backend/src/main/resources/campushire/campushire-pack.json})
 * and exercises reinstall, upgrade, validation and concurrency.
 */
@IntegrationTest
class PackInstallerIntegrationTest {

    static final String INSTALL = "/api/v1/orgs/{org}/packs/install";
    static final String TENANT_MODULES = "$[?(@.scope == 'TENANT')]";

    @Autowired
    MockMvc mvc;

    @Autowired
    PackInstaller installer;

    @Autowired
    RecordOperations records;

    TestApi api;
    Caller owner;
    String org;
    String pack;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        org = createOrg(owner, "IIIT College");
        pack = new ClassPathResource("campushire-pack.json").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void installsTheCampusHirePackAndStudentsSeeOnlyTheirOwnRecords() throws Exception {
        owner.post(INSTALL, pack, org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packKey").value("campushire"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("INSTALLED"))
                .andExpect(jsonPath("$.previousVersion").doesNotExist())
                .andExpect(jsonPath("$.created.modules", contains("department", "batch", "student", "company",
                        "contact", "follow_up", "job", "drive", "application", "interview", "offer")))
                .andExpect(jsonPath("$.created.fields", hasItems("student.roll_number", "student.department",
                        "application.stage", "offer.application")))
                .andExpect(jsonPath("$.created.layouts", hasSize(11)))
                .andExpect(jsonPath("$.created.roles", contains("tpo", "coordinator", "student", "recruiter")))
                .andExpect(jsonPath("$.created.workflows", contains("application")))
                .andExpect(jsonPath("$.created.settings", contains("campushire")))
                .andExpect(jsonPath("$.skipped", hasSize(0)));

        owner.get("/api/v1/orgs/{org}/modules", org).andExpect(jsonPath(TENANT_MODULES, hasSize(11)));
        owner.get("/api/v1/orgs/{org}/modules/student", org)
                .andExpect(jsonPath("$.settings.accessMode").value("ROLES"))
                .andExpect(jsonPath("$.fields[0].apiKey").value("roll_number"))
                .andExpect(jsonPath("$.fields[?(@.apiKey == 'department')].type").value("LOOKUP"))
                .andExpect(jsonPath("$.fields[?(@.apiKey == 'department')].config.targetModuleKey")
                        .value("department"))
                .andExpect(jsonPath("$.fields[?(@.apiKey == 'placement_status')].defaultValue").value("unplaced"))
                .andExpect(jsonPath("$.layout.sections[*].key", contains("identity", "academics", "career",
                        "placement")));
        owner.get("/api/v1/orgs/{org}/modules/contact/fields/company", org)
                .andExpect(jsonPath("$.config.onTargetDelete").value("NONE"));
        owner.get("/api/v1/orgs/{org}/roles", org)
                .andExpect(jsonPath("$[*].apiKey", containsInAnyOrder("tpo", "coordinator", "student", "recruiter")));
        owner.get("/api/v1/orgs/{org}/roles/recruiter", org)
                .andExpect(jsonPath("$.permissions.modules.company.read.field").value("$id"));
        owner.get("/api/v1/orgs/{org}/modules/application/workflow", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.field").value("stage"))
                .andExpect(jsonPath("$.definition.initial").value("applied"))
                .andExpect(jsonPath("$.definition.transitions", hasSize(8)));
        owner.get("/api/v1/orgs/{org}", org)
                .andExpect(jsonPath("$.settings.campushire.policy.maxOffersPerStudent").value(1));
        owner.get("/api/v1/orgs/{org}/packs", org)
                .andExpect(jsonPath("$[0].packKey").value("campushire"))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].label").value("CampusHire"))
                .andExpect(jsonPath("$[0].definitionSha256").isString());
        owner.get("/api/v1/orgs/{org}/audit-events?action=pack.installed", org)
                .andExpect(jsonPath("$.items[0].resourceId").value("campushire"));

        Caller student = api.newUser();
        Caller classmate = api.newUser();
        String department = createRecord(owner, org, "department", "{\"name\": \"Computer Science\", \"code\": \"CSE\"}");
        String batch = createRecord(owner, org, "batch", "{\"name\": \"B.Tech 2022-2026\", \"graduation_year\": 2026}");
        TenantContext admin = new TenantContext(UUID.fromString(org), UUID.fromString(owner.id()), MembershipRole.OWNER);
        RecordView own = records.create(admin, "student", json("{\"roll_number\": \"CS22B001\", \"full_name\": "
                + "\"Asha\", \"email\": \"asha@iiit.test\", \"department\": \"" + department + "\", \"batch\": \""
                + batch + "\", \"cgpa\": \"8.10\"}"), UUID.fromString(student.id()));
        RecordView other = records.create(admin, "student", json("{\"roll_number\": \"CS22B002\", \"full_name\": "
                + "\"Ravi\", \"email\": \"ravi@iiit.test\", \"department\": \"" + department + "\", \"batch\": \""
                + batch + "\"}"), UUID.fromString(classmate.id()));
        assertThat(own.data().get("placement_status").asString()).isEqualTo("unplaced");
        addMemberWithRole(owner, org, student, "student", "{\"student\": \"" + own.id() + "\"}");

        student.get("/api/v1/orgs/{org}/modules/student/records", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(own.id().toString()));
        student.get("/api/v1/orgs/{org}/modules/student/records/{id}", org, other.id())
                .andExpect(status().isNotFound());
        student.patch("/api/v1/orgs/{org}/modules/student/records/{id}", "\"1\"",
                "{\"data\": {\"cgpa\": \"9.90\"}}", org, own.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("field_read_only"));
        student.patch("/api/v1/orgs/{org}/modules/student/records/{id}", "\"1\"",
                "{\"data\": {\"skills\": \"Java, SQL\"}}", org, own.id())
                .andExpect(status().isOk());
        student.get("/api/v1/orgs/{org}/modules/department/records", org)
                .andExpect(jsonPath("$.totalElements").value(1));
        student.get("/api/v1/orgs/{org}/modules/company/records", org).andExpect(status().isOk());
        student.get("/api/v1/orgs/{org}/modules/follow_up/records", org).andExpect(status().isForbidden());
    }

    @Test
    void reinstallingTheSameVersionIsANoOp() throws Exception {
        UUID orgId = UUID.fromString(org);
        UUID actor = UUID.fromString(owner.id());
        assertThat(installer.install(orgId, actor, json(pack)).status()).isEqualTo(Status.INSTALLED);

        owner.post(INSTALL, pack, org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP_TO_DATE"))
                .andExpect(jsonPath("$.previousVersion").value(1))
                .andExpect(jsonPath("$.created.modules", hasSize(0)))
                .andExpect(jsonPath("$.created.fields", hasSize(0)))
                .andExpect(jsonPath("$.skipped", hasSize(0)));
        ObjectNode relabeled = (ObjectNode) json(pack);
        relabeled.put("label", "CampusHire (edited)");
        PackInstallReport report = installer.install(orgId, actor, relabeled);
        assertThat(report.status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(report.skipped()).containsExactly(
                new PackInstallReport.Skipped("pack", "campushire", "changed_without_version_bump"));
        owner.get("/api/v1/orgs/{org}/modules", org).andExpect(jsonPath(TENANT_MODULES, hasSize(11)));
    }

    @Test
    void higherVersionsAddDefinitionsWithoutTouchingExistingOnes() throws Exception {
        UUID orgId = UUID.fromString(org);
        UUID actor = UUID.fromString(owner.id());
        installer.install(orgId, actor, json(pack));
        String batchVersion = etag(owner.get("/api/v1/orgs/{org}/modules/batch", org).andReturn());
        owner.put("/api/v1/orgs/{org}/modules/batch/layout", batchVersion,
                "{\"sections\": [{\"key\": \"mine\", \"label\": \"Mine\", \"fields\": [\"name\"]}]}", org)
                .andExpect(status().isOk());
        owner.put("/api/v1/orgs/{org}/roles/student", "\"1\"",
                "{\"label\": \"Student (custom)\", \"permissions\": {\"modules\": {\"student\": {\"read\": \"OWN\"}}}}",
                org).andExpect(status().isOk());

        ObjectNode upgraded = (ObjectNode) json(pack);
        upgraded.put("version", 2);
        ArrayNode modules = (ArrayNode) upgraded.get("modules");
        ObjectNode batch = (ObjectNode) modules.get(1);
        ((ArrayNode) batch.get("fields").get(1).get("config").get("options"))
                .add(json("{\"value\": \"bsc\", \"label\": \"B.Sc\"}"));
        ObjectNode student = (ObjectNode) modules.get(2);
        ((ArrayNode) student.get("fields")).add(json("{\"apiKey\": \"hostel\", \"label\": \"Hostel\", \"type\": \"TEXT\"}"));
        modules.add(json("""
                {"apiKey": "notice", "labelSingular": "Notice", "labelPlural": "Notices",
                 "settings": {"accessMode": "ROLES"},
                 "fields": [{"apiKey": "batch", "label": "Batch", "type": "LOOKUP", "config": {"targetModuleKey": "batch"}},
                            {"apiKey": "title", "label": "Title", "type": "TEXT", "required": true}],
                 "layout": {"sections": [{"key": "main", "label": "Notice", "fields": ["title", "batch"]}]}}
                """));
        ((ArrayNode) upgraded.get("roles")).add(json("""
                {"apiKey": "alumni", "label": "Alumni", "permissions": {"modules": {"notice": {"read": "ALL"}}}}
                """));
        upgraded.putArray("relationships").add(json("""
                {"apiKey": "mentors", "label": "Mentors", "inverseLabel": "Mentees", "sourceModule": "student",
                 "targetModule": "contact", "cardinality": "MANY_TO_MANY", "onDelete": "DETACH"}
                """));
        ObjectNode policy = (ObjectNode) upgraded.get("settings").get("campushire").get("policy");
        policy.put("maxOffersPerStudent", 2);
        policy.put("allowOffCampus", true);

        PackInstallReport report = installer.install(orgId, actor, upgraded);
        assertThat(report.status()).isEqualTo(Status.UPGRADED);
        assertThat(report.previousVersion()).isEqualTo(1);
        assertThat(report.created().modules()).containsExactly("notice");
        assertThat(report.created().fields()).containsExactly("notice.title", "student.hostel", "notice.batch");
        assertThat(report.created().options()).containsExactly("batch.program:bsc");
        assertThat(report.created().layouts()).containsExactly("notice");
        assertThat(report.created().roles()).containsExactly("alumni");
        assertThat(report.created().relationships()).containsExactly("mentors");
        assertThat(report.created().workflows()).isEmpty();
        assertThat(report.created().settings()).containsExactly("campushire.policy.allowOffCampus");
        assertThat(report.skipped()).contains(new PackInstallReport.Skipped("role", "student", "exists"),
                new PackInstallReport.Skipped("workflow", "application", "exists"));

        owner.get("/api/v1/orgs/{org}/modules/batch", org)
                .andExpect(jsonPath("$.layout.sections[0].key").value("mine"))
                .andExpect(jsonPath("$.fields[?(@.apiKey == 'program')].config.options[*].value",
                        hasItem("bsc")));
        owner.get("/api/v1/orgs/{org}/roles/student", org)
                .andExpect(jsonPath("$.label").value("Student (custom)"));
        owner.get("/api/v1/orgs/{org}/modules/student/fields/hostel", org).andExpect(status().isOk());
        owner.get("/api/v1/orgs/{org}/modules/notice/fields/batch", org)
                .andExpect(jsonPath("$.config.targetModuleKey").value("batch"));
        owner.get("/api/v1/orgs/{org}", org)
                .andExpect(jsonPath("$.settings.campushire.policy.maxOffersPerStudent").value(1))
                .andExpect(jsonPath("$.settings.campushire.policy.allowOffCampus").value(true));
        owner.get("/api/v1/orgs/{org}/packs", org).andExpect(jsonPath("$[0].version").value(2));
        owner.get("/api/v1/orgs/{org}/relationships/mentors", org)
                .andExpect(jsonPath("$.sourceModule").value("student"))
                .andExpect(jsonPath("$.cardinality").value("MANY_TO_MANY"))
                .andExpect(jsonPath("$.onDelete").value("DETACH"));

        owner.post(INSTALL, pack, org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("pack_downgrade"));
    }

    @Test
    void concurrentInstallsCreateEverythingOnce() throws Exception {
        UUID orgId = UUID.fromString(org);
        UUID actor = UUID.fromString(owner.id());
        int installs = 4;
        ExecutorService executor = Executors.newFixedThreadPool(installs);
        try {
            List<Future<PackInstallReport>> results = new ArrayList<>();
            for (int i = 0; i < installs; i++) {
                Callable<PackInstallReport> install = () -> installer.install(orgId, actor, json(pack));
                results.add(executor.submit(install));
            }
            List<Status> statuses = new ArrayList<>();
            for (Future<PackInstallReport> result : results) {
                statuses.add(result.get().status());
            }
            assertThat(statuses).containsOnlyOnce(Status.INSTALLED);
            assertThat(statuses).filteredOn(status -> status == Status.UP_TO_DATE).hasSize(installs - 1);
        } finally {
            executor.shutdownNow();
        }
        owner.get("/api/v1/orgs/{org}/modules", org).andExpect(jsonPath(TENANT_MODULES, hasSize(11)));
        owner.get("/api/v1/orgs/{org}/roles", org).andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void packsAreValidatedStrictlyAndFailuresChangeNothing() throws Exception {
        ObjectNode unknownProperty = (ObjectNode) json(pack);
        ((ObjectNode) unknownProperty.get("modules").get(2).get("fields").get(3)).put("bogus", true);
        owner.post(INSTALL, unknownProperty.toString(), org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("modules[2].fields[3].bogus"))
                .andExpect(jsonPath("$.errors[0].code").value("unknown_property"));

        ObjectNode unknownType = (ObjectNode) json(pack);
        ((ObjectNode) unknownType.get("modules").get(0).get("fields").get(0)).put("type", "COLOR");
        owner.post(INSTALL, unknownType.toString(), org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("modules[0].fields[0].type"));

        ObjectNode badShape = (ObjectNode) json(pack);
        badShape.put("key", "Campus Hire");
        badShape.remove("version");
        ((ArrayNode) badShape.get("modules")).add(badShape.get("modules").get(0).deepCopy());
        MvcResult shape = owner.post(INSTALL, badShape.toString(), org).andExpect(status().isBadRequest()).andReturn();
        List<String> fields = read(shape, "$.errors[*].field");
        assertThat(fields).contains("key", "version", "modules[11].apiKey");

        ObjectNode badRole = (ObjectNode) json(pack);
        ((ObjectNode) badRole.get("roles").get(1).get("permissions").get("modules")).put("nope", "ALL");
        owner.post(INSTALL, badRole.toString(), org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("roles[1].permissions.modules.nope"));

        ObjectNode badRelationship = (ObjectNode) json(pack);
        badRelationship.putArray("relationships").add(json("""
                {"apiKey": "mentors", "label": "Mentors", "sourceModule": "student", "targetModule": "contact",
                 "cardinality": "MANY"}
                """));
        owner.post(INSTALL, badRelationship.toString(), org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("relationships[0].cardinality"));

        ObjectNode badLookup = (ObjectNode) json(pack);
        ((ObjectNode) badLookup.get("modules").get(2).get("fields").get(6).get("config"))
                .put("targetModuleKey", "nowhere");
        owner.post(INSTALL, badLookup.toString(), org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("modules[2].fields[6].config.targetModuleKey"))
                .andExpect(jsonPath("$.detail").value("modules[2].fields[6]: Request validation failed"));

        owner.get("/api/v1/orgs/{org}/modules", org).andExpect(jsonPath(TENANT_MODULES, hasSize(0)));
        owner.get("/api/v1/orgs/{org}/roles", org).andExpect(jsonPath("$", hasSize(0)));
        owner.get("/api/v1/orgs/{org}/packs", org).andExpect(jsonPath("$", hasSize(0)));

        owner.post(INSTALL, "{\"key\": ", org).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_request"));
        owner.post(INSTALL, "{\"key\": \"big\", \"description\": \"" + "x".repeat(600 * 1024) + "\"}", org)
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("payload_too_large"));
        Caller member = api.newUser();
        owner.post("/api/v1/orgs/{org}/members", "{\"userId\": \"" + member.id() + "\", \"role\": \"MEMBER\"}", org)
                .andExpect(status().isCreated());
        member.post(INSTALL, pack, org).andExpect(status().isForbidden());
        member.get("/api/v1/orgs/{org}/packs", org).andExpect(status().isOk());
        api.newUser().post(INSTALL, pack, org).andExpect(status().isNotFound());

        UUID actor = UUID.fromString(owner.id());
        assertThatThrownBy(() -> installer.install(UUID.randomUUID(), actor, json(pack)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("not_found"));
    }

    private static JsonNode json(String value) {
        return JsonMapper.shared().readTree(value);
    }
}
