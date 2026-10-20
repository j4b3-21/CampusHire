package com.bm4a.platform.modules.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestSecurityConfiguration;
import com.bm4a.platform.support.TestcontainersConfiguration;

/**
 * Own context (the shared test profile disables rate limiting) with a real Redis. The window is widened to one hour
 * so a minute boundary can never reset the counter mid-test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, TestSecurityConfiguration.class,
        RateLimitIntegrationTest.RedisContainerConfiguration.class })
@TestPropertySource(properties = { "bm4a.ratelimit.enabled=true", "bm4a.ratelimit.writes-per-minute=3",
        "bm4a.ratelimit.window=1h" })
class RateLimitIntegrationTest {

    private static final String CREATE_ORG = "{\"name\":\"x\"}";

    @Autowired
    MockMvc mvc;

    @Test
    void limitsWritesPerIdentityAndLeavesReadsAndOtherUsersAlone() throws Exception {
        TestApi api = new TestApi(mvc);
        TestApi.Caller alice = api.newUser();

        for (String remaining : new String[] { "2", "1", "0" }) {
            alice.post("/api/v1/orgs", CREATE_ORG)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", remaining));
        }

        MvcResult rejected = alice.post("/api/v1/orgs", CREATE_ORG)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("rate_limited"))
                .andExpect(jsonPath("$.status").value(429))
                .andReturn();
        assertThat(rejected.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(Long.parseLong(rejected.getResponse().getHeader(HttpHeaders.RETRY_AFTER)))
                .isBetween(1L, 3600L);

        api.newUser().post("/api/v1/orgs", CREATE_ORG)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-RateLimit-Remaining", "2"));

        alice.get("/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-RateLimit-Limit"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisContainerConfiguration {

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>(DockerImageName.parse("redis:8.2")).withExposedPorts(6379);
        }
    }
}
