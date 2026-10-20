package com.bm4a.platform.modules.ratelimit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicInteger redisCalls = new AtomicInteger();

    private final StringRedisTemplate unavailableRedis = new StringRedisTemplate() {
        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            redisCalls.incrementAndGet();
            throw new RedisConnectionFailureException("Unable to connect to Redis");
        }
    };

    private final RateLimitFilter filter = new RateLimitFilter(
            new RateLimitProperties(true, 1, Duration.ofSeconds(60)), unavailableRedis, meters,
            JsonMapper.builder().build(), Clock.systemUTC());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() throws Exception {
        authenticate("user-1");

        for (int i = 0; i < 3; i++) {
            AtomicBoolean passed = new AtomicBoolean();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/orgs"), response,
                    (req, res) -> passed.set(true));

            assertThat(passed).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }
        assertThat(meters.counter("bm4a.ratelimit.errors").count()).isEqualTo(3);
        assertThat(meters.counter("bm4a.ratelimit.rejected").count()).isZero();
    }

    @Test
    void ignoresReadsAndUnauthenticatedRequests() throws Exception {
        authenticate("user-2");
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), new MockHttpServletResponse(),
                (req, res) -> { });
        filter.doFilter(new MockHttpServletRequest("POST", "/actuator/health"), new MockHttpServletResponse(),
                (req, res) -> { });

        SecurityContextHolder.clearContext();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/orgs"), new MockHttpServletResponse(),
                (req, res) -> { });

        assertThat(redisCalls).hasValue(0);
    }

    private static void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer("https://issuer.test/realms/bm4a")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
