package com.campushire.analytics.internal;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * @param zone time zone of the institute: date filters, monthly buckets and report dates are interpreted in it
 */
@Validated
@ConfigurationProperties("campushire.analytics")
record AnalyticsProperties(
        @DefaultValue("Asia/Kolkata") @NotNull ZoneId zone,
        @DefaultValue @Valid @NotNull Cache cache) {

    /**
     * @param ttl lifetime of a cached response
     * @param timeout maximum time a Redis read may add to a request before the cache is skipped
     * @param retryAfter how long the cache stays bypassed after a Redis failure
     */
    record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60s") @NotNull Duration ttl,
            @DefaultValue("150ms") @NotNull Duration timeout,
            @DefaultValue("30s") @NotNull Duration retryAfter) {
    }
}
