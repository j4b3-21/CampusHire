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

import tools.jackson.databind.node.ObjectNode;

/**
 * Every query is keyed by organization ID; membership lookups always include the user ID as well.
 */
@Repository
class TenancyRepository {

    private final JdbcClient jdbc;
    private final JsonColumns json;
    private final RowMapper<OrganizationRow> organizationMapper;
    private final RowMapper<MembershipRow> membershipMapper;

    TenancyRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
        this.organizationMapper = (rs, n) -> new OrganizationRow(uuid(rs, "id"), rs.getString("name"),
                rs.getString("status"), json.readObject(rs.getString("settings")), rs.getLong("version"),
                instant(rs, "created_at"), uuid(rs, "created_by"), instant(rs, "updated_at"), uuid(rs, "updated_by"));
        this.membershipMapper = (rs, n) -> new MembershipRow(uuid(rs, "organization_id"), uuid(rs, "user_id"),
                MembershipRole.valueOf(rs.getString("role")), rs.getString("status"), uuid(rs, "role_id"),
                json.readObject(rs.getString("attributes")), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    void insertOrganization(OrganizationRow org) {
        jdbc.sql("""
                INSERT INTO organization (id, name, status, settings, version, created_at, created_by, updated_at, updated_by)
                VALUES (:id, :name, :status, CAST(:settings AS jsonb), :version, :createdAt, :createdBy, :updatedAt, :updatedBy)
                """)
                .param("id", org.id())
                .param("name", org.name())
                .param("status", org.status())
                .param("settings", json.write(org.settings()))
                .param("version", org.version())
                .param("createdAt", ts(org.createdAt()))
                .param("createdBy", org.createdBy())
                .param("updatedAt", ts(org.updatedAt()))
                .param("updatedBy", org.updatedBy())
                .update();
    }

    Optional<OrganizationRow> findOrganization(UUID id) {
        return jdbc.sql("SELECT * FROM organization WHERE id = :id").param("id", id).query(organizationMapper)
                .optional();
    }

    /** Row lock that serializes membership and organization changes within one tenant. */
    Optional<OrganizationRow> lockOrganization(UUID id) {
        return jdbc.sql("SELECT * FROM organization WHERE id = :id FOR UPDATE").param("id", id)
                .query(organizationMapper).optional();
    }

    void updateOrganization(UUID id, String name, String status, String settingsJson, long newVersion, UUID actor,
            Instant now) {
        jdbc.sql("""
                UPDATE organization
                   SET name = :name, status = :status, settings = CAST(:settings AS jsonb), version = :version,
                       updated_at = :now, updated_by = :actor
                 WHERE id = :id
                """)
                .param("id", id)
                .param("name", name)
                .param("status", status)
                .param("settings", settingsJson)
                .param("version", newVersion)
                .param("now", ts(now))
                .param("actor", actor)
                .update();
    }

    List<OrganizationWithRole> findOrganizationsOfUser(UUID userId) {
        return jdbc.sql("""
                SELECT o.*, m.role AS member_role FROM organization o
                  JOIN membership m ON m.organization_id = o.id
                 WHERE m.user_id = :userId AND m.status = 'ACTIVE'
                 ORDER BY o.name, o.id
                """)
                .param("userId", userId)
                .query((rs, n) -> new OrganizationWithRole(organizationMapper.mapRow(rs, n),
                        MembershipRole.valueOf(rs.getString("member_role"))))
                .list();
    }

    Optional<MembershipRow> findMembership(UUID organizationId, UUID userId) {
        return jdbc.sql("SELECT * FROM membership WHERE organization_id = :org AND user_id = :user")
                .param("org", organizationId)
                .param("user", userId)
                .query(membershipMapper)
                .optional();
    }

    List<MembershipRow> findActiveMemberships(UUID organizationId, long offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM membership WHERE organization_id = :org AND status = 'ACTIVE'
                 ORDER BY created_at, user_id LIMIT :limit OFFSET :offset
                """)
                .param("org", organizationId)
                .param("limit", limit)
                .param("offset", offset)
                .query(membershipMapper)
                .list();
    }

    /** Active memberships plus pending invitations that have not expired at {@code now} and hold the role. */
    long countRoleAssignments(UUID organizationId, UUID roleId, Instant now) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM membership
                         WHERE organization_id = :org AND role_id = :role AND status = 'ACTIVE')
                     + (SELECT count(*) FROM invitation
                         WHERE organization_id = :org AND role_id = :role AND status = 'PENDING' AND expires_at > :now)
                """)
                .param("org", organizationId)
                .param("role", roleId)
                .param("now", ts(now))
                .query(Long.class).single();
    }

    long countActiveMemberships(UUID organizationId) {
        return jdbc.sql("SELECT count(*) FROM membership WHERE organization_id = :org AND status = 'ACTIVE'")
                .param("org", organizationId).query(Long.class).single();
    }

    long countActiveOwners(UUID organizationId) {
        return jdbc.sql("""
                SELECT count(*) FROM membership WHERE organization_id = :org AND status = 'ACTIVE' AND role = 'OWNER'
                """).param("org", organizationId).query(Long.class).single();
    }

    void upsertMembership(UUID organizationId, UUID userId, MembershipRole role, String status, UUID roleId,
            ObjectNode attributes, UUID actor, Instant now) {
        jdbc.sql("""
                INSERT INTO membership (organization_id, user_id, role, status, role_id, attributes, created_at,
                                        created_by, updated_at, updated_by)
                VALUES (:org, :user, :role, :status, :roleId, CAST(:attributes AS jsonb), :now, :actor, :now, :actor)
                ON CONFLICT (organization_id, user_id)
                DO UPDATE SET role = EXCLUDED.role, status = EXCLUDED.status, role_id = EXCLUDED.role_id,
                              attributes = EXCLUDED.attributes, updated_at = EXCLUDED.updated_at,
                              updated_by = EXCLUDED.updated_by
                """)
                .param("org", organizationId)
                .param("user", userId)
                .param("role", role.name())
                .param("status", status)
                .param("roleId", roleId)
                .param("attributes", json.write(attributes))
                .param("now", ts(now))
                .param("actor", actor)
                .update();
    }
}
