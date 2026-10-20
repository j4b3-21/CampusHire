package com.bm4a.platform.support;

import static com.bm4a.platform.support.TestApi.read;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.web.servlet.MvcResult;

import com.bm4a.platform.support.TestApi.Caller;

/**
 * Shared test data. The module definitions are generic configuration, exactly what a tenant would send; nothing here
 * is a platform entity.
 */
public final class Fixtures {

    /** A module exercising every supported field type. */
    public static final String EXAMPLE_ASSET = """
            {
              "apiKey": "example_asset",
              "labelSingular": "Example Asset",
              "labelPlural": "Example Assets",
              "fields": [
                {"apiKey": "name", "label": "Name", "type": "TEXT", "required": true, "searchable": true, "sortable": true, "filterable": true},
                {"apiKey": "serial", "label": "Serial", "type": "TEXT", "unique": true, "filterable": true},
                {"apiKey": "notes", "label": "Notes", "type": "LONG_TEXT"},
                {"apiKey": "quantity", "label": "Quantity", "type": "INTEGER", "defaultValue": 1, "config": {"min": 0}, "sortable": true, "filterable": true},
                {"apiKey": "weight", "label": "Weight", "type": "DECIMAL", "config": {"scale": 3}},
                {"apiKey": "active", "label": "Active", "type": "BOOLEAN", "defaultValue": true, "filterable": true},
                {"apiKey": "purchased_on", "label": "Purchased on", "type": "DATE", "sortable": true, "filterable": true},
                {"apiKey": "checked_at", "label": "Checked at", "type": "DATETIME"},
                {"apiKey": "owner_email", "label": "Owner e-mail", "type": "EMAIL"},
                {"apiKey": "owner_phone", "label": "Owner phone", "type": "PHONE"},
                {"apiKey": "manual_url", "label": "Manual", "type": "URL"},
                {"apiKey": "price", "label": "Price", "type": "CURRENCY", "config": {"currencies": ["INR", "USD"]}, "sortable": true, "filterable": true},
                {"apiKey": "condition", "label": "Condition", "type": "SINGLE_SELECT", "filterable": true,
                 "config": {"options": [{"value": "new", "label": "New"}, {"value": "used", "label": "Used"}]}},
                {"apiKey": "tags", "label": "Tags", "type": "MULTI_SELECT", "filterable": true,
                 "config": {"options": [{"value": "it", "label": "IT"}, {"value": "office", "label": "Office"}], "maxSelections": 2}},
                {"apiKey": "specs", "label": "Specs", "type": "JSON"},
                {"apiKey": "invoice", "label": "Invoice", "type": "FILE_REFERENCE"},
                {"apiKey": "photo", "label": "Photo", "type": "IMAGE_REFERENCE"},
                {"apiKey": "parent", "label": "Parent asset", "type": "LOOKUP", "filterable": true, "config": {"targetModuleKey": "example_asset"}}
              ]
            }
            """;

    private Fixtures() {
    }

    public static String createOrg(Caller owner, String name) throws Exception {
        MvcResult result = owner.post("/api/v1/orgs", "{\"name\":\"" + name + "\"}")
                .andExpect(status().isCreated())
                .andReturn();
        return read(result, "$.id");
    }

    public static void createModule(Caller admin, String org, String definition) throws Exception {
        admin.post("/api/v1/orgs/{org}/modules", definition, org).andExpect(status().isCreated());
    }

    public static void addMember(Caller owner, String org, Caller member, String role) throws Exception {
        owner.post("/api/v1/orgs/{org}/members", "{\"userId\":\"" + member.id() + "\",\"role\":\"" + role + "\"}", org)
                .andExpect(status().isCreated());
    }

    /** Adds a MEMBER with an org role and attributes (a JSON object, or {@code null}). */
    public static void addMemberWithRole(Caller admin, String org, Caller member, String roleKey, String attributes)
            throws Exception {
        admin.post("/api/v1/orgs/{org}/members", "{\"userId\":\"" + member.id() + "\",\"role\":\"MEMBER\",\"roleKey\":\""
                + roleKey + "\",\"attributes\":" + attributes + "}", org).andExpect(status().isCreated());
    }

    /** Creates an org role from {@code {apiKey, label, permissions}} and returns its ID. */
    public static String createRole(Caller admin, String org, String definition) throws Exception {
        MvcResult result = admin.post("/api/v1/orgs/{org}/roles", definition, org)
                .andExpect(status().isCreated())
                .andReturn();
        return read(result, "$.id");
    }

    /** Creates a record and returns its ID. */
    public static String createRecord(Caller caller, String org, String module, String data) throws Exception {
        MvcResult result = caller.post("/api/v1/orgs/{org}/modules/{module}/records", "{\"data\":" + data + "}", org,
                module).andExpect(status().isCreated()).andReturn();
        return read(result, "$.id");
    }
}
