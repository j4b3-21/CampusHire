package com.bm4a.platform.modules.workflow.internal;

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
import com.bm4a.platform.modules.workflow.WorkflowTransition;

import tools.jackson.databind.node.ObjectNode;

/** Every statement is scoped by {@code organization_id}. */
@Repository
class WorkflowRepository {

    /** A stored definition; {@code definition} is the canonical document. */
    record DefinitionRow(UUID id, UUID organizationId, UUID moduleId, String fieldKey, ObjectNode definition,
            long version, Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {
    }

    private final JdbcClient jdbc;
    private final JsonColumns json;
    private final RowMapper<DefinitionRow> definitionMapper;
    private final RowMapper<WorkflowTransition> transitionMapper;

    WorkflowRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
        this.definitionMapper = (rs, n) -> new DefinitionRow(uuid(rs, "id"), uuid(rs, "organization_id"),
                uuid(rs, "module_id"), rs.getString("field_key"), json.readObject(rs.getString("definition")),
                rs.getLong("version"), instant(rs, "created_at"), uuid(rs, "created_by"), instant(rs, "updated_at"),
                uuid(rs, "updated_by"));
        this.transitionMapper = (rs, n) -> new WorkflowTransition(uuid(rs, "id"), uuid(rs, "organization_id"),
                uuid(rs, "module_id"), uuid(rs, "record_id"), rs.getLong("record_version"), rs.getString("from_state"),
                rs.getString("to_state"), rs.getString("transition_key"), uuid(rs, "actor_user_id"),
                rs.getString("comment"), instant(rs, "created_at"));
    }

    Optional<DefinitionRow> find(UUID organizationId, UUID moduleId, boolean forUpdate) {
        return jdbc.sql("SELECT * FROM workflow_definition WHERE organization_id = :org AND module_id = :module"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("org", organizationId)
                .param("module", moduleId)
                .query(definitionMapper)
                .optional();
    }

    void insert(DefinitionRow row) {
        jdbc.sql("""
                INSERT INTO workflow_definition (id, organization_id, module_id, field_key, definition, version,
                                                 created_at, created_by, updated_at, updated_by)
                VALUES (:id, :org, :module, :field, CAST(:definition AS jsonb), :version, :createdAt, :createdBy,
                        :updatedAt, :updatedBy)
                """)
                .param("id", row.id())
                .param("org", row.organizationId())
                .param("module", row.moduleId())
                .param("field", row.fieldKey())
                .param("definition", json.write(row.definition()))
                .param("version", row.version())
                .param("createdAt", ts(row.createdAt()))
                .param("createdBy", row.createdBy())
                .param("updatedAt", ts(row.updatedAt()))
                .param("updatedBy", row.updatedBy())
                .update();
    }

    void update(DefinitionRow row) {
        jdbc.sql("""
                UPDATE workflow_definition
                   SET field_key = :field, definition = CAST(:definition AS jsonb), version = :version,
                       updated_at = :updatedAt, updated_by = :updatedBy
                 WHERE id = :id AND organization_id = :org
                """)
                .param("id", row.id())
                .param("org", row.organizationId())
                .param("field", row.fieldKey())
                .param("definition", json.write(row.definition()))
                .param("version", row.version())
                .param("updatedAt", ts(row.updatedAt()))
                .param("updatedBy", row.updatedBy())
                .update();
    }

    void delete(UUID organizationId, UUID id) {
        jdbc.sql("DELETE FROM workflow_definition WHERE id = :id AND organization_id = :org")
                .param("id", id)
                .param("org", organizationId)
                .update();
    }

    void insertTransition(WorkflowTransition t) {
        jdbc.sql("""
                INSERT INTO workflow_transition (id, organization_id, module_id, record_id, record_version, from_state,
                                                 to_state, transition_key, actor_user_id, comment, created_at)
                VALUES (:id, :org, :module, :record, :recordVersion, :from, :to, :transition, :actor, :comment, :at)
                """)
                .param("id", t.id())
                .param("org", t.organizationId())
                .param("module", t.moduleId())
                .param("record", t.recordId())
                .param("recordVersion", t.recordVersion())
                .param("from", t.fromState())
                .param("to", t.toState())
                .param("transition", t.transitionKey())
                .param("actor", t.actorUserId())
                .param("comment", t.comment())
                .param("at", ts(t.createdAt()))
                .update();
    }

    /** Newest first. */
    List<WorkflowTransition> transitions(UUID organizationId, UUID recordId, long offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM workflow_transition WHERE organization_id = :org AND record_id = :record
                 ORDER BY created_at DESC, record_version DESC LIMIT :limit OFFSET :offset
                """)
                .param("org", organizationId)
                .param("record", recordId)
                .param("limit", limit)
                .param("offset", offset)
                .query(transitionMapper)
                .list();
    }

    long countTransitions(UUID organizationId, UUID recordId) {
        return jdbc.sql("SELECT count(*) FROM workflow_transition WHERE organization_id = :org AND record_id = :record")
                .param("org", organizationId)
                .param("record", recordId)
                .query(Long.class).single();
    }
}
