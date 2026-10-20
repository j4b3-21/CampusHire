package com.bm4a.platform.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Issues RS256 access tokens signed with an in-memory test key. The application validates them with the same
 * validator chain as production (see {@code TestSecurityConfiguration}).
 */
public final class TestJwts {

    public static final String ISSUER = "https://issuer.test/realms/bm4a";
    public static final String AUDIENCE = "bm4a-api";
    public static final String PLATFORM_ADMIN_ROLE = "bm4a-platform-admin";

    static final RSAKey SIGNING_KEY = generate("test-signing-key");
    static final RSAKey UNTRUSTED_KEY = generate("untrusted-key");

    private TestJwts() {
    }

    public static Builder forSubject(String subject) {
        return new Builder(subject);
    }

    /** A fresh, unique subject so tests sharing one database never collide. */
    public static Builder newUser() {
        return new Builder("user-" + UUID.randomUUID());
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static final class Builder {

        private final String subject;
        private String issuer = ISSUER;
        private List<String> audience = List.of(AUDIENCE);
        private Instant expiresAt = Instant.now().plusSeconds(300);
        private String email;
        private String name;
        private boolean emailVerified = true;
        private final List<String> roles = new ArrayList<>();
        private RSAKey key = SIGNING_KEY;

        private Builder(String subject) {
            this.subject = subject;
            this.email = subject + "@example.test";
            this.name = "User " + subject;
        }

        public String subject() {
            return subject;
        }

        public String email() {
            return email;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(String... audience) {
            this.audience = List.of(audience);
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        /** The {@code email_verified} claim ({@code true} unless changed). */
        public Builder emailVerified(boolean emailVerified) {
            this.emailVerified = emailVerified;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder platformAdmin() {
            roles.add(PLATFORM_ADMIN_ROLE);
            return this;
        }

        public Builder signedWithUntrustedKey() {
            this.key = UNTRUSTED_KEY;
            return this;
        }

        public String token() {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audience)
                    .issueTime(Date.from(expiresAt.minusSeconds(600)))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("email", email)
                    .claim("email_verified", emailVerified)
                    .claim("name", name)
                    .claim("preferred_username", subject)
                    .claim("realm_access", Map.of("roles", List.copyOf(roles)))
                    .build();
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims);
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (JOSEException ex) {
                throw new IllegalStateException(ex);
            }
        }

        public String bearer() {
            return "Bearer " + token();
        }
    }
}
