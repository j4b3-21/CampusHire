package com.bm4a.platform.modules.tenancy.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Pattern;

/**
 * @param baseUrl public URL of the web application; invitation links are {@code <baseUrl>/invite/<token>}
 */
@Validated
@ConfigurationProperties("bm4a.app")
public record InvitationProperties(
        @DefaultValue("http://localhost:3000") @Pattern(regexp = "^https?://[^\\s]+$") String baseUrl) {

    String acceptUrl(String token) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/invite/" + token;
    }
}
