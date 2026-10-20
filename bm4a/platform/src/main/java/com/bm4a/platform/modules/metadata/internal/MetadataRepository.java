package com.bm4a.platform.modules.metadata.internal;

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

import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;

import tools.jackson.databind.JsonNode;

/**
 * Module lookups are always constrained to one scope: {@code organization_id = :org} for tenant modules,
 * {@code organization_id IS NULL} for global ones, or "own tenant or global" for visibility queries.
 */
@Repository
class MetadataRepository {

    private static final String VISIBLE = "(m.organization_id = :org OR m.organization_id IS NULL)";

    private final JdbcClient jdbc;
    private final JsonColumns json;
    private final RowMapper<ModuleRow> moduleMapper;
    private final RowMapper<FieldRow> fieldMapper;

    MetadataRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
        this.moduleMapper = (rs, n) -> new ModuleRow(uuid(rs, "id"), uuid(rs, "organization_id"),
                rs.getString("api_key"), rs.getString("label_singular"), rs.getString("label_plural"),
                rs.getString("description"), rs.getString("status"), json.readObject(rs.getString("settings")),
                json.readObject(rs.getString("layout")), rs.getLong("version"), instant(rs, "created_at"),
                uuid(rs, "created_by"), instant(rs, "updated_at"), uuid(rs, "updated_by"));
        this.fieldMapper = (rs, n) -> new FieldRow(uuid(rs, "id"), uuid(rs, "module_id"), rs.getString("api_key"),
                rs.getString("label"), rs.getString("description"), FieldType.valueOf(rs.getString("field_type")),
                FieldStatus.valueOf(rs.getString("status")), rs.getBoolean("required"), rs.getBoolean("unique_value"),
                rs.getBoolean("read_only"), rs.getBoolean("sensitive"), rs.getBoolean("searchable"),
                rs.getBoolean("filterable"), rs.getBoolean("sortable"), json.read(rs.getString("default_value")),
                json.mapper().treeToValue(json.read(rs.getString("config")), FieldConfig.class),
                rs.getInt("position"), rs.getLong("version"), instant(rs, "created_at"), uuid(rs, "created_by"),
                instant(rs, "updated_at"), uuid(rs, "updated_by"));
    }

    // ---- modules -------------------------------------------------------------------------------------------

    void insertModule(ModuleRow m) {
        jdbc.sql("""
                INSERT INTO module_definition (id, organization_id, api_key, label_singular, label_plural, description,
                       status, settings, layout, version, created_at, created_by, updated_at, updated_by)
                VALUES (:id, :org, :apiKey, :singular, :plural, :description, :status, CAST(:settings AS jsonb),
                       CAST(:layout AS jsonb), :version, :createdAt, :createdBy, :updatedAt, :updatedBy)
                """)
                .param("id", m.id())
                .param("org", m.organizationId())
                .param("apiKey", m.apiKey())
                .param("singular", m.labelSingular())
                .param("plural", m.labelPlural())
                .param("description", m.description())
                .param("status", m.status())
                .param("settings", json.write(m.settings()))
                .param("layout", json.write(m.layout()))
                .param("version", m.version())
                .param("createdAt", ts(m.createdAt()))
                .param("createdBy", m.createdBy())
                .param("updatedAt", ts(m.updatedAt()))
                .param("updatedBy", m.updatedBy())
                .update();
    }

    void updateModule(ModuleRow m) {
        jdbc.sql("""
                UPDATE module_definition
                   SET label_singular = :singular, label_plural = :plural, description = :description,
                       status = :status, settings = CAST(:settings AS jsonb), layout = CAST(:layout AS jsonb),
                       version = :version, updated_at = :updatedAt, updated_by = :updatedBy
                 WHERE id = :id
                """)
                .param("id", m.id())
                .param("singular", m.labelSingular())
                .param("plural", m.labelPlural())
                .param("description", m.description())
                .param("status", m.status())
                .param("settings", json.write(m.settings()))
                .param("layout", json.write(m.layout()))
                .param("version", m.version())
                .param("updatedAt", ts(m.updatedAt()))
                .param("updatedBy", m.updatedBy())
                .update();
    }

    /** Module owned by exactly this scope (used for modifications). */
    Optional<ModuleRow> findOwned(MetadataScope scope, String apiKey, boolean forUpdate) {
        String sql = scope.isGlobal()
                ? "SELECT m.* FROM module_definition m WHERE m.organization_id IS NULL AND m.api_key = :key"
                : "SELECT m.* FROM module_definition m WHERE m.organization_id = :org AND m.api_key = :key";
        return jdbc.sql(forUpdate ? sql + " FOR UPDATE" : sql)
                .param("org", scope.organizationId())
                .param("key", apiKey)
                .query(moduleMapper)
                .optional();
    }

    /** Module visible to a tenant: its own or a platform-global one. */
    Optional<ModuleRow> findVisible(UUID organizationId, String apiKey) {
        return jdbc.sql("SELECT m.* FROM module_definition m WHERE " + VISIBLE
                        + " AND m.api_key = :key ORDER BY m.organization_id NULLS LAST LIMIT 1")
                .param("org", organizationId)
                .param("key", apiKey)
                .query(moduleMapper)
                .optional();
    }

    Optional<ModuleRow> findVisibleById(UUID organizationId, UUID moduleId) {
        return jdbc.sql("SELECT m.* FROM module_definition m WHERE " + VISIBLE + " AND m.id = :id")
                .param("org", organizationId)
                .param("id", moduleId)
                .query(moduleMapper)
                .optional();
    }

    List<ModuleRow> listVisible(UUID organizationId, boolean includeArchived) {
        return jdbc.sql("SELECT m.* FROM module_definition m WHERE " + VISIBLE
                        + (includeArchived ? "" : " AND m.status = 'ACTIVE'")
                        + " ORDER BY m.organization_id NULLS FIRST, m.api_key")
                .param("org", organizationId)
                .query(moduleMapper)
                .list();
    }

    List<ModuleRow> listGlobal(boolean includeArchived) {
        return jdbc.sql("SELECT m.* FROM module_definition m WHERE m.organization_id IS NULL"
                        + (includeArchived ? "" : " AND m.status = 'ACTIVE'") + " ORDER BY m.api_key")
                .query(moduleMapper)
                .list();
    }

    /** Tenant modules of every organization, in stable pages (maintenance only, see {@link ModuleTupleReconciler}). */
    List<ModuleRow> listTenantModules(UUID afterId, int limit) {
        return jdbc.sql("""
                SELECT m.* FROM module_definition m
                 WHERE m.organization_id IS NOT NULL AND (CAST(:after AS uuid) IS NULL OR m.id > :after)
                 ORDER BY m.id LIMIT :limit
                """)
                .param("after", afterId)
                .param("limit", limit)
                .query(moduleMapper)
                .list();
    }

    long countOwned(MetadataScope scope) {
        return jdbc.sql(scope.isGlobal()
                        ? "SELECT count(*) FROM module_definition WHERE organization_id IS NULL"
                        : "SELECT count(*) FROM module_definition WHERE organization_id = :org")
                .param("org", scope.organizationId())
                .query(Long.class).single();
    }

    boolean globalKeyExists(String apiKey) {
        return jdbc.sql("SELECT count(*) FROM module_definition WHERE organization_id IS NULL AND api_key = :key")
                .param("key", apiKey).query(Long.class).single() > 0;
    }

    boolean tenantKeyExists(String apiKey) {
        return jdbc.sql("SELECT count(*) FROM module_definition WHERE organization_id IS NOT NULL AND api_key = :key")
                .param("key", apiKey).query(Long.class).single() > 0;
    }

    /** Modules visible to the tenant with an ACTIVE or DEPRECATED lookup field targeting {@code targetKey}. */
    List<ModuleRow> referencing(UUID organizationId, String targetKey) {
        return jdbc.sql("""
                SELECT DISTINCT m.* FROM module_definition m
                  JOIN field_definition f ON f.module_id = m.id
                 WHERE (m.organization_id = :org OR m.organization_id IS NULL)
                   AND f.field_type = 'LOOKUP' AND f.status <> 'ARCHIVED'
                   AND f.config ->> 'targetModuleKey' = :target
                """)
                .param("org", organizationId)
                .param("target", targetKey)
                .query(moduleMapper)
                .list();
    }

    // ---- fields --------------------------------------------------------------------------------------------

    List<FieldRow> findFields(UUID moduleId) {
        return jdbc.sql("SELECT * FROM field_definition WHERE module_id = :module ORDER BY position, api_key")
                .param("module", moduleId)
                .query(fieldMapper)
                .list();
    }

    Optional<FieldRow> findField(UUID moduleId, String apiKey) {
        return jdbc.sql("SELECT * FROM field_definition WHERE module_id = :module AND api_key = :key")
                .param("module", moduleId)
                .param("key", apiKey)
                .query(fieldMapper)
                .optional();
    }

    void insertField(FieldRow f) {
        jdbc.sql("""
                INSERT INTO field_definition (id, module_id, api_key, label, description, field_type, status, required,
                       unique_value, read_only, sensitive, searchable, filterable, sortable, default_value, config,
                       position, version, created_at, created_by, updated_at, updated_by)
                VALUES (:id, :module, :apiKey, :label, :description, :type, :status, :required, :unique, :readOnly,
                       :sensitive, :searchable, :filterable, :sortable, CAST(:defaultValue AS jsonb),
                       CAST(:config AS jsonb), :position, :version, :createdAt, :createdBy, :updatedAt, :updatedBy)
                """)
                .param("id", f.id())
                .param("module", f.moduleId())
                .param("apiKey", f.apiKey())
                .param("label", f.label())
                .param("description", f.description())
                .param("type", f.type().name())
                .param("status", f.status().name())
                .param("required", f.required())
                .param("unique", f.unique())
                .param("readOnly", f.readOnly())
                .param("sensitive", f.sensitive())
                .param("searchable", f.searchable())
                .param("filterable", f.filterable())
                .param("sortable", f.sortable())
                .param("defaultValue", json.write(f.defaultValue()))
                .param("config", json.writeValue(f.config()))
                .param("position", f.position())
                .param("version", f.version())
                .param("createdAt", ts(f.createdAt()))
                .param("createdBy", f.createdBy())
                .param("updatedAt", ts(f.updatedAt()))
                .param("updatedBy", f.updatedBy())
                .update();
    }

    void updateField(FieldRow f) {
        jdbc.sql("""
                UPDATE field_definition
                   SET label = :label, description = :description, field_type = :type, status = :status,
                       required = :required, read_only = :readOnly, sensitive = :sensitive, searchable = :searchable,
                       filterable = :filterable, sortable = :sortable, default_value = CAST(:defaultValue AS jsonb),
                       config = CAST(:config AS jsonb), position = :position, version = :version,
                       updated_at = :updatedAt, updated_by = :updatedBy
                 WHERE id = :id
                """)
                .param("id", f.id())
                .param("label", f.label())
                .param("description", f.description())
                .param("type", f.type().name())
                .param("status", f.status().name())
                .param("required", f.required())
                .param("readOnly", f.readOnly())
                .param("sensitive", f.sensitive())
                .param("searchable", f.searchable())
                .param("filterable", f.filterable())
                .param("sortable", f.sortable())
                .param("defaultValue", json.write(f.defaultValue()))
                .param("config", json.writeValue(f.config()))
                .param("position", f.position())
                .param("version", f.version())
                .param("updatedAt", ts(f.updatedAt()))
                .param("updatedBy", f.updatedBy())
                .update();
    }

    // ---- versions ------------------------------------------------------------------------------------------

    void insertSnapshot(UUID moduleId, long version, JsonNode snapshot, UUID actor, Instant now) {
        jdbc.sql("""
                INSERT INTO module_definition_version (module_id, version, snapshot, created_at, created_by)
                VALUES (:module, :version, CAST(:snapshot AS jsonb), :now, :actor)
                """)
                .param("module", moduleId)
                .param("version", version)
                .param("snapshot", json.write(snapshot))
                .param("now", ts(now))
                .param("actor", actor)
                .update();
    }

    List<VersionRow> listSnapshots(UUID moduleId, long offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM module_definition_version WHERE module_id = :module
                 ORDER BY version DESC LIMIT :limit OFFSET :offset
                """)
                .param("module", moduleId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new VersionRow(rs.getLong("version"), json.read(rs.getString("snapshot")),
                        instant(rs, "created_at"), uuid(rs, "created_by")))
                .list();
    }

    long countSnapshots(UUID moduleId) {
        return jdbc.sql("SELECT count(*) FROM module_definition_version WHERE module_id = :module")
                .param("module", moduleId).query(Long.class).single();
    }

    Optional<VersionRow> findSnapshot(UUID moduleId, long version) {
        return jdbc.sql("SELECT * FROM module_definition_version WHERE module_id = :module AND version = :version")
                .param("module", moduleId)
                .param("version", version)
                .query((rs, n) -> new VersionRow(rs.getLong("version"), json.read(rs.getString("snapshot")),
                        instant(rs, "created_at"), uuid(rs, "created_by")))
                .optional();
    }
}
