package com.bm4a.platform.modules.metadata.internal;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.ModuleAccessMode;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.resilience.CircuitBreaker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

/**
 * Redis cache of {@link ModuleSchema} lookups (ADR-007). PostgreSQL stays the source of truth: misses, Redis errors
 * and an open circuit breaker all read the database.
 * <p>
 * Entry keys embed two generation tokens, one per organization and one for platform-global modules (visible to every
 * tenant). A definition change replaces the token after commit, which makes every older entry of that scope
 * unreachable at once (it then expires through its TTL). Readers fetch the tokens before reading the database, so an
 * entry built from data that a concurrent change is replacing is stored under the old token and never served after
 * the change. Lookups inside a transaction that changed definitions bypass the cache, so uncommitted definitions are
 * never cached. Token replacements that fail because Redis is unreachable are retried before the next cache use.
 */
@Component
@EnableConfigurationProperties(SchemaCacheProperties.class)
class SchemaCache {

    private static final String PREFIX = "bm4a:md:v1:";
    private static final String GLOBAL_GENERATION = PREFIX + "gen:global";
    private static final Object CHANGED_IN_TRANSACTION = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(SchemaCache.class);
    private static final TypeReference<List<CachedModule>> MODULE_LIST = new TypeReference<>() {
    };

    /** Returns {orgGeneration, globalGeneration, entry}; the entry is only read when both generations exist. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> LOOKUP = RedisScript.of("""
            local org = redis.call('GET', KEYS[1])
            local global = redis.call('GET', KEYS[2])
            if not org or not global then
              return {org or false, global or false, false}
            end
            return {org, global, redis.call('GET', ARGV[1] .. org .. ':' .. global .. ':' .. ARGV[2])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final JsonColumns json;
    private final SchemaCacheProperties properties;
    private final CircuitBreaker breaker;
    private final Set<String> pendingBumps = ConcurrentHashMap.newKeySet();
    private final Counter hits;
    private final Counter misses;
    private final Counter bypasses;
    private final Counter errors;

    SchemaCache(StringRedisTemplate redis, JsonColumns json, SchemaCacheProperties properties, MeterRegistry meters,
            Clock clock) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
        this.breaker = new CircuitBreaker(properties.circuitOpen(), clock);
        this.hits = result(meters, "hit");
        this.misses = result(meters, "miss");
        this.bypasses = result(meters, "bypass");
        this.errors = result(meters, "error");
        if (properties.enabled()) {
            log.info("Module schema cache enabled (Redis, TTL {})", properties.ttl());
        }
    }

    Optional<ModuleSchema> module(UUID organizationId, String entry, Supplier<Optional<ModuleSchema>> loader) {
        return cached(organizationId, entry, loader,
                value -> value.map(schema -> json.writeValue(CachedModule.of(schema, json))).orElse(null),
                text -> Optional.of(json.mapper().readValue(text, CachedModule.class).schema(json)));
    }

    List<ModuleSchema> modules(UUID organizationId, String entry, Supplier<List<ModuleSchema>> loader) {
        return cached(organizationId, entry, loader,
                value -> json.writeValue(value.stream().map(schema -> CachedModule.of(schema, json)).toList()),
                text -> json.mapper().readValue(text, MODULE_LIST).stream().map(cached -> cached.schema(json))
                        .toList());
    }

    /**
     * Invalidates the cached schemas of an organization ({@code null}: platform-global modules, i.e. every tenant)
     * once the current transaction commits; until then, lookups in this transaction bypass the cache.
     */
    void evictAfterCommit(UUID organizationId) {
        if (!properties.enabled()) {
            return;
        }
        String key = organizationId == null ? GLOBAL_GENERATION : generationKey(organizationId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bump(Set.of(key));
            return;
        }
        @SuppressWarnings("unchecked")
        Set<String> keys = (Set<String>) TransactionSynchronizationManager.getResource(CHANGED_IN_TRANSACTION);
        if (keys == null) {
            Set<String> changed = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(CHANGED_IN_TRANSACTION, changed);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bump(changed);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(CHANGED_IN_TRANSACTION);
                }
            });
            keys = changed;
        }
        keys.add(key);
    }

    private <T> T cached(UUID organizationId, String entry, Supplier<T> loader, Function<T, String> encode,
            Function<String, T> decode) {
        if (!properties.enabled() || TransactionSynchronizationManager.hasResource(CHANGED_IN_TRANSACTION)
                || !breaker.allowRequest()) {
            bypasses.increment();
            return loader.get();
        }
        String orgKey = generationKey(organizationId);
        String entryPrefix = PREFIX + "s:" + scope(organizationId) + ":";
        String orgGeneration;
        String globalGeneration;
        try {
            flushPendingBumps();
            List<?> found = redis.execute(LOOKUP, List.of(orgKey, GLOBAL_GENERATION), entryPrefix, entry);
            orgGeneration = found == null ? null : (String) found.get(0);
            globalGeneration = found == null ? null : (String) found.get(1);
            String text = found == null ? null : (String) found.get(2);
            if (orgGeneration == null) {
                orgGeneration = initGeneration(orgKey);
            }
            if (globalGeneration == null) {
                globalGeneration = initGeneration(GLOBAL_GENERATION);
            }
            if (text != null) {
                T value = decodeOrNull(text, decode);
                if (value != null) {
                    hits.increment();
                    succeeded();
                    return value;
                }
            }
        } catch (RuntimeException ex) {
            failed(ex);
            return loader.get();
        }
        misses.increment();
        T value = loader.get();
        String encoded = encode.apply(value);
        if (encoded != null) {
            try {
                redis.opsForValue().set(entryPrefix + orgGeneration + ":" + globalGeneration + ":" + entry, encoded,
                        properties.ttl());
                succeeded();
            } catch (RuntimeException ex) {
                failed(ex);
            }
        }
        return value;
    }

    private <T> T decodeOrNull(String text, Function<String, T> decode) {
        try {
            return decode.apply(text);
        } catch (JacksonException | IllegalArgumentException ex) {
            log.debug("Ignoring an unreadable cached schema: {}", ex.toString());
            return null;
        }
    }

    private String initGeneration(String key) {
        redis.opsForValue().setIfAbsent(key, newGeneration());
        String generation = redis.opsForValue().get(key);
        if (generation == null) {
            throw new IllegalStateException("Generation " + key + " vanished");
        }
        return generation;
    }

    private void bump(Set<String> keys) {
        pendingBumps.addAll(keys);
        if (!breaker.allowRequest()) {
            return;
        }
        try {
            flushPendingBumps();
            succeeded();
        } catch (RuntimeException ex) {
            failed(ex);
        }
    }

    private void flushPendingBumps() {
        for (String key : new ArrayList<>(pendingBumps)) {
            redis.opsForValue().set(key, newGeneration());
            pendingBumps.remove(key);
        }
    }

    private void succeeded() {
        if (breaker.recordSuccess()) {
            log.info("Redis is reachable again; module schema cache resumed");
        }
    }

    private void failed(RuntimeException ex) {
        errors.increment();
        if (breaker.recordFailure()) {
            log.warn("Module schema cache unavailable, reading PostgreSQL for {}: {}", properties.circuitOpen(),
                    ex.toString());
        }
    }

    private static String generationKey(UUID organizationId) {
        return PREFIX + "gen:o:" + scope(organizationId);
    }

    private static String scope(UUID organizationId) {
        return organizationId == null ? "none" : organizationId.toString();
    }

    private static String newGeneration() {
        return Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36);
    }

    private static Counter result(MeterRegistry meters, String result) {
        return Counter.builder("bm4a.metadata.schema.cache")
                .description("Module schema lookups by cache result")
                .tag("result", result)
                .register(meters);
    }

    /** Cache representation; default values are kept as JSON text so {@code null} and JSON {@code null} differ. */
    record CachedModule(UUID id, UUID organizationId, String apiKey, String labelSingular, String labelPlural,
            boolean active, long version, ModuleAccessMode accessMode, List<CachedField> fields) {

        static CachedModule of(ModuleSchema schema, JsonColumns json) {
            return new CachedModule(schema.id(), schema.organizationId(), schema.apiKey(), schema.labelSingular(),
                    schema.labelPlural(), schema.active(), schema.version(), schema.accessMode(),
                    schema.fields().stream().map(field -> CachedField.of(field, json)).toList());
        }

        ModuleSchema schema(JsonColumns json) {
            return new ModuleSchema(id, organizationId, apiKey, labelSingular, labelPlural, active, version,
                    accessMode, fields.stream().map(field -> field.schema(json)).toList());
        }
    }

    record CachedField(UUID id, String apiKey, String label, FieldType type, FieldStatus status, boolean required,
            boolean unique, boolean readOnly, boolean sensitive, boolean searchable, boolean filterable,
            boolean sortable, String defaultValue, FieldConfig config, int position) {

        static CachedField of(FieldSchema f, JsonColumns json) {
            return new CachedField(f.id(), f.apiKey(), f.label(), f.type(), f.status(), f.required(), f.unique(),
                    f.readOnly(), f.sensitive(), f.searchable(), f.filterable(), f.sortable(),
                    json.write(f.defaultValue()), f.config(), f.position());
        }

        FieldSchema schema(JsonColumns json) {
            return new FieldSchema(id, apiKey, label, type, status, required, unique, readOnly, sensitive, searchable,
                    filterable, sortable, json.read(defaultValue), config, position);
        }
    }
}
