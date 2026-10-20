package com.bm4a.platform.modules.shared.web;

import java.time.Clock;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class WebConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Registered through a registration bean so the filter instance is not itself a module bean; Spring Modulith
     * observability would otherwise proxy it, which breaks {@code GenericFilterBean} initialization.
     */
    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
