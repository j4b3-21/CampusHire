package com.bm4a.platform.modules.access.internal;

import static com.bm4a.platform.modules.shared.persistence.SqlValues.instant;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.ts;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.uuid;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.shared.persistence.JsonColumns;

/** Every statement is scoped by {@code organization_id}. */
@Repository
class RoleRepository {

    private final JdbcClient jdbc;
    private final JsonColumns json;
    private final RowMapper<RoleRow> mapper;

    RoleRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new RoleRow(uuid(rs, "id"), uuid(rs, "organization_id"), rs.getString("api_key"),
                rs.getString("label"), rs.getString("description"), json.readObject(rs.getString("permissions")),
                rs.getLong("version"), instant(rs, "created_at"), uuid(rs, "created_by"), instant(rs, "updated_at"),
                uuid(rs, "updated_by"));
    }

    void insert(RoleRow r) {
        jdbc.sql("""
                INSERT INTO org_role (id, organization_id, api_key, label, description, permissions, version,
                                      created_at, created_by, updated_at, updated_by)
                VALUES (:id, :org, :apiKey, :label, :description, CAST(:permissions AS jsonb), :version, :createdAt,
                        :createdBy, :updatedAt, :updatedBy)
                """)
                .param("id", r.id())
                .param("org", r.organizationId())
                .param("apiKey", r.apiKey())
                .param("label", r.label())
                .param("description", r.description())
                .param("permissions", json.write(r.permissions()))
                .param("version", r.version())
                .param("createdAt", ts(r.createdAt()))
                .param("createdBy", r.createdBy())
                .param("updatedAt", ts(r.updatedAt()))
                .param("updatedBy", r.updatedBy())
                .update();
    }

    void update(RoleRow r) {
        jdbc.sql("""
                UPDATE org_role
                   SET label = :label, description = :description, permissions = CAST(:permissions AS jsonb),
                       version = :version, updated_at = :updatedAt, updated_by = :updatedBy
                 WHERE id = :id AND organization_id = :org
                """)
                .param("id", r.id())
                .param("org", r.organizationId())
                .param("label", r.label())
                .param("description", r.description())
                .param("permissions", json.write(r.permissions()))
                .param("version", r.version())
                .param("updatedAt", ts(r.updatedAt()))
                .param("updatedBy", r.updatedBy())
                .update();
    }

    void delete(UUID organizationId, UUID id) {
        jdbc.sql("DELETE FROM org_role WHERE id = :id AND organization_id = :org")
                .param("id", id)
                .param("org", organizationId)
                .update();
    }

    Optional<RoleRow> find(UUID organizationId, String apiKey, boolean forUpdate) {
        return jdbc.sql("SELECT * FROM org_role WHERE organization_id = :org AND api_key = :key"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("org", organizationId)
                .param("key", apiKey)
                .query(mapper)
                .optional();
    }

    /** Share lock: blocks deletion of the role until the assigning transaction ends. */
    Optional<RoleRow> findForShare(UUID organizationId, String apiKey) {
        return jdbc.sql("SELECT * FROM org_role WHERE organization_id = :org AND api_key = :key FOR SHARE")
                .param("org", organizationId)
                .param("key", apiKey)
                .query(mapper)
                .optional();
    }

    Optional<RoleRow> findById(UUID organizationId, UUID id) {
        return jdbc.sql("SELECT * FROM org_role WHERE organization_id = :org AND id = :id")
                .param("org", organizationId)
                .param("id", id)
                .query(mapper)
                .optional();
    }

    List<RoleRow> findAll(UUID organizationId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM org_role WHERE organization_id = :org AND id IN (:ids)")
                .param("org", organizationId)
                .param("ids", ids)
                .query(mapper)
                .list();
    }

    List<RoleRow> list(UUID organizationId) {
        return jdbc.sql("SELECT * FROM org_role WHERE organization_id = :org ORDER BY api_key")
                .param("org", organizationId)
                .query(mapper)
                .list();
    }

    long count(UUID organizationId) {
        return jdbc.sql("SELECT count(*) FROM org_role WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }
}
