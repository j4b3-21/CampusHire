package com.bm4a.platform.modules.tenancy;

import static com.bm4a.platform.support.TestApi.etag;
import static com.bm4a.platform.support.TestApi.read;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

@IntegrationTest
class TenancyIntegrationTest {

    @Autowired
    MockMvc mvc;

    TestApi api;
    Caller alice;
    Caller bob;
    String orgA;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        alice = api.newUser();
        bob = api.newUser();
        orgA = createOrg(alice, "Org A");
    }

    @Test
    void creatorBecomesOwnerAndSeesOrganization() throws Exception {
        alice.get("/api/v1/orgs/{id}", orgA)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.name").value("Org A"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        alice.get("/api/v1/orgs").andExpect(jsonPath("$[*].id", hasItem(orgA)));
    }

    @Test
    void nonMemberCannotSeeOrTouchAnotherTenant() throws Exception {
        String orgB = createOrg(bob, "Org B");

        bob.get("/api/v1/orgs/{id}", orgA).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
        bob.patch("/api/v1/orgs/{id}", "\"1\"", "{\"name\":\"pwned\"}", orgA).andExpect(status().isNotFound());
        bob.get("/api/v1/orgs/{id}/members", orgA).andExpect(status().isNotFound());
        bob.post("/api/v1/orgs/{id}/members", "{\"userId\":\"" + bob.id() + "\",\"role\":\"OWNER\"}", orgA)
                .andExpect(status().isNotFound());
        bob.get("/api/v1/orgs").andExpect(jsonPath("$[*].id", not(hasItem(orgA))));

        // A non-existent organization looks exactly like a foreign one.
        bob.get("/api/v1/orgs/{id}", "00000000-0000-0000-0000-000000000001").andExpect(status().isNotFound());
        // Bob's own organization still works.
        bob.get("/api/v1/orgs/{id}", orgB).andExpect(status().isOk());
    }

    @Test
    void tenantContextCannotBeForgedThroughHeadersOrBody() throws Exception {
        String orgB = createOrg(bob, "Org B");
        HttpHeaders forged = new HttpHeaders();
        forged.add("X-Organization-Id", orgA);
        forged.add("X-Tenant-Id", orgA);
        bob.getWithHeaders("/api/v1/orgs/{id}", forged, orgA).andExpect(status().isNotFound());
        bob.getWithHeaders("/api/v1/orgs/{id}/members", forged, orgA).andExpect(status().isNotFound());

        bob.patch("/api/v1/orgs/{id}", "\"1\"", "{\"name\":\"x\",\"organizationId\":\"" + orgA + "\"}", orgB)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_property"));
    }

    @Test
    void memberPermissionsAreEnforcedAndRevocationIsImmediate() throws Exception {
        alice.post("/api/v1/orgs/{id}/members", "{\"userId\":\"" + bob.id() + "\",\"role\":\"MEMBER\"}", orgA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("MEMBER"));

        bob.get("/api/v1/orgs/{id}", orgA).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MEMBER"));
        bob.patch("/api/v1/orgs/{id}", "\"1\"", "{\"name\":\"renamed\"}", orgA)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access_denied"));
        Caller carol = api.newUser();
        bob.post("/api/v1/orgs/{id}/members", "{\"userId\":\"" + carol.id() + "\",\"role\":\"MEMBER\"}", orgA)
                .andExpect(status().isForbidden());

        alice.delete("/api/v1/orgs/{id}/members/{user}", null, orgA, bob.id()).andExpect(status().isNoContent());
        bob.get("/api/v1/orgs/{id}", orgA).andExpect(status().isNotFound());
        bob.get("/api/v1/orgs").andExpect(jsonPath("$[*].id", not(hasItem(orgA))));
    }

    @Test
    void roleRulesAndLastOwnerProtection() throws Exception {
        Caller carol = api.newUser();
        alice.post("/api/v1/orgs/{id}/members", "{\"userId\":\"" + bob.id() + "\",\"role\":\"ADMIN\"}", orgA)
                .andExpect(status().isCreated());

        // Admins manage members but cannot grant admin/owner or change roles.
        bob.post("/api/v1/orgs/{id}/members", "{\"userId\":\"" + carol.id() + "\",\"role\":\"ADMIN\"}", orgA)
                .andExpect(status().isForbidden());
        bob.post("/api/v1/orgs/{id}/members", "{\"userId\":\"" + carol.id() + "\",\"role\":\"MEMBER\"}", orgA)
                .andExpect(status().isCreated());
        bob.post("/api/v1/orgs/{id}/members", "{\"userId\":\"" + carol.id() + "\",\"role\":\"MEMBER\"}", orgA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_member"));
        bob.patch("/api/v1/orgs/{id}/members/{user}", null, "{\"role\":\"ADMIN\"}", orgA, carol.id())
                .andExpect(status().isForbidden());
        bob.delete("/api/v1/orgs/{id}/members/{user}", null, orgA, alice.id()).andExpect(status().isForbidden());

        // The last owner can neither leave nor be demoted.
        alice.delete("/api/v1/orgs/{id}/members/{user}", null, orgA, alice.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("last_owner"));
        alice.patch("/api/v1/orgs/{id}/members/{user}", null, "{\"role\":\"MEMBER\"}", orgA, alice.id())
                .andExpect(status().isConflict());

        // After promoting a second owner, the first may step down; role changes reach OpenFGA immediately.
        alice.patch("/api/v1/orgs/{id}/members/{user}", null, "{\"role\":\"OWNER\"}", orgA, bob.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
        alice.patch("/api/v1/orgs/{id}/members/{user}", null, "{\"role\":\"MEMBER\"}", orgA, alice.id())
                .andExpect(status().isOk());
        alice.patch("/api/v1/orgs/{id}", "\"1\"", "{\"name\":\"x\"}", orgA).andExpect(status().isForbidden());

        // Members may leave on their own.
        carol.delete("/api/v1/orgs/{id}/members/{user}", null, orgA, carol.id()).andExpect(status().isNoContent());
        carol.get("/api/v1/orgs/{id}", orgA).andExpect(status().isNotFound());
        bob.get("/api/v1/orgs/{id}/members", orgA).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void updatesRequireCurrentVersion() throws Exception {
        alice.patch("/api/v1/orgs/{id}", null, "{\"name\":\"Renamed\"}", orgA)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("precondition_required"));
        alice.patch("/api/v1/orgs/{id}", "\"7\"", "{\"name\":\"Renamed\"}", orgA)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("version_conflict"));
        MvcResult updated = alice.patch("/api/v1/orgs/{id}", "\"1\"",
                        "{\"name\":\"Renamed\",\"settings\":{\"timeZone\":\"Asia/Kolkata\"}}", orgA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.settings.timeZone").value("Asia/Kolkata"))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(etag(updated)).isEqualTo("\"2\"");
        alice.patch("/api/v1/orgs/{id}", "\"1\"", "{\"name\":\"Again\"}", orgA).andExpect(status().isPreconditionFailed());
        alice.patch("/api/v1/orgs/{id}", "\"2\"", "{\"settings\":[1]}", orgA).andExpect(status().isBadRequest());
    }

    @Test
    void suspendedAndArchivedOrganizationsRejectAccess() throws Exception {
        Caller admin = api.newPlatformAdmin();
        alice.postIfMatch("/api/v1/platform/orgs/{id}/suspend", null, orgA).andExpect(status().isForbidden());
        admin.postIfMatch("/api/v1/platform/orgs/{id}/suspend", null, orgA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        alice.get("/api/v1/orgs/{id}", orgA)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("organization_inactive"));
        // Platform admins have no implicit tenant access.
        admin.get("/api/v1/orgs/{id}", orgA).andExpect(status().isNotFound());

        admin.postIfMatch("/api/v1/platform/orgs/{id}/reactivate", null, orgA).andExpect(status().isOk());
        String version = etag(alice.get("/api/v1/orgs/{id}", orgA).andExpect(status().isOk()).andReturn());

        alice.delete("/api/v1/orgs/{id}", version, orgA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        alice.get("/api/v1/orgs/{id}/members", orgA).andExpect(status().isForbidden());
    }

    @Test
    void addingUnknownUserFails() throws Exception {
        alice.post("/api/v1/orgs/{id}/members", "{\"userId\":\"00000000-0000-0000-0000-000000000009\",\"role\":\"MEMBER\"}",
                orgA).andExpect(status().isNotFound());
        alice.post("/api/v1/orgs/{id}/members", "{\"role\":\"MEMBER\"}", orgA)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("userId"));
        alice.post("/api/v1/orgs", "{\"name\":\"  \"}").andExpect(status().isBadRequest());
    }

    static String createOrg(Caller owner, String name) throws Exception {
        MvcResult result = owner.post("/api/v1/orgs", "{\"name\":\"" + name + "\"}")
                .andExpect(status().isCreated())
                .andReturn();
        return read(result, "$.id");
    }
}
