package com.bm4a.platform.modules.audit;

import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;
import com.bm4a.platform.support.TestJwts;

@IntegrationTest
class AuditIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    TestApi api;
    Caller owner;
    String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        MvcResult created = owner.post("/api/v1/orgs", "{\"name\":\"Audited\"}").andExpect(status().isCreated())
                .andReturn();
        org = read(created, "$.id");
    }

    @Test
    void organizationAndMembershipChangesAreAudited() throws Exception {
        Caller member = api.newUser();
        owner.post("/api/v1/orgs/{id}/members", "{\"userId\":\"" + member.id() + "\",\"role\":\"MEMBER\"}", org)
                .andExpect(status().isCreated());
        owner.patch("/api/v1/orgs/{id}/members/{user}", null, "{\"role\":\"ADMIN\"}", org, member.id())
                .andExpect(status().isOk());
        owner.delete("/api/v1/orgs/{id}/members/{user}", null, org, member.id()).andExpect(status().isNoContent());

        owner.get("/api/v1/orgs/{id}/audit-events", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].action", hasItems("organization.created", "membership.granted",
                        "membership.role_changed", "membership.revoked")))
                .andExpect(jsonPath("$.items[0].action").value("membership.revoked"))
                .andExpect(jsonPath("$.items[0].actorUserId").value(owner.id()))
                .andExpect(jsonPath("$.items[0].resourceId").value(member.id()))
                .andExpect(jsonPath("$.items[0].details.role").value("ADMIN"));

        owner.get("/api/v1/orgs/{id}/audit-events?action=membership.role_changed", org)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].details.from").value("MEMBER"))
                .andExpect(jsonPath("$.items[0].details.to").value("ADMIN"));
    }

    @Test
    void auditEventsCarryTheRequestCorrelationId() throws Exception {
        String correlationId = "audit-corr-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/orgs")
                        .header(HttpHeaders.AUTHORIZATION, TestJwts.newUser().bearer())
                        .header("X-Correlation-Id", correlationId)
                        .contentType("application/json").content("{\"name\":\"Correlated\"}"))
                .andExpect(status().isCreated());

        Long count = jdbc.sql("SELECT count(*) FROM audit_event WHERE correlation_id = :c AND action = 'organization.created'")
                .param("c", correlationId).query(Long.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void onlyAdminsReadAuditAndDenialsAreAudited() throws Exception {
        Caller member = api.newUser();
        owner.post("/api/v1/orgs/{id}/members", "{\"userId\":\"" + member.id() + "\",\"role\":\"MEMBER\"}", org)
                .andExpect(status().isCreated());

        member.get("/api/v1/orgs/{id}/audit-events", org).andExpect(status().isForbidden());
        api.newUser().get("/api/v1/orgs/{id}/audit-events", org).andExpect(status().isNotFound());

        owner.get("/api/v1/orgs/{id}/audit-events?action=security.access_denied", org)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].actorUserId").value(member.id()))
                .andExpect(jsonPath("$.items[0].details.permission").value("AUDIT_VIEW"));
    }

    @Test
    void auditTableIsAppendOnlyAtTheDatabaseLevel() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_event SET action = 'tampered' WHERE organization_id = :o")
                .param("o", UUID.fromString(org)).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event WHERE organization_id = :o")
                .param("o", UUID.fromString(org)).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE audit_event").update()).rootCause().hasMessageContaining("append-only");
    }

    @Test
    void auditEventsOfOtherTenantsAreNotListed() throws Exception {
        Caller other = api.newUser();
        String otherOrg = read(other.post("/api/v1/orgs", "{\"name\":\"Other\"}").andReturn(), "$.id");

        List<String> resourceIds = read(owner.get("/api/v1/orgs/{id}/audit-events?size=100", org).andReturn(),
                "$.items[*].resourceId");
        assertThat(resourceIds).doesNotContain(otherOrg);
    }
}
