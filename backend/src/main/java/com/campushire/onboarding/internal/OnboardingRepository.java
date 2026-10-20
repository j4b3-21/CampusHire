package com.campushire.onboarding.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OnboardingRepository {

    static final int MAX_INSTITUTES = 50;

    record InstituteRow(UUID organizationId, String name, boolean studentFound) {
    }

    private final JdbcClient jdbc;

    OnboardingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Active organizations whose {@code settings.campushire.allowedEmailDomains} contain {@code domain}.
     * Read-only SQL over the platform's {@code organization} table: discovery by e-mail domain spans tenants, and no
     * platform API offers a cross-tenant settings search.
     */
    List<InstituteRow> institutesForDomain(String domain, String email) {
        return jdbc.sql("""
                SELECT o.id, o.name,
                       EXISTS (SELECT 1 FROM campushire_student_projection s
                               WHERE s.organization_id = o.id AND lower(s.email) = :email AND NOT s.deleted)
                           AS student_found
                FROM organization o
                WHERE o.status = 'ACTIVE'
                  AND jsonb_typeof(o.settings -> 'campushire' -> 'allowedEmailDomains') = 'array'
                  AND EXISTS (SELECT 1
                              FROM jsonb_array_elements_text(o.settings -> 'campushire' -> 'allowedEmailDomains') d
                              WHERE lower(d) = :domain)
                ORDER BY o.name, o.id
                LIMIT :limit
                """)
                .param("domain", domain).param("email", email).param("limit", MAX_INSTITUTES)
                .query((rs, n) -> new InstituteRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBoolean("student_found")))
                .list();
    }

    Optional<UUID> studentIdByEmail(UUID organizationId, String email) {
        return jdbc.sql("""
                SELECT student_id FROM campushire_student_projection
                WHERE organization_id = :org AND lower(email) = :email AND NOT deleted
                ORDER BY record_version DESC
                LIMIT 1
                """)
                .param("org", organizationId).param("email", email)
                .query(UUID.class)
                .optional();
    }

    /** Serializes claims of one user in one organization until the end of the transaction (e.g. two open tabs). */
    void lockClaim(UUID organizationId, UUID userId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "campushire.claim:" + organizationId + ":" + userId)
                .query((rs, n) -> 1)
                .single();
    }
}
