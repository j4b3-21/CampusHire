package com.bm4a.platform.modules.identity.internal;

import java.util.Optional;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.identity.AuthenticatedIdentity;
import com.bm4a.platform.modules.identity.IdentityClaims;

@Component
class JwtAuthenticatedIdentity implements AuthenticatedIdentity {

    private final JwtProperties properties;

    JwtAuthenticatedIdentity(JwtProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<IdentityClaims> current() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return Optional.empty();
        }
        Jwt jwt = token.getToken();
        String displayName = firstNonBlank(jwt.getClaimAsString("name"), jwt.getClaimAsString("preferred_username"));
        boolean platformAdmin = token.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + properties.platformAdminRole()).equals(authority.getAuthority()));
        boolean emailVerified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
        return Optional.of(new IdentityClaims(jwt.getClaimAsString("iss"), jwt.getSubject(),
                jwt.getClaimAsString("email"), displayName, platformAdmin, emailVerified));
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
