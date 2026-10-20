package com.campushire.campushire.modules.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        Security security
) {

    public record Security(
            String postLoginRedirectUri
    ) {
    }
}