package com.bm4a.platform.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.bm4a.platform.modules.identity.internal.JwtProperties;
import com.bm4a.platform.modules.identity.internal.JwtValidation;
import com.nimbusds.jose.JOSEException;

/**
 * Replaces only the key source of the production decoder (no network JWKS in tests). Issuer, audience, expiry and
 * subject validation are the production validators.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder(JwtProperties properties) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestJwts.SIGNING_KEY.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidation.validator(properties));
        return decoder;
    }
}
