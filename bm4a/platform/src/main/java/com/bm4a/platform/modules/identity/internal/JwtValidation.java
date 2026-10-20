package com.bm4a.platform.modules.identity.internal;

import java.util.Collection;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * The single validator chain used for every access token, in production and in tests: expiry/not-before (with
 * skew), exact issuer, required audience and a non-blank subject. Signature verification is done by the decoder.
 */
public final class JwtValidation {

    private JwtValidation() {
    }

    public static OAuth2TokenValidator<Jwt> validator(JwtProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(properties.clockSkew()),
                new JwtIssuerValidator(properties.issuerUri()),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(properties.audience())),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank()));
    }
}
