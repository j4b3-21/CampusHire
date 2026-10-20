package com.campushire.analytics.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Short-lived Redis cache for analytics responses (ADR-006). Redis is optional: reads are bounded by a timeout, and
 * any failure bypasses the cache for {@code retryAfter} so an unreachable Redis costs one failed attempt per period
 * instead of latency on every request. Writes are asynchronous.
 */
@Component
class AnalyticsCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsCache.class);
    private static final String PREFIX = "campushire:analytics:v1:";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final AnalyticsProperties.Cache config;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong bypassUntil = new AtomicLong(Long.MIN_VALUE);
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    AnalyticsCache(ObjectProvider<StringRedisTemplate> redis, JsonMapper json, AnalyticsProperties properties,
            Clock clock, MeterRegistry meters) {
        this.redis = properties.cache().enabled() ? redis.getIfAvailable() : null;
        this.json = json;
        this.config = properties.cache();
        this.clock = clock;
        this.hits = counter(meters, "hit");
        this.misses = counter(meters, "miss");
        this.errors = counter(meters, "error");
    }

    /** Key for one organization, endpoint and request identity (scope and parameters, hashed to bound the length). */
    static String key(UUID organizationId, String endpoint, String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return PREFIX + organizationId + ":" + endpoint + ":" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    <T> T get(String key, Class<T> type, Supplier<T> loader) {
        if (!available()) {
            return loader.get();
        }
        String cached = read(key);
        if (cached != null) {
            try {
                T value = json.readValue(cached, type);
                hits.increment();
                return value;
            } catch (JacksonException ex) {
                log.debug("Ignoring unreadable analytics cache entry {}", key, ex);
            }
        }
        misses.increment();
        T value = loader.get();
        if (available()) {
            write(key, value);
        }
        return value;
    }

    private boolean available() {
        return redis != null && clock.millis() >= bypassUntil.get();
    }

    private String read(String key) {
        Future<String> result = executor.submit(() -> redis.opsForValue().get(key));
        try {
            return result.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            result.cancel(true);
            failed(ex);
        } catch (ExecutionException ex) {
            failed(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void write(String key, Object value) {
        String payload;
        try {
            payload = json.writeValueAsString(value);
        } catch (JacksonException ex) {
            log.warn("Analytics response of type {} is not cacheable", value.getClass().getSimpleName(), ex);
            return;
        }
        executor.execute(() -> {
            try {
                redis.opsForValue().set(key, payload, config.ttl());
            } catch (RuntimeException ex) {
                failed(ex);
            }
        });
    }

    private void failed(Throwable cause) {
        errors.increment();
        long now = clock.millis();
        long previous = bypassUntil.getAndSet(now + config.retryAfter().toMillis());
        if (previous < now) {
            log.warn("Analytics cache unavailable, bypassing it for {}: {}", config.retryAfter(), cause.toString());
        }
    }

    private static Counter counter(MeterRegistry meters, String result) {
        return Counter.builder("campushire.analytics.cache")
                .description("Analytics cache lookups")
                .tag("result", result)
                .register(meters);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
