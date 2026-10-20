package com.campushire.dashboard.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.shared.resilience.CircuitBreaker;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Short-lived Redis cache for dashboard payloads. Redis is never required: without a template, with a TTL of zero or
 * while Redis fails (then skipped for {@link #OPEN_FOR}), payloads are computed on every request.
 */
@Component
class DashboardCache {

    static final Duration OPEN_FOR = Duration.ofSeconds(30);
    private static final String PREFIX = "campushire:dashboard:";
    private static final Logger log = LoggerFactory.getLogger(DashboardCache.class);

    private final ObjectProvider<StringRedisTemplate> redis;
    private final JsonMapper jsonMapper;
    private final Duration ttl;
    private final CircuitBreaker breaker;

    DashboardCache(ObjectProvider<StringRedisTemplate> redis, JsonMapper jsonMapper,
            @Value("${campushire.dashboard.cache-ttl:60s}") Duration ttl, Clock clock) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.ttl = ttl;
        this.breaker = new CircuitBreaker(OPEN_FOR, clock);
    }

    JsonNode get(String key, Supplier<JsonNode> compute) {
        StringRedisTemplate template = ttl.isZero() || ttl.isNegative() || !breaker.allowRequest() ? null
                : redis.getIfAvailable();
        if (template == null) {
            return compute.get();
        }
        try {
            String cached = template.opsForValue().get(PREFIX + key);
            if (cached != null) {
                succeeded();
                return jsonMapper.readTree(cached);
            }
        } catch (RuntimeException ex) {
            failed(ex);
            return compute.get();
        }
        JsonNode value = compute.get();
        try {
            template.opsForValue().set(PREFIX + key, jsonMapper.writeValueAsString(value), ttl);
            succeeded();
        } catch (RuntimeException ex) {
            failed(ex);
        }
        return value;
    }

    private void succeeded() {
        if (breaker.recordSuccess()) {
            log.info("Dashboard cache available again");
        }
    }

    private void failed(RuntimeException ex) {
        if (breaker.recordFailure()) {
            log.warn("Dashboard cache unavailable, computing dashboards without it for {}: {}", OPEN_FOR,
                    ex.toString());
        }
    }
}
