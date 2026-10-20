package com.bm4a.platform.modules.records;

import static com.bm4a.platform.support.Fixtures.EXAMPLE_ASSET;
import static com.bm4a.platform.support.Fixtures.addMember;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRecord;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

/**
 * Acceptance tests of the generic record engine against the ExampleAsset module, which is created at runtime through
 * the metadata API: no Java class or table exists for it.
 */
@IntegrationTest
class RecordIntegrationTest {

    static final String RECORDS = "/api/v1/orgs/{org}/modules/example_asset/records";
    static final String RECORD = RECORDS + "/{id}";

    @Autowired
    MockMvc mvc;

    TestApi api;
    Caller owner;
    String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        org = createOrg(owner, "Records");
        createModule(owner, org, EXAMPLE_ASSET);
    }

    @Test
    void createReadUpdateListWithCanonicalValuesAndDefaults() throws Exception {
        String id = createRecord(owner, org, "example_asset", """
                {"name": " Laptop ", "serial": "SN-1", "weight": "1.250", "price": {"amount": 1200.5, "currency": "usd"},
                 "checked_at": "2026-10-20T11:45:00+05:30", "purchased_on": "2026-01-15", "condition": "new",
                 "tags": ["office", "it"], "specs": {"ram": 16}, "photo": {"fileId": "img-1", "contentType": "image/png"}}
                """);

        owner.get(RECORD, org, id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.module").value("example_asset"))
                .andExpect(jsonPath("$.data.name").value("Laptop"))
                .andExpect(jsonPath("$.data.quantity").value(1))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.weight").value("1.25"))
                .andExpect(jsonPath("$.data.price.amount").value("1200.50"))
                .andExpect(jsonPath("$.data.price.currency").value("USD"))
                .andExpect(jsonPath("$.data.checked_at").value("2026-10-20T06:15:00Z"))
                .andExpect(jsonPath("$.data.tags[0]").value("it"))
                .andExpect(jsonPath("$.createdBy").value(owner.id()));

        owner.patch(RECORD, "\"1\"", "{\"data\":{\"quantity\":5,\"specs\":null}}", org, id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.data.quantity").value(5))
                .andExpect(jsonPath("$.data.specs").doesNotExist())
                .andExpect(jsonPath("$.data.serial").value("SN-1"));

        owner.put(RECORD, "\"2\"", "{\"data\":{\"name\":\"Laptop 2\"}}", org, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.data.name").value("Laptop 2"))
                .andExpect(jsonPath("$.data.serial").doesNotExist())
                .andExpect(jsonPath("$.data.quantity").doesNotExist());

        // No-op updates do not create versions.
        owner.patch(RECORD, "\"3\"", "{\"data\":{\"name\":\"Laptop 2\"}}", org, id)
                .andExpect(header().string("ETag", "\"3\""));

        owner.get(RECORDS, org).andExpect(jsonPath("$.totalElements").value(1));
        owner.get("/api/v1/orgs/{org}/audit-events?resourceId={id}", org, id)
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].action").value("record.updated"))
                .andExpect(jsonPath("$.items[0].details.changes.name.to").value("Laptop 2"));
    }

    @Test
    void invalidPayloadsFailWithFieldLevelErrors() throws Exception {
        owner.post(RECORDS, """
                {"data": {"quantity": -1, "condition": "broken", "owner_email": "nope", "price": {"amount": "1", "currency": "EUR"},
                          "tags": ["it", "it"], "unknown": 1, "id": "x", "manual_url": "ftp://x"}}
                """, org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("data.quantity", "data.condition",
                        "data.owner_email", "data.price", "data.tags", "data.unknown", "data.id", "data.manual_url",
                        "data.name")))
                .andExpect(jsonPath("$.errors[?(@.field=='data.name')].code").value("required"))
                .andExpect(jsonPath("$.errors[?(@.field=='data.unknown')].code").value("unknown_field"));

        // System-managed properties cannot be mass-assigned.
        owner.post(RECORDS, "{\"data\":{\"name\":\"x\"},\"organizationId\":\"" + UUID.randomUUID() + "\"}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_property"));
        owner.post(RECORDS, "{\"data\":{\"name\":\"x\"},\"createdBy\":\"" + UUID.randomUUID() + "\"}", org)
                .andExpect(status().isBadRequest());
        owner.post(RECORDS, "{\"data\":[1]}", org).andExpect(status().isBadRequest());
        owner.post(RECORDS, "{}", org).andExpect(status().isBadRequest());
        owner.get(RECORDS, org).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void optimisticConcurrencyPreventsLostUpdates() throws Exception {
        String id = createRecord(owner, org, "example_asset", "{\"name\":\"A\"}");
        owner.put(RECORD, null, "{\"data\":{\"name\":\"B\"}}", org, id).andExpect(status().isPreconditionRequired());
        owner.patch(RECORD, "\"1\"", "{\"data\":{\"name\":\"B\"}}", org, id).andExpect(status().isOk());
        owner.patch(RECORD, "\"1\"", "{\"data\":{\"name\":\"C\"}}", org, id)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("version_conflict"));
        owner.delete(RECORD, "\"1\"", org, id).andExpect(status().isPreconditionFailed());
        owner.get(RECORD, org, id).andExpect(jsonPath("$.data.name").value("B"));
    }

    @Test
    void softDeleteRestoreAndHistory() throws Exception {
        String id = createRecord(owner, org, "example_asset", "{\"name\":\"Desk\",\"serial\":\"D-1\"}");
        owner.patch(RECORD, "\"1\"", "{\"data\":{\"name\":\"Desk v2\"}}", org, id).andExpect(status().isOk());

        owner.delete(RECORD, "\"2\"", org, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.deletedAt").isNotEmpty());
        owner.get(RECORD, org, id).andExpect(status().isNotFound());
        owner.get(RECORDS, org).andExpect(jsonPath("$.totalElements").value(0));
        owner.get(RECORDS + "?deleted=true", org).andExpect(jsonPath("$.items[0].id").value(id));
        owner.patch(RECORD, "\"3\"", "{\"data\":{\"name\":\"x\"}}", org, id).andExpect(status().isNotFound());

        owner.postIfMatch(RECORD + "/restore", "\"3\"", org, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());
        owner.postIfMatch(RECORD + "/restore", "\"4\"", org, id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_deleted"));

        owner.get(RECORD + "/versions", org, id)
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.items[*].changeType", containsInAnyOrder("RESTORED", "DELETED", "UPDATED", "CREATED")));
        owner.get(RECORD + "/versions/1", org, id).andExpect(jsonPath("$.data.name").value("Desk"));

        owner.postIfMatch(RECORD + "/versions/1/restore", "\"4\"", org, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.data.name").value("Desk"));
        owner.get(RECORD + "/versions/5", org, id).andExpect(jsonPath("$.changeType").value("REVERTED"));
    }

    @Test
    void crossTenantRecordAccessIsDenied() throws Exception {
        String id = createRecord(owner, org, "example_asset", "{\"name\":\"Secret\",\"serial\":\"S-1\"}");
        Caller intruder = api.newUser();
        String otherOrg = createOrg(intruder, "Intruder");
        createModule(intruder, otherOrg, EXAMPLE_ASSET);

        intruder.get(RECORD, org, id).andExpect(status().isNotFound());
        intruder.get(RECORD, otherOrg, id).andExpect(status().isNotFound());
        intruder.put(RECORD, "\"1\"", "{\"data\":{\"name\":\"pwned\"}}", otherOrg, id).andExpect(status().isNotFound());
        intruder.delete(RECORD, "\"1\"", otherOrg, id).andExpect(status().isNotFound());
        intruder.get(RECORD + "/versions", otherOrg, id).andExpect(status().isNotFound());
        intruder.get(RECORDS, otherOrg).andExpect(jsonPath("$.totalElements").value(0));
        // Unique values are per tenant, so the intruder learns nothing from them either.
        createRecord(intruder, otherOrg, "example_asset", "{\"name\":\"Mine\",\"serial\":\"S-1\"}");
        // Lookups cannot point into another tenant.
        intruder.post(RECORDS, "{\"data\":{\"name\":\"Child\",\"parent\":\"" + id + "\"}}", otherOrg)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("invalid_reference"));

        owner.get(RECORD, org, id).andExpect(jsonPath("$.data.name").value("Secret"));
    }

    @Test
    void recordPermissionsFollowMembership() throws Exception {
        Caller member = api.newUser();
        addMember(owner, org, member, "MEMBER");
        String id = createRecord(member, org, "example_asset", "{\"name\":\"By member\"}");
        member.get(RECORD, org, id).andExpect(status().isOk());

        owner.delete("/api/v1/orgs/{org}/members/{user}", null, org, member.id()).andExpect(status().isNoContent());
        member.get(RECORD, org, id).andExpect(status().isNotFound());
        member.post(RECORDS, "{\"data\":{\"name\":\"After revocation\"}}", org).andExpect(status().isNotFound());
    }

    @Test
    void filteringSortingAndSearchAreAllowListed() throws Exception {
        createRecord(owner, org, "example_asset", "{\"name\":\"Laptop A\",\"quantity\":3,\"condition\":\"used\",\"tags\":[\"it\"],\"price\":{\"amount\":\"900\",\"currency\":\"INR\"},\"purchased_on\":\"2026-03-01\"}");
        createRecord(owner, org, "example_asset", "{\"name\":\"Laptop B\",\"quantity\":10,\"condition\":\"new\",\"tags\":[\"it\",\"office\"],\"price\":{\"amount\":\"1500\",\"currency\":\"INR\"},\"purchased_on\":\"2026-05-01\"}");
        createRecord(owner, org, "example_asset", "{\"name\":\"Chair\",\"quantity\":1,\"condition\":\"used\",\"tags\":[\"office\"],\"active\":false}");

        owner.get(RECORDS + "?filter[quantity][gte]=3&sort=-quantity", org)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].data.name").value("Laptop B"));
        owner.get(RECORDS + "?filter[condition]=used&sort=name", org)
                .andExpect(jsonPath("$.items[*].data.name", containsInAnyOrder("Chair", "Laptop A")))
                .andExpect(jsonPath("$.items[0].data.name").value("Chair"));
        owner.get(RECORDS + "?filter[condition][in]=used,new", org).andExpect(jsonPath("$.totalElements").value(3));
        owner.get(RECORDS + "?filter[tags][contains]=office", org).andExpect(jsonPath("$.totalElements").value(2));
        owner.get(RECORDS + "?filter[active]=false", org).andExpect(jsonPath("$.items[0].data.name").value("Chair"));
        owner.get(RECORDS + "?filter[price][gt]=1000", org).andExpect(jsonPath("$.items[0].data.name").value("Laptop B"));
        owner.get(RECORDS + "?filter[purchased_on][lt]=2026-04-01", org).andExpect(jsonPath("$.totalElements").value(1));
        owner.get(RECORDS + "?filter[serial][exists]=false", org).andExpect(jsonPath("$.totalElements").value(3));
        owner.get(RECORDS + "?q=laptop&sort=-price", org)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].data.name").value("Laptop B"));
        owner.get(RECORDS + "?size=2&sort=name", org)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        // Injection attempts are just values; unknown fields/operators are rejected before any SQL is built.
        owner.get(RECORDS + "?filter[name]=x' OR '1'='1", org).andExpect(jsonPath("$.totalElements").value(0));
        owner.get(RECORDS + "?filter[name][contains]=%25", org).andExpect(jsonPath("$.totalElements").value(0));
        owner.get(RECORDS + "?filter[name;drop table record]=x", org).andExpect(status().isBadRequest());
        owner.get(RECORDS + "?filter[notes]=x", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_filter"));
        owner.get(RECORDS + "?filter[name][gt]=x", org).andExpect(status().isBadRequest());
        owner.get(RECORDS + "?sort=notes", org).andExpect(jsonPath("$.code").value("invalid_sort"));
        owner.get(RECORDS + "?size=500", org).andExpect(status().isBadRequest());
    }

    @Test
    void uniqueValuesAreEnforcedPerTenantAndReleasedOnDelete() throws Exception {
        String first = createRecord(owner, org, "example_asset", "{\"name\":\"A\",\"serial\":\"ABC-1\"}");
        owner.post(RECORDS, "{\"data\":{\"name\":\"B\",\"serial\":\"abc-1\"}}", org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("unique_violation"))
                .andExpect(jsonPath("$.errors[0].field").value("data.serial"));

        owner.delete(RECORD, "\"1\"", org, first).andExpect(status().isOk());
        createRecord(owner, org, "example_asset", "{\"name\":\"B\",\"serial\":\"ABC-1\"}");
        owner.postIfMatch(RECORD + "/restore", "\"2\"", org, first).andExpect(status().isConflict());
    }

    @Test
    void lookupsAreValidatedAndRestrictDeletion() throws Exception {
        String parent = createRecord(owner, org, "example_asset", "{\"name\":\"Rack\"}");
        owner.post(RECORDS, "{\"data\":{\"name\":\"Orphan\",\"parent\":\"" + UUID.randomUUID() + "\"}}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("invalid_reference"));
        String child = createRecord(owner, org, "example_asset", "{\"name\":\"Server\",\"parent\":\"" + parent + "\"}");

        owner.get(RECORDS + "?filter[parent]=" + parent, org).andExpect(jsonPath("$.items[0].id").value(child));
        owner.delete(RECORD, "\"1\"", org, parent)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("record_referenced"));
        owner.patch(RECORD, "\"1\"", "{\"data\":{\"parent\":null}}", org, child).andExpect(status().isOk());
        owner.delete(RECORD, "\"1\"", org, parent).andExpect(status().isOk());
    }

    @Test
    void fieldLifecycleNeverDestroysStoredValues() throws Exception {
        String id = createRecord(owner, org, "example_asset", "{\"name\":\"Printer\",\"notes\":\"keep me\",\"condition\":\"used\"}");
        String fields = "/api/v1/orgs/{org}/modules/example_asset/fields/";

        owner.postIfMatch(fields + "notes/archive", "\"1\"", org).andExpect(status().isOk());
        owner.get(RECORD, org, id).andExpect(jsonPath("$.data.notes").doesNotExist());
        owner.patch(RECORD, "\"1\"", "{\"data\":{\"notes\":\"new\"}}", org, id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_field"));
        owner.put(RECORD, "\"1\"", "{\"data\":{\"name\":\"Printer 2\",\"condition\":\"used\"}}", org, id)
                .andExpect(status().isOk());
        owner.postIfMatch(fields + "notes/activate", "\"2\"", org).andExpect(status().isOk());
        owner.get(RECORD, org, id).andExpect(jsonPath("$.data.notes").value("keep me"));

        owner.postIfMatch(fields + "condition/deprecate", "\"1\"", org).andExpect(status().isOk());
        owner.patch(RECORD, "\"2\"", "{\"data\":{\"condition\":\"new\"}}", org, id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("field_deprecated"));
        owner.put(RECORD, "\"2\"", "{\"data\":{\"name\":\"Printer 3\"}}", org, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.condition").value("used"));
    }

    @Test
    void readOnlyAndSensitiveFields() throws Exception {
        owner.post("/api/v1/orgs/{org}/modules/example_asset/fields", """
                {"apiKey": "source", "label": "Source", "type": "TEXT", "readOnly": true, "defaultValue": "import"}
                """, org).andExpect(status().isCreated());
        owner.post("/api/v1/orgs/{org}/modules/example_asset/fields", """
                {"apiKey": "pin", "label": "PIN", "type": "TEXT", "sensitive": true}
                """, org).andExpect(status().isCreated());

        owner.post(RECORDS, "{\"data\":{\"name\":\"X\",\"source\":\"manual\"}}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("read_only"));
        String id = createRecord(owner, org, "example_asset", "{\"name\":\"X\",\"pin\":\"1234\"}");
        owner.get(RECORD, org, id).andExpect(jsonPath("$.data.source").value("import"));
        owner.patch(RECORD, "\"1\"", "{\"data\":{\"source\":\"import\",\"pin\":\"9999\"}}", org, id)
                .andExpect(status().isOk());
        owner.get("/api/v1/orgs/{org}/audit-events?resourceId={id}&action=record.updated", org, id)
                .andExpect(jsonPath("$.items[0].details.changes.pin.from").value("[REDACTED]"))
                .andExpect(jsonPath("$.items[0].details.changes.pin.to").value("[REDACTED]"));
    }

    @Test
    void oversizedRecordsAreRejected() throws Exception {
        for (String key : new String[] { "essay1", "essay2", "essay3" }) {
            owner.post("/api/v1/orgs/{org}/modules/example_asset/fields", "{\"apiKey\":\"" + key
                    + "\",\"label\":\"" + key + "\",\"type\":\"LONG_TEXT\",\"config\":{\"maxLength\":100000}}", org)
                    .andExpect(status().isCreated());
        }
        String essay = "e".repeat(99_000);
        owner.post(RECORDS, "{\"data\":{\"name\":\"Fits\",\"essay1\":\"" + essay + "\",\"essay2\":\"" + essay
                + "\"}}", org).andExpect(status().isCreated());
        owner.post(RECORDS, "{\"data\":{\"name\":\"Too big\",\"essay1\":\"" + essay + "\",\"essay2\":\"" + essay
                + "\",\"essay3\":\"" + essay + "\"}}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("payload_too_large"));
    }

    @Test
    void archivedModulesAreReadOnly() throws Exception {
        String id = createRecord(owner, org, "example_asset", "{\"name\":\"Old\"}");
        owner.delete("/api/v1/orgs/{org}/modules/example_asset", "\"1\"", org).andExpect(status().isOk());
        owner.get(RECORD, org, id).andExpect(status().isOk());
        owner.post(RECORDS, "{\"data\":{\"name\":\"New\"}}", org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("module_archived"));
        owner.get("/api/v1/orgs/{org}/modules?includeArchived=true", org)
                .andExpect(jsonPath("$[*].status", hasItem("ARCHIVED")));
    }
}
