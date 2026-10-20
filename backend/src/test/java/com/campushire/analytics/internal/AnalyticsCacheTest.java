package com.campushire.analytics.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class AnalyticsCacheTest {

    record Payload(String name, long count) {
    }

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger loads = new AtomicInteger();
    private AnalyticsCache cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        cache = cache(true);
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    @Test
    void missLoadsAndStoresWithTtl() {
        assertThat(cache.get("k", Payload.class, this::load)).isEqualTo(new Payload("fresh", 1));
        verify(values, timeout(1000)).set(eq("k"),
                argThat(payload -> payload.contains("\"name\":\"fresh\"") && payload.contains("\"count\":1")),
                eq(Duration.ofSeconds(60)));
    }

    @Test
    void hitSkipsTheLoader() {
        when(values.get("k")).thenReturn("{\"name\":\"cached\",\"count\":7}");
        assertThat(cache.get("k", Payload.class, this::load)).isEqualTo(new Payload("cached", 7));
        assertThat(loads).hasValue(0);
    }

    @Test
    void failureBypassesRedisUntilRetryAfter() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(cache.get("k", Payload.class, this::load)).isEqualTo(new Payload("fresh", 1));
        assertThat(cache.get("k", Payload.class, this::load)).isEqualTo(new Payload("fresh", 2));
        verify(values, times(1)).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));

        clock.advance(Duration.ofSeconds(31));
        cache.get("k", Payload.class, this::load);
        verify(values, times(2)).get(anyString());
    }

    @Test
    void slowRedisIsAbandonedAfterTheTimeout() {
        when(values.get("k")).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return null;
        });
        long started = System.nanoTime();
        assertThat(cache.get("k", Payload.class, this::load)).isEqualTo(new Payload("fresh", 1));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void disabledCacheNeverTouchesRedis() {
        cache.close();
        cache = cache(false);
        cache.get("k", Payload.class, this::load);
        verify(redis, never()).opsForValue();
    }

    @Test
    void keysAreScopedByOrganizationAndEndpoint() {
        UUID org = UUID.randomUUID();
        String key = AnalyticsCache.key(org, "overview", "all|batch=");
        assertThat(key).startsWith("campushire:analytics:v1:" + org + ":overview:").hasSize(
                "campushire:analytics:v1:".length() + 36 + ":overview:".length() + 32);
        assertThat(AnalyticsCache.key(org, "overview", "departments:x|batch=")).isNotEqualTo(key);
        assertThat(AnalyticsCache.key(UUID.randomUUID(), "overview", "all|batch=")).isNotEqualTo(key);
    }

    private Payload load() {
        return new Payload("fresh", loads.incrementAndGet());
    }

    private AnalyticsCache cache(boolean enabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("redis", redis));
        AnalyticsProperties properties = new AnalyticsProperties(ZoneId.of("Asia/Kolkata"),
                new AnalyticsProperties.Cache(enabled, Duration.ofSeconds(60), Duration.ofMillis(100),
                        Duration.ofSeconds(30)));
        return new AnalyticsCache(beans.getBeanProvider(StringRedisTemplate.class), JsonMapper.builder().build(),
                properties, clock, new SimpleMeterRegistry());
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-20T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
