package com.bm4a.platform.modules.identity;

/**
 * Verified claims of the authenticated caller. {@code (issuer, subject)} is the stable identity; e-mail and
 * display name are informational and may change between tokens.
 */
public record IdentityClaims(String issuer, String subject, String email, String displayName, boolean platformAdmin) {
}
