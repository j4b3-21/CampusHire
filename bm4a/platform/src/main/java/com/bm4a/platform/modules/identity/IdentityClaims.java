package com.bm4a.platform.modules.identity;

/**
 * Verified claims of the authenticated caller. {@code (issuer, subject)} is the stable identity; e-mail and
 * display name are informational and may change between tokens. {@code emailVerified} reflects the
 * {@code email_verified} claim ({@code false} when absent).
 */
public record IdentityClaims(String issuer, String subject, String email, String displayName, boolean platformAdmin,
        boolean emailVerified) {
}
