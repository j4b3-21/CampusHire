package com.bm4a.platform.modules.metadata;

import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRecord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import com.bm4a.platform.support.RedisMailContainers;
import com.bm4a.platform.support.RedisMailIntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

/**
 * Module schemas cached in Redis (ADR-007): hits, eviction on every definition change (tenant and platform-global),
 * and transparent fallback to PostgreSQL while Redis is unresponsive.
 */
@RedisMailIntegrationTest
class ModuleSchemaCacheIntegrationTest {

    static final String MODULE = """
            {"apiKey": "cached_item", "labelSingular": "Item", "labelPlural": "Items",
             "fields": [{"apiKey": "name", "label": "Name", "type": "TEXT", "required": true}]}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    ModuleSchemas schemas;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    @Qualifier("redisContainer")
    GenericContainer<?> redisContainer;

    TestApi api;
    Caller owner;
    String org;
    UUID orgId;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        org = createOrg(owner, "Cached");
        orgId = UUID.fromString(org);
        createModule(owner, org, MODULE);
    }

    @Test
    void theNextRecordRequestSeesAnAddedField() throws Exception {
        createRecord(owner, org, "cached_item", "{\"name\": \"First\"}");
        assertThat(cachedEntries()).anyMatch(key -> key.endsWith(":k:cached_item"));

        owner.post("/api/v1/orgs/{org}/modules/cached_item/fields",
                "{\"apiKey\": \"size\", \"label\": \"Size\", \"type\": \"INTEGER\"}", org)
                .andExpect(status().isCreated());
        owner.post("/api/v1/orgs/{org}/modules/cached_item/records", "{\"data\": {\"name\": \"Second\", \"size\": 3}}",
                org).andExpect(status().isCreated()).andExpect(jsonPath("$.data.size").value(3));

        owner.postIfMatch("/api/v1/orgs/{org}/modules/cached_item/fields/size/archive", "\"1\"", org)
                .andExpect(status().isOk());
        owner.post("/api/v1/orgs/{org}/modules/cached_item/records", "{\"data\": {\"name\": \"Third\", \"size\": 4}}",
                org).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_field"));
    }

    @Test
    void lookupsAreServedFromRedisUntilADefinitionChange() throws Exception {
        ModuleSchema first = schemas.find(orgId, "cached_item").orElseThrow();
        assertThat(schemas.find(orgId, "cached_item")).contains(first);
        assertThat(schemas.findById(orgId, first.id())).contains(first);
        assertThat(schemas.listActive(orgId)).contains(first);

        // Changed behind the platform's back: only a definition change through the API evicts.
        jdbc.sql("UPDATE module_definition SET label_singular = 'Edited' WHERE id = :id").param("id", first.id())
                .update();
        assertThat(schemas.find(orgId, "cached_item").orElseThrow().labelSingular()).isEqualTo("Item");
        assertThat(schemas.findById(orgId, first.id()).orElseThrow().labelSingular()).isEqualTo("Item");

        owner.patch("/api/v1/orgs/{org}/modules/cached_item", "\"" + first.version() + "\"",
                "{\"labelPlural\": \"Things\"}", org).andExpect(status().isOk());
        ModuleSchema changed = schemas.find(orgId, "cached_item").orElseThrow();
        assertThat(changed.version()).isEqualTo(first.version() + 1);
        assertThat(changed.labelSingular()).isEqualTo("Edited");
        assertThat(changed.labelPlural()).isEqualTo("Things");
        assertThat(schemas.findById(orgId, first.id())).contains(changed);
        assertThat(schemas.listActive(orgId)).contains(changed).doesNotContain(first);
    }

    @Test
    void platformGlobalModuleChangesEvictEveryTenant() throws Exception {
        String key = "glob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(schemas.listActive(orgId)).extracting(ModuleSchema::apiKey).contains("cached_item")
                .doesNotContain(key);
        api.newPlatformAdmin().post("/api/v1/platform/modules", """
                {"apiKey": "%s", "labelSingular": "Global", "labelPlural": "Globals",
                 "fields": [{"apiKey": "code", "label": "Code", "type": "TEXT"}]}
                """.formatted(key)).andExpect(status().isCreated());
        assertThat(schemas.listActive(orgId)).extracting(ModuleSchema::apiKey).contains("cached_item", key);
        assertThat(schemas.find(orgId, key)).isPresent();
    }

    @Test
    void redisOutageFallsBackToPostgresWithoutStaleSchemasAfterwards() throws Exception {
        ModuleSchema cached = schemas.find(orgId, "cached_item").orElseThrow();
        RedisMailContainers.pause(redisContainer);
        try {
            long started = System.nanoTime();
            assertThat(schemas.find(orgId, "cached_item")).contains(cached);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));

            started = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                assertThat(schemas.find(orgId, "cached_item")).contains(cached);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));

            owner.post("/api/v1/orgs/{org}/modules/cached_item/fields",
                    "{\"apiKey\": \"color\", \"label\": \"Color\", \"type\": \"TEXT\"}", org)
                    .andExpect(status().isCreated());
            assertThat(schemas.find(orgId, "cached_item").orElseThrow().field("color")).isPresent();
        } finally {
            RedisMailContainers.unpause(redisContainer);
        }
        Thread.sleep(1_200);
        assertThat(schemas.find(orgId, "cached_item").orElseThrow().field("color")).isPresent();
        assertThat(schemas.find(orgId, "cached_item").orElseThrow().field("color")).isPresent();
        owner.post("/api/v1/orgs/{org}/modules/cached_item/records", "{\"data\": {\"name\": \"x\", \"color\": \"red\"}}",
                org).andExpect(status().isCreated());
    }

    private Set<String> cachedEntries() {
        return redis.keys("bm4a:md:v1:s:" + org + ":*");
    }
}
