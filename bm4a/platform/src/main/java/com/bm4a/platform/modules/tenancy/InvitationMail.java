package com.bm4a.platform.modules.tenancy;

import java.time.Instant;
import java.util.UUID;

/**
 * Content of an invitation e-mail. {@code acceptUrl} contains the secret single-use token: send it only to
 * {@code email} and never log or persist it.
 *
 * @param roleLabel label of the org role, or of the membership role when no org role is assigned
 */
public record InvitationMail(UUID invitationId, UUID organizationId, String organizationName, String inviterName,
        String email, String roleLabel, String acceptUrl, Instant expiresAt) {

    @Override
    public String toString() {
        return "InvitationMail[invitationId=" + invitationId + ", organizationId=" + organizationId + "]";
    }
}
