package com.bm4a.platform.modules.ratelimit.internal;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bm4a.ratelimit", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfiguration {

    /**
     * Registered through a registration bean so the filter is not itself a module bean (Spring Modulith
     * observability would proxy it). Ordered after the Spring Security filter chain so the authenticated principal
     * is available.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimitProperties properties, StringRedisTemplate redis,
            MeterRegistry meterRegistry, JsonMapper jsonMapper, Clock clock) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(properties, redis, meterRegistry, jsonMapper, clock));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10);
        return registration;
    }
}
