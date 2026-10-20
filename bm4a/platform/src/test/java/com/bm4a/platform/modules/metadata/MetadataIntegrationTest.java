package com.bm4a.platform.modules.metadata;

import static com.bm4a.platform.support.TestApi.etag;
import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.bm4a.platform.support.Fixtures.EXAMPLE_ASSET;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

@IntegrationTest
class MetadataIntegrationTest {

    @Autowired
    MockMvc mvc;

    TestApi api;
    Caller owner;
    String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        org = read(owner.post("/api/v1/orgs", "{\"name\":\"Meta\"}").andReturn(), "$.id");
    }

    @Test
    void tenantDefinesModuleWithEveryFieldTypeWithoutCode() throws Exception {
        owner.post("/api/v1/orgs/{org}/modules", EXAMPLE_ASSET, org)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.scope").value("TENANT"))
                .andExpect(jsonPath("$.fields.length()").value(18))
                .andExpect(jsonPath("$.fields[?(@.apiKey=='quantity')].defaultValue").value(1));

        owner.get("/api/v1/orgs/{org}/modules", org).andExpect(jsonPath("$[*].apiKey", hasItem("example_asset")));
        owner.get("/api/v1/orgs/{org}/modules/example_asset/fields/price", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.currencies[1]").value("USD"));
        owner.get("/api/v1/orgs/{org}/modules/example_asset/versions", org)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].snapshot.fields.length()").value(18));
        owner.get("/api/v1/orgs/{org}/audit-events?action=metadata.module.created", org)
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void invalidDefinitionsAreRejectedWithFieldErrors() throws Exception {
        owner.post("/api/v1/orgs/{org}/modules", """
                {"apiKey": "Bad Key", "labelSingular": "", "labelPlural": "X"}
                """, org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("apiKey")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("labelSingular")));

        owner.post("/api/v1/orgs/{org}/modules", """
                {"apiKey": "thing", "labelSingular": "Thing", "labelPlural": "Things", "fields": [
                  {"apiKey": "id", "label": "Id", "type": "TEXT"}]}
                """, org).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("reserved"));

        owner.post("/api/v1/orgs/{org}/modules", """
                {"apiKey": "thing", "labelSingular": "Thing", "labelPlural": "Things", "fields": [
                  {"apiKey": "notes", "label": "Notes", "type": "LONG_TEXT", "unique": true, "sortable": true},
                  {"apiKey": "kind", "label": "Kind", "type": "SINGLE_SELECT"}]}
                """, org).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("fields[0].unique")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fields[0].sortable")));

        owner.post("/api/v1/orgs/{org}/modules", """
                {"apiKey": "thing", "labelSingular": "Thing", "labelPlural": "Things", "fields": [
                  {"apiKey": "kind", "label": "Kind", "type": "SINGLE_SELECT", "config": {"options": []}}]}
                """, org).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fields[0].config.options"));

        owner.post("/api/v1/orgs/{org}/modules", """
                {"apiKey": "thing", "labelSingular": "Thing", "labelPlural": "Things", "fields": [
                  {"apiKey": "ref", "label": "Ref", "type": "LOOKUP", "config": {"targetModuleKey": "missing"}},
                  {"apiKey": "qty", "label": "Qty", "type": "INTEGER", "defaultValue": "ten"},
                  {"apiKey": "flag", "label": "Flag", "type": "BOOLEAN", "config": {"maxLength": 3}},
                  {"apiKey": "odd", "label": "Odd", "type": "TEXT", "config": {"evaluate": "1+1"}}]}
                """, org).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code", hasItem("unknown_module")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fields[1].defaultValue")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fields[2].config.maxLength")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fields[3].config")));

        owner.get("/api/v1/orgs/{org}/modules/thing", org).andExpect(status().isNotFound());
    }

    @Test
    void apiKeysAreUniquePerScopeAndTenantsAreIsolated() throws Exception {
        owner.post("/api/v1/orgs/{org}/modules", EXAMPLE_ASSET, org).andExpect(status().isCreated());
        owner.post("/api/v1/orgs/{org}/modules", EXAMPLE_ASSET, org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("module_exists"));
        owner.post("/api/v1/orgs/{org}/modules/example_asset/fields", "{\"apiKey\":\"name\",\"label\":\"N\",\"type\":\"TEXT\"}", org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("field_exists"));

        Caller other = api.newUser();
        String otherOrg = read(other.post("/api/v1/orgs", "{\"name\":\"Other\"}").andReturn(), "$.id");
        other.get("/api/v1/orgs/{org}/modules/example_asset", otherOrg).andExpect(status().isNotFound());
        other.get("/api/v1/orgs/{org}/modules", otherOrg).andExpect(jsonPath("$[*].apiKey", not(hasItem("example_asset"))));
        other.get("/api/v1/orgs/{org}/modules/example_asset", org).andExpect(status().isNotFound());
        // The same key is independently available in another tenant.
        other.post("/api/v1/orgs/{org}/modules", EXAMPLE_ASSET, otherOrg).andExpect(status().isCreated());
    }

    @Test
    void membersReadDefinitionsButOnlyAdminsChangeThem() throws Exception {
        owner.post("/api/v1/orgs/{org}/modules", EXAMPLE_ASSET, org).andExpect(status().isCreated());
        Caller member = api.newUser();
        owner.post("/api/v1/orgs/{org}/members", "{\"userId\":\"" + member.id() + "\",\"role\":\"MEMBER\"}", org)
                .andExpect(status().isCreated());

        member.get("/api/v1/orgs/{org}/modules/example_asset", org).andExpect(status().isOk());
        member.post("/api/v1/orgs/{org}/modules", EXAMPLE_ASSET.replace("example_asset", "other_asset"), org)
                .andExpect(status().isForbidden());
        member.post("/api/v1/orgs/{org}/modules/example_asset/fields", "{\"apiKey\":\"x\",\"label\":\"X\",\"type\":\"TEXT\"}", org)
                .andExpect(status().isForbidden());
    }

    @Test
    void fieldEvolutionNeverInvalidatesStoredData() throws Exception {
        owner.post("/api/v1/orgs/{org}/modules", EXAMPLE_ASSET, org).andExpect(status().isCreated());
        String base = "/api/v1/orgs/{org}/modules/example_asset/fields/";

        owner.patch(base + "name", null, "{\"label\":\"Title\"}", org).andExpect(status().isPreconditionRequired());
        owner.patch(base + "name", "\"9\"", "{\"label\":\"Title\"}", org).andExpect(status().isPreconditionFailed());
        owner.patch(base + "name", "\"1\"", "{\"label\":\"Title\",\"type\":\"LONG_TEXT\",\"sortable\":false}", org)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.type").value("LONG_TEXT"));

        owner.patch(base + "quantity", "\"1\"", "{\"type\":\"BOOLEAN\"}", org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("incompatible_type_change"));
        owner.patch(base + "quantity", "\"1\"", "{\"type\":\"DECIMAL\"}", org).andExpect(status().isOk());
        owner.patch(base + "serial", "\"1\"", "{\"unique\":false}", org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("unique_immutable"));
        owner.patch(base + "serial", "\"1\"", "{\"apiKey\":\"serial_no\"}", org).andExpect(status().isBadRequest());
        owner.patch(base + "parent", "\"1\"", "{\"config\":{\"targetModuleKey\":\"other\"}}", org)
                .andExpect(status().isBadRequest());

        owner.patch(base + "condition", "\"1\"", """
                {"config": {"options": [{"value": "new", "label": "New"}]}}
                """, org).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("option_removed"));
        owner.patch(base + "condition", "\"1\"", """
                {"config": {"options": [{"value": "new", "label": "New"}, {"value": "used", "label": "Used", "deprecated": true},
                                        {"value": "refurbished", "label": "Refurbished"}]}}
                """, org).andExpect(status().isOk())
                .andExpect(jsonPath("$.config.options[1].deprecated").value(true));

        owner.postIfMatch(base + "notes/archive", "\"1\"", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        owner.postIfMatch(base + "notes/activate", "\"2\"", org).andExpect(jsonPath("$.status").value("ACTIVE"));

        MvcResult module = owner.get("/api/v1/orgs/{org}/modules/example_asset", org).andReturn();
        assertThat(etag(module)).isEqualTo("\"6\"");
        owner.get("/api/v1/orgs/{org}/modules/example_asset/versions/{v}", org, 1)
                .andExpect(jsonPath("$.snapshot.fields[?(@.apiKey=='name')].type").value("TEXT"));
    }

    @Test
    void layoutReferencesExistingFieldsOnce() throws Exception {
        owner.post("/api/v1/orgs/{org}/modules", EXAMPLE_ASSET, org).andExpect(status().isCreated());
        owner.put("/api/v1/orgs/{org}/modules/example_asset/layout", "\"1\"", """
                {"sections": [{"key": "general", "label": "General", "tab": "Details", "columns": 2, "fields": ["name", "serial"]},
                              {"key": "commercial", "label": "Commercial", "fields": ["price", "name"]}]}
                """, org).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("duplicate"));
        owner.put("/api/v1/orgs/{org}/modules/example_asset/layout", "\"1\"", """
                {"sections": [{"key": "general", "label": "General", "fields": ["nope"]}]}
                """, org).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("unknown_field"));
        owner.put("/api/v1/orgs/{org}/modules/example_asset/layout", "\"1\"", """
                {"sections": [{"key": "general", "label": "General", "tab": "Details", "columns": 2, "fields": ["name", "serial"]},
                              {"key": "commercial", "label": "Commercial", "fields": ["price"]}]}
                """, org).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.layout.sections[1].columns").value(1));
    }

    @Test
    void platformGlobalModulesAreReadOnlyForTenants() throws Exception {
        Caller admin = api.newPlatformAdmin();
        String key = "global_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String body = "{\"apiKey\":\"" + key + "\",\"labelSingular\":\"Country\",\"labelPlural\":\"Countries\","
                + "\"fields\":[{\"apiKey\":\"code\",\"label\":\"Code\",\"type\":\"TEXT\",\"unique\":true}]}";

        owner.post("/api/v1/platform/modules", body).andExpect(status().isForbidden());
        admin.post("/api/v1/platform/modules", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("GLOBAL"));

        owner.get("/api/v1/orgs/{org}/modules/{key}", org, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("GLOBAL"));
        owner.patch("/api/v1/orgs/{org}/modules/{key}", "\"1\"", "{\"labelSingular\":\"Hacked\"}", org, key)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("global_module_readonly"));
        owner.post("/api/v1/orgs/{org}/modules", body, org)
                .andExpect(status().isConflict());
        // Tenants may reference global modules from their own lookup fields.
        owner.post("/api/v1/orgs/{org}/modules", "{\"apiKey\":\"office\",\"labelSingular\":\"Office\",\"labelPlural\":\"Offices\","
                + "\"fields\":[{\"apiKey\":\"country\",\"label\":\"Country\",\"type\":\"LOOKUP\",\"config\":{\"targetModuleKey\":\""
                + key + "\"}}]}", org).andExpect(status().isCreated());
    }
}
