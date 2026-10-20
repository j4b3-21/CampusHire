package com.bm4a.platform.modules.tenancy.internal;

import static com.bm4a.platform.modules.shared.persistence.SqlValues.instant;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.ts;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.uuid;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.tenancy.MembershipRole;

/**
 * Tenant-facing statements are scoped by {@code organization_id}; the token lookups used by accepting users are keyed
 * by the token hash, which only the holder of the token can compute.
 */
@Repository
class InvitationRepository {

    /** Status filter values, mapped to constant SQL fragments. */
    private static final String PENDING = "status = 'PENDING' AND expires_at > :now";
    private static final String EXPIRED = "status = 'PENDING' AND expires_at <= :now";

    private final JdbcClient jdbc;
    private final JsonColumns json;
    private final RowMapper<InvitationRow> mapper;

    InvitationRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new InvitationRow(uuid(rs, "id"), uuid(rs, "organization_id"), rs.getString("email"),
                MembershipRole.valueOf(rs.getString("membership_role")), uuid(rs, "role_id"),
                json.readObject(rs.getString("attributes")), rs.getString("token_hash"), rs.getString("status"),
                instant(rs, "expires_at"), instant(rs, "created_at"), uuid(rs, "created_by"),
                instant(rs, "accepted_at"), uuid(rs, "accepted_by"), instant(rs, "revoked_at"),
                uuid(rs, "revoked_by"));
    }

    void insert(InvitationRow row) {
        jdbc.sql("""
                INSERT INTO invitation (id, organization_id, email, membership_role, role_id, attributes, token_hash,
                                        status, expires_at, created_at, created_by)
                VALUES (:id, :org, :email, :membershipRole, :roleId, CAST(:attributes AS jsonb), :tokenHash, :status,
                        :expiresAt, :createdAt, :createdBy)
                """)
                .param("id", row.id())
                .param("org", row.organizationId())
                .param("email", row.email())
                .param("membershipRole", row.membershipRole().name())
                .param("roleId", row.roleId())
                .param("attributes", json.write(row.attributes()))
                .param("tokenHash", row.tokenHash())
                .param("status", row.status())
                .param("expiresAt", ts(row.expiresAt()))
                .param("createdAt", ts(row.createdAt()))
                .param("createdBy", row.createdBy())
                .update();
    }

    Optional<InvitationRow> find(UUID organizationId, UUID id, boolean forUpdate) {
        return jdbc.sql("SELECT * FROM invitation WHERE id = :id AND organization_id = :org"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", id)
                .param("org", organizationId)
                .query(mapper)
                .optional();
    }

    Optional<InvitationRow> findByTokenHash(String tokenHash, boolean forUpdate) {
        return jdbc.sql("SELECT * FROM invitation WHERE token_hash = :hash" + (forUpdate ? " FOR UPDATE" : ""))
                .param("hash", tokenHash)
                .query(mapper)
                .optional();
    }

    boolean pendingExists(UUID organizationId, String email, Instant now) {
        return jdbc.sql("SELECT count(*) FROM invitation WHERE organization_id = :org AND email = :email AND "
                        + PENDING)
                .param("org", organizationId)
                .param("email", email)
                .param("now", ts(now))
                .query(Long.class).single() > 0;
    }

    /** @param status {@code null} for all, otherwise one of PENDING, EXPIRED, ACCEPTED, REVOKED */
    List<InvitationRow> list(UUID organizationId, String status, Instant now, long offset, int limit) {
        return jdbc.sql("SELECT * FROM invitation WHERE organization_id = :org AND " + statusCondition(status)
                        + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
                .param("org", organizationId)
                .param("status", status)
                .param("now", ts(now))
                .param("limit", limit)
                .param("offset", offset)
                .query(mapper)
                .list();
    }

    long count(UUID organizationId, String status, Instant now) {
        return jdbc.sql("SELECT count(*) FROM invitation WHERE organization_id = :org AND " + statusCondition(status))
                .param("org", organizationId)
                .param("status", status)
                .param("now", ts(now))
                .query(Long.class).single();
    }

    void markAccepted(UUID id, UUID userId, Instant now) {
        jdbc.sql("UPDATE invitation SET status = 'ACCEPTED', accepted_at = :now, accepted_by = :user WHERE id = :id")
                .param("id", id)
                .param("user", userId)
                .param("now", ts(now))
                .update();
    }

    void markRevoked(UUID organizationId, UUID id, UUID actor, Instant now) {
        jdbc.sql("""
                UPDATE invitation SET status = 'REVOKED', revoked_at = :now, revoked_by = :actor
                 WHERE id = :id AND organization_id = :org
                """)
                .param("id", id)
                .param("org", organizationId)
                .param("actor", actor)
                .param("now", ts(now))
                .update();
    }

    /** Parameters not referenced by the chosen fragment are ignored by the named-parameter binding. */
    private static String statusCondition(String status) {
        if (status == null) {
            return "TRUE";
        }
        return switch (status) {
            case InvitationRow.PENDING -> PENDING;
            case InvitationRow.EXPIRED -> EXPIRED;
            default -> "status = :status";
        };
    }
}
