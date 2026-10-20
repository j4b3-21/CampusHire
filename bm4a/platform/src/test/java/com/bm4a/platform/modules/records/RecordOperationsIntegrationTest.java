package com.bm4a.platform.modules.records;

import static com.bm4a.platform.support.Fixtures.addMemberWithRole;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRole;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;
import com.bm4a.platform.support.TestRecordWriteGuard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Trusted record operations for application code and the write-guard extension point.
 */
@IntegrationTest
class RecordOperationsIntegrationTest {

    static final String MODULE = """
            {"apiKey": "guarded_item", "labelSingular": "Item", "labelPlural": "Items", "fields": [
              {"apiKey": "title", "label": "Title", "type": "TEXT", "required": true},
              {"apiKey": "code", "label": "Code", "type": "TEXT", "unique": true, "filterable": true},
              {"apiKey": "tags", "label": "Tags", "type": "MULTI_SELECT", "filterable": true,
               "config": {"options": [{"value": "a", "label": "A"}, {"value": "b", "label": "B"}]}}]}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordOperations operations;

    @Autowired
    TestRecordWriteGuard guard;

    TestApi api;
    Caller owner;
    Caller member;
    UUID org;
    TenantContext tenant;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        member = api.newUser();
        org = UUID.fromString(createOrg(owner, "Operations"));
        createModule(owner, org.toString(), MODULE);
        tenant = new TenantContext(org, UUID.fromString(owner.id()), MembershipRole.OWNER);
    }

    @Test
    void createPatchFindAndListWithTheRecordApiRules() throws Exception {
        RecordView first = operations.create(tenant, "guarded_item", json("{\"title\":\"First\",\"code\":\" C-1 \"}"), null);
        assertThat(first.ownerUserId()).isEqualTo(tenant.userId());
        RecordView second = operations.create(tenant, "guarded_item",
                json("{\"title\":\"Second\",\"code\":\"C-2\",\"tags\":[\"b\",\"a\"]}"), UUID.fromString(member.id()));
        assertThat(second.ownerUserId()).isEqualTo(UUID.fromString(member.id()));

        assertThat(operations.findActiveByField(org, "guarded_item", "code", JsonNodeFactory.instance.stringNode("C-1")))
                .map(RecordView::id).contains(first.id());
        assertThat(operations.findActiveByField(org, "guarded_item", "tags", json("[\"a\"]")))
                .map(RecordView::id).contains(second.id());
        assertThat(operations.findActiveByField(org, "guarded_item", "code", JsonNodeFactory.instance.stringNode("X")))
                .isEmpty();
        assertThatThrownBy(() -> operations.findActiveByField(org, "guarded_item", "nope",
                JsonNodeFactory.instance.stringNode("X"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> operations.findActiveByField(org, "missing", "code", json("\"C-1\"")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void patchTransferOwnerAndDelete() throws Exception {
        RecordView created = operations.create(tenant, "guarded_item", json("{\"title\":\"A\",\"code\":\"P-1\"}"), null);
        RecordView patched = operations.patch(tenant, "guarded_item", created.id(), null, json("{\"title\":\"B\"}"));
        assertThat(patched.version()).isEqualTo(2);
        assertThat(patched.data().get("code").asString()).isEqualTo("P-1");
        assertThatThrownBy(() -> operations.patch(tenant, "guarded_item", created.id(), 1L, json("{\"title\":\"C\"}")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
        assertThatThrownBy(() -> operations.create(tenant, "guarded_item", json("{\"title\":\"D\",\"code\":\"p-1\"}"),
                null)).isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("unique_violation"));

        UUID newOwner = UUID.fromString(member.id());
        RecordView transferred = operations.transferOwner(tenant, "guarded_item", created.id(), newOwner);
        assertThat(transferred.ownerUserId()).isEqualTo(newOwner);
        assertThat(transferred.version()).isEqualTo(3);
        assertThat(operations.list(tenant, "guarded_item", Map.of(), null, null, RecordScope.own(newOwner),
                PageQuery.of(0, 10)).items()).extracting(RecordView::id).containsExactly(created.id());
        assertThat(operations.list(tenant, "guarded_item", Map.of("filter[code]", List.of("P-1")), null, null,
                RecordScope.all(), PageQuery.of(0, 10)).totalElements()).isEqualTo(1);
        assertThatThrownBy(() -> operations.transferOwner(tenant, "guarded_item", created.id(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
        owner.get("/api/v1/orgs/{org}/audit-events?resourceId={id}", org, created.id())
                .andExpect(jsonPath("$.items[*].action", hasItem("record.owner_transferred")));

        operations.delete(tenant, "guarded_item", created.id(), null);
        assertThat(operations.get(tenant, "guarded_item", created.id())).isEmpty();
        assertThatThrownBy(() -> operations.get(tenant, "missing", created.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void writeGuardsSeeEveryWriteAndCanVetoIt() throws Exception {
        createRole(owner, org.toString(), """
                {"apiKey": "editor", "label": "Editor", "permissions": {"modules": {"guarded_item": {
                  "read": "ALL", "create": true, "update": "ALL"}}}}
                """);
        addMemberWithRole(owner, org.toString(), member, "editor", null);

        RecordView trusted = operations.create(tenant, "guarded_item", json("{\"title\":\"T\"}"), null);
        member.post("/api/v1/orgs/{org}/modules/guarded_item/records", "{\"data\":{\"title\":\"Via API\"}}", org)
                .andExpect(status().isCreated());
        member.patch("/api/v1/orgs/{org}/modules/guarded_item/records/{id}", "\"1\"",
                "{\"data\":{\"title\":\"" + TestRecordWriteGuard.VETO + "\"}}", org, trusted.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("vetoed"));
        assertThatThrownBy(() -> operations.create(tenant, "guarded_item",
                json("{\"title\":\"" + TestRecordWriteGuard.VETO + "\"}"), null)).isInstanceOf(ApiException.class);
        member.get("/api/v1/orgs/{org}/modules/guarded_item/records?filter[code][exists]=false", org)
                .andExpect(jsonPath("$.totalElements").value(2));

        List<RecordWrite> writes = guard.writes(org);
        assertThat(writes).hasSize(4);
        assertThat(writes.get(0).trusted()).isTrue();
        assertThat(writes.get(0).creating()).isTrue();
        assertThat(writes.get(0).admin()).isTrue();
        assertThat(writes.get(1).trusted()).isFalse();
        assertThat(writes.get(1).actorRoleKey()).isEqualTo("editor");
        assertThat(writes.get(1).admin()).isFalse();
        assertThat(writes.get(2).before().id()).isEqualTo(trusted.id());
        assertThat(writes.get(2).changedFields()).containsExactly("title");
    }

    private static JsonNode json(String value) {
        return JsonMapper.shared().readTree(value);
    }
}
