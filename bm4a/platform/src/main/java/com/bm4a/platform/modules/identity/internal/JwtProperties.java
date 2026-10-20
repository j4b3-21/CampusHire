package com.bm4a.platform.modules.identity.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param issuerUri expected {@code iss} claim (exact match)
 * @param jwkSetUri where signing keys are fetched; may differ from the issuer host inside container networks
 * @param audience required entry of the {@code aud} claim
 * @param platformAdminRole realm role granting platform-wide administration
 * @param clockSkew tolerance for {@code exp}/{@code nbf}
 */
@Validated
@ConfigurationProperties("bm4a.security.jwt")
public record JwtProperties(
        @NotBlank String issuerUri,
        @NotBlank String jwkSetUri,
        @NotBlank String audience,
        @DefaultValue("bm4a-platform-admin") @NotBlank String platformAdminRole,
        @DefaultValue("60s") @NotNull Duration clockSkew) {
}
