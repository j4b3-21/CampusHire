package com.bm4a.platform.modules.records.internal;

import static com.bm4a.platform.modules.shared.persistence.SqlValues.instant;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.ts;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.uuid;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.records.RecordScopes;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * All statements are scoped by {@code organization_id}; single-record statements additionally by {@code module_id}.
 */
@Repository
class RecordRepository {

    private final JdbcClient jdbc;
    private final JsonColumns json;
    private final RowMapper<RecordView> mapper;

    RecordRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new RecordView(uuid(rs, "id"), uuid(rs, "organization_id"), uuid(rs, "module_id"),
                json.readObject(rs.getString("data")), rs.getLong("version"), rs.getLong("definition_version"),
                instant(rs, "created_at"), uuid(rs, "created_by"), instant(rs, "updated_at"), uuid(rs, "updated_by"),
                instant(rs, "deleted_at"), uuid(rs, "deleted_by"), uuid(rs, "owner_user_id"));
    }

    void insert(RecordView r) {
        jdbc.sql("""
                INSERT INTO record (id, organization_id, module_id, data, version, definition_version, created_at,
                                    created_by, updated_at, updated_by, owner_user_id)
                VALUES (:id, :org, :module, CAST(:data AS jsonb), :version, :definitionVersion, :createdAt, :createdBy,
                        :updatedAt, :updatedBy, :owner)
                """)
                .param("id", r.id())
                .param("org", r.organizationId())
                .param("module", r.moduleId())
                .param("data", json.write(r.data()))
                .param("version", r.version())
                .param("definitionVersion", r.definitionVersion())
                .param("createdAt", ts(r.createdAt()))
                .param("createdBy", r.createdBy())
                .param("updatedAt", ts(r.updatedAt()))
                .param("updatedBy", r.updatedBy())
                .param("owner", r.ownerUserId())
                .update();
    }

    /** Writes data, version and lifecycle columns, and the owner. */
    void update(RecordView r) {
        jdbc.sql("""
                UPDATE record
                   SET data = CAST(:data AS jsonb), version = :version, definition_version = :definitionVersion,
                       updated_at = :updatedAt, updated_by = :updatedBy, deleted_at = :deletedAt, deleted_by = :deletedBy,
                       owner_user_id = :owner
                 WHERE id = :id AND organization_id = :org
                """)
                .param("id", r.id())
                .param("org", r.organizationId())
                .param("data", json.write(r.data()))
                .param("version", r.version())
                .param("definitionVersion", r.definitionVersion())
                .param("updatedAt", ts(r.updatedAt()))
                .param("updatedBy", r.updatedBy())
                .param("deletedAt", ts(r.deletedAt()))
                .param("deletedBy", r.deletedBy())
                .param("owner", r.ownerUserId())
                .update();
    }

    Optional<RecordView> find(UUID organizationId, UUID moduleId, UUID id, boolean forUpdate) {
        return jdbc.sql("SELECT * FROM record WHERE id = :id AND organization_id = :org AND module_id = :module"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", id)
                .param("org", organizationId)
                .param("module", moduleId)
                .query(mapper)
                .optional();
    }

    /** Like {@link #find}, additionally restricted by a record scope predicate over alias {@code r}. */
    Optional<RecordView> find(UUID organizationId, UUID moduleId, UUID id, RecordScopes.Predicate scope,
            boolean forUpdate) {
        return jdbc.sql("SELECT r.* FROM record r WHERE r.id = :id AND r.organization_id = :org AND r.module_id = :module"
                        + " AND " + scope.sql() + (forUpdate ? " FOR UPDATE" : ""))
                .params(scope.params())
                .param("id", id)
                .param("org", organizationId)
                .param("module", moduleId)
                .query(mapper)
                .optional();
    }

    /** The oldest active record of the module whose {@code data} contains {@code containment} (JSON). */
    Optional<RecordView> findFirstActiveContaining(UUID organizationId, UUID moduleId, String containment) {
        return jdbc.sql("""
                SELECT * FROM record
                 WHERE organization_id = :org AND module_id = :module AND deleted_at IS NULL
                   AND data @> CAST(:containment AS jsonb)
                 ORDER BY created_at, id LIMIT 1
                """)
                .param("org", organizationId)
                .param("module", moduleId)
                .param("containment", containment)
                .query(mapper)
                .optional();
    }

    /** Whether an active record of the module within {@code scope} contains any of {@code containments} (JSON). */
    boolean existsActiveContaining(UUID organizationId, UUID moduleId, RecordScopes.Predicate scope,
            List<String> containments) {
        Map<String, Object> params = new HashMap<>(scope.params());
        List<String> alternatives = new ArrayList<>();
        for (String containment : containments) {
            String name = "d" + alternatives.size();
            params.put(name, containment);
            alternatives.add("r.data @> CAST(:" + name + " AS jsonb)");
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM record r WHERE r.organization_id = :org AND r.module_id = :module"
                        + " AND r.deleted_at IS NULL AND " + scope.sql() + " AND (" + String.join(" OR ", alternatives)
                        + "))")
                .params(params)
                .param("org", organizationId)
                .param("module", moduleId)
                .query(Boolean.class)
                .single();
    }

    /** The active record holding a normalized unique value of a field. */
    Optional<RecordView> findActiveByUniqueValue(UUID organizationId, UUID moduleId, UUID fieldId, String value) {
        return jdbc.sql("""
                SELECT r.* FROM record_unique_value u JOIN record r ON r.id = u.record_id
                 WHERE u.organization_id = :org AND u.field_id = :field AND u.normalized_value = :value
                   AND r.organization_id = :org AND r.module_id = :module AND r.deleted_at IS NULL
                """)
                .param("org", organizationId)
                .param("field", fieldId)
                .param("value", value)
                .param("module", moduleId)
                .query(mapper)
                .optional();
    }

    List<RecordView> findActive(UUID organizationId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM record WHERE organization_id = :org AND id IN (:ids) AND deleted_at IS NULL")
                .param("org", organizationId)
                .param("ids", ids)
                .query(mapper)
                .list();
    }

    List<RecordView> list(RecordQuery query, long offset, int limit) {
        return jdbc.sql("SELECT r.* FROM record r WHERE " + query.where() + " ORDER BY " + query.orderBy()
                        + " LIMIT :limit OFFSET :offset")
                .params(query.params())
                .param("limit", limit)
                .param("offset", offset)
                .query(mapper)
                .list();
    }

    long count(RecordQuery query) {
        return jdbc.sql("SELECT count(*) FROM record r WHERE " + query.where())
                .params(query.params())
                .query(Long.class)
                .single();
    }

    /** Active records of {@code moduleId} whose {@code data} contains {@code containment} (JSON). */
    long countActiveContaining(UUID organizationId, UUID moduleId, String containment) {
        return jdbc.sql("""
                SELECT count(*) FROM record
                 WHERE organization_id = :org AND module_id = :module AND deleted_at IS NULL
                   AND data @> CAST(:containment AS jsonb)
                """)
                .param("org", organizationId)
                .param("module", moduleId)
                .param("containment", containment)
                .query(Long.class)
                .single();
    }

    // ---- history -------------------------------------------------------------------------------------------

    void insertVersion(RecordView r, String changeType, Instant at, UUID by) {
        jdbc.sql("""
                INSERT INTO record_version (record_id, version, organization_id, change_type, data, deleted, changed_at,
                                            changed_by)
                VALUES (:id, :version, :org, :changeType, CAST(:data AS jsonb), :deleted, :at, :by)
                """)
                .param("id", r.id())
                .param("version", r.version())
                .param("org", r.organizationId())
                .param("changeType", changeType)
                .param("data", json.write(r.data()))
                .param("deleted", r.deleted())
                .param("at", ts(at))
                .param("by", by)
                .update();
    }

    List<RecordVersion> versions(UUID organizationId, UUID recordId, long offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM record_version WHERE organization_id = :org AND record_id = :id
                 ORDER BY version DESC LIMIT :limit OFFSET :offset
                """)
                .param("org", organizationId)
                .param("id", recordId)
                .param("limit", limit)
                .param("offset", offset)
                .query(this::version)
                .list();
    }

    long countVersions(UUID organizationId, UUID recordId) {
        return jdbc.sql("SELECT count(*) FROM record_version WHERE organization_id = :org AND record_id = :id")
                .param("org", organizationId).param("id", recordId).query(Long.class).single();
    }

    Optional<RecordVersion> version(UUID organizationId, UUID recordId, long version) {
        return jdbc.sql("""
                SELECT * FROM record_version WHERE organization_id = :org AND record_id = :id AND version = :version
                """)
                .param("org", organizationId)
                .param("id", recordId)
                .param("version", version)
                .query(this::version)
                .optional();
    }

    private RecordVersion version(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new RecordVersion(rs.getLong("version"), rs.getString("change_type"), json.readObject(rs.getString("data")),
                rs.getBoolean("deleted"), instant(rs, "changed_at"), uuid(rs, "changed_by"));
    }

    // ---- unique values -------------------------------------------------------------------------------------

    /** @return {@code false} when another record already holds the value */
    boolean claimUnique(UUID organizationId, UUID fieldId, String value, UUID recordId) {
        return jdbc.sql("""
                INSERT INTO record_unique_value (organization_id, field_id, normalized_value, record_id)
                VALUES (:org, :field, :value, :record)
                ON CONFLICT (organization_id, field_id, normalized_value) DO NOTHING
                """)
                .param("org", organizationId)
                .param("field", fieldId)
                .param("value", value)
                .param("record", recordId)
                .update() == 1
                || ownsUnique(organizationId, fieldId, value, recordId);
    }

    private boolean ownsUnique(UUID organizationId, UUID fieldId, String value, UUID recordId) {
        return jdbc.sql("""
                SELECT count(*) FROM record_unique_value
                 WHERE organization_id = :org AND field_id = :field AND normalized_value = :value AND record_id = :record
                """)
                .param("org", organizationId)
                .param("field", fieldId)
                .param("value", value)
                .param("record", recordId)
                .query(Long.class).single() == 1;
    }

    void releaseUnique(UUID organizationId, UUID recordId, Collection<UUID> fieldIds) {
        if (fieldIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM record_unique_value WHERE organization_id = :org AND record_id = :record AND field_id IN (:fields)")
                .param("org", organizationId)
                .param("record", recordId)
                .param("fields", fieldIds)
                .update();
    }

    void releaseAllUnique(UUID organizationId, UUID recordId) {
        jdbc.sql("DELETE FROM record_unique_value WHERE organization_id = :org AND record_id = :record")
                .param("org", organizationId)
                .param("record", recordId)
                .update();
    }

    static String containment(String key, JsonNode value, JsonColumns json) {
        ObjectNode document = json.newObject();
        document.set(key, value);
        return json.write(document);
    }
}
