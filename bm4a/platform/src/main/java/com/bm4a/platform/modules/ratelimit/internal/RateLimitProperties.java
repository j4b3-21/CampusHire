package com.bm4a.platform.modules.ratelimit.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * @param enabled whether mutating API calls are rate limited at all
 * @param writesPerMinute maximum mutating requests per identity within one {@code window}
 * @param window length of the fixed window (whole seconds)
 */
@Validated
@ConfigurationProperties("bm4a.ratelimit")
record RateLimitProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("300") @Min(1) int writesPerMinute,
        @DefaultValue("60s") @NotNull Duration window) {
}
