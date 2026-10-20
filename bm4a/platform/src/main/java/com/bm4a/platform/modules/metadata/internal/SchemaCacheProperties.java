package com.bm4a.platform.modules.metadata.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * @param enabled cache module schemas in Redis
 * @param ttl lifetime of a cached entry; changes evict earlier, so this only bounds memory and missed evictions
 * @param circuitOpen how long Redis is skipped after a failure
 */
@Validated
@ConfigurationProperties("bm4a.metadata.cache")
record SchemaCacheProperties(@DefaultValue("true") boolean enabled, @DefaultValue("10m") @NotNull Duration ttl,
        @DefaultValue("5s") @NotNull Duration circuitOpen) {
}
