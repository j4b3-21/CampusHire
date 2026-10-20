package com.bm4a.platform.modules.notifications.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param name product name shown in e-mail ({@code APP_NAME})
 * @param baseUrl public URL of the web application ({@code APP_BASE_URL}); relative links are resolved against it
 */
@Validated
@ConfigurationProperties("bm4a.app")
record AppBranding(@DefaultValue("BM4A") @NotBlank @Size(max = 100) String name,
        @DefaultValue("http://localhost:3000") @Pattern(regexp = "^https?://[^\\s]+$") String baseUrl) {

    /** An absolute path becomes {@code <baseUrl><path>}; anything else is returned unchanged. */
    String resolve(String link) {
        if (link == null || !link.startsWith("/") || link.startsWith("//")) {
            return link;
        }
        return root() + link;
    }

    String root() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
